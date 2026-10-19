package com.openski.android

/** Pause closes the detector, retaining completed excursions but discarding an unfinished one. */
class SegmentedDrillRecorder(private val drill: Drill, private val side: String) {
    private val completed = mutableListOf<PracticeSegment>()
    private val traces = mutableListOf<Pair<Double, RollTrace>>()
    private val evidenceIssues = linkedSetOf<String>()
    private var current: LiveDrillRecorder? = null
    private var start: Double? = null
    private var origin: Double? = null
    private var latest: BootRoll? = null
    private var acceptedThrough = Double.NEGATIVE_INFINITY

    fun accept(roll: BootRoll) {
        if (roll.side != side) return
        if (!roll.timeMs.isFinite() || !roll.degrees.isFinite() || roll.timeMs <= acceptedThrough) {
            if (current != null) evidenceIssues.add("Invalid or non-increasing roll evidence occurred during scoring")
            return
        }
        acceptedThrough = roll.timeMs
        latest = roll
        current?.accept(roll)
    }

    fun neutral(nowMs: Double): Boolean = latest?.let {
        nowMs - it.timeMs in 0.0..1000.0 && kotlin.math.abs(it.degrees) < 3
    } == true

    fun begin(nowMs: Double): Boolean {
        if (current != null || complete() || !neutral(nowMs)) return false
        require(completed.lastOrNull()?.let { nowMs >= it.endedAtMs } != false)
        if (origin == null) origin = nowMs
        current = LiveDrillRecorder(drill.copy(movements = drill.movements - count()), side).also { it.begin(nowMs) }
        start = nowMs
        return true
    }

    fun pause(nowMs: Double): Boolean {
        require(nowMs.isFinite())
        val recorder = current ?: run {
            latest = null; acceptedThrough = maxOf(acceptedThrough, nowMs); return false
        }
        val began = start!!
        val ended = maxOf(nowMs, began, acceptedThrough)
        acceptedThrough = ended
        val run = recorder.run()
        evidenceIssues += run.evidenceIssues
        completed += PracticeSegment(began, ended, run.detection.movements.filter { it.endMs <= ended })
        traces += began to run.trace
        current = null; start = null
        // A new sample is required before resume; a stale pre-pause neutral cannot restart capture.
        latest = null
        return true
    }

    fun segments(nowMs: Double): List<PracticeSegment> = completed.toList() +
        (current?.let { listOf(PracticeSegment(start!!, maxOf(nowMs, start!!, acceptedThrough), it.run().detection.movements)) } ?: emptyList())

    fun run(nowMs: Double, segmented: Boolean): DrillRun {
        val intervals = segments(nowMs)
        val movements = intervals.flatMap { it.movements }.take(drill.movements)
        return DrillRun(DryDetection(movements, movements.map { it.endMs }), trace(), false,
            evidenceIssues + current?.run()?.evidenceIssues.orEmpty(), intervals,
            if (segmented) PracticeTiming.POLICY else "continuous_v1")
    }

    fun count(): Int = completed.sumOf { it.movements.size } + (current?.count() ?: 0)
    fun complete() = count() >= drill.movements
    fun trace(): RollTrace {
        val base = origin ?: return RollTrace(floatArrayOf(), floatArrayOf())
        val parts = traces + (current?.let { listOf(start!! to it.trace()) } ?: emptyList())
        return RollTrace(parts.flatMap { (began, trace) -> trace.seconds.map { it + ((began - base) / 1000).toFloat() } }.toFloatArray(),
            parts.flatMap { (_, trace) -> trace.degrees.toList() }.toFloatArray())
    }
}
