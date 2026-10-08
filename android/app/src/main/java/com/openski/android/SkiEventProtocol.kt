package com.openski.android

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Frames on the firmware's movement characteristic share one UUID and differ by their first byte. */
sealed interface MotionFrame

object MotionFrames {
    /** Version 1 is the `tilt_v1` excursion, 2 a `ski_v0` half-turn, 3 the `ski_v0` state frame. */
    fun decode(bytes: ByteArray): MotionFrame? = when (bytes.firstOrNull()?.toInt()) {
        1 -> MovementEvent.decode(bytes)
        2 -> SkiEvent.decode(bytes)
        3 -> SkiState.decode(bytes)
        else -> null
    }
}

/**
 * One completed half-turn from the firmware's experimental `ski_v0` recogniser (docs/ble-protocol.md,
 * frame version 2). Cuff lean in a sensor-derived frame, not a validated ski edge angle.
 * Positive roll means the leg leaned toward the sensor's lateral axis.
 */
data class SkiEvent(val sequence: Int, val startMs: Long, val durationMs: Int,
    val peakRollDegrees: Float, val peakRateDps: Float, val pitchDegrees: Float,
    val positive: Boolean, val outsideEnvelope: Boolean, val pitchOutside: Boolean,
    val skippedSamples: Boolean) : MotionFrame {
    companion object {
        const val SIZE = 18
        const val VERSION = 2

        fun decode(bytes: ByteArray): SkiEvent? {
            if (bytes.size != SIZE || bytes[0].toInt() != VERSION) return null
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            b.get()
            val flags = b.get().toInt() and 255
            val sequence = b.short.toInt() and 0xffff
            val start = b.int.toLong() and 0xffff_ffffL
            val duration = b.short.toInt() and 0xffff
            val roll = b.short / 100f
            val rate = (b.short.toInt() and 0xffff) / 10f
            val pitch = b.short / 100f
            val reserved = b.short.toInt()
            val positive = flags and 1 != 0
            if (flags and 0xf0 != 0 || reserved != 0 || roll == 0f || positive != (roll > 0f)) return null
            return SkiEvent(sequence, start, duration, roll, rate, pitch, positive,
                flags and 2 != 0, flags and 4 != 0, flags and 8 != 0)
        }
    }
}

/** The once-a-second `ski_v0` state frame (frame version 3), sent in both firmware modes. */
data class SkiState(val sequence: Int, val timeMs: Long, val rollDegrees: Float, val pitchDegrees: Float,
    val vibrationMps2: Float, val gyroDps: Float, val zeroed: Boolean, val production: Boolean,
    val gapInLastSecond: Boolean) : MotionFrame {
    companion object {
        const val SIZE = 16
        const val VERSION = 3

        fun decode(bytes: ByteArray): SkiState? {
            if (bytes.size != SIZE || bytes[0].toInt() != VERSION) return null
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            b.get()
            val flags = b.get().toInt() and 255
            val sequence = b.short.toInt() and 0xffff
            val time = b.int.toLong() and 0xffff_ffffL
            val roll = b.short / 100f
            val pitch = b.short / 100f
            val vibration = (b.short.toInt() and 0xffff) / 100f
            val gyro = (b.short.toInt() and 0xffff) / 10f
            if (flags and 0xf8 != 0 || Math.abs(roll) > 90f || Math.abs(pitch) > 180f) return null
            return SkiState(sequence, time, roll, pitch, vibration, gyro,
                flags and 1 != 0, flags and 2 != 0, flags and 4 != 0)
        }
    }
}
