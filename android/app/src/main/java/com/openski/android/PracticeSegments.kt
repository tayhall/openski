package com.openski.android

/** A contiguous scored interval on the phone-aligned roll timeline, excluding countdown/paused time. */
data class PracticeSegment(val startedAtMs: Double, val endedAtMs: Double, val movements: List<DryMovement>) {
    init {
        require(startedAtMs.isFinite() && endedAtMs.isFinite() && endedAtMs >= startedAtMs)
        require(movements.all { it.startMs.isFinite() && it.endMs.isFinite() && it.peakRoll.isFinite() &&
            it.startMs >= startedAtMs && it.endMs <= endedAtMs && it.endMs > it.startMs })
        require(movements.zipWithNext().all { (a, b) -> a.endMs <= b.startMs })
    }
}

data class PracticeTimingSummary(val activeMs: Double, val movements: List<DryMovement>, val beatSeconds: List<Double>)

/** Pauses split timing pairs: a single movement in an interval supplies no observed beat. */
object PracticeTiming {
    const val POLICY = "active_segments_v1"

    fun summary(segments: List<PracticeSegment>, maximumMovements: Int): PracticeTimingSummary {
        require(maximumMovements > 0)
        require(segments.zipWithNext().all { (a, b) -> a.endedAtMs <= b.startedAtMs })
        val movements = mutableListOf<DryMovement>()
        val beats = mutableListOf<Double>()
        for (segment in segments) {
            val accepted = segment.movements.take(maximumMovements - movements.size)
            beats += accepted.zipWithNext { a, b -> (b.startMs - a.startMs) / 1000.0 }
            movements += accepted
        }
        return PracticeTimingSummary(segments.sumOf { it.endedAtMs - it.startedAtMs }, movements, beats)
    }
}

/** Monotonic UI/audio timing. Repeated pause/resume callbacks cannot count the same interval twice. */
class PracticeClock {
    private var accumulatedMs = 0L
    private var runningSince: Long? = null

    fun resume(nowMs: Long): Boolean {
        if (runningSince != null) return false
        runningSince = nowMs
        return true
    }

    fun pause(nowMs: Long): Boolean {
        val start = runningSince ?: return false
        require(nowMs >= start)
        accumulatedMs += nowMs - start
        runningSince = null
        return true
    }

    fun elapsedMs(nowMs: Long): Long = accumulatedMs + (runningSince?.let {
        require(nowMs >= it)
        nowMs - it
    } ?: 0L)
}
