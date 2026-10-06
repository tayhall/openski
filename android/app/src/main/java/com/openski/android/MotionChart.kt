package com.openski.android

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.DashPathEffect
import android.graphics.Path
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import kotlin.math.*

class MotionChart(context: Context) : View(context) {
    var points: List<TimelinePoint> = emptyList()
    var startMs = 0.0
    var endMs = 1.0
    var originMs = 0.0
    var selectedMs = 0.0
    var channel = 0
    var onSeek: (Double) -> Unit = {}
    var roll: List<BootRoll> = emptyList()
    var markers: List<TestMarker> = emptyList()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private fun unit(n: Float)=n*resources.displayMetrics.density
    private var plotLeft=unit(56f)
    private var plotRight=unit(1f)
    private val rightDash=DashPathEffect(floatArrayOf(unit(5f),unit(3f)),0f)
    init {
        isFocusable=true
        contentDescription="Motion timeline. Drag to seek, or use left and right arrow keys."
    }
    private fun value(p: TimelinePoint): Float = p.sample.let { s -> when (channel) {
        1 -> s.gyroX; 2 -> s.gyroY; 3 -> s.gyroZ
        4 -> sqrt(s.gyroX*s.gyroX+s.gyroY*s.gyroY+s.gyroZ*s.gyroZ)
        5 -> s.accelX
        6,7 -> s.accelX
        else -> sqrt(s.accelX*s.accelX+s.accelY*s.accelY+s.accelZ*s.accelZ)
    } }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRoundRect(0f,0f,width.toFloat(),height.toFloat(),unit(16f),unit(16f),paint.apply { color=SkiUi.SURFACE_RAISED })
        val top = unit(32f)
        val bottom = height - unit(30f)
        val visible = if(channel in 5..7) roll.filter { it.timeMs in startMs..endMs }.map {
            val value=when(channel) { 6->it.pitchDegrees; 7->it.yawDegrees; else->it.degrees }
            TimelinePoint(it.side,it.timeMs,SensorSample(0,0,value.toFloat(),0f,0f,0f,0f,0f),"orientation")
        } else points.filter { it.timeMs in startMs..endMs }
        var low = visible.minOfOrNull(::value) ?: 0f
        var high = visible.maxOfOrNull(::value) ?: 1f
        val minimumSpan=when(channel) { in 5..7 -> 10f; in 1..4 -> 0.5f; else -> 1f }
        if(high-low<minimumSpan) { val centre=(low+high)/2; low=centre-minimumSpan/2; high=centre+minimumSpan/2 }
        val padding = (high - low) * 0.1f
        low -= padding; high += padding
        paint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,10f,resources.displayMetrics)
        paint.typeface=Typeface.create("sans-serif",Typeface.NORMAL)
        val left = (0..4).maxOf { paint.measureText(String.format(java.util.Locale.US,"%.1f",low+(high-low)*it/4)) }+unit(16f)
        val right = width - unit(14f)
        plotLeft=left; plotRight=right
        if(right<=left || bottom<=top) return
        fun x(t: Double) = (left + (t-startMs)/(endMs-startMs).coerceAtLeast(1.0)*(right-left)).toFloat()
        fun y(v: Float) = bottom - (v-low)/(high-low)*(bottom-top)
        paint.strokeWidth = unit(1f)
        for (i in 0..4) {
            val v = low + (high-low)*i/4
            paint.color = SkiUi.BORDER
            canvas.drawLine(left, y(v), right, y(v), paint)
            paint.color = SkiUi.MUTED
            canvas.drawText(String.format(java.util.Locale.US, "%.1f", v), unit(6f), y(v)+(paint.fontMetrics.descent*-1), paint)
        }
        canvas.drawText(String.format(java.util.Locale.US, "%.1fs", (startMs-originMs)/1000), left, height-unit(10f), paint)
        val endLabel=String.format(java.util.Locale.US, "%.1fs", (endMs-originMs)/1000)
        canvas.drawText(endLabel,right-paint.measureText(endLabel),height-unit(10f),paint)
        canvas.save(); canvas.clipRect(left,top,right,bottom)
        for (side in listOf("L", "R")) {
            paint.color = if (side == "L") SkiUi.ACCENT else SkiUi.SKY
            paint.strokeWidth = unit(1.6f)
            val stream = visible.filter { it.side == side }
            // Per-column extrema preserve brief peaks even in a whole-day view.
            var column = -1
            var minimum = 0f
            var maximum = 0f
            var last: TimelinePoint? = null
            val trace=Path()
            fun flush() { if (column >= 0) canvas.drawLine(column.toFloat(), y(minimum), column.toFloat(), y(maximum), paint) }
            for (p in stream) {
                val px = x(p.timeMs).toInt()
                val v = value(p)
                if (px != column) { flush(); column = px; minimum = v; maximum = v }
                else { minimum = min(minimum, v); maximum = max(maximum, v) }
                val previous = last
                if (previous != null && p.timeMs-previous.timeMs <= 250) {
                    trace.lineTo(x(p.timeMs),y(v))
                } else trace.moveTo(x(p.timeMs),y(v))
                last = p
            }
            flush()
            paint.style=Paint.Style.STROKE
            paint.pathEffect=if(side=="R") rightDash else null
            canvas.drawPath(trace,paint)
            paint.pathEffect=null; paint.style=Paint.Style.FILL
        }
        if (selectedMs in startMs..endMs) {
            paint.color = SkiUi.TEXT; paint.strokeWidth = unit(1f)
            canvas.drawLine(x(selectedMs), top, x(selectedMs), bottom, paint)
        }
        val labelEnds=FloatArray(3) { left-unit(8f) }
        markers.filter { it.timeMs.toDouble() in startMs..endMs }.distinctBy { it.timeMs to it.label }.take(80).forEach { marker ->
            paint.color=0x70FFC164; paint.strokeWidth=unit(0.7f)
            canvas.drawLine(x(marker.timeMs.toDouble()),top,x(marker.timeMs.toDouble()),bottom,paint)
            val labelX=x(marker.timeMs.toDouble()).coerceIn(left,(right-paint.measureText(marker.label)).coerceAtLeast(left))
            val row=labelEnds.indexOfFirst { labelX>=it+unit(8f) }
            if(row>=0) {
                paint.color=0xFFFFC164.toInt()
                canvas.drawText(marker.label,labelX,top+unit(12f+14*row),paint)
                labelEnds[row]=labelX+paint.measureText(marker.label)
            }
        }
        canvas.restore()
        if (visible.isEmpty()) { paint.color = SkiUi.MUTED; canvas.drawText("No samples in this interval",left,top+unit(30f),paint) }
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE) {
            parent?.requestDisallowInterceptTouchEvent(true)
            onSeek(startMs + ((event.x-plotLeft)/(plotRight-plotLeft).coerceAtLeast(1f)).coerceIn(0f,1f)*(endMs-startMs))
            return true
        }
        if (event.action == MotionEvent.ACTION_UP) { performClick(); parent?.requestDisallowInterceptTouchEvent(false); return true }
        if(event.action==MotionEvent.ACTION_CANCEL) { parent?.requestDisallowInterceptTouchEvent(false); return true }
        return super.onTouchEvent(event)
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    override fun onKeyDown(keyCode: Int,event: KeyEvent): Boolean {
        if(keyCode==KeyEvent.KEYCODE_DPAD_LEFT || keyCode==KeyEvent.KEYCODE_DPAD_RIGHT) {
            onSeek((selectedMs+(if(keyCode==KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1)*(endMs-startMs)/100).coerceIn(startMs,endMs))
            return true
        }
        return super.onKeyDown(keyCode,event)
    }
}
