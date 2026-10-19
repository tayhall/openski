package com.openski.android

import org.junit.Assert.*
import org.junit.Test

class RollChartSummaryTest {
    private val trace = RollTrace(floatArrayOf(0f, 1.5f, 3f), floatArrayOf(0f, -24f, 18f))

    @Test fun measuredFactsIncludeNegativePeakAndSavedTarget() {
        val text = RollChartSummary.describe(trace, "Measured cuff roll", 20.0, trace, "previous attempt")
        assertTrue(text.contains("3.0 seconds, peak absolute cuff roll 24.0 degrees"))
        assertTrue(text.contains("Target depth 20.0 degrees"))
        assertTrue(text.contains("Dashed line: previous attempt"))
    }

    @Test fun reconstructedTraceWithoutTargetDoesNotInventInstructions() {
        val text = RollChartSummary.describe(trace, "Reconstructed half-turns", 0.0, null, "target wave")
        assertTrue(text.startsWith("Reconstructed half-turns"))
        assertTrue(text.contains("No target depth recorded"))
        assertFalse(text.contains("shaded"))
        assertFalse(text.contains("Dashed line"))
    }

    @Test fun demoAndEmptyComparisonAreLabelledHonestly() {
        val text = RollChartSummary.describe(RollTrace(floatArrayOf(), floatArrayOf()), "Simulated cuff roll", 15.0, trace, "target wave")
        assertTrue(text.contains("Simulated cuff roll. Solid line: no samples"))
        assertTrue(text.contains("Dashed line: target wave"))
    }
}
