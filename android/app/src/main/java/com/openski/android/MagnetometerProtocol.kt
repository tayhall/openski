package com.openski.android

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One raw LIS3MDL magnetometer reading from the movement characteristic (frame version 5, 16 bytes, about 10 Hz,
 * only from a boot that has the magnetometer). Counts are uncalibrated: no hard-iron or soft-iron correction, and
 * a boot-mounted sensor sits next to buckles and batteries. Scale is 6842 counts per gauss at the +-4 gauss range.
 *
 * Layout (little-endian): [0] 5; [1] flags (bit 0 raw value near range limit, bit 1 samples missed);
 * [2..3] sequence; [4..7] time in milliseconds on the live frame's clock; [8..13] int16 X, Y, Z; [14..15] reserved.
 * Reserved bits and bytes are ignored rather than rejected, so a later firmware may use them.
 */
data class MagnetometerFrame(val sequence: Int, val timeMs: Long, val x: Int, val y: Int, val z: Int,
    val nearRange: Boolean, val missedSamples: Boolean) : MotionFrame {
    val flags get() = (if (nearRange) 1 else 0) or (if (missedSamples) 2 else 0)
    val gaussX get() = x / COUNTS_PER_GAUSS
    val gaussY get() = y / COUNTS_PER_GAUSS
    val gaussZ get() = z / COUNTS_PER_GAUSS
    companion object {
        const val SIZE = 16
        const val VERSION = 5
        const val COUNTS_PER_GAUSS = 6842.0

        fun decode(bytes: ByteArray): MagnetometerFrame? {
            if (bytes.size != SIZE || bytes[0].toInt() != VERSION) return null
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            b.get()
            val flags = b.get().toInt() and 255
            val sequence = b.short.toInt() and 0xffff
            val time = b.int.toLong() and 0xffff_ffffL
            return MagnetometerFrame(sequence, time, b.short.toInt(), b.short.toInt(), b.short.toInt(),
                flags and 1 != 0, flags and 2 != 0)
        }
    }
}
