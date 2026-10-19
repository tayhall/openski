package com.openski.android

import android.app.AlertDialog
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/** The mounting chooser is explicit; repeated service snapshots must not stack or dismiss it. */
object DrillMountingRegression {
    fun run(instrumentation: Instrumentation) {
        fun main(block: () -> Unit) {
            var result: Result<Unit>? = null
            instrumentation.runOnMainSync { result = runCatching(block) }
            result!!.getOrThrow()
        }
        val context = instrumentation.targetContext
        val prefs = context.getSharedPreferences("openski-training", Context.MODE_PRIVATE)
        for (side in listOf("L", "R")) {
            val key = "mount_$side"
            val original = if (prefs.contains(key)) prefs.getInt(key, -1) else null
            prefs.edit().remove(key).commit()
            val activity = instrumentation.startActivitySync(Intent(context, DrillActivity::class.java)
                .putExtra("drill", "slow-rolls").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as DrillActivity
            val apply = DrillActivity::class.java.getDeclaredMethod("apply", DrillState::class.java).apply { isAccessible = true }
            val picker = DrillActivity::class.java.getDeclaredField("mountingDialog").apply { isAccessible = true }
            val attempt = Attempt("slow-rolls", 1, 0, 0, 0, false, status = PracticeStatus.ACTIVE)
            val state = DrillState(DrillPhase.MOUNTING, Programme.drills.first(), attempt, side, "Choose mounting", 0, 0.0,
                RollTrace(floatArrayOf(), floatArrayOf()), 1f, null, null)
            fun find(view: View): TextView? {
                if (view is TextView && view.isClickable && view.text.toString() == "Choose mounting") return view
                if (view is ViewGroup) for (index in 0 until view.childCount) find(view.getChildAt(index))?.let { return it }
                return null
            }
            try {
                val serviceField = DrillActivity::class.java.getDeclaredField("service").apply { isAccessible = true }
                val deadline = android.os.SystemClock.elapsedRealtime() + 3000
                var connected = false
                while (!connected && android.os.SystemClock.elapsedRealtime() < deadline) {
                    main { connected = serviceField.get(activity) != null }
                    if (!connected) android.os.SystemClock.sleep(25)
                }
                check(connected) { "Drill screen did not bind to service" }
                var first: AlertDialog? = null
                main {
                    apply.invoke(activity, state)
                    checkNotNull(find(activity.window.decorView)).performClick()
                    first = picker.get(activity) as AlertDialog
                    repeat(5) { apply.invoke(activity, state) }
                    check(picker.get(activity) === first && first!!.isShowing)
                    checkNotNull(find(activity.window.decorView)).performClick()
                    check(picker.get(activity) === first) { "Stacked mounting dialogs" }
                    first!!.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
                }
                instrumentation.waitForIdleSync() // Dialog buttons dispatch their listeners through the main handler.
                var second: AlertDialog? = null
                main {
                    check(picker.get(activity) == null && !first!!.isShowing)
                    checkNotNull(find(activity.window.decorView)).performClick()
                    second = picker.get(activity) as AlertDialog
                    second!!.cancel()
                }
                instrumentation.waitForIdleSync()
                main {
                    check(picker.get(activity) == null && !second!!.isShowing)
                    checkNotNull(find(activity.window.decorView)).performClick()
                    val third = picker.get(activity) as AlertDialog
                    third.listView.performItemClick(third.listView.getChildAt(0), 0, 0)
                    check(ProgressStore(context).mounting(side) == Mounting.options.first())
                    check(!third.isShowing && picker.get(activity) == null)
                }
            } finally {
                main { activity.finish() }
                instrumentation.waitForIdleSync()
                prefs.edit().apply { if (original == null) remove(key) else putInt(key, original) }.commit()
            }
        }
    }
}
