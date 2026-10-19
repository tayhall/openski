package com.openski.android

import org.junit.Assert.*
import org.junit.Test

class ModeRequestTrackerTest {
    private val tracker = ModeRequestTracker()

    @Test fun oldTrainingAcknowledgementCannotClearAnInterveningOnSnowCommand() {
        tracker.queued("L", false, 0)
        tracker.queued("L", true, 1)
        tracker.queued("L", false, 2)
        tracker.acknowledged("L", 0, false)
        assertTrue(tracker.pending("L", 0))
        tracker.acknowledged("L", 0, true)
        assertTrue(tracker.pending("L", 0))
        tracker.acknowledged("L", 0, false)
        assertFalse(tracker.pending("L", 0))
    }

    @Test fun laterMatchingAcknowledgementCanCoverAnEarlierLostAcknowledgement() {
        tracker.queued("L", true, 0)
        tracker.queued("L", false, 1)
        tracker.acknowledged("L", 0, false)
        assertFalse(tracker.pending("L", 0))
    }

    @Test fun rejectedCommandStaysPendingOnlyWhileAnotherCommandIsOutstanding() {
        tracker.queued("L", false, 0)
        tracker.queued("L", true, 1)
        tracker.acknowledged("L", 1, false)
        assertTrue(tracker.pending("L", 0))
        tracker.acknowledged("L", 1, true)
        assertFalse(tracker.pending("L", 0))
        tracker.acknowledged("L", 0, true)
        assertFalse(tracker.pending("L", 0))
    }

    @Test fun lostAcknowledgementStopsCountingAsPendingAfterTheTimeout() {
        tracker.queued("L", true, 1000)
        assertTrue(tracker.pending("L", 10_999))
        assertFalse(tracker.pending("L", 11_000))
        assertTrue(tracker.shouldSend("L", true, 11_000))
    }

    @Test fun onlyIdenticalDesiredRequestsAreThrottledForTwoSeconds() {
        assertTrue(tracker.shouldSend("L", true, 0))
        tracker.queued("L", true, 100)
        assertFalse(tracker.shouldSend("L", true, 2099))
        assertTrue(tracker.shouldSend("L", true, 2100))
        assertTrue(tracker.shouldSend("L", false, 101))
        assertTrue(tracker.shouldSend("R", true, 101))
    }

    @Test fun matchingAcknowledgementCanClearOnlySameTargetRemainder() {
        tracker.queued("L", false, 0)
        tracker.queued("L", false, 2000)
        tracker.acknowledged("L", 0, false)
        assertFalse(tracker.pending("L", 0))
        tracker.acknowledged("R", 0, false)
        assertFalse(tracker.pending("R", 0))
    }

    @Test fun unrelatedAcknowledgementCannotClearPendingAndClearIsSideSpecific() {
        tracker.queued("L", true, 0)
        tracker.queued("R", false, 0)
        tracker.acknowledged("L", 0, false)
        assertTrue(tracker.pending("L", 0))
        tracker.clear("L")
        assertFalse(tracker.pending("L", 0))
        assertTrue(tracker.pending("R", 0))
    }
}
