package com.openski.android

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.abs
import kotlin.math.max

/**
 * The drill's signature view: boot roll drawn as a carved line across groomed snow.
 * The target band shows the depth to aim for and a ghost line shows the previous attempt.
 */
class CarvedLineView(context: Context, private var trace: RollTrace, private val ghost: RollTrace?,
                     private val targetDegrees: Double, private val lineColor: Int) : View(context) {
    /** 0..1 of the trace that has been drawn so far. */
    var progress = 1f
        set(value) { field = value.coerceIn(0f, 1f); invalidate() }

    private var liveWindow = 0f
    private var live = false

    /** Live mode: the line grows with the latest trace across a window of [windowSeconds]. */
    fun showLive(latest: RollTrace, windowSeconds: Float) {
        trace = latest; liveWindow = windowSeconds; live = true; progress = 1f
    }

    private val corduroy = Paint().apply { color = Snow.GLACIER; strokeWidth = Snow.dp(context, 1).toFloat() }
    private val band = Paint().apply { color = 0xFFE4F0F5.toInt() }
    private val neutral = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Snow.GLACIER_DEEP; style = Paint.Style.STROKE; strokeWidth = Snow.dp(context, 1).toFloat()
        pathEffect = DashPathEffect(floatArrayOf(Snow.dp(context, 6).toFloat(), Snow.dp(context, 6).toFloat()), 0f)
    }
    private val ghostPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Snow.GLACIER_DEEP; style = Paint.Style.STROKE; strokeWidth = Snow.dp(context, 3).toFloat()
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = lineColor; style = Paint.Style.STROKE; strokeWidth = Snow.dp(context, 5).toFloat()
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val head = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = lineColor }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Snow.INK_SOFT; textSize = Snow.dp(context, 12).toFloat()
        typeface = context.resources.getFont(R.font.barlow_medium)
    }
    private val clip = Path()

    init {
        contentDescription = "Boot roll over time. The dashed middle is neutral and the line should reach into the shaded zone beyond ${targetDegrees.toInt()} degrees."
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val width = MeasureSpec.getSize(widthSpec)
        setMeasuredDimension(width, Snow.dp(context, 220))
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        clip.reset(); clip.addRoundRect(0f, 0f, w, h, Snow.dp(context, 24).toFloat(), Snow.dp(context, 24).toFloat(), Path.Direction.CW)
        canvas.save(); canvas.clipPath(clip)
        canvas.drawColor(Snow.PAPER)
        val spacing = Snow.dp(context, 9).toFloat()
        var y = spacing / 2
        while (y < h) { canvas.drawLine(0f, y, w, y, corduroy); y += spacing }
        val extent = max(targetDegrees * 1.5, max(peak(trace), peak(ghost))).coerceAtLeast(1.0).toFloat()
        val mid = h / 2; val pad = Snow.dp(context, 18).toFloat()
        fun yOf(degrees: Float) = mid - degrees / extent * (h / 2 - pad)
        // Shade beyond the target on both sides: the line should reach into the shaded zone.
        if (targetDegrees > 0) {   // no target (zero) means no band and no label
            canvas.drawRect(0f, 0f, w, yOf(targetDegrees.toFloat()), band)
            canvas.drawRect(0f, yOf(-targetDegrees.toFloat()), w, h, band)
        }
        canvas.drawLine(0f, mid, w, mid, neutral)
        if (targetDegrees > 0) canvas.drawText("Reach ${targetDegrees.toInt()}°", pad, yOf(targetDegrees.toFloat()) - Snow.dp(context, 4), label)
        val total = max(max(trace.duration, liveWindow), if (live) 0f else ghost?.duration ?: 0f).coerceAtLeast(1f)
        fun xOf(seconds: Float) = pad + seconds / total * (w - pad * 2)
        ghost?.let { draw(canvas, it, 1f, ::xOf, ::yOf, ghostPaint) }
        draw(canvas, trace, progress, ::xOf, ::yOf, line)
        if (trace.seconds.isNotEmpty() && (progress < 1f || live)) {
            val last = if (live) trace.seconds.size - 1 else (trace.seconds.size * progress).toInt().coerceIn(0, trace.seconds.size - 1)
            canvas.drawCircle(xOf(trace.seconds[last]), yOf(trace.degrees[last]), Snow.dp(context, 7).toFloat(), head)
        }
        canvas.restore()
    }

    private fun peak(trace: RollTrace?) = trace?.degrees?.maxOfOrNull { abs(it) }?.toDouble() ?: 0.0

    private fun draw(canvas: Canvas, trace: RollTrace, fraction: Float, xOf: (Float) -> Float,
                     yOf: (Float) -> Float, paint: Paint) {
        val count = (trace.seconds.size * fraction).toInt()
        if (count < 2) return
        val path = Path()
        for (i in 0 until count) {
            val x = xOf(trace.seconds[i]); val y = yOf(trace.degrees[i])
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, paint)
    }
}
