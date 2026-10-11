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
    /** Signal-quality bits: 1 accelerometer near rail, 2 gyro near rail, 4 timestamp gap, 8 timestamp wrap
     *  (live frame v2); 16 one or more earlier samples were dropped just before this flash record. */
    val quality: Int = 0,
) {
    companion object {
        const val FRAME_SIZE = 19
        const val FRAME_SIZE_V2 = 20
        const val QUALITY_MASK = 0x0f

        fun decode(bytes: ByteArray): SensorSample? {
            val version = if (bytes.isNotEmpty()) bytes[0].toInt() else return null
            val quality = when {
                bytes.size == FRAME_SIZE && version == 1 -> 0
                bytes.size == FRAME_SIZE_V2 && version == 2 -> bytes[19].toInt() and QUALITY_MASK
                else -> return null
            }
            val frame = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            frame.get() // protocol version
            val sequence = frame.short.toInt() and 0xffff
            val timestamp = frame.int.toLong() and 0xffff_ffffL
            return SensorSample(
                sequence, timestamp,
                frame.short / 100f, frame.short / 100f, frame.short / 100f,
                frame.short / 1000f, frame.short / 1000f, frame.short / 1000f,
                quality,
            )
        }
    }
}
