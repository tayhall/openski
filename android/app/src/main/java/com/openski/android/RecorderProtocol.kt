package com.openski.android

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class RecorderStatus(val opcode: Int, val result: Int, val flags: Int,
    val samples: Int, val dropped: Int, val capacity: Int, val version: Int = 2) {
    val storageReady get() = flags and 1 != 0
    val recording get() = flags and 2 != 0
    val hasSession get() = flags and 4 != 0
    val full get() = flags and 8 != 0
    val storageError get() = flags and 16 != 0
    val production get() = flags and 64 != 0
    companion object {
        fun decode(bytes: ByteArray): RecorderStatus? {
            if (bytes.size != 16) return null
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val version = b.get().toInt() and 255
            if (version != 2 && version != 3) return null
            val opcode = b.get().toInt() and 255
            val result = b.get().toInt() and 255
            val flags = b.get().toInt() and 255
            val samples = b.int
            val dropped = b.int
            val capacity = b.int
            if (samples < 0 || dropped < 0 || capacity < 0 || samples > capacity) return null
            return RecorderStatus(opcode, result, flags, samples, dropped, capacity, version)
        }
    }
}

data class FlashRecord(val timestampUs: Long, val sample: SensorSample, val raw: ByteArray) {
    companion object {
        /** [withFlags] is true only for version-3 records (marker 0xd1), whose low three timestamp bits are flags:
         *  bit 0 accelerometer near rail, bit 1 gyro near rail, bit 2 earlier samples dropped immediately before
         *  (mapped to quality 16). Legacy records must never be masked. */
        fun decode(raw: ByteArray, withFlags: Boolean = false): FlashRecord? {
            if (raw.size != 16) return null
            val b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
            val stored = b.int.toLong() and 0xffff_ffffL
            val flags = if (withFlags) (stored and 7L).toInt() else 0
            val us = if (withFlags) stored and 7L.inv() else stored
            val quality = (flags and 3) or (if (flags and 4 != 0) 16 else 0)
            return FlashRecord(us, SensorSample(-1, us / 1000,
                b.short / 100f, b.short / 100f, b.short / 100f,
                b.short / 1000f, b.short / 1000f, b.short / 1000f, quality), raw.copyOf())
        }
    }
}

data class FlashChunk(val firstIndex: Int, val records: List<FlashRecord>, val withFlags: Boolean = false) {
    companion object {
        fun decode(bytes: ByteArray): FlashChunk? {
            if (bytes.size < 20 || (bytes.size - 4) % 16 != 0) return null
            val marker = bytes[0].toInt() and 255
            if (marker != 0xd0 && marker != 0xd1) return null
            val withFlags = marker == 0xd1
            val index = (bytes[1].toInt() and 255) or ((bytes[2].toInt() and 255) shl 8) or
                ((bytes[3].toInt() and 255) shl 16)
            return FlashChunk(index, (4 until bytes.size step 16).map {
                FlashRecord.decode(bytes.copyOfRange(it, it + 16), withFlags) ?: return null
            }, withFlags)
        }
    }
}

/** A gap, duplicate, or overrun must never qualify a transfer for erasure. */
class TransferValidator(val expected: Int) {
    var next = 0
        private set
    private var withFlags: Boolean? = null
    fun accept(chunk: FlashChunk): Boolean {
        if (withFlags != null && withFlags != chunk.withFlags) return false
        if (chunk.firstIndex != next || chunk.records.isEmpty() || chunk.records.size > expected - next) return false
        withFlags = chunk.withFlags
        next += chunk.records.size
        return true
    }
    fun complete(status: RecorderStatus) = status.opcode == 0x85 && status.result == 0 &&
        !status.recording && !status.storageError && status.samples == expected && next == expected
}

object RecorderCommand {
    const val START = 1
    const val STOP = 2
    const val INFO = 3
    const val ERASE = 4
    const val DOWNLOAD = 5
    const val CANCEL = 6
    const val ZERO = 7
    const val SET_MODE = 8
    /** For DOWNLOAD [offset] is the first record; for SET_MODE it is the mode (0 diagnostics, 1 production). */
    fun bytes(opcode: Int, offset: Int = 0): ByteArray = when (opcode) {
        DOWNLOAD -> ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN).put(opcode.toByte()).putInt(offset).array()
        SET_MODE -> byteArrayOf(opcode.toByte(), offset.toByte())
        else -> byteArrayOf(opcode.toByte())
    }
}
