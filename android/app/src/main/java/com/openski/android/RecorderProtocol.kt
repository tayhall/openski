package com.openski.android

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class RecorderStatus(val opcode: Int, val result: Int, val flags: Int,
    val samples: Int, val dropped: Int, val capacity: Int) {
    val storageReady get() = flags and 1 != 0
    val recording get() = flags and 2 != 0
    val hasSession get() = flags and 4 != 0
    val full get() = flags and 8 != 0
    val storageError get() = flags and 16 != 0
    companion object {
        fun decode(bytes: ByteArray): RecorderStatus? {
            if (bytes.size != 16 || bytes[0].toInt() != 2) return null
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            b.get()
            val opcode = b.get().toInt() and 255
            val result = b.get().toInt() and 255
            val flags = b.get().toInt() and 255
            val samples = b.int
            val dropped = b.int
            val capacity = b.int
            if (samples < 0 || dropped < 0 || capacity < 0 || samples > capacity) return null
            return RecorderStatus(opcode, result, flags, samples, dropped, capacity)
        }
    }
}

data class FlashRecord(val timestampUs: Long, val sample: SensorSample, val raw: ByteArray) {
    companion object {
        fun decode(raw: ByteArray): FlashRecord? {
            if (raw.size != 16) return null
            val b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
            val us = b.int.toLong() and 0xffff_ffffL
            return FlashRecord(us, SensorSample(-1, us / 1000,
                b.short / 100f, b.short / 100f, b.short / 100f,
                b.short / 1000f, b.short / 1000f, b.short / 1000f), raw.copyOf())
        }
    }
}

data class FlashChunk(val firstIndex: Int, val records: List<FlashRecord>) {
    companion object {
        fun decode(bytes: ByteArray): FlashChunk? {
            if (bytes.size < 20 || (bytes.size - 4) % 16 != 0 || (bytes[0].toInt() and 255) != 0xd0) return null
            val index = (bytes[1].toInt() and 255) or ((bytes[2].toInt() and 255) shl 8) or
                ((bytes[3].toInt() and 255) shl 16)
            return FlashChunk(index, (4 until bytes.size step 16).map {
                FlashRecord.decode(bytes.copyOfRange(it, it + 16)) ?: return null
            })
        }
    }
}

/** A gap, duplicate, or overrun must never qualify a transfer for erasure. */
class TransferValidator(val expected: Int) {
    var next = 0
        private set
    fun accept(chunk: FlashChunk): Boolean {
        if (chunk.firstIndex != next || chunk.records.isEmpty() || chunk.records.size > expected - next) return false
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
    fun bytes(opcode: Int, offset: Int = 0): ByteArray = if (opcode == DOWNLOAD) {
        ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN).put(opcode.toByte()).putInt(offset).array()
    } else byteArrayOf(opcode.toByte())
}
