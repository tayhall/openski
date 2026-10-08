package com.openski.android

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One completed tilt excursion from the firmware's experimental `tilt_v1` recogniser
 * (docs/ble-protocol.md). Angles are sensor-frame estimates, not validated boot or ski angles.
 */
data class MovementEvent(val axis: Int, val sequence: Int, val startMs: Long, val durationMs: Int,
    val peakTiltDegrees: Float, val aboutAxisDegrees: Float, val axisFraction: Float) : MotionFrame {
    val axisName get() = "xyz"[axis]
    companion object {
        const val SIZE = 15

        fun decode(bytes: ByteArray): MovementEvent? {
            if (bytes.size != SIZE || bytes[0].toInt() != 1) return null
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            b.get()
            val axis = b.get().toInt() and 255
            val sequence = b.short.toInt() and 0xffff
            val start = b.int.toLong() and 0xffff_ffffL
            val duration = b.short.toInt() and 0xffff
            val peak = b.short / 100f
            val about = b.short / 100f
            val fraction = (b.get().toInt() and 255) / 100f
            if (axis > 2 || peak < 0 || fraction > 1f) return null
            return MovementEvent(axis, sequence, start, duration, peak, about, fraction)
        }
    }
}
