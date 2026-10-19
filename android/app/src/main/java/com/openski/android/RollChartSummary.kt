package com.openski.android

import java.util.Locale
import kotlin.math.abs

/** Facts for chart accessibility; source and comparison labels come from the owning result. */
object RollChartSummary {
    fun describe(trace: RollTrace, source: String, target: Double, comparison: RollTrace?, comparisonLabel: String): String {
        fun one(value: Float) = String.format(Locale.US, "%.1f", value)
        fun facts(t: RollTrace): String = if (t.degrees.isEmpty()) "no samples"
            else "${one(t.duration)} seconds, peak absolute cuff roll ${one(t.degrees.maxOf { abs(it) })} degrees"
        return buildString {
            append("$source. Solid line: ${facts(trace)}. ")
            if (target > 0) append("Target depth ${one(target.toFloat())} degrees, shaded band. ")
            else append("No target depth recorded. ")
            comparison?.let { append("Dashed line: $comparisonLabel, ${facts(it)}. ") }
            append("Middle line: neutral. Cuff roll is not ski edge angle.")
        }
    }
}
