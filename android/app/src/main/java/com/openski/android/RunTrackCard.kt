package com.openski.android

import android.content.Context
import android.view.View
import android.widget.LinearLayout

/** Distance, speed and vertical drop for a run, or the plain reason there are none. Shared by Done and the logbook. */
object RunTrackCard {
    fun build(context: Context, report: TrackReport): View {
        val card = Snow.card(context, 18)
        card.addView(Snow.text(context, report.headline, Snow.Type.TITLE))
        report.rows.forEach { (label, value) ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(Snow.text(context, label, Snow.Type.BODY, Snow.INK_SOFT), LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(Snow.text(context, value, Snow.Type.STRONG))
            card.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Snow.dp(context, 8) })
        }
        card.addView(Snow.text(context, report.note, Snow.Type.CAPTION, Snow.INK_SOFT),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = Snow.dp(context, 10) })
        return card
    }
}
