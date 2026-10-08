package com.openski.android

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** A simple ridge line for the Ready screen. Decoration only. */
private class SummitView(context: Context) : View(context) {
    private val far = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Snow.GLACIER }
    private val near = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Snow.GLACIER_DEEP }
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), Snow.dp(context, 130))

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        fun ridge(points: List<Pair<Float, Float>>): Path = Path().apply {
            moveTo(0f, h)
            points.forEach { (x, y) -> lineTo(x * w, y * h) }
            lineTo(w, h); close()
        }
        canvas.drawPath(ridge(listOf(0f to .55f, .18f to .30f, .30f to .48f, .52f to .08f, .70f to .42f, .86f to .25f, 1f to .5f)), far)
        canvas.drawPath(ridge(listOf(0f to .85f, .22f to .60f, .40f to .78f, .62f to .45f, .80f to .72f, 1f to .62f)), near)
    }
}

/**
 * The run flow: Ready, Zeroing, Running, Saving, Done. It only shows what the service's [RunController] reports and
 * sends it commands, so rotating or closing the screen never changes a run. Dry-ski boot roll against a training
 * target; not an on-snow technique score.
 */
class RunActivity : Activity() {
    private lateinit var frame: FrameLayout
    private var service: SensorSessionService? = null
    private var bound = false
    private var state: RunState? = null
    private var structure = ""
    private var modeLabel = "Unknown"
    private var notice: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private var timerView: TextView? = null
    private var turnsView: TextView? = null
    private var verdictView: TextView? = null
    private var progressViews = mapOf<String, TextView>()

    private val refresh = object : Runnable {
        override fun run() {
            service?.let { apply(it.runState()) }
            handler.postDelayed(this, 1000)
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val connected = (binder as? SensorSessionService.LocalBinder)?.service ?: return
            service = connected
            connected.setRunListener { update -> runOnUiThread { apply(update) } }
            apply(connected.runState())
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Snow.configureWindow(this)
        // A polite live region: changing the description makes TalkBack announce the new phase.
        frame = FrameLayout(this).apply { setBackgroundColor(Snow.SNOW); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        SkiUi.applyInsets(frame)
        setContentView(frame)
    }

    override fun onStart() {
        super.onStart()
        if (!bound) bound = bindService(Intent(this, SensorSessionService::class.java), connection, Context.BIND_AUTO_CREATE)
        handler.post(refresh)
    }

    override fun onStop() {
        handler.removeCallbacks(refresh)
        service?.setRunListener(null)
        if (bound) { unbindService(connection); bound = false }
        service = null
        super.onStop()
    }

    private fun t(text: String, type: Snow.Type = Snow.Type.BODY, tint: Int = Snow.INK) = Snow.text(this, text, type, tint)
    private fun LinearLayout.add(view: View, top: Int = 0, bottom: Int = 0) =
        addView(view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Snow.dp(this@RunActivity, top); bottomMargin = Snow.dp(this@RunActivity, bottom) })

    private fun name(side: String) = if (side == "L") "Left" else "Right"
    private fun clock(ms: Long) = "%d:%02d".format(ms / 60000, ms / 1000 % 60)
    private fun mark(ok: Boolean) = if (ok) "✓" else "○"

    private fun modeSummary(): String {
        val modes = service?.bootModes()?.values?.toList().orEmpty()
        return when {
            modes.isEmpty() || modes.any { it == null } -> "Unknown"
            modes.all { it == true } -> "On snow"
            modes.all { it == false } -> "Training"
            else -> "Mixed"
        }
    }

    /** Rebuilds the screen only when something other than a running clock or counter changed, so taps are never lost. */
    private fun apply(update: RunState) {
        modeLabel = modeSummary()
        val signature = listOf(update.phase, update.message, update.readiness.reason, update.zeroTimedOut, update.zeroed,
            update.banner, modeLabel, update.summary != null, update.demo, update.connected, update.earbuds).joinToString("|")
        val previous = state
        state = update
        if (signature != structure) {
            structure = signature
            render(update)
            if (previous?.phase != update.phase) announce(update)
        } else updateLive(update)
    }

    private fun announce(s: RunState) {
        val text = when (s.phase) {
            RunPhase.ZEROING -> "Stand upright and hold still."
            RunPhase.RUNNING -> "Run started."
            RunPhase.SAVING -> "Run ended. Saving boot data."
            RunPhase.DONE -> "Run saved."
            RunPhase.READY -> return
        }
        frame.contentDescription = text
    }

    private fun updateLive(s: RunState) {
        timerView?.text = clock(s.elapsedMs)
        turnsView?.text = "${s.turns.values.sum()} turns"
        verdictView?.text = verdictLine(s)
        progressViews.forEach { (side, view) -> view.text = progressLine(side, s) }
    }

    private fun verdictLine(s: RunState) = when (s.lastVerdict?.verdict) {
        Verdict.POSITIVE -> "▲ Last turns matched the target"
        Verdict.NEGATIVE -> "▼ Last turns were off target"
        Verdict.NONE -> "● Last turns were close"
        null -> "Waiting for your first turns"
    }

    private fun progressLine(side: String, s: RunState) = "${name(side)} boot · ${s.saveProgress[side]?.let { "$it%" } ?: "waiting"}"

    private fun render(s: RunState) {
        timerView = null; turnsView = null; verdictView = null; progressViews = emptyMap()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val inset = Snow.dp(this@RunActivity, 20)
            setPadding(inset, inset, inset, Snow.dp(this@RunActivity, 28))
        }
        when (s.phase) {
            RunPhase.READY -> ready(column, s)
            RunPhase.ZEROING -> zeroing(column, s)
            RunPhase.RUNNING -> running(column, s)
            RunPhase.SAVING -> saving(column, s)
            RunPhase.DONE -> done(column, s)
        }
        frame.keepScreenOn = s.phase == RunPhase.READY || s.phase == RunPhase.ZEROING   // never while the phone is in a pocket
        frame.removeAllViews()
        frame.addView(ScrollView(this).apply { isFillViewport = true; addView(column) })
    }

    private fun ready(column: LinearLayout, s: RunState) {
        column.add(SummitView(this), bottom = 8)
        column.add(t("Ready?", Snow.Type.HERO))
        column.add(t("Check your boots and earbuds, then start. Your phone goes in your pocket.", Snow.Type.BODY, Snow.INK_SOFT), top = 4, bottom = 16)
        val status = Snow.card(this, 18)
        RunController.SIDES.forEachIndexed { index, side ->
            val ok = s.connected[side] == true
            status.add(t("${mark(ok)}  ${name(side)} boot · ${if (ok) "connected" else "not connected"}", Snow.Type.STRONG), top = if (index == 0) 0 else 10)
        }
        status.add(t("${mark(s.earbuds)}  Earbuds · ${if (s.earbuds) "connected" else "not connected"}", Snow.Type.STRONG), top = 10)
        column.add(status, bottom = 12)
        column.add(Snow.button(this, "Boot mode: $modeLabel · tap to switch", Snow.ButtonKind.SECONDARY) {
            service?.setOnSnowMode(modeLabel != "On snow")
        }, bottom = 10)
        column.add(Snow.button(this, "Coaching: ${CoachSettingsStore(this).settings.targetLabel()}", Snow.ButtonKind.SECONDARY) {
            startActivity(Intent(this, CoachingActivity::class.java))
        }, bottom = 16)
        if (s.message.isNotEmpty()) column.add(t(s.message, Snow.Type.BODY, Snow.INK_SOFT), bottom = 8)
        s.readiness.reason?.let { column.add(t(it, Snow.Type.STRONG), bottom = 8) }
        notice?.let { column.add(t(it, Snow.Type.STRONG), bottom = 8) }
        val start = Snow.button(this, "Start run") { tryStart(false) }.apply {
            minHeight = Snow.dp(this@RunActivity, 64)
            if (!s.readiness.ok) { isEnabled = false; alpha = 0.4f; contentDescription = "Start run, unavailable. ${s.readiness.reason}" }
        }
        column.add(start, bottom = 10)
        column.add(Snow.button(this, "Try with demo boots", Snow.ButtonKind.QUIET) { tryStart(true) })
    }

    /** Starts a run and shows the reason on this screen if it could not start. */
    private fun tryStart(demo: Boolean) {
        notice = service?.startRun(demo)
        if (notice != null) { structure = ""; state?.let { apply(it) } }
    }

    private fun zeroing(column: LinearLayout, s: RunState) {
        column.add(t("Stand upright.", Snow.Type.HERO))
        column.add(t("Hold still.", Snow.Type.HERO), bottom = 16)
        val status = Snow.card(this, 18)
        RunController.SIDES.forEachIndexed { index, side ->
            val ok = s.zeroed[side] == true
            status.add(t("${mark(ok)}  ${name(side)} boot · ${if (ok) "zeroed" else "waiting"}", Snow.Type.STRONG), top = if (index == 0) 0 else 10)
        }
        column.add(status, bottom = 12)
        if (s.zeroTimedOut) {
            column.add(t(s.message, Snow.Type.BODY), bottom = 12)
            column.add(Snow.button(this, "Try again") { service?.retryZero() }, bottom = 10)
        }
        column.add(Snow.button(this, "Cancel", Snow.ButtonKind.QUIET) { service?.cancelRun() })
    }

    private fun running(column: LinearLayout, s: RunState) {
        column.add(t("Run in progress", Snow.Type.TITLE, Snow.INK_SOFT))
        timerView = t(clock(s.elapsedMs), Snow.Type.HERO).also { column.add(it, top = 4) }
        turnsView = t("${s.turns.values.sum()} turns", Snow.Type.DISPLAY).also { column.add(it, top = 4) }
        verdictView = t(verdictLine(s), Snow.Type.BODY).also { column.add(it, top = 12, bottom = 16) }
        s.banner?.let { column.add(t(it, Snow.Type.STRONG), bottom = 16) }
        column.add(Snow.button(this, "Stop run") { service?.stopRun() }.apply { minHeight = Snow.dp(this@RunActivity, 64) })
        column.add(t("You can lock the phone and put it in your pocket. The run ends by itself after a quiet spell.", Snow.Type.CAPTION, Snow.INK_SOFT), top = 16)
    }

    private fun saving(column: LinearLayout, s: RunState) {
        column.add(t("Run ended", Snow.Type.HERO))
        column.add(t("Saving boot data. Keep the boots on and nearby; you can ride the lift and close the app.", Snow.Type.BODY, Snow.INK_SOFT), top = 4, bottom = 16)
        val card = Snow.card(this, 18)
        progressViews = RunController.SIDES.associateWith { side ->
            t(progressLine(side, s), Snow.Type.STRONG).also { card.add(it, top = if (side == "L") 0 else 10) }
        }
        column.add(card, bottom = 16)
        column.add(Snow.button(this, "Finish later", Snow.ButtonKind.QUIET) { service?.abandonRunSave() })
        column.add(t("Saving carries on in the background; the boots stay in On snow mode until you switch them back.", Snow.Type.CAPTION, Snow.INK_SOFT), top = 8)
    }

    private fun done(column: LinearLayout, s: RunState) {
        val summary = s.summary
        column.add(t(if (s.message.isEmpty()) "Run saved" else "Run finished", Snow.Type.HERO))
        if (s.message.isNotEmpty()) column.add(t(s.message, Snow.Type.BODY), top = 4)
        if (summary != null) {
            val card = Snow.card(this, 18)
            card.add(t("${clock(summary.durationMs)} · ${summary.turns.values.sum()} turns", Snow.Type.DISPLAY))
            card.add(t("Left ${summary.turns["L"] ?: 0}, right ${summary.turns["R"] ?: 0}", Snow.Type.BODY, Snow.INK_SOFT), top = 4)
            card.add(t("▲ ${summary.matched} matched   ▼ ${summary.offTarget} off target   ● ${summary.unclear} close", Snow.Type.STRONG), top = 10)
            if (summary.rawDataCapped) card.add(t("Raw data covers the first 10 minutes only.", Snow.Type.BODY), top = 10)
            if (summary.gapSeconds > 0) card.add(t("A boot lost its link for ${summary.gapSeconds} s; turns in that time are missing.", Snow.Type.BODY), top = 6)
            card.add(t("This compares dry-ski boot roll with a training target. It is not an on-snow technique score.", Snow.Type.CAPTION, Snow.INK_SOFT), top = 10)
            column.add(card, top = 16, bottom = 16)
        }
        column.add(Snow.button(this, "Back to ready") { service?.resetRun() }, bottom = 10)
        column.add(Snow.button(this, "Open in logbook", Snow.ButtonKind.SECONDARY) {
            startActivity(Intent(this, MainActivity::class.java))
        })
    }
}
