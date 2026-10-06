package com.openski.android

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private fun value(p: TimelinePoint): Float = p.sample.let { s -> when (channel) {
        1 -> s.gyroX; 2 -> s.gyroY; 3 -> s.gyroZ
        4 -> sqrt(s.gyroX*s.gyroX+s.gyroY*s.gyroY+s.gyroZ*s.gyroZ)
        else -> sqrt(s.accelX*s.accelX+s.accelY*s.accelY+s.accelZ*s.accelZ)
    } }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(18, 28, 40))
        val left = 64f
        val right = width - 12f
        val top = 18f
        val bottom = height - 35f
        val visible = points.filter { it.timeMs in startMs..endMs }
        var low = visible.minOfOrNull(::value) ?: 0f
        var high = visible.maxOfOrNull(::value) ?: 1f
        if (low == high) { low -= 1; high += 1 }
        val padding = (high - low) * 0.1f
        low -= padding; high += padding
        fun x(t: Double) = (left + (t-startMs)/(endMs-startMs).coerceAtLeast(1.0)*(right-left)).toFloat()
        fun y(v: Float) = bottom - (v-low)/(high-low)*(bottom-top)
        paint.textSize = 11 * resources.displayMetrics.scaledDensity
        paint.strokeWidth = 1f
        for (i in 0..4) {
            val v = low + (high-low)*i/4
            paint.color = Color.rgb(48, 60, 74)
            canvas.drawLine(left, y(v), right, y(v), paint)
            paint.color = Color.LTGRAY
            canvas.drawText(String.format(java.util.Locale.US, "%.1f", v), 4f, y(v), paint)
        }
        canvas.drawText(String.format(java.util.Locale.US, "%.1fs", (startMs-originMs)/1000), left, height-10f, paint)
        canvas.drawText(String.format(java.util.Locale.US, "%.1fs", (endMs-originMs)/1000), right-60f, height-10f, paint)
        for (side in listOf("L", "R")) {
            paint.color = if (side == "L") Color.rgb(100, 230, 189) else Color.rgb(100, 181, 255)
            paint.strokeWidth = 2f
            val stream = visible.filter { it.side == side }
            // Per-column extrema preserve brief peaks even in a whole-day view.
            var column = -1
            var minimum = 0f
            var maximum = 0f
            var last: TimelinePoint? = null
            fun flush() { if (column >= 0) canvas.drawLine(column.toFloat(), y(minimum), column.toFloat(), y(maximum), paint) }
            for (p in stream) {
                val px = x(p.timeMs).toInt()
                val v = value(p)
                if (px != column) { flush(); column = px; minimum = v; maximum = v }
                else { minimum = min(minimum, v); maximum = max(maximum, v) }
                val previous = last
                if (previous != null && p.timeMs-previous.timeMs <= 250) canvas.drawLine(x(previous.timeMs), y(value(previous)), x(p.timeMs), y(v), paint)
                last = p
            }
            flush()
        }
        if (selectedMs in startMs..endMs) {
            paint.color = Color.WHITE; paint.strokeWidth = 1f
            canvas.drawLine(x(selectedMs), top, x(selectedMs), bottom, paint)
        }
        if (visible.isEmpty()) { paint.color = Color.LTGRAY; canvas.drawText("No samples in this interval", left, top+30, paint) }
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE) {
            parent?.requestDisallowInterceptTouchEvent(true)
            onSeek(startMs + ((event.x-64)/(width-76).coerceAtLeast(1)).coerceIn(0f,1f)*(endMs-startMs))
            return true
        }
        if (event.action == MotionEvent.ACTION_UP) { performClick(); parent?.requestDisallowInterceptTouchEvent(false); return true }
        return super.onTouchEvent(event)
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
