package com.openski.android

import android.app.Instrumentation
import android.content.res.Configuration
import android.graphics.Rect
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.graphics.Insets
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout

/** Geometry and stable control identity, independent of Bluetooth or capture state. */
object PhaseLayoutRegression {
    fun run(instrumentation: Instrumentation) {
        var result: Result<Unit>? = null
        instrumentation.runOnMainSync { result = runCatching {
            for (font in listOf(1.3f, 2f)) {
                val configuration = Configuration(instrumentation.targetContext.resources.configuration).apply { fontScale = font }
                val context = ContextThemeWrapper(instrumentation.targetContext.createConfigurationContext(configuration),
                    android.R.style.Theme_Material_Light_NoActionBar)
                for ((width, height) in listOf(320 to 480, 480 to 320)) {
                    val layout = PhaseLayout(context)
                    val root = FrameLayout(context).apply { addView(layout, ViewGroup.LayoutParams(-1, -1)) }
                    val column = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        repeat(25) { addView(Snow.text(context, "Boot status and additional preparation detail $it")) }
                    }
                    var commands = 0
                    layout.content(column)
                    layout.controls(listOf(PhaseLayout.Action("stop", "Stop run") { commands++ }))
                    fun measure() {
                        root.measure(View.MeasureSpec.makeMeasureSpec(Snow.dp(context, width), View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(Snow.dp(context, height), View.MeasureSpec.EXACTLY))
                        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
                    }
                    measure()
                    val button = layout.actions.getChildAt(1) as Button
                    check(layout.scroll.bottom <= layout.actions.top) { "Footer overlaps scrolling content" }
                    check(layout.actions.bottom <= layout.height && layout.scroll.height > 0)
                    check(button.height >= Snow.dp(context, 56))
                    button.isFocusableInTouchMode = true
                    check(button.requestFocus())
                    layout.scroll.scrollTo(0, Snow.dp(context, 180))
                    val before = layout.scroll.scrollY
                    repeat(4) { index ->
                        layout.controls(listOf(PhaseLayout.Action("stop", "Stop run") { commands += 10 }),
                            "A longer changing warning $index. Read all preparation details above before starting.")
                        measure()
                        check(layout.actions.getChildAt(1) === button && button.hasFocus()) { "Status replaced focused action" }
                        check(layout.scroll.scrollY == before) { "Footer update moved content scroll" }
                        check(layout.scroll.bottom <= layout.actions.top)
                    }
                    button.performClick()
                    check(commands == 10) { "Persistent action retained an obsolete callback" }
                    if (android.os.Build.VERSION.SDK_INT >= 30) {
                        SkiUi.applyInsets(root, ime = true)
                        root.dispatchApplyWindowInsets(WindowInsets.Builder()
                            .setInsets(WindowInsets.Type.systemBars(), Insets.of(0, 12, 0, 16))
                            .setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, 80)).build())
                        measure()
                        val rect = Rect(); layout.actions.getDrawingRect(rect)
                        root.offsetDescendantRectToMyCoords(layout.actions, rect)
                        check(rect.bottom <= root.height - 80) { "Keyboard covers the footer" }
                    }
                }
            }
        } }
        result!!.getOrThrow()
    }
}
