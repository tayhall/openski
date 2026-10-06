package com.openski.android

import android.Manifest
import android.animation.ValueAnimator
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * One drill: read the cue, roll through the set, see your carved line and what to change next.
 * Runs on demo boots, or on a live boot through [SensorSessionService] (saved as a test session in the logbook).
 */
class DrillActivity : Activity(), SensorSessionService.Listener {
    private enum class Phase { INTRO, CONNECTING, CALIBRATE, STARTING, HOLDING, SETTLING, RUNNING, DONE }

    private lateinit var store: ProgressStore
    private lateinit var drill: Drill
    private lateinit var frame: FrameLayout
    private var animator: ValueAnimator? = null
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    private var phase = Phase.INTRO
    private var service: SensorSessionService? = null
    private var bound = false
    private val lastSample = ConcurrentHashMap<String, Long>()
    private val statuses = ConcurrentHashMap<String, String>()
    @Volatile private var recorder: LiveDrillRecorder? = null
    @Volatile private var sessionId: String? = null
    private var ownsRecording = false
    private var side = "L"
    private var holdUntil = 0L
    private var calibrationRequested = false
    private var runStartedAt = 0L

    private var statusView: TextView? = null
    private var messageView: TextView? = null
    private var counterView: TextView? = null
    private var lineView: CarvedLineView? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val bound = (binder as? SensorSessionService.LocalBinder)?.service ?: return
            service = bound
            bound.setListener(this@DrillActivity)
            bound.setUiVisible(true)
            bound.restoreConnections()
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Snow.configureWindow(this)
        store = ProgressStore(this)
        drill = Programme.drill(intent.getStringExtra("drill") ?: "") ?: run { finish(); return }
        frame = FrameLayout(this).apply { setBackgroundColor(Snow.SNOW) }
        SkiUi.applyInsets(frame)
        setContentView(frame)
        intro()
        handler.post(ticker)
    }

    override fun onDestroy() {
        animator?.cancel()
        handler.removeCallbacksAndMessages(null)
        if (ownsRecording && phase != Phase.DONE) stopRecording()
        if (bound) {
            service?.setListener(null)
            unbindService(connection)
            bound = false
        }
        io.shutdown()
        super.onDestroy()
    }

    private fun t(text: String, type: Snow.Type = Snow.Type.BODY, tint: Int = Snow.INK) = Snow.text(this, text, type, tint)
    private fun LinearLayout.add(view: View, top: Int = 0, bottom: Int = 0) =
        addView(view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Snow.dp(this@DrillActivity, top); bottomMargin = Snow.dp(this@DrillActivity, bottom) })

    private fun show(content: LinearLayout.() -> Unit) {
        statusView = null; messageView = null; counterView = null; lineView = null
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val inset = Snow.dp(this@DrillActivity, 20)
            setPadding(inset, inset, inset, Snow.dp(this@DrillActivity, 28))
            content()
        }
        frame.removeAllViews()
        frame.addView(ScrollView(this).apply { isFillViewport = true; addView(column) })
    }

    private fun backLink(label: String = "Path", action: () -> Unit = { finish() }) =
        Snow.button(this, "‹  $label", Snow.ButtonKind.QUIET) { action() }.apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(0, paddingTop, paddingRight, paddingBottom)
        }

    private fun savedBoots() = getSharedPreferences(SensorSessionService.PREFS, MODE_PRIVATE).let {
        listOf("L", "R").filter { side -> it.getString("sensor_$side", null) != null }
    }

    // Intro ---------------------------------------------------------------------------------------------

    private fun intro() {
        phase = Phase.INTRO
        val boots = savedBoots()
        show {
            add(backLink(), bottom = 8)
            val kicker = LinearLayout(this@DrillActivity).apply { gravity = Gravity.CENTER_VERTICAL }
            kicker.addView(PisteMarker(this@DrillActivity, drill.piste, 24))
            kicker.addView(t(drill.piste.title, Snow.Type.STRONG, Snow.INK_SOFT), LinearLayout.LayoutParams(-2, -2).apply { marginStart = Snow.dp(this@DrillActivity, 8) })
            add(kicker)
            add(t(drill.title, Snow.Type.HERO), top = 8)
            add(t(drill.summary, Snow.Type.BODY, Snow.INK_SOFT), top = 6, bottom = 20)

            val facts = LinearLayout(this@DrillActivity)
            fun fact(value: String, label: String) = LinearLayout(this@DrillActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(t(value, Snow.Type.DISPLAY)); addView(t(label, Snow.Type.BODY, Snow.INK_SOFT))
            }
            facts.addView(fact("${drill.movements}", "rolls"), LinearLayout.LayoutParams(0, -2, 1f))
            facts.addView(fact("${drill.paceSeconds} s", "per roll"), LinearLayout.LayoutParams(0, -2, 1f))
            facts.addView(fact("${drill.depthDegrees.toInt()}°", "aim for"), LinearLayout.LayoutParams(0, -2, 1f))
            add(facts, bottom = 20)

            val cue = Snow.card(this@DrillActivity, 18, Snow.GLACIER)
            cue.addView(t("Coach's cue", Snow.Type.TITLE))
            cue.add(t(drill.cue, Snow.Type.BODY), top = 6)
            add(cue, bottom = 20)

            add(t("Stand tall and still in your boots.\nRoll to one side, then the other, on the beat.\nReturn to neutral between rolls.", Snow.Type.BODY), bottom = 24)
            if (boots.isNotEmpty()) {
                add(Snow.button(this@DrillActivity, "Start with my boots") { startLive() })
                add(Snow.button(this@DrillActivity, "Try with demo boots", Snow.ButtonKind.SECONDARY) { startRun() }, top = 10)
                add(t("Your boots are scored on dry-ski roll. Each drill is saved to the logbook as a test session.", Snow.Type.CAPTION, Snow.INK_SOFT), top = 12)
            } else {
                add(Snow.button(this@DrillActivity, "Start with demo boots") { startRun() })
                add(t("Demo boots are simulated, so these scores don't measure your skiing.", Snow.Type.CAPTION, Snow.INK_SOFT), top = 12)
                add(Snow.button(this@DrillActivity, "Set up my boots", Snow.ButtonKind.SECONDARY) {
                    startActivity(Intent(this@DrillActivity, MainActivity::class.java))
                }, top = 12)
            }
        }
    }

    // Live boots ----------------------------------------------------------------------------------------

    private fun needed(): Array<String> {
        val wanted = buildList {
            if (Build.VERSION.SDK_INT >= 31) { add(Manifest.permission.BLUETOOTH_SCAN); add(Manifest.permission.BLUETOOTH_CONNECT) }
            else add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }.toTypedArray()
    }

    private fun startLive() {
        val missing = needed()
        if (missing.isNotEmpty()) { requestPermissions(missing, REQUEST_PERMISSIONS); return }
        connect()
    }

    @Deprecated("Permission callback used for compatibility with Android 8 through 12")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_PERMISSIONS) return
        val bluetooth = if (Build.VERSION.SDK_INT >= 31) checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            else checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (bluetooth) connect() else problem("Bluetooth permission is needed to talk to your boots. Allow it in Settings, then try again.")
    }

    private fun problem(message: String) {
        phase = Phase.INTRO
        show {
            add(backLink("Back") { intro() }, bottom = 8)
            add(t("Can't use your boots yet", Snow.Type.DISPLAY), bottom = 10)
            add(t(message, Snow.Type.BODY, Snow.INK_SOFT), bottom = 20)
            add(Snow.button(this@DrillActivity, "Try demo boots") { startRun() }, bottom = 10)
            add(Snow.button(this@DrillActivity, "Open Geek mode", Snow.ButtonKind.SECONDARY) {
                startActivity(Intent(this@DrillActivity, MainActivity::class.java))
            })
        }
    }

    private fun connect() {
        if (!bound) bound = bindService(Intent(this, SensorSessionService::class.java), connection, Context.BIND_AUTO_CREATE)
        phase = Phase.CONNECTING
        show {
            add(backLink("Back") { abandon() }, bottom = 8)
            add(t(drill.title, Snow.Type.DISPLAY), bottom = 6)
            add(t("Connecting to your boots", Snow.Type.TITLE), top = 10)
            add(t("Switch the sensors on and keep the phone close.", Snow.Type.BODY, Snow.INK_SOFT), top = 6, bottom = 18)
            statusView = t("Looking for your boots…", Snow.Type.BODY)
            add(statusView!!, bottom = 20)
            add(Snow.button(this@DrillActivity, "Open Geek mode", Snow.ButtonKind.SECONDARY) {
                startActivity(Intent(this@DrillActivity, MainActivity::class.java))
            })
        }
    }

    /** Which boots are streaming right now; the drill follows one of them. */
    private fun liveBoots(now: Long = System.currentTimeMillis()) =
        listOf("L", "R").filter { (lastSample[it] ?: 0L) > now - 1500 }

    private fun chooseBoot(boot: String) {
        side = boot
        recorder = LiveDrillRecorder(drill, boot)
        if (store.mounting(boot) == null) MountingPicker.show(this, store, boot) { calibrateScreen() } else calibrateScreen()
    }

    private fun calibrateScreen(message: String? = null) {
        phase = Phase.CALIBRATE
        val boot = if (side == "L") "left" else "right"
        val mounting = store.mounting(side)
        show {
            add(backLink("Back") { abandon() }, bottom = 8)
            add(t(drill.title, Snow.Type.DISPLAY), bottom = 6)
            add(t("Calibrate your $boot boot", Snow.Type.TITLE), top = 10)
            add(t("Stand tall with your boots flat on the floor. When you tap Calibrate, hold still for three seconds.", Snow.Type.BODY, Snow.INK_SOFT), top = 6, bottom = 18)
            message?.let { add(t(it, Snow.Type.STRONG, Snow.RED), bottom = 14) }
            add(Snow.button(this@DrillActivity, if (message == null) "Calibrate" else "Try again") { beginCalibration() })
            mounting?.let {
                add(t("Sensor mounting: ${it.label(store.landmarks())}", Snow.Type.CAPTION, Snow.INK_SOFT), top = 16)
                add(Snow.button(this@DrillActivity, "Change mounting", Snow.ButtonKind.QUIET) {
                    MountingPicker.show(this@DrillActivity, store, side) { calibrateScreen() }
                })
            }
        }
    }

    private fun beginCalibration() {
        val running = service ?: return problem("The sensor service is not ready yet. Try again in a moment.")
        if (running.sessionId() != null && !ownsRecording) {
            return calibrateScreen("A recording is already running. Stop it in Geek mode first.")
        }
        calibrationRequested = false
        holdUntil = 0L
        if (ownsRecording && sessionId != null) { startHold(); return }
        phase = Phase.STARTING
        messageView = null
        show {
            add(t("Starting", Snow.Type.DISPLAY), bottom = 10)
            messageView = t("Opening a test recording…", Snow.Type.BODY, Snow.INK_SOFT)
            add(messageView!!)
        }
        val start = Intent(this, SensorSessionService::class.java).setAction(SensorSessionService.ACTION_START_RECORDING)
            .putExtra("test_session", true)
        try { startForegroundService(start); ownsRecording = true }
        catch (error: Exception) { calibrateScreen("Could not start recording: ${error.message ?: "service error"}") }
    }

    private fun startHold() {
        phase = Phase.HOLDING
        holdUntil = System.currentTimeMillis() + 3200
        calibrationRequested = false
        show {
            add(t("Hold still", Snow.Type.DISPLAY), bottom = 10)
            counterView = t("3", Snow.Type.HERO)
            add(counterView!!)
            messageView = t("Keep both boots flat and quiet.", Snow.Type.BODY, Snow.INK_SOFT)
            add(messageView!!, top = 8)
        }
    }

    private fun requestCalibration() {
        calibrationRequested = true
        val mounting = store.mounting(side) ?: Mounting(0, 1)
        service?.calibrateTest(side, mounting.axis, mounting.sign) { message ->
            if (isDestroyed) return@calibrateTest
            if (message.contains("calibrated", ignoreCase = true)) settle() else calibrateScreen(message)
        }
    }

    private fun settle() {
        phase = Phase.SETTLING
        show {
            add(t("Ready", Snow.Type.DISPLAY), bottom = 10)
            add(t("Stand neutral. The drill starts as soon as your boot is steady.", Snow.Type.BODY, Snow.INK_SOFT))
        }
    }

    private fun beginRun() {
        val active = recorder ?: return
        phase = Phase.RUNNING
        val now = System.currentTimeMillis()
        runStartedAt = now
        active.begin(now.toDouble())
        service?.markTest("START_TEST", now, "Drill: ${drill.title}")
        val window = (drill.movements * drill.paceSeconds * 1.15).toFloat()
        show {
            add(t(drill.title, Snow.Type.DISPLAY), bottom = 16)
            val line = CarvedLineView(this@DrillActivity, RollTrace(FloatArray(0), FloatArray(0)), store.lastTrace(drill.id, false),
                drill.depthDegrees, Snow.pisteColor(drill.piste))
            line.showLive(RollTrace(FloatArray(0), FloatArray(0)), window)
            lineView = line
            add(line, bottom = 16)
            counterView = t("0 of ${drill.movements}", Snow.Type.HERO)
            add(counterView!!)
            add(t("Roll on the beat, one every ${drill.paceSeconds} s. Aim for ${drill.depthDegrees.toInt()}°.", Snow.Type.BODY, Snow.INK_SOFT), top = 4, bottom = 20)
            add(Snow.button(this@DrillActivity, "Finish now", Snow.ButtonKind.SECONDARY) { finishLive() })
        }
    }

    private fun finishLive() {
        val active = recorder ?: return
        if (phase != Phase.RUNNING) return
        phase = Phase.DONE
        service?.markTest("PAUSE", System.currentTimeMillis(), "Drill set ended")
        stopRecording()
        val run = active.run()
        if (run.detection.movements.isEmpty()) {
            phase = Phase.INTRO
            show {
                add(backLink("Path"), bottom = 8)
                add(t("No rolls detected", Snow.Type.DISPLAY), bottom = 10)
                add(t("Check the sensor mounting, then calibrate again and roll further each side.", Snow.Type.BODY, Snow.INK_SOFT), bottom = 20)
                add(Snow.button(this@DrillActivity, "Try again") { restart() }, bottom = 10)
                add(Snow.button(this@DrillActivity, "Change mounting", Snow.ButtonKind.SECONDARY) {
                    MountingPicker.show(this@DrillActivity, store, side) { restart() }
                })
            }
            return
        }
        result(run)
    }

    private fun restart() { recorder = null; sessionId = null; ownsRecording = false; intro() }

    /** Leave the live flow and, if we opened a test recording, close it. */
    private fun abandon() {
        if (ownsRecording) stopRecording()
        restart()
    }

    private fun stopRecording() {
        if (!ownsRecording) return
        ownsRecording = false
        val id = sessionId
        sessionId = null
        try { startService(Intent(this, SensorSessionService::class.java).setAction(SensorSessionService.ACTION_STOP_RECORDING)) } catch (_: Exception) {}
        id?.let { sessionId ->
            val title = "Drill · ${drill.title}"
            val appContext = applicationContext
            // Let the service finish the session first, then name it so it reads clearly in the logbook.
            // A fresh handler, so leaving this screen does not cancel the rename.
            Handler(Looper.getMainLooper()).postDelayed({
                Executors.newSingleThreadExecutor().execute {
                    val database = LocalSessionStore(appContext)
                    try { database.editSession(sessionId, title, "Dry-ski drill from the training path.", 0, 0, 0) }
                    catch (_: Exception) { /* the session keeps its default name */ }
                    finally { database.close() }
                }
            }, 1500)
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            when (phase) {
                Phase.CONNECTING -> {
                    val live = liveBoots()
                    val saved = savedBoots()
                    statusView?.text = saved.joinToString("\n") { boot ->
                        val name = if (boot == "L") "Left boot" else "Right boot"
                        "$name: " + if (boot in live) "streaming" else (statuses[boot] ?: "looking…")
                    }.ifEmpty { "No boots are paired yet." }
                    if (live.isNotEmpty()) chooseBoot(if ("L" in live) "L" else live.first())
                }
                Phase.HOLDING -> {
                    val remaining = holdUntil - System.currentTimeMillis()
                    counterView?.text = ((remaining + 999) / 1000).coerceIn(0, 3).toString()
                    if (remaining <= 0 && !calibrationRequested) {
                        messageView?.text = "Calibrating…"
                        requestCalibration()
                    }
                }
                Phase.SETTLING -> {
                    if (recorder?.neutral(System.currentTimeMillis().toDouble()) == true) beginRun()
                }
                Phase.RUNNING -> {
                    val active = recorder
                    if (active != null) {
                        val window = (drill.movements * drill.paceSeconds * 1.15).toFloat()
                        lineView?.showLive(active.trace(), window)
                        counterView?.text = "${active.count()} of ${drill.movements}"
                        val elapsed = (System.currentTimeMillis() - runStartedAt) / 1000.0
                        if (active.complete() || elapsed > drill.movements * drill.paceSeconds * 3 + 20) finishLive()
                    }
                }
                else -> {}
            }
            handler.postDelayed(this, 100)
        }
    }

    // SensorSessionService.Listener (BLE and service threads) ---------------------------------------------

    override fun onSensorStatus(side: String, message: String) { statuses[side] = message }
    override fun onSensorSample(side: String, sample: SensorSample) { lastSample[side] = System.currentTimeMillis() }
    override fun onBootOrientation(side: String, value: BootRoll?) { if (value != null) recorder?.accept(value) }
    override fun onRecordingChanged(sessionId: String?, message: String) = runOnUiThread {
        if (isDestroyed || !ownsRecording && phase != Phase.STARTING) return@runOnUiThread
        if (sessionId != null) {
            this.sessionId = sessionId
            if (phase == Phase.STARTING) startHold()
        } else if (phase == Phase.STARTING) {
            ownsRecording = false
            calibrateScreen(message)
        }
    }

    // Demo boots ----------------------------------------------------------------------------------------

    private fun startRun() {
        val before = store.attempts().count { it.drillId == drill.id && it.demo }
        val run = DemoDrill.simulate(drill, before)
        val color = Snow.pisteColor(drill.piste)
        phase = Phase.INTRO
        show {
            add(t("Demo boots", Snow.Type.CAPTION, Snow.INK_SOFT), bottom = 4)
            add(t(drill.title, Snow.Type.DISPLAY), bottom = 16)
            val counter = t("0 of ${drill.movements}", Snow.Type.HERO)
            val line = CarvedLineView(this@DrillActivity, run.trace, null, drill.depthDegrees, color).apply { progress = 0f }
            add(line, bottom = 16)
            add(counter)
            add(t("Roll on the beat. Aim for ${drill.depthDegrees.toInt()}°.", Snow.Type.BODY, Snow.INK_SOFT), top = 4)
            animator?.cancel()
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 6500; interpolator = LinearInterpolator()
                addUpdateListener {
                    val fraction = it.animatedValue as Float
                    line.progress = fraction
                    val elapsed = fraction * run.trace.duration * 1000
                    counter.text = "${run.detection.movements.count { m -> m.endMs <= elapsed }} of ${drill.movements}"
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    private var cancelled = false
                    override fun onAnimationCancel(a: android.animation.Animator) { cancelled = true }
                    override fun onAnimationEnd(a: android.animation.Animator) { if (!cancelled) result(run) }
                })
                start()
            }
        }
    }

    // Result --------------------------------------------------------------------------------------------

    private fun result(run: DrillRun) {
        phase = Phase.DONE
        val result = DrillScoring.score(drill, run.detection)
        val progress = store.progress()
        val previousBest = progress.best(drill.id)?.score
        val vert = result.vert(previousBest)
        val wasOpen = Programme.drills.filter { progress.unlocked(it) }.map { it.id }.toSet()
        val ghost = store.lastTrace(drill.id, run.demo)
        store.record(Attempt(drill.id, System.currentTimeMillis(), result.score, result.stars, vert, run.demo))
        store.saveTrace(drill.id, run.demo, run.trace)
        val after = store.progress()
        val unlocked = Programme.drills.firstOrNull { after.unlocked(it) && it.id !in wasOpen }
        val headline = when (result.stars) { 3 -> "Clean set"; 2 -> "Good set"; 1 -> "Passed"; else -> "Not quite yet" }
        val again: () -> Unit = { restart(); if (run.demo) startRun() else startLive() }
        show {
            add(t(if (run.demo) "Demo boots" else "Your boots", Snow.Type.CAPTION, Snow.INK_SOFT), bottom = 4)
            add(t(headline, Snow.Type.HERO), bottom = 14)
            add(CarvedLineView(this@DrillActivity, run.trace, ghost, drill.depthDegrees, Snow.pisteColor(drill.piste)), bottom = 6)
            if (ghost != null) add(t("Grey line: your last try", Snow.Type.CAPTION, Snow.INK_SOFT), bottom = 12)
            val summary = LinearLayout(this@DrillActivity).apply { gravity = Gravity.CENTER_VERTICAL }
            summary.addView(StarRow(this@DrillActivity, result.stars, 28))
            summary.addView(t("${result.score}", Snow.Type.DISPLAY), LinearLayout.LayoutParams(-2, -2).apply { marginStart = Snow.dp(this@DrillActivity, 14) })
            summary.addView(View(this@DrillActivity), LinearLayout.LayoutParams(0, 1, 1f))
            summary.addView(t("+$vert vert", Snow.Type.TITLE, Snow.ORANGE))
            add(summary, top = 8, bottom = 16)

            val cue = Snow.card(this@DrillActivity, 18, Snow.GLACIER)
            cue.addView(t("Next time", Snow.Type.TITLE))
            cue.add(t(result.cue, Snow.Type.BODY), top = 6)
            add(cue, bottom = 16)

            val metrics = Snow.card(this@DrillActivity, 18)
            listOf("Tempo" to result.tempo, "Steadiness" to result.steadiness, "Left and right balance" to result.balance, "Depth" to result.depth)
                .forEachIndexed { index, (name, value) -> metrics.add(metric(name, value), top = if (index == 0) 0 else 14) }
            add(metrics, bottom = 20)

            unlocked?.let { add(t("Unlocked: ${it.title}", Snow.Type.TITLE, Snow.ORANGE), bottom = 12) }
            if (!run.demo) add(t("Saved to your logbook as a test session.", Snow.Type.CAPTION, Snow.INK_SOFT), bottom = 12)
            if (result.passed && unlocked != null) add(Snow.button(this@DrillActivity, "Next drill") {
                startActivity(Intent(this@DrillActivity, DrillActivity::class.java).putExtra("drill", unlocked.id)); finish()
            }, bottom = 10)
            add(Snow.button(this@DrillActivity, "Try again", if (result.passed && unlocked != null) Snow.ButtonKind.SECONDARY else Snow.ButtonKind.PRIMARY) { again() }, bottom = 10)
            add(Snow.button(this@DrillActivity, "Back to path", Snow.ButtonKind.QUIET) { finish() })
        }
    }

    private fun metric(name: String, value: Int): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        contentDescription = "$name $value out of 100"
        val head = LinearLayout(this@DrillActivity)
        head.addView(t(name, Snow.Type.STRONG), LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(t("$value", Snow.Type.STRONG))
        addView(head)
        val height = Snow.dp(this@DrillActivity, 8)
        val track = FrameLayout(this@DrillActivity).apply { background = Snow.rounded(this@DrillActivity, Snow.GLACIER, 4) }
        val fill = View(this@DrillActivity).apply { background = Snow.rounded(this@DrillActivity, Snow.pisteColor(drill.piste), 4) }
        track.addView(fill, FrameLayout.LayoutParams(0, height))
        track.post { fill.layoutParams = FrameLayout.LayoutParams(track.width * value / 100, height) }
        addView(track, LinearLayout.LayoutParams(-1, height).apply { topMargin = Snow.dp(this@DrillActivity, 6) })
    }

    companion object { private const val REQUEST_PERMISSIONS = 41 }
}
