package com.openski.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartTextTest {
    @Test fun motionSummaryNamesTheChannelRangeAndExtremes() {
        val text = ChartText.motion(1, 120, 0.0, 2.4, -1.25, 3.0)
        assertTrue(text, text.startsWith("Gyro X (rad/s) from 0.0 to 2.4 seconds."))
        assertTrue(text, "120 samples, lowest -1.2" in text || "120 samples, lowest -1.3" in text)
        assertTrue(text, "highest 3.0" in text)
    }

    @Test fun motionSummaryAdmitsAnEmptyWindow() {
        assertTrue("No samples" in ChartText.motion(0, 0, 0.0, 1.0, null, null))
    }

    @Test fun profileSummaryNamesDirectionByLineStyleNotColour() {
        val profiles = listOf(
            RollProfile(true, 5, List(21) { 0.0 }, 12.34, 40),
            RollProfile(false, 4, List(21) { 0.0 }, 10.0, 55))
        val text = ChartText.turnProfile(profiles, positiveLeft = true)
        assertTrue(text, "Left direction, solid line: 5 turns, mean peak 12.3 degrees at 40 percent" in text)
        assertTrue(text, "Right direction, dashed line: 4 turns" in text)
        assertTrue(text, "lime" !in text && "blue" !in text)
    }

    @Test fun profileSummaryAdmitsNoData() {
        assertEquals("Mean boot roll profiles. No complete calibrated intervals.", ChartText.turnProfile(emptyList(), true))
    }
}
