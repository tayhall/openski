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
                    fun buttons(view: View): List<Button> = (if (view is Button) listOf(view) else emptyList()) +
                        if (view is ViewGroup) (0 until view.childCount).flatMap { buttons(view.getChildAt(it)) } else emptyList()
                    val button = buttons(layout.actions).single()
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
                        check(buttons(layout.actions).single() === button && button.hasFocus()) { "Status replaced focused action" }
                        check(layout.scroll.scrollY == before) { "Footer update moved content scroll" }
                        check(layout.scroll.bottom <= layout.actions.top)
                    }
                    button.performClick()
                    check(commands == 10) { "Persistent action retained an obsolete callback" }
                    if (android.os.Build.VERSION.SDK_INT >= 30) {
                        SkiUi.applyInsets(root, ime = true)
                        val cases = listOf(
                            "READY" to listOf(PhaseLayout.Action("start", "Start run", enabled = false) {}),
                            "ZEROING" to listOf(PhaseLayout.Action("retry-zero", "Try again") {},
                                PhaseLayout.Action("cancel", "Cancel", Snow.ButtonKind.QUIET) {}),
                            "SAVING" to listOf(PhaseLayout.Action("background", "Continue saving in background", Snow.ButtonKind.SECONDARY) {})
                        )
                        for ((phase, controls) in cases) for (keyboardDp in listOf(120, 160)) {
                            layout.controls(controls, if (phase == "READY") "Connect both saved boots and choose an audio route before starting." else null)
                            val keyboard = Snow.dp(context, keyboardDp)
                            root.dispatchApplyWindowInsets(WindowInsets.Builder()
                                .setInsets(WindowInsets.Type.systemBars(), Insets.of(0, Snow.dp(context, 24), 0, Snow.dp(context, 24)))
                                .setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, keyboard)).build())
                            measure()
                            val bounds = Rect(); layout.actions.getDrawingRect(bounds)
                            root.offsetDescendantRectToMyCoords(layout.actions, bounds)
                            check(bounds.bottom <= root.height - keyboard) { "$phase keyboard covers footer: font=$font $width x $height IME=$keyboardDp" }
                            check(layout.scroll.height > 0 && layout.scroll.bottom <= layout.actions.top) {
                                "$phase footer removes content or overlaps it: font=$font $width x $height IME=$keyboardDp"
                            }
                            val visible = buttons(layout.actions)
                            check(visible.size == controls.size)
                            visible.forEach { command ->
                                val rect = Rect(); command.getDrawingRect(rect)
                                root.offsetDescendantRectToMyCoords(command, rect)
                                check(rect.top >= bounds.top && rect.bottom <= bounds.bottom && rect.left >= 0 && rect.right <= root.width) {
                                    "$phase command is clipped: ${command.text}"
                                }
                                check(command.height >= Snow.dp(context, 64))
                                check(command.layout.height <= command.height - command.paddingTop - command.paddingBottom) {
                                    "$phase command text is clipped: ${command.text}"
                                }
                            }
                        }
                    }
                }
            }
        } }
        result!!.getOrThrow()
    }
}
