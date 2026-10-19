package com.openski.android

/** Facts about the evidence used during scoring, independently of a later raw-data replay. */
enum class ScoringEvidenceQuality { UNKNOWN, SUFFICIENT, INSUFFICIENT }
enum class ScoringEvidenceSource { LIVE_ROLL, FIRMWARE_EVENTS, SIMULATED }
enum class ReplayQuality { UNKNOWN, USABLE, MARGINAL, POOR }

data class PracticeEvidence(
    val scoredSide: String? = null,
    val mounting: Mounting? = null,
    val scoringQuality: ScoringEvidenceQuality = ScoringEvidenceQuality.UNKNOWN,
    val scoringSource: ScoringEvidenceSource? = null,
    val replayQuality: ReplayQuality = ReplayQuality.UNKNOWN,
    val recoveryPending: Boolean = false,
    val timingPolicy: String? = null,
)

enum class PracticeEligibilityState {
    COMPLETE_JUDGEABLE, INCOMPLETE, INSUFFICIENT_DATA, AWAITING_RECOVERY, INTERRUPTED, LEGACY_UNKNOWN, ACTIVE
}

data class PracticeEligibilityResult(val state: PracticeEligibilityState, val reason: String, val nextAction: String) {
    val judgeable get() = state == PracticeEligibilityState.COMPLETE_JUDGEABLE
}

/** Presentation eligibility never rewrites a previously committed score, stars or reward. */
object PracticeEligibility {
    fun evaluate(attempt: Attempt, evidence: PracticeEvidence): PracticeEligibilityResult = when {
        attempt.status == PracticeStatus.ACTIVE -> PracticeEligibilityResult(PracticeEligibilityState.ACTIVE,
            "This practice is still active.", "Return to the active practice.")
        attempt.status == PracticeStatus.INTERRUPTED -> PracticeEligibilityResult(PracticeEligibilityState.INTERRUPTED,
            "Practice was interrupted; an uninterrupted scored set was not recovered.", "Review retained recordings or start a fresh set.")
        attempt.status == PracticeStatus.INCOMPLETE -> PracticeEligibilityResult(PracticeEligibilityState.INCOMPLETE,
            "The requested set was not completed.", "Repeat the target when you are ready.")
        evidence.scoringQuality == ScoringEvidenceQuality.INSUFFICIENT -> PracticeEligibilityResult(PracticeEligibilityState.INSUFFICIENT_DATA,
            "The evidence used during this set was insufficient to judge it.", "Check the connection and mounting before repeating.")
        evidence.scoringQuality == ScoringEvidenceQuality.SUFFICIENT && validSource(attempt, evidence) ->
            PracticeEligibilityResult(PracticeEligibilityState.COMPLETE_JUDGEABLE,
                if (attempt.demo) "Completed simulated cuff-roll practice." else "Completed cuff-roll practice with sufficient scoring evidence.",
                "Review the observed target difference before choosing another set.")
        evidence.recoveryPending -> PracticeEligibilityResult(PracticeEligibilityState.AWAITING_RECOVERY,
            "Scoring evidence is unknown and sensor recovery is pending.", "Keep the sensors nearby and finish recovery; replay cannot retrospectively validate the live score.")
        else -> PracticeEligibilityResult(PracticeEligibilityState.LEGACY_UNKNOWN,
            "The evidence used for this saved score was not recorded or is incompatible with its source.",
            "Keep this historical result and complete a new set for an evidence-backed comparison.")
    }

    internal fun validSource(attempt: Attempt, evidence: PracticeEvidence): Boolean =
        if (attempt.demo) evidence.scoringSource == ScoringEvidenceSource.SIMULATED
        else evidence.scoringSource in setOf(ScoringEvidenceSource.LIVE_ROLL, ScoringEvidenceSource.FIRMWARE_EVENTS)
}

enum class ComparisonExclusion {
    NOT_JUDGEABLE, DIFFERENT_SOURCE, DIFFERENT_DRILL, UNKNOWN_TARGET, DIFFERENT_TARGET,
    UNSUPPORTED_SCORING_VERSION, DIFFERENT_SCORING_VERSION, UNKNOWN_CONTEXT, DIFFERENT_CONTEXT,
    UNKNOWN_TIMING_POLICY, DIFFERENT_TIMING_POLICY
}

data class PracticeComparisonEligibility(val exclusions: Set<ComparisonExclusion>) {
    val comparable get() = exclusions.isEmpty()
}

/** Conservative descriptive comparisons; equal unknown values never establish compatibility. */
object PracticeComparisonPolicy {
    fun evaluate(first: Attempt, firstEvidence: PracticeEvidence, second: Attempt, secondEvidence: PracticeEvidence,
        supportedVersions: Set<Int> = setOf(1)): PracticeComparisonEligibility {
        val excluded = linkedSetOf<ComparisonExclusion>()
        if (!PracticeEligibility.evaluate(first, firstEvidence).judgeable ||
            !PracticeEligibility.evaluate(second, secondEvidence).judgeable) excluded += ComparisonExclusion.NOT_JUDGEABLE
        if (first.demo != second.demo || firstEvidence.scoringSource != secondEvidence.scoringSource)
            excluded += ComparisonExclusion.DIFFERENT_SOURCE
        if (first.drillId != second.drillId) excluded += ComparisonExclusion.DIFFERENT_DRILL
        if (first.target == null || second.target == null) excluded += ComparisonExclusion.UNKNOWN_TARGET
        else if (first.target != second.target) excluded += ComparisonExclusion.DIFFERENT_TARGET
        if (first.scoringVersion !in supportedVersions || second.scoringVersion !in supportedVersions)
            excluded += ComparisonExclusion.UNSUPPORTED_SCORING_VERSION
        if (first.scoringVersion != second.scoringVersion) excluded += ComparisonExclusion.DIFFERENT_SCORING_VERSION
        val supportedTimingPolicies = setOf("continuous_v1", PracticeTiming.POLICY)
        if (firstEvidence.timingPolicy !in supportedTimingPolicies || secondEvidence.timingPolicy !in supportedTimingPolicies)
            excluded += ComparisonExclusion.UNKNOWN_TIMING_POLICY
        if (!firstEvidence.timingPolicy.isNullOrBlank() && !secondEvidence.timingPolicy.isNullOrBlank() &&
            firstEvidence.timingPolicy != secondEvidence.timingPolicy) excluded += ComparisonExclusion.DIFFERENT_TIMING_POLICY
        // Simulated exercises have no physical mounting or scored boot; their explicit source establishes that fact.
        if (!first.demo || !second.demo) {
            if (firstEvidence.scoredSide !in listOf("L", "R") || secondEvidence.scoredSide !in listOf("L", "R") ||
                firstEvidence.mounting == null || secondEvidence.mounting == null) excluded += ComparisonExclusion.UNKNOWN_CONTEXT
            else if (firstEvidence.scoredSide != secondEvidence.scoredSide || firstEvidence.mounting != secondEvidence.mounting)
                excluded += ComparisonExclusion.DIFFERENT_CONTEXT
        }
        return PracticeComparisonEligibility(excluded)
    }
}
