package com.openski.android

data class HeadingTurnSummary(val count: Int, val medianDurationMs: Double?, val cadencePerMinute: Double?)

/**
 * Replays the heading-rate detector over orientation output. Experimental turn candidates, not
 * ski turn classification. Rolls must be for one side.
 */
object HeadingTurnAnalysis {
    fun detect(rolls: List<BootRoll>): List<HeadingTurn> {
        val detector = HeadingTurnDetector()
        val turns = ArrayList<HeadingTurn>()
        var segment: Int? = null
        rolls.sortedBy { it.timeMs }.forEach { r ->
            if (segment != null && r.segment != segment) detector.breakSegment()
            segment = r.segment
            val flags = if (r.quality and 3 != 0) HeadingTurnDetector.FLAG_NEAR_RAIL else 0
            detector.accept(r.timeMs, r.headingRateRadps, flags)?.let(turns::add)
        }
        return turns
    }

    fun summarise(turns: List<HeadingTurn>): HeadingTurnSummary {
        if (turns.isEmpty()) return HeadingTurnSummary(0, null, null)
        val durations = turns.map { it.endMs - it.startMs }.sorted()
        val median = (durations[(durations.size - 1) / 2] + durations[durations.size / 2]) / 2
        val span = turns.last().startMs - turns.first().startMs
        val cadence = if (turns.size >= 2 && span > 0) (turns.size - 1) * 60_000.0 / span else null
        return HeadingTurnSummary(turns.size, median, cadence)
    }
}
