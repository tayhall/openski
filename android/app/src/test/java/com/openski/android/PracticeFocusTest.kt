package com.openski.android

import org.junit.Assert.*
import org.junit.Test

class PracticeFocusTest {
    @Test fun focusMetadataIsOptionalAndUnknownValuesStayUnknown() {
        assertNull(PracticeFocus.decode(null))
        assertNull(PracticeFocus.decode("future_focus"))
        assertEquals(PracticeFocus.ROLL_RANGE, PracticeFocus.decode("ROLL_RANGE"))
        val review = PracticeReview(PracticeEvidence(), 0, null, null, null, null)
        assertNull(review.focus); assertNull(review.targetSource); assertNull(review.activeIntervals)
    }
    @Test fun intervalMetadataKeepsPhoneAlignedPauseGapWithoutInventingActiveTime() {
        val intervals = listOf(PracticeInterval(1000.0, 3000.0), PracticeInterval(8000.0, 11000.0))
        val review = PracticeReview(PracticeEvidence(), 0, null, null, null, null,
            focus = PracticeFocus.RHYTHM, targetSource = PracticeTargetSource.CONFIRMED_PRESET, activeIntervals = intervals)
        assertEquals(5000.0, review.activeIntervals!!.sumOf { it.endedAtMs - it.startedAtMs }, 0.0)
        assertEquals("confirmed preset", PracticeTargetSource.label(review.targetSource))
    }
    @Test fun invalidOrOverlappingActiveIntervalsAreRejected() {
        assertTrue(runCatching { PracticeInterval(Double.NaN, 3.0) }.isFailure)
        assertTrue(runCatching { PracticeInterval(4.0, 3.0) }.isFailure)
        assertTrue(runCatching { PracticeReview(PracticeEvidence(), 0, null, null, null, null,
            activeIntervals = listOf(PracticeInterval(1000.0, 4000.0), PracticeInterval(3000.0, 5000.0))) }.isFailure)
    }
}
