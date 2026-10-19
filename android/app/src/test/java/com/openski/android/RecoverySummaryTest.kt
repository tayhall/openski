package com.openski.android

import org.junit.Assert.*
import org.junit.Test

class RecoverySummaryTest {
    private val capture = SensorCapture("c", "session", "L", "sensor", 1, "pending", 200, 0, "")

    @Test fun completeDownloadDoesNotClaimValidatedOrErased() {
        val full = RecoverySummary.item(capture, transferPercent = 100)
        assertEquals(RecoveryPhase.DOWNLOADING, full.phase)
        assertFalse(full.validatedCopy)
        assertTrue(full.pending)
        val checking = RecoverySummary.item(capture, transferPercent = 100, validating = true)
        assertEquals(RecoveryPhase.VALIDATING, checking.phase)
        assertFalse(checking.validatedCopy)
    }

    @Test fun verifiedPhoneCopyStillHasPendingSensorErase() {
        val verified = RecoverySummary.item(capture.copy(state = "verified"), paused = true, failed = true)
        assertEquals(RecoveryPhase.SAVED_AWAITING_ERASE, verified.phase)
        assertTrue(verified.validatedCopy && verified.pending && verified.canRetry)
        assertTrue(verified.label().contains("erase pending"))
        val erased = RecoverySummary.item(capture.copy(state = "erased"), paused = true)
        assertEquals(RecoveryPhase.SAVED, erased.phase)
        assertTrue(erased.validatedCopy)
        assertFalse(erased.pending || erased.canRetry)
    }

    @Test fun restartPendingAndPausedCaptureRemainRecoverable() {
        val waiting = RecoverySummary.item(capture.copy(state = "recording"))
        assertEquals(RecoveryPhase.WAITING, waiting.phase)
        assertTrue(waiting.canRetry)
        val paused = RecoverySummary.item(capture, paused = true)
        assertEquals(RecoveryPhase.PAUSED, paused.phase)
        assertTrue(paused.canRetry && paused.pending)
    }

    @Test fun failedTransferRetainsRetryButMissingSensorCopyDoesNot() {
        val failed = RecoverySummary.item(capture.copy(error = "Download gap"), failed = true)
        assertEquals(RecoveryPhase.FAILED, failed.phase)
        assertTrue(failed.canRetry && failed.pending)
        assertEquals("Download gap", failed.detail)
        assertEquals(RecoveryPhase.FAILED, RecoverySummary.item(capture.copy(error = "Download gap")).phase)
        assertEquals(RecoveryPhase.DOWNLOADING, RecoverySummary.item(capture.copy(error = "Download gap"), transferPercent = 12).phase)
        val lost = RecoverySummary.item(capture.copy(state = "lost"), failed = true)
        assertFalse(lost.canRetry || lost.pending || lost.validatedCopy)
        assertTrue(lost.detail.contains("live samples"))
    }

    @Test fun retryFailureDoesNotHideAnEarlierValidatedPhoneCopy() {
        val failed = RecoverySummary.item(capture.copy(error = "Erase not acknowledged"), hasCommittedCopy = true)
        assertEquals(RecoveryPhase.FAILED, failed.phase)
        assertTrue(failed.pending && failed.canRetry && failed.validatedCopy)
        assertTrue(failed.detail.contains("previously validated phone copy is retained"))
        assertTrue(failed.detail.contains("Erase not acknowledged"))
    }

    @Test fun liveOnlyAndActiveCaptureAreNotPresentedAsSavedBackups() {
        val active = RecoverySummary.item(capture.copy(state = "recording"), activeSessionId = "session")
        assertEquals(RecoveryPhase.RECORDING, active.phase)
        assertFalse(active.canRetry || active.validatedCopy)
        val unavailable = RecoverySummary.item(capture.copy(state = "unavailable"))
        assertEquals(RecoveryPhase.LIVE_ONLY, unavailable.phase)
        assertFalse(unavailable.pending || unavailable.validatedCopy)
    }
}
