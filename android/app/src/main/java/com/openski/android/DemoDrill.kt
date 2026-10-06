package com.openski.android

import kotlin.math.PI
import kotlin.math.sin

/** A boot-roll curve over time, in seconds and degrees. Positive and negative roll are opposite edges. */
data class RollTrace(val seconds: FloatArray, val degrees: FloatArray) {
    val duration get() = if (seconds.isEmpty()) 0f else seconds.last()
}

data class DrillRun(val detection: DryDetection, val trace: RollTrace, val demo: Boolean)

/**
 * Sensor-free practice run. It is always labelled as demo boots and is not a measurement.
 * Each repeat of a drill is a little steadier, so the first-use flow shows what progress looks like.
 */
object DemoDrill {
    fun simulate(drill: Drill, attemptNumber: Int): DrillRun {
        val random = java.util.Random(drill.id.hashCode().toLong() * 31 + attemptNumber)
        val skill = (attemptNumber / 4.0).coerceIn(0.0, 1.0)
        val jitter = 0.32 - 0.24 * skill
        val depthFactor = 0.68 + 0.3 * skill
        val weakSide = 0.78 + 0.2 * skill
        val seconds = ArrayList<Float>()
        val degrees = ArrayList<Float>()
        var clock = 1.0
        fun rest(until: Double) {
            var t = seconds.lastOrNull()?.toDouble() ?: 0.0
            while (t < until) { seconds.add(t.toFloat()); degrees.add(0f); t += 0.05 }
        }
        rest(clock)
        for (index in 0 until drill.movements) {
            val positive = index % 2 == 0
            // pace is the beat: sweep plus a short settle at neutral add up to one beat.
            val duration = (drill.paceSeconds * (1 + jitter * (random.nextDouble() * 2 - 1)) * 0.85).coerceAtLeast(0.4)
            val peak = drill.depthDegrees * depthFactor * (if (positive) 1.0 else weakSide) * (1 + 0.08 * (random.nextDouble() * 2 - 1))
            val steps = (duration / 0.05).toInt().coerceAtLeast(8)
            for (step in 0..steps) {
                val phase = step.toDouble() / steps
                seconds.add((clock + duration * phase).toFloat())
                degrees.add((sin(PI * phase) * peak * if (positive) 1 else -1).toFloat())
            }

            clock += duration
            val gap = drill.paceSeconds * 0.15
            rest(clock + gap)
            clock += gap
        }
        // Detect movements with the same tracker live drills and session replay use, so demo and live agree.
        val detector = DrySkiAnalysis.Tracker()
        val detected = seconds.indices.mapNotNull {
            detector.accept(BootRoll("L", seconds[it] * 1000.0, degrees[it].toDouble(), 0.0))
        }
        return DrillRun(DryDetection(detected, detected.map { it.endMs }),
            RollTrace(seconds.toFloatArray(), degrees.toFloatArray()), demo = true)
    }
}
