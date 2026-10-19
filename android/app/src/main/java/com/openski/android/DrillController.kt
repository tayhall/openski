package com.openski.android

enum class DrillPhase { IDLE, PREPARING, CONNECTING, MOUNTING, STARTING, HOLDING, CALIBRATING, SETTLING, COUNTDOWN, RUNNING, PAUSED, RESUMING, SAVING, SAVE_FAILED, DONE }

interface DrillEffects {
    fun prepare(attempt: Attempt, completed: (Result<Int>) -> Unit)
    fun openSession(attempt: Attempt, completed: (Result<Unit>) -> Unit)
    fun calibrate(attempt: Attempt, side: String, mounting: Mounting, completed: (Result<Unit>) -> Unit)
    fun mark(attempt: Attempt, label: String)
    fun stopSession(attempt: Attempt)
    fun save(attempt: Attempt, run: DrillRun?, completed: (Result<Attempt>) -> Unit)
}

data class DrillState(val phase: DrillPhase, val drill: Drill?, val attempt: Attempt?, val side: String?,
    val message: String, val count: Int, val seconds: Double, val trace: RollTrace, val animation: Float,
    val run: DrillRun?, val result: DrillResult?, val ghost: RollTrace? = null, val countdownSeconds: Int? = null)

/** Main-thread state machine. Screens subscribe; detaching never issues a command. Deadlines are monotonic. */
class DrillController(private val effects: DrillEffects, private val now: () -> Long, private val wall: () -> Long) {
    var phase = DrillPhase.IDLE; private set
    private var drill: Drill? = null
    private var attempt: Attempt? = null
    private var side: String? = null
    private var mounting: Mounting? = null
    private var message = ""
    private var deadline = 0L
    private var clock = PracticeClock()
    private var hasPaused = false
    private var recorder: SegmentedDrillRecorder? = null
    private var demoRun: DrillRun? = null
    private var finishedRun: DrillRun? = null
    private var pendingResult: Attempt? = null
    val active get() = phase !in listOf(DrillPhase.IDLE, DrillPhase.DONE)
    val id get() = attempt?.id
    val demo get() = attempt?.demo == true

    fun restoreInterrupted(previous: Attempt) {
        restoreSaved(previous.copy(status = PracticeStatus.INTERRUPTED, stars = 0, vert = 0))
    }

    fun restoreSaved(previous: Attempt) {
        check(!active)
        require(previous.status != PracticeStatus.ACTIVE)
        drill = runCatching { PracticeRepeat.drill(previous) }.getOrNull() ?: Programme.drill(previous.drillId)
        attempt = previous
        phase = DrillPhase.DONE
        message = when (previous.status) {
            PracticeStatus.COMPLETED -> "Saved practice result."
            PracticeStatus.INCOMPLETE -> "This incomplete set was saved without a completed reward."
            else -> "Practice was interrupted. Check the raw session or recovery where available; no completed reward was granted."
        }
    }

    fun start(drill: Drill, demo: Boolean, id: String, sessionId: String?): String? {
        if (active) return "A practice set is already active. Return to it or finish it first."
        this.drill = drill
        attempt = Attempt(drill.id, wall(), 0, 0, 0, demo, id, sessionId, PracticeStatus.ACTIVE, PracticeTarget.of(drill))
        clock = PracticeClock(); hasPaused = false
        side = null; mounting = null; recorder = null; demoRun = null; finishedRun = null; pendingResult = null
        phase = DrillPhase.PREPARING; message = "Preparing practice…"
        effects.prepare(attempt!!) { result ->
            if (!matches(id, DrillPhase.PREPARING)) return@prepare
            result.onSuccess { before ->
                if (demo) {
                    demoRun = DemoDrill.simulate(drill, before)
                    clock.resume(now()); phase = DrillPhase.RUNNING; message = "Demo boots · simulated"
                } else { phase = DrillPhase.CONNECTING; message = "Waiting for a live boot stream" }
            }.onFailure { finish(PracticeStatus.INTERRUPTED, it.message ?: "Could not prepare practice. Retry saving or reopen later.") }
        }
        return null
    }

    /** [live] means a fresh stream, not just a saved pairing or connected GATT link. */
    fun tick(live: Set<String>, mountings: Map<String, Mounting?>, recordingId: String?) {
        val a = attempt ?: return
        if (phase == DrillPhase.CONNECTING) {
            side = listOf("L", "R").firstOrNull { it in live } ?: return
            recorder = SegmentedDrillRecorder(drill!!, side!!)
            phase = DrillPhase.MOUNTING
        }
        if (phase == DrillPhase.MOUNTING) {
            if (side !in live) { side = null; phase = DrillPhase.CONNECTING; return }
            mounting = mountings[side] ?: run { message = "Choose how this sensor points toward the toe"; return }
            phase = DrillPhase.STARTING; message = "Opening practice recording…"
            effects.openSession(a) { result ->
                if (!matches(a.id, DrillPhase.STARTING)) return@openSession
                result.onSuccess { phase = DrillPhase.HOLDING; deadline = now() + 3200; message = "Hold still in neutral" }
                    .onFailure { finish(PracticeStatus.INTERRUPTED, it.message ?: "Could not open recording") }
            }
            return // A synchronous open callback may change the recording ID; observe it on the next tick.
        }
        if (!a.demo && phase in listOf(DrillPhase.HOLDING, DrillPhase.CALIBRATING, DrillPhase.SETTLING, DrillPhase.COUNTDOWN, DrillPhase.RUNNING, DrillPhase.PAUSED, DrillPhase.RESUMING) && recordingId != a.sessionId) {
            finish(PracticeStatus.INTERRUPTED, "Recording stopped. This practice is interrupted."); return
        }
        when (phase) {
            DrillPhase.HOLDING -> {
                if (side !in live) { deadline = now() + 3200; message = "Waiting for the boot stream; hold neutral when it returns" }
                else if (now() >= deadline) {
                    phase = DrillPhase.CALIBRATING; message = "Calibrating…"
                    effects.calibrate(a, side!!, mounting!!) { result ->
                        if (!matches(a.id, DrillPhase.CALIBRATING)) return@calibrate
                        result.onSuccess { phase = DrillPhase.SETTLING; message = "Return to neutral to begin" }
                            .onFailure { finish(PracticeStatus.INTERRUPTED, it.message ?: "Calibration failed") }
                    }
                } else message = "Hold still · ${((deadline - now() + 999) / 1000).coerceIn(0, 3)}"
            }
            DrillPhase.COUNTDOWN -> {
                if (side !in live || recorder?.neutral(wall().toDouble()) != true) {
                    phase = if (hasPaused) DrillPhase.RESUMING else DrillPhase.SETTLING
                    message = "Return to neutral for the count-in"
                } else if (now() >= deadline && recorder?.begin(wall().toDouble()) == true) {
                    clock.resume(now()); phase = DrillPhase.RUNNING; message = "Roll on the chosen beat"
                    effects.mark(a, "START_TEST")
                } else message = "Hold neutral · count-in ${((deadline - now() + 999) / 1000).coerceIn(0, 3)}"
            }
            DrillPhase.RUNNING -> {
                if (a.demo) {
                    if (clock.elapsedMs(now()) >= DEMO_MS) finish(PracticeStatus.COMPLETED, "Demo finished", demoRun)
                } else {
                    if (side !in live) { pause("Boot stream unavailable. Reconnect, then Resume from neutral."); return }
                    message = "Roll on the chosen beat"
                    if (clock.elapsedMs(now()) > (drill!!.movements * drill!!.paceSeconds * 3 + 20) * 1000)
                        finish(PracticeStatus.INCOMPLETE, "Practice timed out", currentRun())
                }
            }
            else -> Unit
        }
    }

    fun accept(roll: BootRoll) {
        if (phase !in listOf(DrillPhase.SETTLING, DrillPhase.RESUMING, DrillPhase.COUNTDOWN, DrillPhase.RUNNING) || attempt?.demo != false) return
        val r = recorder ?: return
        r.accept(roll)
        if (phase in listOf(DrillPhase.SETTLING, DrillPhase.RESUMING) && r.neutral(wall().toDouble())) {
            deadline = now() + 3000; phase = DrillPhase.COUNTDOWN; message = "Hold neutral · count-in 3"
        }
        if (phase == DrillPhase.RUNNING && r.complete()) finish(PracticeStatus.COMPLETED, "Set completed", currentRun())
    }

    fun pause(reason: String = "Practice paused"): Boolean {
        if (phase !in listOf(DrillPhase.RUNNING, DrillPhase.RESUMING, DrillPhase.COUNTDOWN)) return false
        clock.pause(now())
        if (!demo) recorder?.pause(wall().toDouble())
        hasPaused = true; phase = DrillPhase.PAUSED; message = reason
        effects.mark(attempt!!, "PAUSE")
        return true
    }

    fun resume(): Boolean {
        if (phase != DrillPhase.PAUSED) return false
        if (demo) { clock.resume(now()); phase = DrillPhase.RUNNING; message = "Demo boots · simulated"; effects.mark(attempt!!, "START_TEST") }
        else { phase = DrillPhase.RESUMING; message = "Return to neutral to resume" }
        return true
    }

    private fun currentRun() = recorder?.run(wall().toDouble(), hasPaused)

    fun finishEarly() {
        if (!active || phase in listOf(DrillPhase.SAVING, DrillPhase.SAVE_FAILED)) return
        finish(PracticeStatus.INCOMPLETE, "Set ended early", if (attempt?.demo == true) null else currentRun())
    }

    fun interrupt(reason: String) {
        if (active && phase !in listOf(DrillPhase.SAVING, DrillPhase.SAVE_FAILED))
            finish(PracticeStatus.INTERRUPTED, reason, if (attempt?.demo == true) null else currentRun())
    }

    private fun finish(status: PracticeStatus, reason: String, run: DrillRun? = null) {
        val a = attempt ?: return
        clock.pause(now())
        phase = DrillPhase.SAVING; message = reason; finishedRun = run
        val result = run?.let { DrillScoring.score(drill!!, it) }
        pendingResult = a.copy(status = if (status == PracticeStatus.COMPLETED && (result?.completed ?: 0) < drill!!.movements)
            PracticeStatus.INCOMPLETE else status, score = result?.score ?: 0, stars = result?.stars ?: 0)
        pendingResult = pendingResult!!.let { it.copy(review = PracticeReview.capture(it, run, side, mounting)) }
        effects.mark(a, "PAUSE")
        effects.stopSession(a)
        persist()
    }

    fun retrySave() { if (phase == DrillPhase.SAVE_FAILED) { phase = DrillPhase.SAVING; persist() } }

    private fun persist() {
        val pending = pendingResult ?: return
        effects.save(pending, finishedRun) { result ->
            if (!matches(pending.id, DrillPhase.SAVING)) return@save
            result.onSuccess { attempt = it; phase = DrillPhase.DONE }
                .onFailure { phase = DrillPhase.SAVE_FAILED; message = "Practice could not be saved. Retry save." }
        }
    }

    private fun matches(id: String, phase: DrillPhase) = attempt?.id == id && this.phase == phase

    fun state(): DrillState {
        val demo = attempt?.demo == true
        val trace = finishedRun?.trace ?: if (demo) demoRun?.trace else recorder?.trace()
        return DrillState(phase, drill, attempt, side, message,
            finishedRun?.detection?.movements?.size ?: if (demo) demoRun?.detection?.movements?.count {
                it.endMs <= (clock.elapsedMs(now())).coerceAtLeast(0) / DEMO_MS.toDouble() * (demoRun!!.trace.duration * 1000)
            } ?: 0 else recorder?.count() ?: 0,
            clock.elapsedMs(now()).coerceAtLeast(0) / 1000.0,
            trace ?: RollTrace(floatArrayOf(), floatArrayOf()),
            if (demo && phase in listOf(DrillPhase.RUNNING, DrillPhase.PAUSED)) ((clock.elapsedMs(now())) / DEMO_MS.toFloat()).coerceIn(0f, 1f) else 1f,
            finishedRun, finishedRun?.let { DrillScoring.score(drill!!, it) },
            countdownSeconds = if (phase == DrillPhase.COUNTDOWN) ((deadline - now() + 999) / 1000).toInt().coerceIn(0, 3) else null)
    }

    companion object { const val DEMO_MS = 6500L }
}
