package com.openski.android

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** Service-owned Ready, Zeroing, Running, Saving and Done flow. Leaving the screen does not stop capture. */
class RunActivity : Activity() {
    private lateinit var frame: FrameLayout
    private lateinit var phaseLayout: PhaseLayout
    private var renderedPhase: RunPhase? = null
    private var preparationExpanded = false
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
    private val gpsSettings by lazy { GpsSettings(this) }
    private var lastFix: GpsFix? = null
    private var lastFixAt = 0L   // phone uptime when it arrived, so a wrong clock cannot make it look stale
    private var gpsView: TextView? = null
    private var startWhenBound = false   // a start tap answered by the location prompt, waiting for the service
    private val probe by lazy { GpsListener(this) { fix -> lastFix = fix; lastFixAt = android.os.SystemClock.elapsedRealtime(); statusCache = null; gpsView?.text = gpsStatus().row() } }

    private var statusAt = 0L
    private var statusCache: GpsStatus? = null

    /** Cached for half a second: the permission and provider checks are Binder calls and this is read every refresh. */
    private fun gpsStatus(): GpsStatus {
        val now = android.os.SystemClock.elapsedRealtime()
        statusCache?.let { if (now - statusAt < 500) return it }
        val fix = lastFix
        return GpsStatusRule.decide(gpsSettings.enabled, probe.permitted, probe.providerOn,
            fix?.let { now - lastFixAt }, fix?.accuracyM).also { statusCache = it; statusAt = now }
    }

    /** Listens only while the Ready screen is showing, so the row can say "ready" before the run starts. */
    private fun syncProbe() {
        if (gpsSettings.enabled && state?.phase == RunPhase.READY) probe.start() else probe.stop()
    }

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
            if (startWhenBound) {
                startWhenBound = false
                if (state?.phase == RunPhase.READY) tryStart(false)
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Snow.configureWindow(this)
        frame = FrameLayout(this).apply { setBackgroundColor(Snow.SNOW) }
        preparationExpanded = savedInstanceState?.getBoolean("preparation_expanded") ?: false
        phaseLayout = PhaseLayout(this)
        frame.addView(phaseLayout)
        val screen = Snow.screen(this, frame)
        SkiUi.applyInsets(screen, ime = true)
        setContentView(screen)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("preparation_expanded", preparationExpanded)
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        if (!bound) bound = bindService(Intent(this, SensorSessionService::class.java), connection, Context.BIND_AUTO_CREATE)
        handler.post(refresh)
    }

    override fun onStop() {
        handler.removeCallbacks(refresh)
        probe.stop()
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
        val signature = listOf(update.phase, update.message, update.readiness.issues, update.readiness.reason, update.zeroTimedOut, update.zeroed,
            update.banner, modeLabel, update.summary != null, update.demo, update.connected, update.earbuds,
            gpsStatus().state, gpsSettings.enabled).joinToString("|")
        val previous = state
        state = update
        syncProbe()
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
            RunPhase.DONE -> "Run finished."
            RunPhase.READY -> return
        }
        @Suppress("DEPRECATION")
        frame.announceForAccessibility(text)
    }

    private fun updateLive(s: RunState) {
        timerView?.text = clock(s.elapsedMs)
        turnsView?.text = "${s.turns.values.sum()} turns"
        verdictView?.text = verdictLine(s)
        progressViews.forEach { (side, view) -> view.text = progressLine(side, s) }
        gpsView?.text = gpsStatus().row()
    }

    private fun verdictLine(s: RunState) = when (s.lastVerdict?.verdict) {
        Verdict.POSITIVE -> "▲ Last turns matched the target"
        Verdict.NEGATIVE -> "▼ Last turns were off target"
        Verdict.NONE -> "● Last turns were close"
        null -> "Waiting for your first turns"
    }

    private fun progressLine(side: String, s: RunState) = "${name(side)} boot · ${s.saveProgress[side]?.let { "$it%" } ?: "waiting"}"

    private fun render(s: RunState) {
        timerView = null; turnsView = null; verdictView = null; progressViews = emptyMap(); gpsView = null
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
        phaseLayout.content(column, renderedPhase != s.phase)
        renderedPhase = s.phase
        val controls = when (s.phase) {
            RunPhase.READY -> listOf(PhaseLayout.Action("start", "Start run", enabled = s.readiness.ok,
                description = if (s.readiness.ok) null else "Start run, unavailable. ${s.readiness.reason}") { tryStart(false) })
            RunPhase.ZEROING -> buildList {
                if (s.zeroTimedOut) add(PhaseLayout.Action("retry-zero", "Try again") { service?.retryZero() })
                add(PhaseLayout.Action("cancel", "Cancel", Snow.ButtonKind.QUIET) { service?.cancelRun() })
            }
            RunPhase.RUNNING -> listOf(PhaseLayout.Action("stop", "Stop run") { service?.stopRun() })
            RunPhase.SAVING -> listOf(PhaseLayout.Action("background", "Continue saving in background", Snow.ButtonKind.SECONDARY) { service?.abandonRunSave() })
            RunPhase.DONE -> emptyList()
        }
        phaseLayout.controls(controls, if (s.phase == RunPhase.READY && !s.readiness.ok) s.readiness.reason else null)
    }

    private fun ready(column: LinearLayout, s: RunState) {
        column.add(t("Ready to ski?", Snow.Type.DISPLAY))
        column.add(t(CoachSettingsStore(this).settings.targetLabel(), Snow.Type.STRONG), top = 4)
        column.add(t("Check your boots and earbuds, then start. Your phone goes in your pocket.", Snow.Type.BODY, Snow.INK_SOFT), top = 4, bottom = 16)
        column.add(t(GestureSyncPrompts.READY, Snow.Type.STRONG), bottom = 12)
        val status = Snow.card(this, 18)
        RunController.SIDES.forEachIndexed { index, side ->
            val ok = s.connected[side] == true
            status.add(t("${mark(ok)}  ${name(side)} boot · ${if (ok) "connected" else "not connected"}", Snow.Type.STRONG), top = if (index == 0) 0 else 10)
        }
        status.add(t("${mark(s.earbuds)}  Earbuds · ${if (s.earbuds) "connected" else "not connected"}", Snow.Type.STRONG), top = 10)
        gpsView = t(gpsStatus().row(), Snow.Type.STRONG).also { it.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE; status.add(it, top = 10) }
        if (gpsSettings.enabled && gpsStatus().state == GpsState.NO_PERMISSION && gpsSettings.asked) {
            // The system prompt may not appear again after a refusal or an approximate-only grant.
            status.add(Snow.button(this, "Allow location in Settings", Snow.ButtonKind.QUIET) {
                startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:$packageName")))
            }, top = 8)
        }
        column.add(status, bottom = 12)
        val preparation = PreparationDetails(this, "Preparation settings", preparationExpanded) { preparationExpanded = it }
        val settingsColumn = preparation.body
        settingsColumn.add(t("Reported boot mode: $modeLabel", Snow.Type.STRONG), bottom = 8)
        settingsColumn.add(Snow.button(this, "Prepare boots and recover recordings", Snow.ButtonKind.SECONDARY) {
            startActivity(Intent(this, BootSetupActivity::class.java).reuseIfOpen())
        }, bottom = 10)
        settingsColumn.add(Snow.button(this, "GPS for runs: ${if (gpsSettings.enabled) "On" else "Off"} · tap to switch", Snow.ButtonKind.SECONDARY) {
            gpsSettings.enabled = !gpsSettings.enabled
            lastFix = null; statusCache = null
            structure = ""; state?.let { apply(it) }
        }, bottom = 10)
        settingsColumn.add(Snow.button(this, "Coaching: ${CoachSettingsStore(this).settings.targetLabel()}", Snow.ButtonKind.SECONDARY) {
            startActivity(Intent(this, CoachingActivity::class.java).reuseIfOpen())
        }, bottom = 16)
        if (s.message.isNotEmpty()) column.add(t(s.message, Snow.Type.BODY, Snow.INK_SOFT), bottom = 8)
        s.readiness.issues.forEach { issue ->
            column.add(t(issue.message, Snow.Type.STRONG), bottom = 6)
            val label = when (issue.action) {
                PreparationAction.CONNECT -> "Connect"
                PreparationAction.RECOVER -> "Open recovery${issue.side?.let { " · ${name(it)} boot" } ?: ""}"
                PreparationAction.AUDIO -> "Choose audio route and test sounds"
                PreparationAction.STORAGE -> "Manage phone storage"
                PreparationAction.RETURN_TO_CAPTURE -> "Return to active capture"
                PreparationAction.MOUNTING -> "Set mounting${issue.side?.let { " · ${name(it)} boot" } ?: ""}"
                PreparationAction.ZERO -> "Prepare neutral zero"
            }
            column.add(Snow.button(this, label, Snow.ButtonKind.SECONDARY) {
                startActivity(when (issue.action) {
                    PreparationAction.AUDIO -> Intent(this, CoachingActivity::class.java).reuseIfOpen()
                    PreparationAction.STORAGE -> Intent(android.provider.Settings.ACTION_INTERNAL_STORAGE_SETTINGS)
                    PreparationAction.RETURN_TO_CAPTURE -> activeCaptureIntent()
                    else -> Intent(this, BootSetupActivity::class.java).reuseIfOpen()
                })
            }.apply {
                // The visible label is short because the sentence above names the boot; TalkBack gets the full action.
                if (issue.action == PreparationAction.CONNECT) contentDescription = "Connect ${issue.side?.let(::name)?.lowercase() ?: "saved"} boot"
            }, bottom = 10)
        }
        if (s.readiness.issues.isEmpty()) s.readiness.reason?.let { column.add(t(it, Snow.Type.STRONG), bottom = 8) }
        notice?.let { column.add(t(it, Snow.Type.STRONG), bottom = 8) }
        column.add(preparation, bottom = 12)
        column.add(Snow.button(this, "Try with demo boots", Snow.ButtonKind.QUIET) { tryStart(true) })
    }

    private fun activeCaptureIntent(): Intent {
        val connected = service ?: return Intent(this, BootSetupActivity::class.java).reuseIfOpen()
        val drill = connected.drillState()
        return when {
            drill.phase !in listOf(DrillPhase.IDLE, DrillPhase.DONE) -> Intent(this, DrillActivity::class.java).putExtra("drill", drill.drill?.id)
            connected.coachState().running -> Intent(this, CoachingActivity::class.java).reuseIfOpen()
            connected.sessionId() != null -> Intent(this, MainActivity::class.java)
            else -> Intent(this, BootSetupActivity::class.java).reuseIfOpen()
        }
    }

    /** Starts a run and shows the reason on this screen if it could not start. Asks for location once, first. */
    private fun tryStart(demo: Boolean) {
        if (!demo && gpsSettings.enabled && !probe.permitted && !gpsSettings.asked) {
            gpsSettings.asked = true
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQUEST_LOCATION)
            return
        }
        probe.stop()   // the service starts its own listener; do not run two
        notice = service?.startRun(demo)
        if (notice != null) { structure = ""; state?.let { apply(it) } }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        statusCache = null
        if (requestCode != REQUEST_LOCATION) return
        // A refusal is fine: the run goes ahead without GPS and the row says why. The activity may have been
        // recreated behind the dialog, so start now only if the service is bound, otherwise when it binds.
        if (service != null) tryStart(false) else startWhenBound = true
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
        }
    }

    private fun running(column: LinearLayout, s: RunState) {
        column.add(t("Run in progress", Snow.Type.TITLE, Snow.INK_SOFT))
        timerView = t(clock(s.elapsedMs), Snow.Type.HERO).also { column.add(it, top = 4) }
        turnsView = t("${s.turns.values.sum()} turns", Snow.Type.DISPLAY).also { column.add(it, top = 4) }
        verdictView = t(verdictLine(s), Snow.Type.BODY).also { column.add(it, top = 12, bottom = 16) }
        s.banner?.let { column.add(t(it, Snow.Type.STRONG), bottom = 16) }
        column.add(t(GestureSyncPrompts.RUNNING, Snow.Type.STRONG), bottom = 12)
        column.add(t("You can lock the phone and put it in your pocket. The run ends by itself after a quiet spell.", Snow.Type.CAPTION, Snow.INK_SOFT), top = 16)
    }

    private fun saving(column: LinearLayout, s: RunState) {
        column.add(t("Run ended", Snow.Type.HERO))
        column.add(t("Saving boot data. Keep the boots on and nearby; you can ride the lift and close the app.", Snow.Type.BODY, Snow.INK_SOFT), top = 4, bottom = 16)
        if (s.demo) {
            val card = Snow.card(this, 18)
            progressViews = RunController.SIDES.associateWith { side ->
                t(progressLine(side, s), Snow.Type.STRONG).also { card.add(it, top = if (side == "L") 0 else 10) }
            }
            column.add(card, bottom = 16)
        } else {
            column.add(RecoveryCard(this, service?.lastRunId()), bottom = 16)
        }
        column.add(t("Saving carries on in the background; the boots stay in On snow mode until you switch them back.", Snow.Type.CAPTION, Snow.INK_SOFT), top = 8)
    }

    private fun done(column: LinearLayout, s: RunState) {
        val summary = s.summary
        column.add(t(if (s.message.isEmpty()) "Run saved" else "Run finished", Snow.Type.HERO))
        if (s.message.isNotEmpty()) column.add(t(s.message, Snow.Type.BODY), top = 4)
        if (!s.demo) column.add(RecoveryCard(this, service?.lastRunId()), top = 12, bottom = 12)
        if (summary != null) {
            val card = Snow.card(this, 18)
            card.add(t("${clock(summary.durationMs)} · ${summary.turns.values.sum()} turns", Snow.Type.DISPLAY))
            card.add(t("Left ${summary.turns["L"] ?: 0}, right ${summary.turns["R"] ?: 0}", Snow.Type.BODY, Snow.INK_SOFT), top = 4)
            card.add(t("▲ ${summary.matched} matched   ▼ ${summary.offTarget} off target   ● ${summary.unclear} close", Snow.Type.STRONG), top = 10)
            if (summary.rawDataCapped) card.add(t("Raw data covers the first 10 minutes only.", Snow.Type.BODY), top = 10)
            if (summary.gapSeconds > 0) card.add(t("A boot lost its link for ${summary.gapSeconds} s; turns in that time are missing.", Snow.Type.BODY), top = 6)
            card.add(t("This compares dry-ski boot roll with a training target. It is not an on-snow technique score.", Snow.Type.CAPTION, Snow.INK_SOFT), top = 10)
            column.add(card, top = 16, bottom = 16)
            column.add(RunTrackCard.build(this, RunTrack.report(s.demo, summary.gps, summary.track, summary.gpsProblem)), bottom = 16)
        }
        val comparisonHolder = FrameLayout(this)
        column.add(comparisonHolder, bottom = 16)
        service?.lastRunId()?.let { id ->
            service?.loadRunComparison(id) { comparison ->
                if (comparison != null && comparisonHolder.isAttachedToWindow) comparisonHolder.addView(RunComparisonCard.build(this, comparison))
            }
        }
        column.add(t(GestureSyncPrompts.DONE, Snow.Type.CAPTION, Snow.INK_SOFT), bottom = 12)
        column.add(Snow.button(this, "Back to ready") { service?.resetRun() }, bottom = 10)
        val resultId = service?.lastRunId()
        if (resultId != null) column.add(Snow.button(this, "Open saved run", Snow.ButtonKind.SECONDARY) {
            startActivity(Intent(this, SessionDetailActivity::class.java).putExtra("session_id", resultId))
        }) else column.add(t("No saved session is linked to this result.", Snow.Type.CAPTION))
    }

    private companion object { const val REQUEST_LOCATION = 41 }
}
