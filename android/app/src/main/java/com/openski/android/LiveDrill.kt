package com.openski.android

/**
 * Collects one boot's live roll for a drill. It uses the same movement detector as the sensor service and
 * session replay, so a live set is scored exactly like a recorded one. Called from the BLE thread, read from the UI.
 */
class LiveDrillRecorder(val drill: Drill, val side: String) {
    private val detector = DrySkiAnalysis.Tracker()
    private val seconds = ArrayList<Float>()
    private val degrees = ArrayList<Float>()
    private val movements = mutableListOf<DryMovement>()
    private var startMs: Double? = null
    private var lastRollMs = 0.0
    private var lastDegrees = 0.0
    private var latestDegrees: Double? = null

    /** Latest roll for the boot, running or not. Used to wait for a neutral stance before the set starts. */
    @Synchronized fun neutral(nowMs: Double, maxAgeMs: Double = 1000.0): Boolean =
        latestDegrees?.let { kotlin.math.abs(it) < 3 && nowMs - lastRollMs < maxAgeMs } == true

    @Synchronized fun begin(timeMs: Double) { startMs = timeMs }
    @Synchronized fun started() = startMs != null
    @Synchronized fun count() = movements.size
    @Synchronized fun complete() = movements.size >= drill.movements
    @Synchronized fun elapsedSeconds(nowMs: Double) = startMs?.let { (nowMs - it) / 1000 } ?: 0.0
    @Synchronized fun current() = lastDegrees

    /** Returns true when this roll completes a movement. */
    @Synchronized fun accept(roll: BootRoll): Boolean {
        if (roll.side != side) return false
        latestDegrees = roll.degrees; lastRollMs = roll.timeMs; lastDegrees = roll.degrees
        val start = startMs ?: return false
        if (roll.timeMs < start) return false
        seconds.add(((roll.timeMs - start) / 1000).toFloat()); degrees.add(roll.degrees.toFloat())
        val movement = detector.accept(roll) ?: return false
        if (movements.size >= drill.movements) return false
        movements.add(movement)
        return true
    }

    @Synchronized fun trace() = RollTrace(seconds.toFloatArray(), degrees.toFloatArray())
    @Synchronized fun run() = DrillRun(DryDetection(movements.toList(), movements.map { it.endMs }), trace(), demo = false)
}

/** A sensor-to-boot mounting choice: which signed sensor axis points toward the toe. */
data class Mounting(val axis: Int, val sign: Int) {
    val label get() = "${if (sign > 0) "+" else "−"}${"XYZ"[axis]} toward the toe"
    companion object {
        val options = (0..2).flatMap { axis -> listOf(1, -1).map { Mounting(axis, it) } }
    }
}
