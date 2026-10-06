package com.openski.android

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Shared native styling for the recording and session-review surfaces. */
object SkiUi {
    val CANVAS = 0xFF0B1116.toInt()
    val SURFACE = 0xFF141D24.toInt()
    val SURFACE_RAISED = 0xFF1C2931.toInt()
    val BORDER = 0xFF30414C.toInt()
    val TEXT = 0xFFF1F5F5.toInt()
    val SECONDARY = 0xFFB8C6CE.toInt()
    val MUTED = 0xFF94A7B5.toInt()
    val ACCENT = 0xFFC7F36B.toInt()
    val SKY = 0xFF74D5E8.toInt()
    val DANGER = 0xFFFFB4AB.toInt()

    enum class ButtonStyle { PRIMARY, SECONDARY, QUIET, DANGER }

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density + .5f).toInt()

    fun rounded(context: Context, fill: Int, radius: Int = 18, stroke: Int? = BORDER) =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(context, radius).toFloat()
            stroke?.let { setStroke(dp(context, 1), it) }
        }

    fun label(context: Context, text: String, size: Float = 14f, tint: Int = TEXT,
              bold: Boolean = false) = TextView(context).apply {
        this.text = text
        textSize = size
        setTextColor(tint)
        typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        includeFontPadding = false
        setLineSpacing(dp(context, 3).toFloat(), 1.08f)
    }

    fun card(context: Context, padding: Int = 18) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(context, SURFACE)
        val inset = dp(context, padding)
        setPadding(inset, inset, inset, inset)
    }

    fun section(context: Context, title: String, subtitle: String? = null) =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(context, title, 20f, TEXT, true))
            subtitle?.let {
                addView(label(context, it, 13f, MUTED).apply { setPadding(0, dp(context, 6), 0, 0) })
            }
        }

    fun chip(context: Context, text: String, accent: Int = ACCENT) =
        label(context, text, 11f, accent, true).apply {
            background = rounded(context, SURFACE_RAISED, 8, BORDER)
            setPadding(dp(context, 9), dp(context, 6), dp(context, 9), dp(context, 6))
            gravity = Gravity.CENTER
        }

    fun button(context: Context, text: String, style: ButtonStyle = ButtonStyle.SECONDARY,
               onClick: () -> Unit) = Button(context).apply {
        this.text = text
        styleButton(this, style)
        setOnClickListener { onClick() }
    }

    fun styleButton(button: Button, style: ButtonStyle = ButtonStyle.SECONDARY) {
        val context = button.context
        val fill = when (style) {
            ButtonStyle.PRIMARY -> ACCENT
            ButtonStyle.QUIET -> CANVAS
            else -> SURFACE_RAISED
        }
        val ink = when (style) {
            ButtonStyle.PRIMARY -> CANVAS
            ButtonStyle.DANGER -> DANGER
            else -> TEXT
        }
        val stroke = when (style) {
            ButtonStyle.PRIMARY -> null
            ButtonStyle.DANGER -> DANGER
            ButtonStyle.QUIET -> null
            else -> BORDER
        }
        val enabled = RippleDrawable(ColorStateList.valueOf(0x30FFFFFF),
            rounded(context, fill, 12, stroke), null)
        button.background = StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), rounded(context, SURFACE, 12, BORDER))
            addState(intArrayOf(), enabled)
        }
        button.backgroundTintList = null
        button.setTextColor(ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(MUTED, ink)
        ))
        button.isAllCaps = false
        button.textSize = 14f
        button.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        button.minHeight = dp(context, 48)
        button.minimumHeight = dp(context, 48)
        button.minWidth = dp(context, 48)
        button.minimumWidth = dp(context, 48)
        button.setPadding(dp(context, 14), dp(context, 12), dp(context, 14), dp(context, 12))
        button.gravity = Gravity.CENTER
        button.stateListAnimator = null
    }

    @Suppress("DEPRECATION")
    fun configureWindow(activity: Activity) {
        activity.window.statusBarColor = CANVAS
        activity.window.navigationBarColor = CANVAS
        activity.window.decorView.systemUiVisibility = 0
        if (Build.VERSION.SDK_INT >= 29) {
            activity.window.isNavigationBarContrastEnforced = false
            activity.window.isStatusBarContrastEnforced = false
        }
        if (Build.VERSION.SDK_INT >= 30) {
            activity.window.insetsController?.setSystemBarsAppearance(0,
                android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
        }
    }

    /** Call once on the root, before adding it to the window. Keeps edge-to-edge content reachable. */
    fun applyInsets(root: View, bottom: Boolean = true) {
        val left = root.paddingLeft
        val top = root.paddingTop
        val right = root.paddingRight
        val baseBottom = root.paddingBottom
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(left + bars.left, top + bars.top, right + bars.right,
                    baseBottom + if (bottom) bars.bottom else 0)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(left + insets.systemWindowInsetLeft, top + insets.systemWindowInsetTop,
                    right + insets.systemWindowInsetRight,
                    baseBottom + if (bottom) insets.systemWindowInsetBottom else 0)
            }
            insets
        }
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) { view.requestApplyInsets() }
            override fun onViewDetachedFromWindow(view: View) = Unit
        })
    }
}
