package com.openski.android

enum class DrillPhase { IDLE, PREPARING, CONNECTING, MOUNTING, STARTING, HOLDING, CALIBRATING, SETTLING, RUNNING, SAVING, SAVE_FAILED, DONE }

interface DrillEffects {
    fun prepare(attempt: Attempt, completed: (Result<Int>) -> Unit)
    fun openSession(attempt: Attempt, completed: (Result<Unit>) -> Unit)
    fun calibrate(attempt: Attempt, side: String, mounting: Mounting, completed: (Result<Unit>) -> Unit)
    fun mark(attempt: Attempt, label: String)
    fun stopSession(attempt: Attempt)
    fun save(attempt: Attempt, run: DrillRun?, completed: (Result<Attempt>) -> Unit)
    /** A set whose result could not be saved was given up; its ACTIVE row may be closed as interrupted. */
    fun abandon(attempt: Attempt) {}
}

data class DrillState(val phase: DrillPhase, val drill: Drill?, val attempt: Attempt?, val side: String?,
    val message: String, val count: Int, val seconds: Double, val trace: RollTrace, val animation: Float,
    val run: DrillRun?, val result: DrillResult?, val ghost: RollTrace? = null)

/** Main-thread state machine. Screens subscribe; detaching never issues a command. Deadlines are monotonic. */
class DrillController(private val effects: DrillEffects, private val now: () -> Long, private val wall: () -> Long) {
    var phase = DrillPhase.IDLE; private set
    private var drill: Drill? = null
    private var attempt: Attempt? = null
    private var side: String? = null
    private var mounting: Mounting? = null
    private var message = ""
    private var deadline = 0L
    private var started = 0L
    private var recorder: LiveDrillRecorder? = null
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
        side = null; mounting = null; recorder = null; demoRun = null; finishedRun = null; pendingResult = null
        phase = DrillPhase.PREPARING; message = "Preparing practice…"
        effects.prepare(attempt!!) { result ->
            if (!matches(id, DrillPhase.PREPARING)) return@prepare
            result.onSuccess { before ->
                if (demo) {
                    demoRun = DemoDrill.simulate(drill, before)
                    started = now(); phase = DrillPhase.RUNNING; message = "Demo boots · simulated"
                } else { phase = DrillPhase.CONNECTING; message = "Waiting for a live boot stream" }
            }.onFailure {
                // Nothing was recorded or written yet, so a refusal leaves no attempt behind.
                attempt = null; phase = DrillPhase.IDLE
                message = it.message ?: "Could not prepare practice. Try again later."
            }
        }
        return null
    }

    /** [live] means a fresh stream, not just a saved pairing or connected GATT link. */
    fun tick(live: Set<String>, mountings: Map<String, Mounting?>, recordingId: String?) {
        val a = attempt ?: return
        if (phase == DrillPhase.CONNECTING) {
            side = listOf("L", "R").firstOrNull { it in live } ?: return
            recorder = LiveDrillRecorder(drill!!, side!!)
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
        if (!a.demo && phase in listOf(DrillPhase.HOLDING, DrillPhase.CALIBRATING, DrillPhase.SETTLING, DrillPhase.RUNNING) && recordingId != a.sessionId) {
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
            DrillPhase.RUNNING -> {
                if (a.demo) {
                    if (now() - started >= DEMO_MS) finish(PracticeStatus.COMPLETED, "Demo finished", demoRun)
                } else {
                    message = if (side in live) "Roll on the chosen beat" else "Boot stream unavailable · waiting to reconnect"
                    if (now() - started > (drill!!.movements * drill!!.paceSeconds * 3 + 20) * 1000)
                        finish(PracticeStatus.INCOMPLETE, "Practice timed out", recorder?.run())
                }
            }
            else -> Unit
        }
    }

    fun accept(roll: BootRoll) {
        if (phase !in listOf(DrillPhase.SETTLING, DrillPhase.RUNNING) || attempt?.demo != false) return
        val r = recorder ?: return
        r.accept(roll)
        if (phase == DrillPhase.SETTLING && r.neutral(wall().toDouble())) {
            started = now(); r.begin(wall().toDouble()); phase = DrillPhase.RUNNING
            effects.mark(attempt!!, "START_TEST")
        }
        if (phase == DrillPhase.RUNNING && r.complete()) finish(PracticeStatus.COMPLETED, "Set completed", r.run())
    }

    fun finishEarly() {
        if (phase == DrillPhase.SAVE_FAILED) { abandonSave(); return }
        if (!active || phase == DrillPhase.SAVING) return
        finish(PracticeStatus.INCOMPLETE, "Set ended early", if (attempt?.demo == true) null else recorder?.run())
    }

    fun interrupt(reason: String) {
        if (phase == DrillPhase.SAVE_FAILED) abandonSave()
        else if (active && phase != DrillPhase.SAVING)
            finish(PracticeStatus.INTERRUPTED, reason, if (attempt?.demo == true) null else recorder?.run())
    }

    private fun finish(status: PracticeStatus, reason: String, run: DrillRun? = null) {
        val a = attempt ?: return
        phase = DrillPhase.SAVING; message = reason; finishedRun = run
        val result = run?.let { DrillScoring.score(drill!!, it.detection) }
        pendingResult = a.copy(status = if (status == PracticeStatus.COMPLETED && (result?.completed ?: 0) < drill!!.movements)
            PracticeStatus.INCOMPLETE else status, score = result?.score ?: 0, stars = result?.stars ?: 0)
        pendingResult = pendingResult!!.let { it.copy(review = PracticeReview.capture(it, run, side, mounting)) }
        effects.mark(a, "PAUSE")
        effects.stopSession(a)
        persist()
    }

    fun retrySave() { if (phase == DrillPhase.SAVE_FAILED) { phase = DrillPhase.SAVING; persist() } }

    /** Gives up on a result that cannot be saved, so a storage failure cannot hold the service active forever. */
    private fun abandonSave() {
        val pending = pendingResult ?: return
        attempt = pending.copy(status = PracticeStatus.INTERRUPTED, stars = 0, vert = 0)
        pendingResult = null; phase = DrillPhase.DONE
        message = "Practice could not be saved and was closed as interrupted. The raw recording, if any, has been kept."
        effects.abandon(pending)
    }

    /** The attempt and evidence the set will be judged on if it completes, while it is still in progress. */
    fun prospective(): Pair<Attempt, PracticeEvidence>? {
        val a = attempt?.takeIf { it.status == PracticeStatus.ACTIVE } ?: return null
        return a.copy(status = PracticeStatus.COMPLETED) to PracticeEvidence(if (a.demo) null else side, if (a.demo) null else mounting,
            ScoringEvidenceQuality.SUFFICIENT, if (a.demo) ScoringEvidenceSource.SIMULATED else ScoringEvidenceSource.LIVE_ROLL,
            timingPolicy = "continuous_v1")
    }

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
                it.endMs <= (now() - started).coerceAtLeast(0) / DEMO_MS.toDouble() * (demoRun!!.trace.duration * 1000)
            } ?: 0 else recorder?.count() ?: 0,
            if (phase == DrillPhase.RUNNING) (now() - started).coerceAtLeast(0) / 1000.0 else 0.0,
            trace ?: RollTrace(floatArrayOf(), floatArrayOf()),
            if (demo && phase == DrillPhase.RUNNING) ((now() - started) / DEMO_MS.toFloat()).coerceIn(0f, 1f) else 1f,
            finishedRun, finishedRun?.let { DrillScoring.score(drill!!, it.detection) })
    }

    companion object { const val DEMO_MS = 6500L }
}
