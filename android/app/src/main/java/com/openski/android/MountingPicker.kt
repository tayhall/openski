package com.openski.android

import android.app.Activity
import android.app.AlertDialog

/** Asks once per boot which sensor axis points toward the toe, then remembers it. */
object MountingPicker {
    fun show(activity: Activity, store: ProgressStore, side: String, onDone: (Mounting) -> Unit = {}) {
        val boot = if (side == "L") "left" else "right"
        val current = store.mounting(side)
        AlertDialog.Builder(activity)
            .setTitle("Which way does the $boot sensor point?")
            .setSingleChoiceItems(Mounting.options.map { it.label }.toTypedArray(), current?.let { Mounting.options.indexOf(it) } ?: -1) { dialog, which ->
                val choice = Mounting.options[which]
                store.setMounting(side, choice)
                dialog.dismiss()
                onDone(choice)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
