package com.openski.android

import org.junit.Assert.*
import org.junit.Test

class ProductionModeRuleTest {
    @Test fun allowedWhenIdle() = assertNull(ProductionModeRule.blockedReason(recording = false, recovering = false))

    @Test fun blockedWhileRecordingBecauseTheRawStreamWouldStop() {
        val reason = ProductionModeRule.blockedReason(recording = true, recovering = false)!!
        assertTrue(reason.contains("recording", ignoreCase = true))
    }

    @Test fun blockedWhileRecoveringFlash() =
        assertNotNull(ProductionModeRule.blockedReason(recording = false, recovering = true))

    @Test fun diagnosticsIsNeverBlocked() = assertNull(ProductionModeRule.blockedReason(recording = true, recovering = true, production = false))

    @Test fun recordingIsAllowedWhenNoBootIsInProduction() =
        assertNull(ProductionModeRule.recordingBlockedReason(emptyList()))

    @Test fun recordingIsBlockedAndNamesTheBootInProduction() {
        val reason = ProductionModeRule.recordingBlockedReason(listOf("R"))!!
        assertTrue(reason.contains("right", ignoreCase = true))
        assertFalse(reason.contains("left", ignoreCase = true))
        assertTrue(reason.contains("diagnostics", ignoreCase = true))
    }

    @Test fun recordingNamesBothBootsInStableOrder() {
        val reason = ProductionModeRule.recordingBlockedReason(listOf("R", "L"))!!
        assertTrue(reason.indexOf("left") in 0 until reason.indexOf("right"))
    }
}
