package com.openski.android

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import java.util.UUID

/** Real View/accessibility and exact-record regressions, without synthetic BLE or audio claims. */
class PracticeUiRegression(private val instrumentation: Instrumentation) {
    private val targetContext get() = instrumentation.targetContext
    private fun runOnMainSync(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun waitForIdleSync() = instrumentation.waitForIdleSync()

    private fun main(block: () -> Unit) {
        var result: Result<Unit>? = null
        runOnMainSync { result = runCatching(block) }
        result!!.getOrThrow()
    }
    private fun all(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()
    private fun open(type: Class<out Activity>, key: String? = null, value: String? = null): Activity =
        instrumentation.startActivitySync(Intent(targetContext, type).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (key != null) putExtra(key, value)
        })
    private fun waitUntil(test: () -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 5000
        var ok = false
        while (!ok && android.os.SystemClock.elapsedRealtime() < deadline) {
            main { ok = test() }
            if (!ok) android.os.SystemClock.sleep(30)
        }
        check(ok) { "UI did not reach expected state" }
    }
    private fun texts(a: Activity) = all(a.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }

    fun run() {
        val home = open(HomeActivity::class.java) as HomeActivity
        try {
            main {
                val button = Snow.button(targetContext, "Start practice") {}
                val root = home.window.decorView as ViewGroup
                root.addView(button)
                val buttonInfo = button.createAccessibilityNodeInfo()
                check(buttonInfo.className.toString() == "android.widget.Button")
                check(button.minimumHeight >= Snow.dp(targetContext, 48))
                val chart = CarvedLineView(targetContext, RollTrace(floatArrayOf(0f, 2f), floatArrayOf(0f, -21f)), null, 0.0, Snow.BLUE,
                    "Reconstructed half-turns")
                root.addView(chart)
                val chartInfo = chart.createAccessibilityNodeInfo()
                val summary = chartInfo.contentDescription.toString()
                check(summary.contains("21.0 degrees") && summary.contains("No target depth"))
                root.removeView(button); root.removeView(chart)
            }
            waitForIdleSync()
            main {
                val navField = HomeActivity::class.java.getDeclaredField("navItems").apply { isAccessible = true }
                @Suppress("UNCHECKED_CAST")
                val items = navField.get(home) as List<TextView>
                items[1].performClick()
                check(items[1].createAccessibilityNodeInfo().isSelected)
                check(!items[0].createAccessibilityNodeInfo().isSelected)
                check(items[1].createAccessibilityNodeInfo().className.toString() == "android.widget.Button")
            }
        } finally { main { home.finish() }; waitForIdleSync() }
        val coach = open(CoachingActivity::class.java) as CoachingActivity
        try {
            val serviceField = CoachingActivity::class.java.getDeclaredField("service").apply { isAccessible = true }
            waitUntil { serviceField.get(coach) != null }
            val stateField = CoachingActivity::class.java.getDeclaredField("state").apply { isAccessible = true }
            val render = CoachingActivity::class.java.getDeclaredMethod("render").apply { isAccessible = true }
            main {
                stateField.set(coach, CoachState(running = true, demo = true))
                render.invoke(coach)
            }
            waitForIdleSync()
            main {
                val scroll = all(coach.window.decorView).filterIsInstance<ScrollView>().single()
                val stop = all(scroll).filterIsInstance<TextView>().single { it.text.toString() == "Stop coaching" }
                stop.isFocusableInTouchMode = true
                stop.requestFocus()
                scroll.scrollTo(0, 80)
                val before = scroll.scrollY
                repeat(6) { index ->
                    stateField.set(coach, CoachState(running = true, demo = true, message = "Window $index",
                        last = CoachVerdict(Verdict.POSITIVE, 80, 80, 80, 80, null, 2.0, 20.0, false)))
                    render.invoke(coach)
                    check(all(coach.window.decorView).filterIsInstance<ScrollView>().single() === scroll)
                    check(stop.hasFocus() && scroll.scrollY == before) { "Verdict replaced focus/scroll" }
                }
            }
        } finally { main { coach.finish() }; waitForIdleSync() }

        val run = open(RunActivity::class.java) as RunActivity
        try {
            val serviceField = RunActivity::class.java.getDeclaredField("service").apply { isAccessible = true }
            waitUntil { serviceField.get(run) != null }
            val done = RunActivity::class.java.getDeclaredMethod("done", android.widget.LinearLayout::class.java, RunState::class.java).apply { isAccessible = true }
            val runId = SensorSessionService::class.java.getDeclaredField("lastRunId").apply { isAccessible = true }
            val monitor = instrumentation.addMonitor(SessionDetailActivity::class.java.name, null, false)
            var previousId: Any? = null
            try {
                main {
                    val service = serviceField.get(run) as SensorSessionService
                    previousId = runId.get(service)
                    runId.set(service, "exact-ui-run")
                    val column = android.widget.LinearLayout(run)
                    done.invoke(run, column, service.runState().copy(phase = RunPhase.DONE))
                    all(column).filterIsInstance<TextView>().single { it.text.toString() == "Open saved run" }.performClick()
                }
                val detail = instrumentation.waitForMonitorWithTimeout(monitor, 5000) ?: error("Result link did not open session detail")
                try {
                    check(detail.intent.getStringExtra("session_id") == "exact-ui-run") { "Result link lost exact session identity" }
                    waitUntil { texts(detail).contains("Session unavailable") }
                } finally { main { detail.finish() }; waitForIdleSync() }
            } finally {
                instrumentation.removeMonitor(monitor)
                main { (serviceField.get(run) as? SensorSessionService)?.let { runId.set(it, previousId) } }
            }
        } finally { main { run.finish() }; waitForIdleSync() }

        val ids = listOf("ui-${UUID.randomUUID()}", "ui-${UUID.randomUUID()}")
        LocalSessionStore(targetContext).use { db ->
            val records = PracticeStore(db)
            records.record(Attempt("slow-rolls", 1, 11, 1, 2, true, id = ids[0]))
            records.record(Attempt("slow-rolls", 2, 92, 3, 7, true, id = ids[1], sessionId = "missing-ui-raw"))
        }
        try {
            repeat(2) {
                val result = open(PracticeResultActivity::class.java, "attempt_id", ids[1])
                try {
                    waitUntil { texts(result).any { it.startsWith("92 / 100") } }
                    main {
                        val text = texts(result).joinToString("\n")
                        check(!text.contains("11 / 100"))
                        check(text.contains("target was not recorded") && text.contains("raw session is no longer"))
                    }
                } finally { main { result.finish() }; waitForIdleSync() }
            }
            val missing = open(PracticeResultActivity::class.java, "attempt_id", "missing-ui-attempt")
            try { waitUntil { texts(missing).contains("Saved practice unavailable") } }
            finally { main { missing.finish() }; waitForIdleSync() }
        } finally {
            LocalSessionStore(targetContext).use { db -> ids.forEach { db.writableDatabase.delete("practice_attempts", "id=?", arrayOf(it)) } }
        }
        val missingRaw = open(SessionDetailActivity::class.java, "session_id", "missing-ui-raw")
        try { waitUntil { texts(missingRaw).contains("Session unavailable") } }
        finally { main { missingRaw.finish() }; waitForIdleSync() }
    }
}
