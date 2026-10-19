package com.openski.android

/** Android JSON implementation: v1 stays unknown, v2 round-trips focus and phone-aligned intervals. */
object PracticePilotMetadataRegression {
    fun run() {
        val original = PracticeReview(PracticeEvidence(scoringQuality = ScoringEvidenceQuality.SUFFICIENT,
            scoringSource = ScoringEvidenceSource.SIMULATED, timingPolicy = PracticeTiming.POLICY),
            4, 2.0, 20.0, 21.0, 19.0, 2, focus = PracticeFocus.DIRECTIONAL_CONSISTENCY,
            targetSource = PracticeTargetSource.SAVED_TARGET,
            activeIntervals = listOf(PracticeInterval(1000.0, 5000.0), PracticeInterval(9000.0, 13000.0)))
        check(PracticeReview.decode(original.json().toString()) == original)
        val legacy = original.json().put("format_version", 1)
        listOf("focus", "target_source", "active_intervals", "active_interval_clock").forEach { legacy.remove(it) }
        check(PracticeReview.decode(legacy.toString()) == original.copy(focus = null, targetSource = null, activeIntervals = null))
        check(PracticeReview.decode(original.json().put("focus", "future_focus").toString()).focus == null)
        check(runCatching { PracticeReview.decode(original.json().put("active_interval_clock", "other_clock").toString()) }.isFailure)
    }
}
