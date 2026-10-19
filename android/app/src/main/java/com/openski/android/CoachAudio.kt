package com.openski.android

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * Plays the coaching sounds. A thin Android shell: the sounds come from [ToneSynth] and the judging from [Coach].
 * Legacy coaching mixes cues with music. Optional practice mode requests focus and requires explicit resume after interruption.
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
    @Volatile private var generation = 0L
    private var practiceGate: PracticeAudioGate? = null
    private var availabilityListener: ((PracticeAudioAvailability) -> Unit)? = null
    private var lastAvailability: PracticeAudioAvailability? = null
    private var focusRequest: AudioFocusRequest? = null
    private var focusGranted = false
    private val clips = mutableSetOf<AudioTrack>()
    private val soundCallbacks = mutableListOf<Runnable>()

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
        if (running || registered || practiceGate != null) return "Audio is already in use. Finish the current practice or coaching first."
        cancelClips()
        return startStream(settings, target)
    }

    /** Uses this same audio owner; focus/route recovery stays paused until [resumePractice]. */
    fun startPractice(settings: CoachSettings, target: CoachTarget, onAvailability: (PracticeAudioAvailability) -> Unit): String? {
        if (running || registered || practiceGate != null) return "Audio is already in use. Finish the current practice or coaching first."
        cancelClips()
        gain = settings.normalised().gainPercent / 100f
        allowSpeaker = settings.allowPhoneSpeaker
        val gate = PracticeAudioGate()
        practiceGate = gate
        availabilityListener = onAvailability
        lastAvailability = null
        gate.start(routeOk(), false)
        if (!routeOk()) { publishAvailability(); return gate.state.message }
        val token = generation
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .setAcceptsDelayedFocusGain(false)
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener({ change ->
                if (token != generation || practiceGate !== gate) return@setOnAudioFocusChangeListener
                focusGranted = change == AudioManager.AUDIOFOCUS_GAIN
                gate.focusChanged(focusGranted)
                applyPracticeAvailability()
            }, main).build()
        focusRequest = request
        focusGranted = runCatching { audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED }.getOrDefault(false)
        gate.start(routeOk(), focusGranted)
        if (!gate.state.usable) {
            generation++  // A queued focus callback from a denied request cannot revive this preparation.
            runCatching { audioManager.abandonAudioFocusRequest(request) }
            focusRequest = null
            publishAvailability()
            return gate.state.message
        }
        val error = startStream(settings, target)
        if (error != null) {
            stop()
            // startStream releases native resources on failure; retain the actionable failed availability.
            val failed = PracticeAudioGate().apply { start(routeOk(), false); fail(error) }
            practiceGate = failed; availabilityListener = onAvailability; lastAvailability = null
            publishAvailability()
            return error
        }
        applyPracticeAvailability()
        return null
    }

    fun practiceAvailability(): PracticeAudioAvailability = practiceGate?.state
        ?: PracticeAudioAvailability(PracticeAudioPhase.STOPPED, "Practice audio is stopped.")

    fun pausePractice() { practiceGate?.pause(); applyPracticeAvailability() }

    /** Does not start a failed/unstarted stream or silently select the phone speaker. */
    fun resumePractice(): String? {
        val gate = practiceGate ?: return "Start practice audio before resuming."
        gate.routeChanged(routeOk())
        if (!running) return "Practice audio did not start. Finish preparation and start again."
        if (!focusGranted && routeOk()) focusRequest?.let { request ->
            focusGranted = runCatching { audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED }.getOrDefault(false)
            gate.focusChanged(focusGranted)
        }
        if (!gate.resume()) { applyPracticeAvailability(); return gate.state.message }
        applyPracticeAvailability()
        return null
    }

    private fun publishAvailability() {
        val state = practiceGate?.state ?: return
        if (state != lastAvailability) { lastAvailability = state; availabilityListener?.invoke(state) }
    }

    private fun applyPracticeAvailability() {
        val gate = practiceGate ?: return
        val wasPaused = paused
        paused = !gate.state.usable
        if (paused) cancelClips()
        if (paused != wasPaused) track?.let { output ->
            runCatching { if (paused) { output.pause(); output.flush() } else output.play() }
        }
        publishAvailability()
    }

    private fun startStream(settings: CoachSettings, target: CoachTarget): String? {
        gain = settings.gainPercent / 100f
        allowSpeaker = settings.allowPhoneSpeaker
        if (!routeOk()) return "Connect earbuds or a helmet speaker first, or allow the phone speaker in the coaching settings."
        running = true
        paused = false
        return try {
            audioManager.registerAudioDeviceCallback(deviceCallback, main)
            registered = true
            if (!settings.metronome) return null
            val minBuffer = AudioTrack.getMinBufferSize(ToneSynth.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuffer <= 0) { stop(); return "This phone could not start audio." }
            val created = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(minBuffer * 2, ToneSynth.SAMPLE_RATE / 2))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track = created
            created.play()
            val clock = MetronomeClock(target.beatSeconds)
            val token = generation
            thread = Thread({ pump(created, clock, token) }, "coach-metronome").also { it.start() }
            null
        } catch (error: Exception) {
            stop()
            "Audio could not start: ${error.message ?: "unknown error"}"
        }
    }

    /** Writes the metronome one beat at a time; the blocking write paces it in real time. */
    private fun pump(output: AudioTrack, clock: MetronomeClock, token: Long) {
        while (running && token == generation) {
            if (paused) { try { Thread.sleep(50) } catch (_: InterruptedException) { return }; continue }
            val beat = clock.nextBeat()
            val scaled = ShortArray(beat.size) { (beat[it] * gain).toInt().toShort() }
            var offset = 0
            while (running && token == generation && !paused && offset < scaled.size) {
                val written = runCatching { output.write(scaled, offset, scaled.size - offset) }.getOrDefault(-1)
                if (written <= 0) {
                    if (!running || token != generation || paused) break
                    running = false
                    main.post {
                        if (token == generation) {
                            practiceGate?.fail("Audio stopped unexpectedly. Finish this attempt and retry audio preparation.")
                            applyPracticeAvailability()
                            onProblem("Audio stopped unexpectedly.")
                        }
                    }
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
    fun playChime(clip: ShortArray) { if (routeOk() && practiceGate?.state?.usable != false) playClip(clip) }

    /** Tests respect the selected speaker permission when supplied by preparation. */
    fun playTestSounds(settings: CoachSettings) {
        if (running || practiceGate != null) return
        allowSpeaker = settings.allowPhoneSpeaker
        playTestSounds(settings.normalised().gainPercent)
    }

    /** Plays a tick, the accent, then both chirps, so the skier can set the volume before starting. */
    fun playTestSounds(gainPercent: Int) {
        if (running || practiceGate != null) return
        cancelClips()
        gain = gainPercent.coerceIn(0, 100) / 100f
        val token = generation
        listOf(0L to ToneSynth.tick(false), 500L to ToneSynth.tick(true), 1200L to ToneSynth.chirpPositive(),
            2200L to ToneSynth.chirpNegative()).forEach { (delay, clip) -> scheduleSound(delay) { if (token == generation && routeOk()) playClip(clip) } }
    }

    /** Practice preview uses the same focus/route gate and cancellation as an active set. */
    fun playPracticeExamples() {
        if (practiceGate?.state?.usable != true) return
        cancelClips()
        val token = generation
        listOf(0L to ToneSynth.tick(false), 1000L to ToneSynth.tick(false), 2000L to ToneSynth.tick(false),
            3000L to ToneSynth.startChime(), 4000L to ToneSynth.tick(false), 4500L to ToneSynth.tick(true),
            5000L to ToneSynth.tone(1000.0, 90), 6000L to ToneSynth.endChime()).forEach { (delay, clip) ->
            scheduleSound(delay) { if (token == generation && practiceGate?.state?.usable == true) playClip(clip) }
        }
    }

    private fun playClip(clip: ShortArray) {
        if (!routeOk() || practiceGate?.state?.usable == false) return
        val scaled = ShortArray(clip.size) { (clip[it] * gain).toInt().toShort() }
        var created: AudioTrack? = null
        try {
            val output = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setBufferSizeInBytes(scaled.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            created = output
            clips.add(output)
            output.write(scaled, 0, scaled.size)
            output.play()
            scheduleSound(clip.size * 1000L / ToneSynth.SAMPLE_RATE + 300) { if (clips.remove(output)) runCatching { output.stop(); output.release() } }
        } catch (error: Exception) {
            created?.let { output -> clips.remove(output); runCatching { output.stop(); output.release() } }
            practiceGate?.fail("Could not play a practice sound. Finish this attempt and retry audio preparation.")
            applyPracticeAvailability()
            onProblem("Could not play a coaching sound.")
        }
    }

    private fun routeChanged() {
        if (!running) return
        val ok = routeOk()
        practiceGate?.let { gate ->
            gate.routeChanged(ok)
            applyPracticeAvailability()
            return
        }
        if (ok == !paused) return
        paused = !ok
        // Pausing the stream and discarding what is queued stops the last quarter-second of ticks from sounding.
        track?.let { runCatching { if (ok) it.play() else { it.pause(); it.flush() } } }
        onRoute(ok)
    }

    private fun scheduleSound(delay: Long, action: () -> Unit) {
        val callback = object : Runnable {
            override fun run() { soundCallbacks.remove(this); action() }
        }
        soundCallbacks.add(callback)
        main.postDelayed(callback, delay)
    }

    private fun cancelClips() {
        soundCallbacks.forEach(main::removeCallbacks)
        soundCallbacks.clear()
        clips.toList().forEach { output -> runCatching { output.stop(); output.release() } }
        clips.clear()
    }

    fun stop() {
        running = false
        generation++
        cancelClips()
        track?.let { runCatching { it.pause(); it.flush() } }
        thread?.let { runCatching { it.interrupt(); it.join(500) } }
        thread = null
        track?.let { runCatching { it.stop(); it.release() } }
        track = null
        if (registered) runCatching { audioManager.unregisterAudioDeviceCallback(deviceCallback) }
        registered = false
        focusRequest?.let { request -> runCatching { audioManager.abandonAudioFocusRequest(request) } }
        focusRequest = null; focusGranted = false
        practiceGate?.stop(); publishAvailability()
        practiceGate = null; availabilityListener = null; lastAvailability = null
        paused = false
    }
}
