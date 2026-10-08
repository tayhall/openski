package com.openski.android

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * Plays the coaching sounds. A thin Android shell: the sounds come from [ToneSynth] and the judging from [Coach].
 * No audio focus is requested, so music or a podcast keeps playing and the cues mix over it.
 * Callbacks run on the main thread.
 */
class CoachAudio(
    private val context: Context,
    private val onRoute: (headphones: Boolean) -> Unit,
    private val onProblem: (String) -> Unit,
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var gain = 0.6f
    @Volatile private var running = false
    @Volatile private var paused = false
    private var allowSpeaker = false
    private var track: AudioTrack? = null
    private var thread: Thread? = null
    private var registered = false

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
    private val format = AudioFormat.Builder()
        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
        .setSampleRate(ToneSynth.SAMPLE_RATE)
        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
        .build()

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = routeChanged()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = routeChanged()
    }

    /** True when earbuds, a headset or a helmet speaker is connected for output. */
    fun headphonesConnected(): Boolean =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { isHeadphone(it.type) }

    private fun isHeadphone(type: Int): Boolean = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_HEADSET -> true
        else -> (Build.VERSION.SDK_INT >= 28 && type == AudioDeviceInfo.TYPE_HEARING_AID) ||
            (Build.VERSION.SDK_INT >= 31 && type == AudioDeviceInfo.TYPE_BLE_HEADSET)
    }

    private fun routeOk() = allowSpeaker || headphonesConnected()

    /** Starts the metronome (if enabled) and watches the audio route. Returns a reason if it cannot start. */
    fun start(settings: CoachSettings, target: CoachTarget): String? {
        gain = settings.gainPercent / 100f
        allowSpeaker = settings.allowPhoneSpeaker
        if (!routeOk()) return "Connect earbuds or a helmet speaker first, or allow the phone speaker in the coaching settings."
        running = true
        paused = false
        audioManager.registerAudioDeviceCallback(deviceCallback, main)
        registered = true
        if (!settings.metronome) return null
        val minBuffer = AudioTrack.getMinBufferSize(ToneSynth.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) { stop(); return "This phone could not start audio." }
        return try {
            val created = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(minBuffer * 2, ToneSynth.SAMPLE_RATE / 2))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            created.play()
            track = created
            val clock = MetronomeClock(target.beatSeconds)
            thread = Thread({ pump(created, clock) }, "coach-metronome").also { it.start() }
            null
        } catch (error: Exception) {
            stop()
            "Audio could not start: ${error.message ?: "unknown error"}"
        }
    }

    /** Writes the metronome one beat at a time; the blocking write paces it in real time. */
    private fun pump(output: AudioTrack, clock: MetronomeClock) {
        while (running) {
            if (paused) { try { Thread.sleep(50) } catch (_: InterruptedException) { return }; continue }
            val beat = clock.nextBeat()
            val scaled = ShortArray(beat.size) { (beat[it] * gain).toInt().toShort() }
            var offset = 0
            while (running && !paused && offset < scaled.size) {
                val written = output.write(scaled, offset, scaled.size - offset)
                if (written < 0) {
                    running = false
                    main.post { onProblem("Audio stopped unexpectedly.") }
                    return
                }
                offset += written
            }
        }
    }

    /** Plays the section chirp, if the verdict has one. */
    fun chirp(verdict: Verdict) {
        if (!running || paused || !routeOk()) return   // never trust the flag alone: the route callback can lag
        val clip = when (verdict) {
            Verdict.POSITIVE -> ToneSynth.chirpPositive()
            Verdict.NEGATIVE -> ToneSynth.chirpNegative()
            Verdict.NONE -> return
        }
        playClip(clip)
    }

    /** Plays a short chime (the start or end of a run), at the user's volume. */
    fun playChime(clip: ShortArray) = playClip(clip)

    /** Plays a tick, the accent, then both chirps, so the skier can set the volume before starting. */
    fun playTestSounds(gainPercent: Int) {
        gain = gainPercent.coerceIn(0, 100) / 100f
        listOf(0L to ToneSynth.tick(false), 500L to ToneSynth.tick(true), 1200L to ToneSynth.chirpPositive(),
            2200L to ToneSynth.chirpNegative()).forEach { (delay, clip) -> main.postDelayed({ playClip(clip) }, delay) }
    }

    private fun playClip(clip: ShortArray) {
        val scaled = ShortArray(clip.size) { (clip[it] * gain).toInt().toShort() }
        try {
            val output = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setBufferSizeInBytes(scaled.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            output.write(scaled, 0, scaled.size)
            output.play()
            main.postDelayed({ runCatching { output.stop(); output.release() } }, clip.size * 1000L / ToneSynth.SAMPLE_RATE + 300)
        } catch (error: Exception) {
            onProblem("Could not play a coaching sound.")
        }
    }

    private fun routeChanged() {
        if (!running) return
        val ok = routeOk()
        if (ok == !paused) return
        paused = !ok
        // Pausing the stream and discarding what is queued stops the last quarter-second of ticks from sounding.
        track?.let { runCatching { if (ok) it.play() else { it.pause(); it.flush() } } }
        onRoute(ok)
    }

    fun stop() {
        running = false
        thread?.let { runCatching { it.join(1000) } }
        thread = null
        track?.let { runCatching { it.stop(); it.release() } }
        track = null
        if (registered) runCatching { audioManager.unregisterAudioDeviceCallback(deviceCallback) }
        registered = false
    }
}
