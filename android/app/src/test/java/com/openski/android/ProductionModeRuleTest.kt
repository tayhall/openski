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
}
