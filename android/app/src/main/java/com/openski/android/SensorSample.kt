package com.openski.android

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class SensorSample(
    val sequence: Int,
    val timestampMs: Long,
    val accelX: Float,
    val accelY: Float,
    val accelZ: Float,
    val gyroX: Float,
    val gyroY: Float,
    val gyroZ: Float,
) {
    companion object {
        const val FRAME_SIZE = 19

        fun decode(bytes: ByteArray): SensorSample? {
            if (bytes.size != FRAME_SIZE || bytes[0].toInt() != 1) return null
            val frame = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            frame.get() // protocol version
            val sequence = frame.short.toInt() and 0xffff
            val timestamp = frame.int.toLong() and 0xffff_ffffL
            return SensorSample(
                sequence, timestamp,
                frame.short / 100f, frame.short / 100f, frame.short / 100f,
                frame.short / 1000f, frame.short / 1000f, frame.short / 1000f,
            )
        }
    }
}
