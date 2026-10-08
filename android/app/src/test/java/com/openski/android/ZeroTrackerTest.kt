package com.openski.android

import org.junit.Assert.*
import org.junit.Test

class ZeroTrackerTest {
    @Test fun aBootThatNeverGotACommandCountsAsZeroedWhenItReportsSo() {
        val tracker = ZeroTracker()
        tracker.frame("L", true, 500)
        assertTrue(tracker.isZeroed("L"))
        assertFalse(tracker.isZeroed("R"))
    }

    @Test fun aReportAlreadyInFlightWhenTheCommandWasSentIsIgnored() {
        val tracker = ZeroTracker()
        tracker.frame("L", true, 0)            // zeroed earlier
        tracker.requested("L", 10_000)         // zero again
        tracker.frame("L", true, 10_300)       // a stale heartbeat from before the command was handled
        assertFalse(tracker.isZeroed("L"))
        tracker.frame("L", false, 10_900)      // the boot is waiting for stillness
        assertFalse(tracker.isZeroed("L"))
        tracker.frame("L", true, 11_900)       // a second later it reports zeroed for real
        assertTrue(tracker.isZeroed("L"))
    }

    @Test fun losingTheZeroClearsIt() {
        val tracker = ZeroTracker()
        tracker.frame("R", true, 0)
        tracker.frame("R", false, 1000)
        assertFalse(tracker.isZeroed("R"))
    }

    @Test fun eachBootIsTrackedSeparately() {
        val tracker = ZeroTracker()
        tracker.requested("L", 0); tracker.requested("R", 0)
        tracker.frame("L", true, 1500)
        assertTrue(tracker.isZeroed("L"))
        assertFalse(tracker.isZeroed("R"))
    }
}
