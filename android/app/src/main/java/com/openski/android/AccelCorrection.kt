package com.openski.android


/**
 * Per-sensor accelerometer correction learned from the six-face bench check: true = (reading - offset) / scale,
 * per axis. It is applied only where orientation is computed. Recorded and exported samples stay raw.
 * Gyro bias is handled separately by the neutral-stance calibration.
 */
data class AccelCorrection(val offset: Vector3, val scale: Vector3) {
    fun apply(v: Vector3) = Vector3((v.x - offset.x) / scale.x, (v.y - offset.y) / scale.y, (v.z - offset.z) / scale.z)

    fun apply(s: SensorSample) = s.copy(
        accelX = ((s.accelX - offset.x) / scale.x).toFloat(),
        accelY = ((s.accelY - offset.y) / scale.y).toFloat(),
        accelZ = ((s.accelZ - offset.z) / scale.z).toFloat())

    fun apply(p: TimelinePoint) = p.copy(sample = apply(p.sample))

    /** Six comma-separated numbers: offset x,y,z then scale x,y,z. */
    fun toStorage() = listOf(offset.x, offset.y, offset.z, scale.x, scale.y, scale.z).joinToString(",")

    companion object {
        /** Needs a check for every axis, and scale factors that are plausibly close to 1. */
        fun fromAxisChecks(checks: List<AxisCheck>): AccelCorrection? {
            val byAxis = checks.associateBy { it.axis }
            if (byAxis.keys != setOf(0, 1, 2)) return null
            fun vector(value: (AxisCheck) -> Double) = Vector3(value(byAxis[0]!!), value(byAxis[1]!!), value(byAxis[2]!!))
            val scale = vector { 1 + it.scalePercent / 100 }
            if (listOf(scale.x, scale.y, scale.z).any { it !in 0.9..1.1 }) return null
            return AccelCorrection(vector { it.offset }, scale)
        }

        fun fromStorage(text: String): AccelCorrection? {
            val n = text.split(",").mapNotNull { it.trim().toDoubleOrNull() }
            if (n.size != 6 || n.drop(3).any { it !in 0.9..1.1 }) return null
            return AccelCorrection(Vector3(n[0], n[1], n[2]), Vector3(n[3], n[4], n[5]))
        }
    }
}

/** Corrects each side's points using [lookup], leaving sides with no correction untouched. */
fun List<TimelinePoint>.corrected(lookup: (String) -> AccelCorrection?): List<TimelinePoint> {
    val bySide = mapOf("L" to lookup("L"), "R" to lookup("R"))
    if (bySide.values.all { it == null }) return this
    return map { point -> bySide[point.side]?.apply(point) ?: point }
}
