package com.openski.android

import kotlin.math.PI
import kotlin.math.sin

/** Generates the coaching sounds as 16-bit mono PCM, so no audio files ship with the app. */
object ToneSynth {
    const val SAMPLE_RATE = 44100
    /** Loudest sample as a fraction of full scale, before the user's gain. */
    const val PEAK = 0.5

    /** A sine burst with a linear fade in and out so it never clicks. */
    fun tone(frequencyHz: Double, millis: Int, fadeMillis: Int = 8, sampleRate: Int = SAMPLE_RATE): ShortArray {
        val count = sampleRate * millis / 1000
        val fade = (sampleRate * fadeMillis / 1000).coerceAtLeast(1)
        return ShortArray(count) { i ->
            val envelope = minOf(1.0, i.toDouble() / fade, (count - 1 - i).toDouble() / fade)
            (sin(2 * PI * frequencyHz * i / sampleRate) * envelope * PEAK * Short.MAX_VALUE).toInt().toShort()
        }
    }

    fun concat(vararg parts: ShortArray): ShortArray {
        val out = ShortArray(parts.sumOf { it.size })
        var offset = 0
        for (part in parts) { part.copyInto(out, offset); offset += part.size }
        return out
    }

    /** The metronome click. The accent, on every second beat, is higher so left and right feel different. */
    fun tick(accent: Boolean): ShortArray = tone(if (accent) 1600.0 else 1200.0, 30, fadeMillis = 3)

    /** Two rising notes: the last few turns matched the target. */
    fun chirpPositive(): ShortArray = concat(tone(880.0, 110, 10), tone(1320.0, 110, 10))

    /** Two falling notes in a lower register: the last few turns did not. */
    fun chirpNegative(): ShortArray = concat(tone(660.0, 110, 10), tone(440.0, 110, 10))

    /** Three rising notes, longer than a chirp: the run has started. */
    fun startChime(): ShortArray = concat(tone(660.0, 140, 12), tone(880.0, 140, 12), tone(1175.0, 220, 14))

    /** Three falling notes, longer than a chirp: the run is over and is being saved. */
    fun endChime(): ShortArray = concat(tone(1175.0, 140, 12), tone(880.0, 140, 12), tone(660.0, 220, 14))
}

/**
 * Produces the metronome one beat at a time. Each beat is a whole number of samples, and the fraction left over is
 * carried into the next beat, so the tempo does not drift however long the metronome runs.
 */
class MetronomeClock(private val beatSeconds: Double, private val sampleRate: Int = ToneSynth.SAMPLE_RATE) {
    private var carried = 0.0
    private var beatIndex = 0L

    fun nextBeat(): ShortArray {
        val exact = beatSeconds * sampleRate + carried
        val length = exact.toInt()
        carried = exact - length
        val accent = beatIndex % 2L == 0L
        beatIndex++
        val beat = ShortArray(length)
        val tick = ToneSynth.tick(accent)
        tick.copyInto(beat, 0, 0, minOf(tick.size, length))
        return beat
    }
}
