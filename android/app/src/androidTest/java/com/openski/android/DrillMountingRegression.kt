package com.openski.android

import android.app.AlertDialog
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.os.SystemClock

/** Exercises the real UI ticker with one streaming boot, without needing Bluetooth hardware. */
object DrillMountingRegression {
    fun run(instrumentation: Instrumentation) {
        val context = instrumentation.targetContext
        val prefs = context.getSharedPreferences("openski-training", Context.MODE_PRIVATE)
        for (side in listOf("L", "R")) {
            val key = "mount_$side"
            val original = if (prefs.contains(key)) prefs.getInt(key, -1) else null
            prefs.edit().remove(key).commit()
            val activity = instrumentation.startActivitySync(
                Intent(context, DrillActivity::class.java)
                    .putExtra("drill", "slow-rolls")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ) as DrillActivity
            val phase = DrillActivity::class.java.getDeclaredField("phase").apply { isAccessible = true }
            val picker = DrillActivity::class.java.getDeclaredField("mountingDialog").apply { isAccessible = true }
            fun connect() = instrumentation.runOnMainSync {
                phase.set(activity, phase.type.enumConstants!!.first { it.toString() == "CONNECTING" })
                activity.onSensorSample(side, SensorSample(1, 0, 0f, 0f, 9.81f, 0f, 0f, 0f))
            }
            fun dialog(): AlertDialog {
                var result: AlertDialog? = null
                instrumentation.runOnMainSync { result = picker.get(activity) as? AlertDialog }
                return checkNotNull(result) { "Missing mounting picker for $side" }
            }
            try {
                connect()
                SystemClock.sleep(300)
                val first = dialog()
                SystemClock.sleep(500) // Several connection ticks used to stack new dialogs here.
                instrumentation.runOnMainSync {
                    check(phase.get(activity)?.toString() == "MOUNTING")
                    check(picker.get(activity) === first && first.isShowing)
                    first.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
                }
                SystemClock.sleep(300)
                instrumentation.runOnMainSync {
                    check(phase.get(activity)?.toString() == "INTRO")
                    check(picker.get(activity) == null && !first.isShowing)
                }
                connect()
                SystemClock.sleep(300)
                val second = dialog()
                instrumentation.runOnMainSync { second.cancel() } // Android Back/outside dismissal.
                SystemClock.sleep(300)
                instrumentation.runOnMainSync { check(phase.get(activity)?.toString() == "INTRO") }
                connect()
                SystemClock.sleep(300)
                val third = dialog()
                instrumentation.runOnMainSync {
                    third.listView.performItemClick(third.listView.getChildAt(0), 0, 0)
                    check(phase.get(activity)?.toString() == "CALIBRATE")
                    check(ProgressStore(context).mounting(side) == Mounting.options.first())
                    check(!third.isShowing)
                }
            } finally {
                instrumentation.runOnMainSync { activity.finish() }
                instrumentation.waitForIdleSync()
                prefs.edit().apply { if (original == null) remove(key) else putInt(key, original) }.commit()
            }
        }
    }
}
