package com.openski.android

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Light "snow" look for the training experience: piste-sign type, piste-marker shapes and one orange action.
 * The older recording and review screens still use [SkiUi]; they move over in a later pass.
 */
object Snow {
    val SNOW = 0xFFF4F7F9.toInt()
    val PAPER = 0xFFFFFFFF.toInt()
    val GLACIER = 0xFFCFE3EC.toInt()
    val GLACIER_DEEP = 0xFF93B4C4.toInt()
    val INK = 0xFF14232E.toInt()
    val INK_SOFT = 0xFF4A5C69.toInt()
    val ORANGE = 0xFFFF5B2E.toInt()
    val BLUE = 0xFF1E6FD9.toInt()
    val RED = 0xFFD6362F.toInt()
    val GOLD = 0xFFF2B01E.toInt()

    fun pisteColor(piste: Piste) = when (piste) { Piste.BLUE -> BLUE; Piste.RED -> RED; Piste.BLACK -> INK }

    enum class Type(val size: Float, val font: Int) {
        HERO(44f, R.font.barlow_condensed_bold),
        DISPLAY(32f, R.font.barlow_condensed_bold),
        TITLE(22f, R.font.barlow_condensed_semibold),
        BODY(16f, R.font.barlow_regular),
        STRONG(16f, R.font.barlow_medium),
        CAPTION(13f, R.font.barlow_medium),
    }

    fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density + .5f).toInt()
    fun dp(view: View, value: Int) = dp(view.context, value)

    fun text(context: Context, text: String, type: Type = Type.BODY, tint: Int = INK) = TextView(context).apply {
        this.text = text
        textSize = type.size
        setTextColor(tint)
        typeface = context.resources.getFont(type.font)
        includeFontPadding = false
        setLineSpacing(0f, when (type) { Type.BODY -> 1.18f; Type.HERO, Type.DISPLAY -> .92f; else -> 1.05f })
    }

    fun rounded(context: Context, fill: Int, radius: Int = 20, stroke: Int? = null, strokeDp: Int = 1) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(context, radius).toFloat()
        stroke?.let { setStroke(dp(context, strokeDp), it) }
    }

    fun card(context: Context, padding: Int = 20, fill: Int = PAPER, radius: Int = 24) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(context, fill, radius, GLACIER)
        val inset = dp(context, padding)
        setPadding(inset, inset, inset, inset)
    }

    enum class ButtonKind { PRIMARY, SECONDARY, QUIET }

    /** One orange primary per screen. Ink text on orange keeps 5:1 contrast. */
    fun button(context: Context, label: String, kind: ButtonKind = ButtonKind.PRIMARY, onClick: () -> Unit) =
        TextView(context).apply {
            text = label
            textSize = 18f
            typeface = context.resources.getFont(R.font.barlow_condensed_bold)
            includeFontPadding = false
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            minHeight = dp(context, 56)
            setPadding(dp(context, 22), dp(context, 16), dp(context, 22), dp(context, 16))
            val fill = when (kind) { ButtonKind.PRIMARY -> ORANGE; ButtonKind.SECONDARY -> PAPER; ButtonKind.QUIET -> 0 }
            setTextColor(INK)
            val shape = rounded(context, fill, 28, if (kind == ButtonKind.SECONDARY) INK else null, 2)
            background = RippleDrawable(ColorStateList.valueOf(0x33000000), shape, null)
            setOnClickListener { onClick() }
        }

    fun fullWidth(topDp: Int = 0, context: Context? = null, bottomDp: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(-1, -2).apply {
            context?.let { topMargin = dp(it, topDp); bottomMargin = dp(it, bottomDp) }
        }

    fun gap(context: Context, heightDp: Int) = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(-1, dp(context, heightDp))
    }

    @Suppress("DEPRECATION")
    fun configureWindow(activity: Activity) {
        activity.window.statusBarColor = SNOW
        activity.window.navigationBarColor = SNOW
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            activity.window.insetsController?.setSystemBarsAppearance(
                android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
        } else {
            activity.window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
    }
}

/** Piste sign: circle for blue, square for red, diamond for black, so level never depends on colour alone. */
class PisteMarker(context: Context, private val piste: Piste, sizeDp: Int = 22, private val dim: Boolean = false) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val size = Snow.dp(context, sizeDp)
    init { contentDescription = "${piste.title} piste" }
    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(size, size)
    override fun onDraw(canvas: Canvas) {
        paint.color = Snow.pisteColor(piste)
        paint.alpha = if (dim) 90 else 255
        val c = size / 2f; val r = size * 0.42f
        when (piste) {
            Piste.BLUE -> canvas.drawCircle(c, c, r, paint)
            Piste.RED -> canvas.drawRoundRect(c - r, c - r, c + r, c + r, size * .08f, size * .08f, paint)
            Piste.BLACK -> canvas.drawPath(Path().apply {
                moveTo(c, c - r * 1.2f); lineTo(c + r * 1.2f, c); lineTo(c, c + r * 1.2f); lineTo(c - r * 1.2f, c); close()
            }, paint)
        }
    }
}

/** One to three gold stars, always drawn as three so the maximum is visible. */
class StarRow(context: Context, private val stars: Int, private val sizeDp: Int = 18) : View(context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val unit = Snow.dp(context, sizeDp)
    private val gap = Snow.dp(context, 3)
    init { contentDescription = "$stars of 3 stars" }
    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(unit * 3 + gap * 2, unit)
    override fun onDraw(canvas: Canvas) {
        for (index in 0 until 3) {
            fill.color = if (index < stars) Snow.GOLD else Snow.GLACIER
            val cx = index * (unit + gap) + unit / 2f; val cy = unit / 2f; val outer = unit / 2f; val inner = outer * .42f
            canvas.drawPath(Path().apply {
                for (point in 0 until 10) {
                    val radius = if (point % 2 == 0) outer else inner
                    val angle = Math.PI / 5 * point - Math.PI / 2
                    val x = cx + (radius * Math.cos(angle)).toFloat(); val y = cy + (radius * Math.sin(angle)).toFloat()
                    if (point == 0) moveTo(x, y) else lineTo(x, y)
                }
                close()
            }, fill)
        }
    }
}
