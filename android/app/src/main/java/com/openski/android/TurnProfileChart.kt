package com.openski.android

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.DashPathEffect
import android.util.TypedValue
import android.view.View

class TurnProfileChart(context: Context): View(context) {
    var profiles: List<RollProfile> = emptyList()
    var positiveLeft = true
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private fun unit(n: Float)=n*resources.displayMetrics.density
    init { contentDescription="Mean boot roll profiles. Left direction is lime, right direction is blue." }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRoundRect(0f,0f,width.toFloat(),height.toFloat(),unit(16f),unit(16f),paint.apply { color=SkiUi.SURFACE_RAISED })
        val left=unit(48f); val right=width-unit(24f); val top=unit(28f); val bottom=height-unit(30f)
        if(right<=left || bottom<=top) return
        val maximum=(profiles.maxOfOrNull { it.peakDegrees } ?: 1.0).coerceAtLeast(5.0)*1.1
        paint.textSize=TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,10f,resources.displayMetrics)
        paint.strokeWidth=unit(1f)
        for(i in 0..4) {
            val y=bottom-(bottom-top)*i/4
            paint.color=SkiUi.BORDER; canvas.drawLine(left,y,right,y,paint)
            paint.color=SkiUi.MUTED
            canvas.drawText(String.format(java.util.Locale.US,"%.0f°",maximum*i/4),unit(6f),y-paint.fontMetrics.descent,paint)
            val label="${i*25}%"
            canvas.drawText(label,left+(right-left)*i/4-paint.measureText(label)/2,height-unit(10f),paint)
        }
        for(profile in profiles) {
            paint.color=if(profile.positive==positiveLeft) SkiUi.ACCENT else SkiUi.SKY
            paint.strokeWidth=unit(2f)
            paint.pathEffect=if(profile.positive!=positiveLeft) DashPathEffect(floatArrayOf(unit(5f),unit(3f)),0f) else null
            for(i in 1..20) canvas.drawLine(left+(right-left)*(i-1)/20,
                bottom-((bottom-top)*profile.degrees[i-1]/maximum).toFloat(),left+(right-left)*i/20,
                bottom-((bottom-top)*profile.degrees[i]/maximum).toFloat(),paint)
            paint.pathEffect=null
        }
        if(profiles.isEmpty()) { paint.color=SkiUi.MUTED
            canvas.drawText("No complete",left,top+unit(24f),paint)
            canvas.drawText("calibrated intervals",left,top+unit(44f),paint)
        }
    }
}
