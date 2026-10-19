package com.openski.android

import android.app.Instrumentation
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.util.UUID

/** Persisted recovery is visible in guided setup, without requiring a live sensor or a telemetry listener. */
object PreparationUiRegression {
    fun run(instrumentation: Instrumentation) {
        val context = instrumentation.targetContext
        fun main(block: () -> Unit) {
            var result: Result<Unit>? = null
            instrumentation.runOnMainSync { result = runCatching(block) }
            result!!.getOrThrow()
        }
        fun all(view: View): List<View> = listOf(view) + if (view is ViewGroup)
            (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()
        val isolated = "preparation-${UUID.randomUUID()}.db"
        try {
            LocalSessionStore(context, isolated).use { db ->
                db.createSession("old-pending", 1); db.finishSession("old-pending", 2)
                db.createCapture("old-pending", "L", "old", 1, "pending")
                db.createSession("new-saved", 3); db.finishSession("new-saved", 4)
                val saved = db.createCapture("new-saved", "R", "new", 3, "erased")
                check(db.pendingRecoverySides() == setOf("L"))
                check(db.recoveryCaptures().map { it.sessionId }.toSet() == setOf("old-pending", "new-saved"))
                check(db.recoveryCaptures("new-saved").map { it.id } == listOf(saved.id))
            }
            LocalSessionStore(context, isolated).use { db ->
                check(db.pendingRecoverySides() == setOf("L")) { "Reopen lost pending recovery" }
                check(RecoverySummary.item(db.recoveryCaptures("old-pending").single()).canRetry)
            }
        } finally { context.deleteDatabase(isolated) }

        val sessionId = "preparation-ui-${UUID.randomUUID()}"
        val prefs = context.getSharedPreferences(SensorSessionService.PREFS, android.content.Context.MODE_PRIVATE)
        val originallyPaused = prefs.getBoolean("recovery_paused", false)
        val hadPausePreference = prefs.contains("recovery_paused")
        var captureId: String? = null
        LocalSessionStore(context).use { db ->
            db.createSession(sessionId, 5); db.finishSession(sessionId, 6)
            captureId = db.createCapture(sessionId, "R", "test-preparation", 5, "pending").id
        }
        try {
            repeat(2) { index ->
                val activity = instrumentation.startActivitySync(Intent(context, BootSetupActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as BootSetupActivity
                try {
                    val deadline = android.os.SystemClock.elapsedRealtime() + 5000
                    var loaded = false
                    while (!loaded && android.os.SystemClock.elapsedRealtime() < deadline) {
                        main {
                            val texts = all(activity.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }
                            loaded = texts.any { text -> text.contains(if (index == 1) "Right boot · recovery paused" else "Right boot ·") }
                        }
                        if (!loaded) android.os.SystemClock.sleep(30)
                    }
                    check(loaded) { "Persisted recovery did not appear in setup" }
                    main {
                        val field = BootSetupActivity::class.java.getDeclaredField("service").apply { isAccessible = true }
                        val service = field.get(activity) as SensorSessionService
                        check(service.productionBlockedReason()?.contains("recovery") == true)
                        check(!service.canAssign("R", "replacement"))
                        check(!service.forgetSensor("R"))
                        val before = LocalSessionStore(context).use { it.recoveryCaptures(sessionId).single() }
                        check(!service.setSensorMode("R", false))
                        check(LocalSessionStore(context).use { it.recoveryCaptures(sessionId).single() } == before)
                        if (index == 0) {
                            check(!service.recordingActive())
                            service.onStartCommand(Intent(context, SensorSessionService::class.java)
                                .setAction(SensorSessionService.ACTION_PAUSE_RECOVERY), 0, 0)
                            check(prefs.getBoolean("recovery_paused", false))
                        }
                    }
                } finally { main { activity.finish() }; instrumentation.waitForIdleSync() }
            }
        } finally {
            context.stopService(Intent(context, SensorSessionService::class.java))
            prefs.edit().apply { if (hadPausePreference) putBoolean("recovery_paused", originallyPaused) else remove("recovery_paused") }.commit()
            LocalSessionStore(context).use { db ->
                captureId?.let { db.writableDatabase.delete("captures", "id=?", arrayOf(it)) }
                check(db.deleteSession(sessionId))
            }
        }
    }
}
