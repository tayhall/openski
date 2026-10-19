package com.openski.android

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** A view of the service-owned practice. Leaving or recreating this screen never stops capture. */
class DrillActivity : Activity() {
    private lateinit var drill: Drill
    private lateinit var store: ProgressStore
    private lateinit var frame: FrameLayout
    private lateinit var phaseLayout: PhaseLayout
    private var preparationExpanded = false
    private var renderedScreen = ""
    private var service: SensorSessionService? = null
    private var bound = false
    private var snapshot: DrillState? = null
    private var showIntro = false
    private var signature = ""
    private var notice: String? = null
    private var repeat: Attempt? = null
    private var repeatLoading = false
    private val repeatIo = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var pendingStart: Boolean? = null
    private var counter: TextView? = null
    private var status: TextView? = null
    private var line: CarvedLineView? = null
    private var mountingDialog: android.app.AlertDialog? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as? SensorSessionService.LocalBinder)?.service
            service?.observeDrill(this@DrillActivity, ::apply)
            pendingStart?.let { pendingStart = null; begin(it) }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            status?.text = "Reconnecting to practice service…"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Snow.configureWindow(this)
        drill = Programme.drill(intent.getStringExtra("drill") ?: "") ?: run { finish(); return }
        store = ProgressStore(this)
        if (savedInstanceState?.containsKey("pending_start") == true) pendingStart = savedInstanceState.getBoolean("pending_start")
        showIntro = savedInstanceState?.getBoolean("intro") ?: !intent.getBooleanExtra("resume_active", false)
        frame = FrameLayout(this).apply { setBackgroundColor(Snow.SNOW) }
        preparationExpanded = savedInstanceState?.getBoolean("preparation_expanded") ?: false
        phaseLayout = PhaseLayout(this)
        frame.addView(phaseLayout)
        SkiUi.applyInsets(frame, ime = true)
        setContentView(frame)
        repeatLoading = intent.hasExtra("repeat_attempt_id")
        intro()
        intent.getStringExtra("repeat_attempt_id")?.let { id ->
            repeatLoading = true
            repeatIo.execute {
                val loaded = runCatching { requireNotNull(store.attempt(id)) { "Saved practice is unavailable." }.also { PracticeRepeat.drill(it) } }
                runOnUiThread {
                    if (!isDestroyed) {
                        repeatLoading = false
                        loaded.onSuccess { repeat = it; drill = PracticeRepeat.drill(it); notice = "Saved target · ${if (it.demo) "Demo" else "My practice"}. Confirm below; preparation and calibration run again." }
                            .onFailure { notice = it.message ?: "Could not load saved target." }
                        if (loaded.isSuccess && pendingStart != null) {
                            pendingStart = null; begin(repeat!!.demo)
                        } else if (snapshot?.phase !in listOf(null, DrillPhase.IDLE, DrillPhase.DONE)) apply(snapshot!!)
                        else intro()
                    }
                }
            }
        }
    }
    override fun onDestroy() { repeatIo.shutdown(); super.onDestroy() }

    override fun onStart() {
        super.onStart()
        bound = bindService(Intent(this, SensorSessionService::class.java), connection, Context.BIND_AUTO_CREATE)
    }
    override fun onStop() {
        mountingDialog?.dismiss(); mountingDialog = null
        service?.removeDrillObserver(this)
        if (bound) unbindService(connection)
        bound = false; service = null
        super.onStop()
    }
    override fun onSaveInstanceState(state: Bundle) {
        pendingStart?.let { state.putBoolean("pending_start", it) }
        state.putBoolean("intro", showIntro)
        state.putBoolean("preparation_expanded", preparationExpanded)
        super.onSaveInstanceState(state)
    }

    private fun t(text: String, type: Snow.Type = Snow.Type.BODY, tint: Int = Snow.INK) = Snow.text(this, text, type, tint)
    private fun LinearLayout.add(view: android.view.View, top: Int = 0, bottom: Int = 0) =
        addView(view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Snow.dp(this@DrillActivity, top); bottomMargin = Snow.dp(this@DrillActivity, bottom) })
    private fun show(actions: List<PhaseLayout.Action> = emptyList(), screen: String = "result", content: LinearLayout.() -> Unit) {
        counter = null; status = null; line = null
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Snow.dp(this@DrillActivity, 20), Snow.dp(this@DrillActivity, 20), Snow.dp(this@DrillActivity, 20), Snow.dp(this@DrillActivity, 28))
            content()
        }
        phaseLayout.content(column, screen != renderedScreen)
        phaseLayout.controls(actions)
        renderedScreen = screen
    }
    private fun back() = Snow.button(this, "‹ Back", Snow.ButtonKind.QUIET) { finish() }.apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL }

    private fun intro() {
        val paired = listOf("L", "R").any { store.sensorAddress(it) != null }
        val start = when {
            repeatLoading -> null
            repeat != null -> PhaseLayout.Action("repeat-saved", "Repeat saved target · ${if (repeat!!.demo) "Demo" else "My practice"}") { begin(repeat!!.demo) }
            intent.hasExtra("repeat_attempt_id") -> null
            paired -> PhaseLayout.Action("start-real", "Start with my boots") { begin(false) }
            else -> PhaseLayout.Action("start-demo", "Try Demo") { begin(true) }
        }
        show(listOfNotNull(start), "intro") {
            add(back(), bottom = 8)
            add(t(drill.title, Snow.Type.DISPLAY), bottom = 8)
            notice?.let { add(t(it), bottom = 12) }
            add(t(drill.summary), bottom = 12)
            add(t("${drill.movements} rolls · ${drill.paceSeconds} s beat · ${drill.depthDegrees.toInt()}° cuff roll", Snow.Type.STRONG), bottom = 16)
            add(t("Coach's cue", Snow.Type.TITLE), bottom = 6)
            add(t(drill.cue), bottom = 16)
            val details = PreparationDetails(this@DrillActivity, "How to practise", preparationExpanded) { preparationExpanded = it }
            details.body.add(t("Stand neutral in your boots. Roll to one side, then the other, returning to neutral between rolls."), bottom = 12)
            TechniqueVideos.forDrill(drill.id)?.let { video ->
                details.body.add(Snow.button(this@DrillActivity, "Watch: ${video.title}", Snow.ButtonKind.QUIET) { TechniqueVideos.open(this@DrillActivity, video.id) }, bottom = 12)
            }
            add(details, bottom = 12)
            if (repeatLoading) add(t("Loading saved target…"))
            else if (intent.hasExtra("repeat_attempt_id") && repeat == null) add(t("Exact repeat is unavailable. Return to the saved result to choose a current preset."))
            else if (repeat == null && paired) add(Snow.button(this@DrillActivity, "Try Demo", Snow.ButtonKind.SECONDARY) { begin(true) }, bottom = 10)
            add(t("Demo is simulated and separate from My practice. Measured scores describe dry cuff roll, not skiing technique.", Snow.Type.CAPTION))
            add(Snow.button(this@DrillActivity, "Set up boots", Snow.ButtonKind.QUIET) {
                startActivity(Intent(this@DrillActivity, BootSetupActivity::class.java))
            }, top = 12)
        }
    }

    private fun begin(demo: Boolean) {
        if (repeatLoading) { pendingStart = demo; return }
        if (intent.hasExtra("repeat_attempt_id") && repeat == null) { notice = "The saved target is unavailable. Return to the result."; intro(); return }
        val needed = buildList {
            if (!demo) {
                if (Build.VERSION.SDK_INT >= 31) { add(Manifest.permission.BLUETOOTH_SCAN); add(Manifest.permission.BLUETOOTH_CONNECT) }
                else add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) {
            pendingStart = demo
            requestPermissions(needed.toTypedArray(), REQUEST_PERMISSIONS)
            return
        }
        launch(demo)
    }
    private fun launch(demo: Boolean) {
        if (repeatLoading) { pendingStart = demo; return }
        if (intent.hasExtra("repeat_attempt_id") && repeat == null) { notice = "The saved target is unavailable. Return to the result."; intro(); return }
        val running = service ?: run { pendingStart = demo; notice = "Connecting to practice service…"; intro(); return }
        showIntro = false; signature = ""
        val problem = running.startDrill(drill.id, demo, repeat)
        if (problem != null) { notice = problem; showIntro = true; intro() } else apply(running.drillState())
    }
    @Deprecated("Platform permission callback")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_PERMISSIONS) return
        val demo = pendingStart ?: return
        pendingStart = null
        val permitted = demo || if (Build.VERSION.SDK_INT >= 31)
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == android.content.pm.PackageManager.PERMISSION_GRANTED
            else checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (permitted) launch(demo) else { notice = "Allow Bluetooth permission to connect boots, or try Demo."; intro() }
    }

    private fun apply(state: DrillState) {
        snapshot = state
        if (state.phase !in listOf(DrillPhase.IDLE, DrillPhase.DONE)) showIntro = false
        if (showIntro) {
            if (state.phase == DrillPhase.DONE && state.drill?.id == drill.id && state.attempt?.status == PracticeStatus.INTERRUPTED) {
                notice = state.message; intro()
            }
            return
        }
        if (state.phase == DrillPhase.IDLE || (state.phase == DrillPhase.DONE && state.drill?.id != drill.id)) {
            signature = ""; intro(); return
        }
        state.drill?.let { drill = it }
        val structure = listOf(state.phase, state.attempt?.id, state.side, state.attempt?.status).joinToString("|")
        if (structure != signature) {
            if (state.phase != DrillPhase.MOUNTING) { mountingDialog?.dismiss(); mountingDialog = null }
            signature = structure
            if (state.phase == DrillPhase.DONE) result(state) else active(state)
        }
        status?.text = state.message
        counter?.text = "${state.count} of ${drill.movements}"
        line?.let { chart ->
            if (state.attempt?.demo == true) chart.progress = state.animation
            else chart.showLive(state.trace, (drill.movements * drill.paceSeconds * 1.15).toFloat())
        }
    }

    private fun active(state: DrillState) = show(actions = when (state.phase) {
        DrillPhase.SAVE_FAILED -> listOf(PhaseLayout.Action("retry-save", "Retry save") { service?.retryDrillSave() })
        DrillPhase.SAVING -> emptyList()
        else -> listOf(PhaseLayout.Action("finish", "Finish practice", Snow.ButtonKind.SECONDARY) { service?.finishDrill(state.attempt?.id) })
    }, screen = state.phase.name) {
        add(back(), bottom = 8)
        add(t(drill.title, Snow.Type.DISPLAY), bottom = 10)
        add(t(if (state.attempt?.demo == true) "Demo boots · simulated" else "My practice · ${state.side ?: "connecting"}", Snow.Type.CAPTION), bottom = 12)
        status = t(state.message).also { add(it, bottom = 16) }
        if (state.phase == DrillPhase.MOUNTING) {
            add(Snow.button(this@DrillActivity, "Choose mounting") {
                state.side?.let { side ->
                    if (mountingDialog?.isShowing != true) mountingDialog = MountingPicker.show(this@DrillActivity, store, side,
                        onCancel = { mountingDialog = null }, onDone = { mountingDialog = null })
                }
            }, bottom = 12)
        }
        if (state.phase == DrillPhase.RUNNING) {
            val chart = CarvedLineView(this@DrillActivity, state.trace, state.ghost,
                drill.depthDegrees, Snow.pisteColor(drill.piste), if (state.attempt?.demo == true) "Simulated cuff roll" else "Measured cuff roll")
            line = chart; add(chart, bottom = 12)
            counter = t("${state.count} of ${drill.movements}", Snow.Type.HERO).also { add(it, bottom = 12) }
        }
        add(t("You can leave this screen or lock the phone. Practice continues in the service; Finish ends the set.", Snow.Type.CAPTION), top = 12)
    }

    private fun result(state: DrillState) {
        if (state.run != null && state.result != null && state.attempt != null) {
            showResult(state.run, state.result, state.ghost, state.attempt, service?.nextPracticeDrill(), state.message)
        } else interruptedResult(state)
    }

    private fun interruptedResult(state: DrillState) = show {
        val attempt = state.attempt ?: return@show
        add(back(), bottom = 8)
        add(t(if (attempt.demo) "Demo boots" else "My practice", Snow.Type.CAPTION), bottom = 8)
        add(t(when (attempt.status) {
            PracticeStatus.COMPLETED -> if (attempt.eligible) "Set completed" else "Cannot judge this set"
            PracticeStatus.INTERRUPTED -> "Practice interrupted"
            else -> "Incomplete set"
        }, Snow.Type.HERO), bottom = 12)
        add(t(state.message), bottom = 12)
        if (attempt.eligible) {
            add(StarRow(this@DrillActivity, attempt.stars, 28), bottom = 8)
            add(t("${attempt.score} · +${attempt.vert} practice points", Snow.Type.TITLE), bottom = 12)
        }
        add(t(if (attempt.demo) "Simulated results stay in Demo; they do not change My practice achievements."
            else "Cuff-roll exercise, not an on-snow technique score.", Snow.Type.CAPTION), bottom = 12)
        if (state.run != null && attempt.eligible) {
            add(CarvedLineView(this@DrillActivity, state.trace, null, drill.depthDegrees, Snow.pisteColor(drill.piste),
                if (attempt.demo) "Simulated cuff roll" else "Measured cuff roll"), bottom = 12)
            add(StarRow(this@DrillActivity, attempt.stars, 28), bottom = 8)
            add(t("${attempt.score} · +${attempt.vert} practice points", Snow.Type.TITLE), bottom = 12)
            state.result?.let { result ->
                add(t(result.cue), bottom = 16)
                add(t("Tempo ${result.tempo} · Steadiness ${result.steadiness} · Directional balance ${result.balance} · Depth ${result.depth}"), bottom = 16)
            }
        }
        attempt.sessionId?.let { id ->
            add(Snow.button(this@DrillActivity, "Open raw session", Snow.ButtonKind.SECONDARY) {
                startActivity(Intent(this@DrillActivity, SessionDetailActivity::class.java).putExtra("session_id", id))
            }, bottom = 10)
        }
        add(Snow.button(this@DrillActivity, "Try again") { showIntro = true; notice = null; signature = ""; intro() }, bottom = 10)
        add(Snow.button(this@DrillActivity, "Open saved result", Snow.ButtonKind.SECONDARY) {
            startActivity(Intent(this@DrillActivity, PracticeResultActivity::class.java).putExtra("attempt_id", attempt.id))
        }, bottom = 10)
        add(Snow.button(this@DrillActivity, "Back to path", Snow.ButtonKind.QUIET) { finish() })
    }

    private fun showResult(run: DrillRun, result: DrillResult, ghost: RollTrace?, attempt: Attempt, unlocked: Drill?, message: String) {
        val vert = attempt.vert
        val headline = if (attempt.status == PracticeStatus.INTERRUPTED) "Practice interrupted" else if (attempt.status == PracticeStatus.COMPLETED && !attempt.eligible) "Cannot judge this set" else if (!attempt.eligible) "Incomplete set" else when (result.stars) { 3 -> "Clean set"; 2 -> "Good set"; 1 -> "Passed"; else -> "Not quite yet" }
        val again: () -> Unit = { begin(run.demo) }
        show {
            add(t(if (run.demo) "Demo boots" else "Your boots", Snow.Type.CAPTION, Snow.INK_SOFT), bottom = 4)
            add(t(headline, Snow.Type.HERO), bottom = 14)
            if (attempt.status == PracticeStatus.INTERRUPTED) add(t(message), bottom = 12)
            add(t(if (run.demo) "Simulated results stay in Demo. They do not change My practice achievements."
                else if (!attempt.eligible) "This set is unscored and earns no stars or practice points."
                else "Measured cuff-roll exercise · My practice", Snow.Type.CAPTION, Snow.INK_SOFT), bottom = 12)
            add(CarvedLineView(this@DrillActivity, run.trace, ghost, drill.depthDegrees, Snow.pisteColor(drill.piste),
                if (run.demo) "Simulated cuff roll" else "Measured cuff roll"), bottom = 6)
            if (ghost != null) add(t("Dashed grey line: your last try", Snow.Type.CAPTION, Snow.INK_SOFT), bottom = 12)
            val eligibility = PracticeEligibility.evaluate(attempt, attempt.review?.evidence ?: PracticeEvidence())
            add(t(eligibility.reason), bottom = 12)
            attempt.review?.let { add(t(it.observation(attempt.target)), bottom = 12) }
            if (attempt.eligible) {
            val summary = LinearLayout(this@DrillActivity).apply { gravity = Gravity.CENTER_VERTICAL }
            summary.addView(StarRow(this@DrillActivity, attempt.stars, 28))
            summary.addView(t("${result.score}", Snow.Type.DISPLAY), LinearLayout.LayoutParams(-2, -2).apply { marginStart = Snow.dp(this@DrillActivity, 14) })
            summary.addView(View(this@DrillActivity), LinearLayout.LayoutParams(0, 1, 1f))
            add(summary, top = 8, bottom = 16)
            add(t("+$vert practice points", Snow.Type.TITLE), bottom = 12)
            }

            val cue = Snow.card(this@DrillActivity, 18, Snow.GLACIER)
            cue.addView(t("Next time", Snow.Type.TITLE))
            cue.add(t(if (attempt.eligible) result.cue else eligibility.nextAction, Snow.Type.BODY), top = 6)
            add(cue, bottom = 16)

            if (attempt.eligible) {
            val metrics = Snow.card(this@DrillActivity, 18)
            listOf("Tempo" to result.tempo, "Steadiness" to result.steadiness, "Left and right balance" to result.balance, "Depth" to result.depth)
                .forEachIndexed { index, (name, value) -> metrics.add(metric(name, value), top = if (index == 0) 0 else 14) }
            add(metrics, bottom = 20)
            }

            if (attempt.eligible && result.passed) unlocked?.let { add(t("Next practice: ${it.title}", Snow.Type.TITLE), bottom = 12) }
            if (!run.demo) add(t("The raw session is in your logbook; sensor recovery may still be pending.", Snow.Type.CAPTION, Snow.INK_SOFT), bottom = 12)
            attempt.sessionId?.let { add(RecoveryCard(this@DrillActivity, it), bottom = 12) }
            add(Snow.button(this@DrillActivity, "Open saved result", Snow.ButtonKind.SECONDARY) {
                startActivity(Intent(this@DrillActivity, PracticeResultActivity::class.java).putExtra("attempt_id", attempt.id))
            }, bottom = 10)
            if (attempt.eligible && result.passed && unlocked != null) add(Snow.button(this@DrillActivity, "Next drill") {
                startActivity(Intent(this@DrillActivity, DrillActivity::class.java).putExtra("drill", unlocked.id)); finish()
            }, bottom = 10)
            add(Snow.button(this@DrillActivity, "Try again", if (attempt.eligible && result.passed && unlocked != null) Snow.ButtonKind.SECONDARY else Snow.ButtonKind.PRIMARY) { again() }, bottom = 10)
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
