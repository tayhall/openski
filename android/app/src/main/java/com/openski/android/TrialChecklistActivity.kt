package com.openski.android

import android.app.Activity
import android.os.Bundle
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Read-only garden trial checklist (T0 to T6) with a tick box per trial. Geek mode look (SkiUi); content in [GardenTrials]. */
class TrialChecklistActivity : Activity() {
    private lateinit var store: GardenTrialStore
    private lateinit var progress: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SkiUi.configureWindow(this)
        store = GardenTrialStore(this)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val inset = dp(20)
            setPadding(inset, inset, inset, inset * 2)
        }
        column.addView(SkiUi.label(this, "GEEK MODE · GARDEN TRIALS", 12f, SkiUi.ACCENT, true))
        column.addView(SkiUi.label(this, "Trials checklist", 28f, SkiUi.TEXT, true).apply { setPadding(0, dp(4), 0, dp(6)) })
        column.addView(SkiUi.label(this, GardenTrials.INTRO, 14f, SkiUi.SECONDARY), spaced(8))
        progress = SkiUi.label(this, "", 13f, SkiUi.ACCENT, true)
        column.addView(progress, spaced(16))
        val done = store.done()
        GardenTrials.all.forEach { trial -> column.addView(card(trial, trial.id in done), spaced(12)) }
        column.addView(SkiUi.label(this, "Ticks are kept on this phone only. Record each result as a dated entry in docs/bench-results.md.", 12f, SkiUi.MUTED))
        val root = ScrollView(this).apply { setBackgroundColor(SkiUi.CANVAS); isFillViewport = true; addView(column) }
        SkiUi.applyInsets(root)
        setContentView(root)
        renderProgress()
    }

    private fun card(trial: GardenTrial, done: Boolean) = SkiUi.card(this).apply {
        addView(CheckBox(this@TrialChecklistActivity).apply {
            text = "${trial.id}  ${trial.title}"
            textSize = 16f
            setTextColor(SkiUi.TEXT)
            buttonTintList = android.content.res.ColorStateList.valueOf(SkiUi.ACCENT)
            minHeight = dp(48)
            isChecked = done
            setOnCheckedChangeListener { _, checked -> store.setDone(trial.id, checked); renderProgress() }
        })
        addView(SkiUi.label(this@TrialChecklistActivity, "WHAT TO DO", 11f, SkiUi.MUTED, true).apply { setPadding(0, dp(10), 0, dp(4)) })
        addView(SkiUi.label(this@TrialChecklistActivity, trial.steps.mapIndexed { i, s -> "${i + 1}  $s" }.joinToString("\n"), 14f, SkiUi.SECONDARY))
        addView(SkiUi.label(this@TrialChecklistActivity, "EXPORT AND LOOK AT", 11f, SkiUi.MUTED, true).apply { setPadding(0, dp(12), 0, dp(4)) })
        val files = if (trial.exports.isEmpty()) "Session ZIP (the result is on the Analysis tab)"
            else "Session ZIP · " + trial.exports.joinToString(", ")
        addView(SkiUi.label(this@TrialChecklistActivity, files, 14f, SkiUi.SKY))
        addView(SkiUi.label(this@TrialChecklistActivity, trial.judge, 12f, SkiUi.MUTED).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(0, dp(8), 0, 0)
        })
    }

    private fun renderProgress() { progress.text = GardenTrials.progress(store.done()) }

    private fun dp(value: Int) = SkiUi.dp(this, value)
    private fun spaced(bottom: Int) = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(bottom) }
}
