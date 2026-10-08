package com.openski.android

/** One synthetic half-turn and how long after the previous one it should be delivered, in milliseconds. */
data class DemoStep(val event: SkiEvent, val afterMs: Long)

/**
 * Synthetic half-turns for demo boots, so the metronome and chirps can be heard without hardware.
 * Blocks of [blockSize] turns alternate between matching the target and drifting off it (shallow and slow),
 * so both chirps are heard.
 */
class DemoCoachFeed(private val target: CoachTarget, private val blockSize: Int = 8) {
    private var n = 0
    private var clockMs = 100_000L

    fun next(): DemoStep {
        val onTarget = (n / blockSize) % 2 == 0
        val wobble = WOBBLE[n % WOBBLE.size]
        val beatMs = (target.beatSeconds * 1000 * (if (onTarget) 1.0 + wobble / 40 else 1.5)).toLong()
        val depth = target.depthDegrees * (if (onTarget) 1.0 + wobble / 30 else 0.65)
        clockMs += beatMs
        val positive = n % 2 == 0
        val event = SkiEvent(
            sequence = n + 1,
            startMs = clockMs,
            durationMs = (beatMs * 0.9).toInt(),
            peakRollDegrees = (if (positive) depth else -depth).toFloat(),
            peakRateDps = (depth * 3.14159 / (beatMs / 1000.0)).toFloat(),
            pitchDegrees = 15f,
            positive = positive,
            outsideEnvelope = false,
            pitchOutside = false,
            skippedSamples = false,
        )
        n++
        return DemoStep(event, beatMs)
    }

    private companion object {
        val WOBBLE = doubleArrayOf(0.0, 1.5, -1.0, 0.5)
    }
}
