package com.openski.android

enum class RunPhase { READY, ZEROING, RUNNING, SAVING, DONE }
enum class RunEnd { QUIET, MANUAL, ERROR }

/** What the run needs from the boots and the phone. The service implements it; tests fake it. Sides are "L" and "R". */
interface RunBoots {
    fun connected(side: String): Boolean
    /** True if the boot is in on-snow mode, false for training mode, null if not known yet. */
    fun productionMode(side: String): Boolean?
    fun zeroed(side: String): Boolean
    /** An earlier recording is still on the boot's flash. */
    fun flashRetained(side: String): Boolean
    /** The boot's flash filled up during this run. */
    fun flashFull(side: String): Boolean
    fun setProduction(side: String, production: Boolean)
    fun zero(side: String)
    /** Starts the phone session (which starts each boot's recorder) and coaching. False if it could not start. */
    fun beginSession(demo: Boolean): Boolean
    /** Stops the phone session and coaching; the existing recovery then saves the boots' flash. */
    fun endSession()
    /** Percent of this boot's flash downloaded, or null when nothing is downloading. */
    fun saveProgress(side: String): Int?
    /** Every boot's flash for this run is downloaded, checked and erased. */
    fun flashSaved(): Boolean
    fun flashSaveFailed(): Boolean
    fun earbudsReady(): Boolean
    fun storageOk(): Boolean
}

data class RunGap(val side: String, val startMs: Long, val endMs: Long)
data class RunStartInfo(
    val startedAtMs: Long, val demo: Boolean, val target: CoachTarget, val targetLabel: String,
    val windowSize: Int, val coachBoot: String, val modeBefore: Map<String, Boolean?>,
)
data class RunEndInfo(val endedAtMs: Long, val endedBy: RunEnd, val firstTurnMs: Long?, val lastTurnMs: Long?, val gaps: List<RunGap>)

/** Where a run's records go. The service writes them to the database as they arrive. */
interface RunSink {
    fun started(info: RunStartInfo)
    fun halfTurn(side: String, event: SkiEvent, receivedMs: Long)
    fun verdict(side: String, verdict: CoachVerdict, atMs: Long)
    fun ended(info: RunEndInfo)
}

data class Readiness(val ok: Boolean, val reason: String?)

data class RunSummary(
    val durationMs: Long, val endedBy: RunEnd, val turns: Map<String, Int>,
    val matched: Int, val offTarget: Int, val unclear: Int, val rawDataCapped: Boolean, val gapSeconds: Int,
)

data class RunState(
    val phase: RunPhase, val readiness: Readiness, val demo: Boolean, val message: String, val zeroTimedOut: Boolean,
    val elapsedMs: Long, val turns: Map<String, Int>, val lastVerdict: CoachVerdict?, val zeroed: Map<String, Boolean>,
    val saveProgress: Map<String, Int?>, val banner: String?, val summary: RunSummary?,
    val connected: Map<String, Boolean>, val earbuds: Boolean,
)

/**
 * The run lifecycle: Ready, Zeroing, Running, Saving, Done. Pure logic: time comes in as arguments and the boots,
 * phone and database are reached through [RunBoots] and [RunSink]. It compares dry-ski boot roll with a training
 * target and says nothing about on-snow technique.
 */
class RunController(private val boots: RunBoots, private val sink: RunSink, private val coach: () -> CoachSettings) {
    private var phase = RunPhase.READY
    private var demo = false
    private var message = ""
    private var zeroStartedAt = 0L
    private var zeroTimedOut = false
    private var runStartedAt = 0L
    private var endedAt = 0L
    private var savingStartedAt = 0L
    private var firstTurnAt: Long? = null
    private var lastTurnAt: Long? = null
    private var endedBy = RunEnd.MANUAL
    private var turns = mutableMapOf("L" to 0, "R" to 0)
    private var matched = 0
    private var offTarget = 0
    private var unclear = 0
    private var lastVerdict: CoachVerdict? = null
    private var modeBefore = mapOf<String, Boolean?>()
    private val changed = mutableSetOf<String>()
    private val gapStart = mutableMapOf<String, Long>()
    private val gaps = mutableListOf<RunGap>()
    private var summary: RunSummary? = null

    fun readiness(forDemo: Boolean = false): Readiness {
        if (!forDemo) {
            SIDES.firstOrNull { !boots.connected(it) }?.let { return Readiness(false, "Connect your ${name(it)} boot") }
            SIDES.firstOrNull { boots.flashRetained(it) }?.let {
                return Readiness(false, "The ${name(it)} boot is still holding your last run. Saving it first")
            }
        }
        if (!boots.earbudsReady()) return Readiness(false, "Connect earbuds, or allow the phone speaker in the coaching settings")
        if (!boots.storageOk()) return Readiness(false, "Phone storage is too low to record")
        return Readiness(true, null)
    }

    /** Flips both connected boots between training (false) and on-snow (true) mode. Only outside a run. */
    fun setOnSnowMode(production: Boolean) {
        if (phase != RunPhase.READY && phase != RunPhase.DONE) return
        SIDES.filter { boots.connected(it) }.forEach { boots.setProduction(it, production) }
    }

    /** Begins zeroing. Returns null if started, otherwise the reason it could not. */
    fun start(nowMs: Long, demoRun: Boolean = false): String? {
        if (phase != RunPhase.READY) return "A run is already in progress"
        val ready = readiness(demoRun)
        if (!ready.ok) return ready.reason
        demo = demoRun
        turns = mutableMapOf("L" to 0, "R" to 0)
        matched = 0; offTarget = 0; unclear = 0
        lastVerdict = null; firstTurnAt = null; lastTurnAt = null
        gaps.clear(); gapStart.clear(); changed.clear(); summary = null
        modeBefore = SIDES.associateWith { boots.productionMode(it) }
        if (!demo) {
            for (side in SIDES) {
                if (modeBefore[side] != true) { changed.add(side); boots.setProduction(side, true) }
                boots.zero(side)
            }
        }
        zeroStartedAt = nowMs
        zeroTimedOut = false
        phase = RunPhase.ZEROING
        message = "Stand upright. Hold still."
        return null
    }

    fun cancel() {
        if (phase != RunPhase.ZEROING) return
        restoreMode()
        phase = RunPhase.READY
        message = ""
    }

    fun retryZero(nowMs: Long) {
        if (phase != RunPhase.ZEROING || !zeroTimedOut) return
        SIDES.filterNot { boots.zeroed(it) }.forEach { boots.zero(it) }
        zeroStartedAt = nowMs
        zeroTimedOut = false
        message = "Stand upright. Hold still."
    }

    fun stop(nowMs: Long) {
        if (phase == RunPhase.RUNNING) end(nowMs, RunEnd.MANUAL)
    }

    /** Leaves the Done screen. */
    fun reset() {
        if (phase != RunPhase.DONE) return
        phase = RunPhase.READY
        message = ""
        summary = null
    }

    fun onHalfTurn(side: String, event: SkiEvent, nowMs: Long) {
        if (phase != RunPhase.RUNNING) return
        turns[side] = (turns[side] ?: 0) + 1
        if (firstTurnAt == null) firstTurnAt = nowMs
        lastTurnAt = nowMs
        sink.halfTurn(side, event, nowMs)
    }

    fun onVerdict(side: String, verdict: CoachVerdict, nowMs: Long) {
        if (phase != RunPhase.RUNNING) return
        lastVerdict = verdict
        when (verdict.verdict) {
            Verdict.POSITIVE -> matched++
            Verdict.NEGATIVE -> offTarget++
            Verdict.NONE -> unclear++
        }
        sink.verdict(side, verdict, nowMs)
    }

    fun onLink(side: String, connected: Boolean, nowMs: Long) {
        if (phase != RunPhase.RUNNING || demo) return
        if (!connected) gapStart.putIfAbsent(side, nowMs)
        else gapStart.remove(side)?.let { gaps.add(RunGap(side, it, nowMs)) }
    }

    /** Advances the time-based transitions. Call about twice a second. */
    fun tick(nowMs: Long) {
        when (phase) {
            RunPhase.ZEROING -> when {
                demo || SIDES.all { boots.zeroed(it) } -> beginRun(nowMs)
                !zeroTimedOut && nowMs - zeroStartedAt >= ZERO_TIMEOUT_MS -> {
                    zeroTimedOut = true
                    val waiting = SIDES.filterNot { boots.zeroed(it) }.joinToString(" and ") { name(it) }
                    message = "The $waiting boot has not zeroed. Hold still, or check it is connected."
                }
            }
            RunPhase.RUNNING -> {
                val last = lastTurnAt
                val limit = if (last == null) FIRST_TURN_MS else QUIET_MS
                if (nowMs - (last ?: runStartedAt) >= limit) end(nowMs, RunEnd.QUIET)
            }
            RunPhase.SAVING -> when {
                demo -> if (nowMs - savingStartedAt >= DEMO_SAVE_MS) finish(nowMs, saved = true)
                boots.flashSaveFailed() -> finish(nowMs, saved = false)
                boots.flashSaved() -> finish(nowMs, saved = true)
            }
            else -> Unit
        }
    }

    fun state(nowMs: Long): RunState {
        val elapsed = when (phase) {
            RunPhase.RUNNING -> nowMs - runStartedAt
            RunPhase.SAVING, RunPhase.DONE -> endedAt - runStartedAt
            else -> 0L
        }
        val progress = SIDES.associateWith { side ->
            if (phase != RunPhase.SAVING) null
            else if (demo) ((nowMs - savingStartedAt) * 100 / DEMO_SAVE_MS).toInt().coerceIn(0, 100)
            else boots.saveProgress(side)
        }
        val banner = if (phase == RunPhase.RUNNING && gapStart.isNotEmpty())
            "The ${gapStart.keys.sorted().joinToString(" and ") { name(it) }} boot lost its link. Reconnecting" else null
        return RunState(phase, readiness(), demo, message, zeroTimedOut, elapsed, turns.toMap(), lastVerdict,
            SIDES.associateWith { demo || boots.zeroed(it) }, progress, banner, summary,
            SIDES.associateWith { boots.connected(it) }, boots.earbudsReady())
    }

    private fun beginRun(nowMs: Long) {
        if (!boots.beginSession(demo)) {
            message = "Could not start recording. Check the boots and try again."
            restoreMode()
            phase = RunPhase.READY
            return
        }
        val settings = coach().normalised()
        sink.started(RunStartInfo(nowMs, demo, settings.target(), settings.targetLabel(), settings.windowSize,
            settings.coachBoot.name, modeBefore))
        runStartedAt = nowMs
        phase = RunPhase.RUNNING
        message = ""
    }

    private fun end(nowMs: Long, reason: RunEnd) {
        gapStart.toMap().forEach { (side, start) -> gaps.add(RunGap(side, start, nowMs)) }
        gapStart.clear()
        boots.endSession()
        endedAt = nowMs
        endedBy = reason
        savingStartedAt = nowMs
        sink.ended(RunEndInfo(nowMs, reason, firstTurnAt, lastTurnAt, gaps.toList()))
        phase = RunPhase.SAVING
        message = "Run ended. Saving boot data"
    }

    private fun finish(nowMs: Long, saved: Boolean) {
        if (saved) {
            restoreMode()
            message = ""
        } else {
            message = "Saving did not finish. The boots keep their data and stay in on-snow mode; open the logbook to retry."
        }
        summary = RunSummary(endedAt - runStartedAt, endedBy, turns.toMap(), matched, offTarget, unclear,
            !demo && SIDES.any { boots.flashFull(it) }, gaps.sumOf { it.endMs - it.startMs }.div(1000).toInt())
        phase = RunPhase.DONE
    }

    /** Puts back the mode of every boot this run switched to on-snow. */
    private fun restoreMode() {
        changed.forEach { boots.setProduction(it, false) }
        changed.clear()
    }

    private fun name(side: String) = if (side == "L") "left" else "right"

    companion object {
        val SIDES = listOf("L", "R")
        const val ZERO_TIMEOUT_MS = 15_000L
        /** No half-turn for this long after the last one ends the run. */
        const val QUIET_MS = 20_000L
        /** Before the first half-turn the skier is still getting going, so the limit is longer. */
        const val FIRST_TURN_MS = 120_000L
        const val DEMO_SAVE_MS = 6_000L
    }
}
