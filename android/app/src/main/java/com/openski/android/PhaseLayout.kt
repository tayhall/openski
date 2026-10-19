package com.openski.android

import android.content.Context
import android.text.TextUtils
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView

/** Scrolling explanations never overlap the phase's persistent controls. Insets belong to the parent. */
class PhaseLayout(context: Context) : LinearLayout(context) {
    data class Action(val key: String, val label: String, val kind: Snow.ButtonKind = Snow.ButtonKind.PRIMARY,
                      val enabled: Boolean = true, val description: String? = null, val run: () -> Unit)
    val scroll = ScrollView(context).apply { isFillViewport = true }
    val actions = LinearLayout(context).apply {
        orientation = VERTICAL
        val pad = Snow.dp(context, 16)
        setPadding(pad, Snow.dp(context, 8), pad, Snow.dp(context, 8))
        setBackgroundColor(Snow.SNOW)
    }
    private val reason = Snow.text(context, "", Snow.Type.CAPTION).apply {
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        visibility = View.GONE
    }
    private val buttonRow = LinearLayout(context).apply { orientation = VERTICAL }
    private var compact = false
    private var keys = emptyList<String>()
    private val buttons = mutableListOf<Button>()
    init {
        orientation = VERTICAL
        setBackgroundColor(Snow.SNOW)
        addView(scroll, LayoutParams(-1, 0, 1f))
        addView(actions, LayoutParams(-1, -2))
        actions.addView(reason, LayoutParams(-1, -2).apply { bottomMargin = Snow.dp(context, 6) })
        actions.addView(buttonRow, LayoutParams(-1, -2))
    }
    fun content(view: View, resetScroll: Boolean = false) {
        val previous = if (resetScroll) 0 else scroll.scrollY
        scroll.removeAllViews(); scroll.addView(view)
        scroll.post { scroll.scrollTo(0, previous) }
    }
    /** Same action keys keep native button identity/focus as status and connection facts change. */
    fun controls(items: List<Action>, blockingReason: String? = null) {
        reason.text = blockingReason
        reason.visibility = if (blockingReason.isNullOrBlank()) View.GONE else View.VISIBLE
        reason.contentDescription = blockingReason
        if (keys != items.map { it.key }) {
            buttons.forEach { buttonRow.removeView(it) }
            buttons.clear()
            keys = items.map { it.key }
            items.forEach { item ->
                val button = Snow.button(context, item.label, item.kind) { item.run() }.apply { minHeight = Snow.dp(context, 64) }
                buttons.add(button)
                buttonRow.addView(button)
            }
            arrangeButtons()
        }
        items.zip(buttons).forEach { (item, button) ->
            button.text = item.label; button.isEnabled = item.enabled
            button.alpha = if (item.enabled) 1f else .4f
            button.contentDescription = item.description
            button.setOnClickListener { item.run() }
        }
        actions.visibility = if (items.isEmpty() && blockingReason.isNullOrBlank()) View.GONE else View.VISIBLE
    }
    private fun arrangeButtons() {
        val sideBySide = compact && buttons.size > 1
        buttonRow.orientation = if (sideBySide) HORIZONTAL else VERTICAL
        buttons.forEachIndexed { index, button ->
            button.layoutParams = if (sideBySide) LayoutParams(0, -2, 1f).apply {
                if (index > 0) marginStart = Snow.dp(context, 8)
            } else LayoutParams(-1, -2).apply {
                if (index > 0) topMargin = Snow.dp(context, 6)
            }
        }
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val short = MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED &&
            MeasureSpec.getSize(heightMeasureSpec) - paddingTop - paddingBottom < Snow.dp(context, 240)
        if (short != compact) {
            compact = short
            val vertical = Snow.dp(context, if (compact) 4 else 8)
            actions.setPadding(actions.paddingLeft, vertical, actions.paddingRight, vertical)
            reason.maxLines = if (compact) 1 else 2
            arrangeButtons()
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

}

/** Secondary explanations remain available without preceding the current action. */
class PreparationDetails(context: Context, label: String, initiallyExpanded: Boolean = false,
                         private val changed: (Boolean) -> Unit = {}) : LinearLayout(context) {
    val body = LinearLayout(context).apply { orientation = VERTICAL }
    private var expanded = initiallyExpanded
    private val toggle = Snow.button(context, label, Snow.ButtonKind.SECONDARY) { setExpanded(!expanded) }
    init {
        orientation = VERTICAL
        addView(toggle, LayoutParams(-1, -2))
        addView(body, LayoutParams(-1, -2).apply { topMargin = Snow.dp(context, 10) })
        setExpanded(initiallyExpanded)
    }
    private fun setExpanded(value: Boolean) {
        expanded = value
        body.visibility = if (value) View.VISIBLE else View.GONE
        if (android.os.Build.VERSION.SDK_INT >= 30) toggle.stateDescription = if (value) "Expanded" else "Collapsed"
        else toggle.contentDescription = "${toggle.text}, ${if (value) "expanded" else "collapsed"}"
        changed(value)
    }
}
