package com.openski.android

import android.animation.ValueAnimator
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The card that shows a run's two waves: a sentence, the numbers, one tile per coaching window, and a chart for the
 * selected window (recorded line solid, target pale, the target depth as a band) with a replay. Styled in the light
 * Snow look so it works inside either look. Dry-ski boot roll against a training target, not on-snow technique.
 */
object RunComparisonCard {
    fun build(context: Context, comparison: RunComparison): View {
        val card = Snow.card(context, 18)
        fun text(value: String, type: Snow.Type = Snow.Type.BODY, tint: Int = Snow.INK) = Snow.text(context, value, type, tint)
        fun LinearLayout.add(view: View, top: Int = 0) =
            addView(view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Snow.dp(context, top) })

        card.addView(text("How your turns compared", Snow.Type.TITLE))
        if (comparison.demo) card.add(text("Demo run · simulated turns", Snow.Type.CAPTION, Snow.INK_SOFT), 2)
        card.add(text(comparison.sentence), 6)
        numberLines(comparison).forEach { card.add(text(it, Snow.Type.CAPTION, Snow.INK_SOFT), 6) }
        if (comparison.windows.isEmpty()) return card

        var selected = comparison.defaultWindow.coerceIn(0, comparison.windows.lastIndex)
        val tiles = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val chartHolder = FrameLayout(context)
        var animator: ValueAnimator? = null
        var line: CarvedLineView? = null
        val tileViews = mutableListOf<TextView>()

        fun styleTiles() = tileViews.forEachIndexed { index, tile ->
            val on = index == selected
            tile.setTextColor(if (on) Snow.SNOW else Snow.INK)
            tile.background = Snow.rounded(context, if (on) Snow.INK else Snow.PAPER, 16, Snow.INK, 2)
            tile.contentDescription = tileDescription(index, comparison.windows[index]) + if (on) ", selected" else ""
        }

        fun showChart() {
            animator?.cancel()
            val window = comparison.windows[selected]
            val depth = comparison.target?.depthDegrees ?: max(10.0, window.recorded.degrees.maxOfOrNull { kotlin.math.abs(it) }?.toDouble() ?: 10.0)
            line = CarvedLineView(context, window.recorded, window.target, depth, Snow.BLUE)
            chartHolder.removeAllViews()
            chartHolder.addView(line, FrameLayout.LayoutParams(-1, -2))
            styleTiles()
        }

        comparison.windows.forEachIndexed { index, window ->
            val tile = TextView(context).apply {
                text = "${marker(window.verdict)}\n${index + 1}"
                textSize = 16f
                gravity = Gravity.CENTER
                minWidth = Snow.dp(context, 56); minHeight = Snow.dp(context, 56)
                isClickable = true; isFocusable = true
                setOnClickListener { selected = index; showChart() }
            }
            tileViews.add(tile)
            tiles.addView(tile, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = Snow.dp(context, 8) })
        }
        card.add(HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(tiles) }, 12)
        card.add(chartHolder, 12)
        showChart()

        card.add(Snow.button(context, "Play", Snow.ButtonKind.SECONDARY) {
            val chart = line ?: return@button
            animator?.cancel()
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = (comparison.windows[selected].recorded.duration * 1000).toLong().coerceIn(1500L, 6000L)
                interpolator = LinearInterpolator()
                addUpdateListener { chart.progress = it.animatedValue as Float }
                start()
            }
        }, 12)
        card.add(text(if (comparison.target != null)
            "Reconstructed from your half-turns. The pale line is the target. Dry-ski boot roll against a training target, not on-snow technique."
        else "Reconstructed from your half-turns. Dry-ski boot roll, not on-snow technique.", Snow.Type.CAPTION, Snow.INK_SOFT), 10)
        return card
    }

    private fun marker(verdict: Verdict?) = when (verdict) {
        Verdict.POSITIVE -> "▲"
        Verdict.NEGATIVE -> "▼"
        Verdict.NONE -> "●"
        null -> "–"
    }

    private fun tileDescription(index: Int, window: ComparisonWindow): String {
        val outcome = when (window.verdict) {
            Verdict.POSITIVE -> "matched the target"
            Verdict.NEGATIVE -> "off target"
            Verdict.NONE -> "close"
            null -> if (window.partial) "too few turns for a verdict" else "no verdict"
        }
        return "Window ${index + 1}, $outcome"
    }

    /** The plain facts behind the sentence, one per line. */
    internal fun numberLines(comparison: RunComparison): List<String> {
        val n = comparison.numbers
        val target = comparison.target
        val lines = mutableListOf<String>()
        if (n.turns == 0) return lines
        fun one(value: Double) = String.format(Locale.US, "%.1f", value)
        if (target != null && n.depthDifferenceDegrees != null)
            lines += "Depth: ${(target.depthDegrees + n.depthDifferenceDegrees).roundToInt()}° average, aim ${target.depthDegrees.roundToInt()}°"
        if (target != null && n.beatDifferenceSeconds != null)
            lines += "Beat: ${one(target.beatSeconds + n.beatDifferenceSeconds)} s average, aim ${one(target.beatSeconds)} s"
        if (n.leftDepthDegrees != null && n.rightDepthDegrees != null)
            lines += "Left turns ${n.leftDepthDegrees.roundToInt()}° · right turns ${n.rightDepthDegrees.roundToInt()}°"
        if (n.matchedShare != null) lines += "Matched ${(n.matchedShare * n.windowsWithVerdict).roundToInt()} of ${n.windowsWithVerdict} windows"
        return lines
    }
}
