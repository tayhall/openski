package com.openski.android

import org.json.JSONObject
import kotlin.math.abs

/** Immutable facts from the movements actually supplied to scoring, never reconstructed from a score. */
data class PracticeReview(val evidence: PracticeEvidence, val movements: Int, val meanBeatSeconds: Double?,
    val meanPeakDegrees: Double?, val positivePeakDegrees: Double?, val negativePeakDegrees: Double?, val knownBeatCount: Int = 0, val evidenceIssues: Set<String> = emptySet()) {
    val scoreEligible get() = evidence.scoringQuality == ScoringEvidenceQuality.SUFFICIENT

    fun observation(target: PracticeTarget?): String {
        if (!scoreEligible) return evidenceIssues.firstOrNull() ?: "Not enough complete, alternating movement evidence to explain a score."
        if (target == null) return "The original target is unknown; no target difference can be calculated."
        val peak = meanPeakDegrees ?: return "The mean cuff-roll peak was not recorded."
        val delta = peak - target.depthDegrees
        return "Mean cuff-roll peak ${one(peak)}° · ${one(abs(delta))}° ${if (delta < 0) "below" else "above"} the saved ${one(target.depthDegrees)}° target."
    }

    fun nextAction(): String = if (!scoreEligible) "Check the sensor connection and mounting, then try a fresh set."
        else "Repeat the same target and count the beat; compare cuff-roll observations, not skiing technique."

    fun json(): JSONObject = JSONObject().put("format_version", 1).put("scoring_quality", evidence.scoringQuality.name)
        .put("scoring_source", evidence.scoringSource?.name ?: JSONObject.NULL).put("scored_side", evidence.scoredSide ?: JSONObject.NULL)
        .put("mount_axis", evidence.mounting?.axis ?: JSONObject.NULL).put("mount_sign", evidence.mounting?.sign ?: JSONObject.NULL)
        .put("evidence_issues", org.json.JSONArray(evidenceIssues.toList())).put("known_beat_count", knownBeatCount).put("timing_policy", evidence.timingPolicy ?: JSONObject.NULL).put("movements", movements)
        .put("mean_beat_s", meanBeatSeconds ?: JSONObject.NULL).put("mean_peak_deg", meanPeakDegrees ?: JSONObject.NULL)
        .put("positive_peak_deg", positivePeakDegrees ?: JSONObject.NULL).put("negative_peak_deg", negativePeakDegrees ?: JSONObject.NULL)

    companion object {
        private fun one(value: Double) = String.format(java.util.Locale.US, "%.1f", value)
        fun capture(attempt: Attempt, run: DrillRun?, side: String?, mounting: Mounting?,
            movementGroups: List<List<DryMovement>>? = null, timingPolicy: String = "continuous_v1"): PracticeReview {
            require(movementGroups == null || timingPolicy != "continuous_v1") { "Segmented movements need an explicit segmented timing policy" }
            var remaining = attempt.target?.movements ?: Int.MAX_VALUE
            val groups = (movementGroups ?: listOf(run?.detection?.movements.orEmpty())).map { group ->
                group.take(remaining).also { remaining -= it.size }
            }
            val moves = groups.flatten()
            val beats = groups.flatMap { group -> group.zipWithNext { a, b -> (b.startMs - a.startMs) / 1000 } }
            val positive = moves.filter { it.positive }.map { it.peakRoll }
            val negative = moves.filterNot { it.positive }.map { it.peakRoll }
            val sufficient = run != null && run.evidenceIssues.isEmpty() && attempt.target != null && moves.size >= attempt.target.movements && beats.isNotEmpty() &&
                beats.all { it.isFinite() && it > 0 } && moves.all { it.startMs.isFinite() && it.endMs.isFinite() && it.endMs > it.startMs && it.peakRoll.isFinite() && it.peakRoll >= 0 } &&
                groups.all { group -> group.zipWithNext().all { (a, b) -> a.positive != b.positive } } && positive.isNotEmpty() && negative.isNotEmpty() && (attempt.demo || (side in listOf("L", "R") && mounting != null))
            fun List<Double>.mean() = takeIf { it.isNotEmpty() && it.all(Double::isFinite) }?.average()
            return PracticeReview(PracticeEvidence(if (attempt.demo) null else side, if (attempt.demo) null else mounting,
                if (sufficient) ScoringEvidenceQuality.SUFFICIENT else ScoringEvidenceQuality.INSUFFICIENT,
                if (attempt.demo) ScoringEvidenceSource.SIMULATED else ScoringEvidenceSource.LIVE_ROLL,
                timingPolicy = timingPolicy), moves.size, beats.mean(), moves.map { it.peakRoll }.mean(), positive.mean(), negative.mean(), beats.size, run?.evidenceIssues.orEmpty())
        }

        fun decode(value: String): PracticeReview {
            val j = JSONObject(value)
            require(j.getInt("format_version") == 1)
            fun number(key: String) = if (j.isNull(key)) null else j.getDouble(key)
            fun string(key: String) = if (j.isNull(key)) null else j.getString(key)
            return PracticeReview(PracticeEvidence(string("scored_side"), if (j.isNull("mount_axis")) null else Mounting(j.getInt("mount_axis"), j.getInt("mount_sign")),
                ScoringEvidenceQuality.valueOf(j.getString("scoring_quality")), string("scoring_source")?.let(ScoringEvidenceSource::valueOf),
                timingPolicy = string("timing_policy")), j.getInt("movements"), number("mean_beat_s"), number("mean_peak_deg"), number("positive_peak_deg"), number("negative_peak_deg"), j.optInt("known_beat_count", 0), j.optJSONArray("evidence_issues")?.let { values -> (0 until values.length()).map { values.getString(it) }.toSet() }.orEmpty())
        }
    }
}

data class PracticeReflection(val intention: String = "", val reflection: String = "")

/** Additive export metadata; original raw CSV and session format contracts remain intact. */
data class PracticeExportReview(val attempt: Attempt, val reflection: PracticeReflection) {
    fun json(): JSONObject = JSONObject().put("format_version", 1).put("attempt_id", attempt.id)
        .put("source", if (attempt.demo) "simulated" else "measured_cuff_roll").put("status", attempt.status.name)
        .put("scoring_version", attempt.scoringVersion).put("original_score", attempt.score)
        .put("score_eligible", attempt.review?.scoreEligible ?: JSONObject.NULL)
        .put("review", attempt.review?.json() ?: JSONObject.NULL)
        .put("target", attempt.target?.let { JSONObject().put("movements", it.movements).put("beat_s", it.beatSeconds).put("peak_deg", it.depthDegrees) } ?: JSONObject.NULL)
        .put("user_reported_intention", reflection.intention).put("user_reported_reflection", reflection.reflection)
}
