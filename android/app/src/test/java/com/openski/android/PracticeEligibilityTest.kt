package com.openski.android

import org.junit.Assert.*
import org.junit.Test

class PracticeEligibilityTest {
    private val attempt = Attempt("drill", 1, 83, 3, 166, false, target = PracticeTarget(12, 2.0, 20.0))
    private val evidence = PracticeEvidence("L", Mounting(0, 1), ScoringEvidenceQuality.SUFFICIENT, ScoringEvidenceSource.LIVE_ROLL, timingPolicy = "continuous_v1")

    @Test fun completionAndEvidenceAreIndependent() {
        assertEquals(PracticeEligibilityState.COMPLETE_JUDGEABLE, PracticeEligibility.evaluate(attempt, evidence).state)
        for ((status, expected) in listOf(PracticeStatus.ACTIVE to PracticeEligibilityState.ACTIVE,
            PracticeStatus.INCOMPLETE to PracticeEligibilityState.INCOMPLETE, PracticeStatus.INTERRUPTED to PracticeEligibilityState.INTERRUPTED)) {
            assertEquals(expected, PracticeEligibility.evaluate(attempt.copy(status = status), evidence).state)
        }
        assertEquals(83, attempt.score); assertEquals(3, attempt.stars); assertEquals(166, attempt.vert)
    }

    @Test fun recoveredReplayCannotUpgradeUnknownOrInsufficientLiveEvidence() {
        val unknown = evidence.copy(scoringQuality = ScoringEvidenceQuality.UNKNOWN, replayQuality = ReplayQuality.USABLE)
        assertEquals(PracticeEligibilityState.LEGACY_UNKNOWN, PracticeEligibility.evaluate(attempt, unknown).state)
        assertEquals(PracticeEligibilityState.AWAITING_RECOVERY, PracticeEligibility.evaluate(attempt, unknown.copy(recoveryPending = true)).state)
        assertEquals(PracticeEligibilityState.INSUFFICIENT_DATA, PracticeEligibility.evaluate(attempt,
            unknown.copy(scoringQuality = ScoringEvidenceQuality.INSUFFICIENT, recoveryPending = true)).state)
    }

    @Test fun firmwareScoreAndReplayQualityRemainSeparate() {
        val firmware = evidence.copy(scoringSource = ScoringEvidenceSource.FIRMWARE_EVENTS, replayQuality = ReplayQuality.POOR, recoveryPending = true)
        assertTrue(PracticeEligibility.evaluate(attempt, firmware).judgeable)
        assertFalse(PracticeEligibility.evaluate(attempt, evidence.copy(scoringSource = ScoringEvidenceSource.SIMULATED)).judgeable)
        assertTrue(PracticeEligibility.evaluate(attempt.copy(demo = true), evidence.copy(scoringSource = ScoringEvidenceSource.SIMULATED)).judgeable)
    }

    @Test fun sameKnownMeasuredContextIsComparable() {
        assertTrue(PracticeComparisonPolicy.evaluate(attempt, evidence, attempt.copy(id = "other"), evidence).comparable)
    }

    @Test fun matchingUnknownsDoNotEstablishCompatibility() {
        val result = PracticeComparisonPolicy.evaluate(attempt.copy(target = null), PracticeEvidence(), attempt.copy(target = null), PracticeEvidence())
        assertTrue(result.exclusions.containsAll(setOf(ComparisonExclusion.NOT_JUDGEABLE, ComparisonExclusion.UNKNOWN_TARGET, ComparisonExclusion.UNKNOWN_CONTEXT)))
        assertFalse(result.comparable)
    }

    @Test fun eachKnownIncompatibilityIsExplained() {
        fun exclusions(other: Attempt = attempt, otherEvidence: PracticeEvidence = evidence) =
            PracticeComparisonPolicy.evaluate(attempt, evidence, other, otherEvidence).exclusions
        assertTrue(ComparisonExclusion.DIFFERENT_SOURCE in exclusions(attempt.copy(demo = true), evidence.copy(scoringSource = ScoringEvidenceSource.SIMULATED)))
        assertTrue(ComparisonExclusion.DIFFERENT_SOURCE in exclusions(otherEvidence = evidence.copy(scoringSource = ScoringEvidenceSource.FIRMWARE_EVENTS)))
        assertTrue(ComparisonExclusion.DIFFERENT_DRILL in exclusions(attempt.copy(drillId = "other")))
        assertTrue(ComparisonExclusion.DIFFERENT_TARGET in exclusions(attempt.copy(target = PracticeTarget(10, 2.0, 20.0))))
        assertTrue(ComparisonExclusion.DIFFERENT_CONTEXT in exclusions(otherEvidence = evidence.copy(scoredSide = "R")))
        assertTrue(ComparisonExclusion.DIFFERENT_CONTEXT in exclusions(otherEvidence = evidence.copy(mounting = Mounting(1, -1))))
        assertTrue(ComparisonExclusion.DIFFERENT_SCORING_VERSION in exclusions(attempt.copy(scoringVersion = 2)))
        assertTrue(ComparisonExclusion.UNKNOWN_TIMING_POLICY in exclusions(otherEvidence = evidence.copy(timingPolicy = null)))
        assertTrue(ComparisonExclusion.DIFFERENT_TIMING_POLICY in exclusions(otherEvidence = evidence.copy(timingPolicy = "active_segments_v1")))
    }

    @Test fun equalUnsupportedVersionsAreExcludedUnlessExplicitlySupported() {
        val future = attempt.copy(scoringVersion = 2)
        assertTrue(ComparisonExclusion.UNSUPPORTED_SCORING_VERSION in PracticeComparisonPolicy.evaluate(future, evidence, future, evidence).exclusions)
        assertTrue(PracticeComparisonPolicy.evaluate(future, evidence, future, evidence, setOf(2)).comparable)
    }

    @Test fun explicitSimulatedSourceDoesNotRequireInventedPhysicalMounting() {
        val demo = attempt.copy(demo = true)
        val simulated = PracticeEvidence(scoringQuality = ScoringEvidenceQuality.SUFFICIENT, scoringSource = ScoringEvidenceSource.SIMULATED, timingPolicy = "continuous_v1")
        assertTrue(PracticeComparisonPolicy.evaluate(demo, simulated, demo, simulated).comparable)
    }

    @Test fun matchingUnsupportedTimingPoliciesRemainExcluded() {
        val future = evidence.copy(timingPolicy = "future_policy_v2")
        val comparison = PracticeComparisonPolicy.evaluate(attempt, future, attempt, future)
        assertFalse(comparison.comparable)
        assertTrue(ComparisonExclusion.UNKNOWN_TIMING_POLICY in comparison.exclusions)
    }
}
