package com.openski.android

import android.app.Activity
import android.app.AlertDialog

/** Asks once per boot which sensor axis points toward the toe, then remembers it. */
object MountingPicker {
    fun show(activity: Activity, store: ProgressStore, side: String, onCancel: () -> Unit = {},
        canSave: () -> Boolean = { true }, onDone: (Mounting) -> Unit = {}): AlertDialog {
        val boot = if (side == "L") "left" else "right"
        val current = store.mounting(side)
        return AlertDialog.Builder(activity)
            .setTitle("Which way does the $boot sensor point?")
            .setSingleChoiceItems(store.landmarks().let { marks -> Mounting.options.map { it.label(marks) } }.toTypedArray(), current?.let { Mounting.options.indexOf(it) } ?: -1) { dialog, which ->
                val choice = Mounting.options[which]
                if (!canSave()) {
                    android.widget.Toast.makeText(activity, "Finish active practice or recording before changing mounting.", android.widget.Toast.LENGTH_LONG).show()
                    dialog.dismiss(); onCancel(); return@setSingleChoiceItems
                }
                store.setMounting(side, choice)
                dialog.dismiss()
                onDone(choice)
            }
            .setNegativeButton("Cancel") { _, _ -> onCancel() }
            .setOnCancelListener { onCancel() }
            .show()
    }
}
