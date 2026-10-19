package com.openski.android

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

class HistoryActivity : Activity() {
    private val io = Executors.newSingleThreadExecutor()
    private var kind = HistoryKind.DRILLS
    private var demo = false
    private var rows = emptyList<HistoryRow>()
    private var more = false
    private var loading = false
    private var error: String? = null
    private var generation = 0
    private var currentScroll: ScrollView? = null
    override fun onCreate(state: Bundle?) {
        super.onCreate(state); Snow.configureWindow(this)
        kind = state?.getString("kind")?.let { runCatching { HistoryKind.valueOf(it) }.getOrNull() } ?: HistoryKind.DRILLS
        demo = state?.getBoolean("demo") ?: intent.getBooleanExtra("demo", false)
    }
    override fun onResume() { super.onResume(); load(false) }
    override fun onSaveInstanceState(state: Bundle) { state.putString("kind", kind.name); state.putBoolean("demo", demo); super.onSaveInstanceState(state) }
    override fun onDestroy() { generation++; io.shutdown(); super.onDestroy() }
    private fun load(append: Boolean) {
        val token = ++generation; val selected = kind; val source = demo
        val before = if (append) rows.lastOrNull()?.let { HistoryCursor(it.time, it.id) } else null
        if (!append) { rows = emptyList(); currentScroll?.scrollTo(0,0) }
        loading = true; error = null; render()
        io.execute {
            val result = runCatching {
                // Trigger transactional legacy import before querying history.
                if (selected == HistoryKind.DRILLS) ProgressStore(this).ensureImported()
                LocalSessionStore(this).use { HistoryStore(it).page(selected, source, before) }
            }
            runOnUiThread {
                if (!isDestroyed && token == generation) {
                    loading = false
                    result.onSuccess { rows = if (append) rows + it else it; more = it.size == 50 }
                        .onFailure { error = "Could not load history. Saved records are retained." }
                    render()
                }
            }
        }
    }
    private fun open(row: HistoryRow) {
        val route = when (kind) {
            HistoryKind.RUNS -> Intent(this, SessionDetailActivity::class.java).putExtra("session_id", row.id)
            HistoryKind.DRILLS -> Intent(this, PracticeResultActivity::class.java).putExtra("attempt_id", row.id)
            HistoryKind.DAYS -> Intent(this, SkiDayActivity::class.java).putExtra(SkiDayActivity.EXTRA_DAY, row.id)
        }
        startActivity(route)
    }
    private fun render() {
        val scrollY = currentScroll?.scrollY ?: 0
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = Snow.dp(this@HistoryActivity, 20); setPadding(pad,pad,pad,pad)
        }
        fun text(value: String, type: Snow.Type = Snow.Type.BODY) { column.addView(Snow.text(this,value,type)) }
        fun button(value: String, selected: Boolean = false, action: () -> Unit) {
            column.addView(Snow.button(this,value,Snow.ButtonKind.SECONDARY,action).apply { isSelected = selected })
        }
        button("‹ Back") { finish() }; text("History",Snow.Type.HERO)
        HistoryKind.entries.forEach { option -> button(when(option) { HistoryKind.RUNS -> "Runs"; HistoryKind.DRILLS -> "Drills"; HistoryKind.DAYS -> "Ski days" },kind == option) { kind = option; load(false) } }
        if (kind != HistoryKind.DAYS) {
            text(if(demo) "Demo · simulated" else "My practice · measured")
            button(if(demo) "Show My practice" else "Show Demo") { demo = !demo; load(false) }
        } else text("Phone tracking and imported GPX days. Day data stays separate from boot practice.")
        rows.forEach { row ->
            val title = if(kind == HistoryKind.DRILLS) Programme.drill(row.title)?.title ?: row.title else row.title.ifBlank { if(kind == HistoryKind.RUNS) "Run" else "Ski day" }
            button("$title · ${DateFormat.getDateTimeInstance().format(Date(row.time))} · ${row.detail.lowercase().replace('_',' ')}") { open(row) }
        }
        if(loading) text("Loading history…")
        else if(error != null) { text(error!!); button("Retry") { load(rows.isNotEmpty()) } }
        else if(rows.isEmpty()) text("No saved records in this section.")
        else if(more) button("Load more") { load(true) }
        val scroll = ScrollView(this).apply { setBackgroundColor(Snow.SNOW); addView(column) }
        currentScroll = scroll
        SkiUi.applyInsets(scroll); setContentView(scroll)
        scroll.post { if (currentScroll === scroll) scroll.scrollTo(0,scrollY) }
    }
}
