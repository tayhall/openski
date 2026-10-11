package com.openski.android

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RecorderProtocolTest {
    private fun record(us: Int = -1): FlashRecord = FlashRecord.decode(ByteBuffer.allocate(16)
        .order(ByteOrder.LITTLE_ENDIAN).putInt(us).putShort((-123).toShort()).putShort(200).putShort(981)
        .putShort((-500).toShort()).putShort(1000).putShort(0).array())!!
    private fun complete(count: Int, result: Int = 0, flags: Int = 5) = RecorderStatus(0x85,result,flags,count,0,60000)

    @Test fun decodesSignedAxesAndUnsignedClock() {
        val record=record()
        assertEquals(0xffff_ffffL,record.timestampUs)
        assertEquals(-1.23f,record.sample.accelX,0.0001f)
        assertEquals(-0.5f,record.sample.gyroX,0.0001f)
        assertNull(FlashRecord.decode(ByteArray(15)))
    }
    @Test fun parsesChunksAndRejectsMalformedPayloads() {
        val payload=byteArrayOf(0xd0.toByte(),2,1,0)+record().raw+record(20000).raw
        assertEquals(258,FlashChunk.decode(payload)!!.firstIndex)
        assertEquals(2,FlashChunk.decode(payload)!!.records.size)
        assertNull(FlashChunk.decode(payload.copyOf(21)))
        assertNull(FlashChunk.decode(payload.copyOf().also { it[0]=0 }))
    }
    @Test fun onlyContiguousCompleteTransfersCanBeErased() {
        val transfer=TransferValidator(3)
        assertTrue(transfer.accept(FlashChunk(0,listOf(record(),record()))))
        assertFalse(transfer.accept(FlashChunk(0,listOf(record()))))
        assertFalse(transfer.accept(FlashChunk(3,listOf(record()))))
        assertFalse(transfer.accept(FlashChunk(2,listOf(record(),record()))))
        assertFalse(transfer.complete(complete(3)))
        assertTrue(transfer.accept(FlashChunk(2,listOf(record()))))
        assertTrue(transfer.complete(complete(3)))
        assertFalse(transfer.complete(complete(2)))
        assertFalse(transfer.complete(complete(3,5)))
        assertFalse(transfer.complete(complete(3,flags=7)))
        assertFalse(transfer.complete(complete(3,flags=21)))
    }
    @Test fun validatesResponseShapeAndCounters() {
        val bytes=ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).put(2).put(3).put(0).put(5)
            .putInt(10).putInt(1).putInt(60000).array()
        assertEquals(10,RecorderStatus.decode(bytes)!!.samples)
        assertNull(RecorderStatus.decode(bytes.copyOf(15)))
        assertNull(RecorderStatus.decode(bytes.copyOf().also { it[0]=1 }))
        assertNull(RecorderStatus.decode(bytes.copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(4,-1) }))
    }
    @Test fun encodesDownloadOffsetLittleEndian() {
        assertArrayEquals(byteArrayOf(5,0x78,0x56,0x34,0x12),RecorderCommand.bytes(5,0x12345678))
    }

    @Test fun encodesTheMotionCommands() {
        assertArrayEquals(byteArrayOf(7), RecorderCommand.bytes(RecorderCommand.ZERO))
        assertArrayEquals(byteArrayOf(8, 0), RecorderCommand.bytes(RecorderCommand.SET_MODE, 0))
        assertArrayEquals(byteArrayOf(8, 1), RecorderCommand.bytes(RecorderCommand.SET_MODE, 1))
        assertArrayEquals(byteArrayOf(5, 4, 0, 0, 0), RecorderCommand.bytes(RecorderCommand.DOWNLOAD, 4))
        assertArrayEquals(byteArrayOf(3), RecorderCommand.bytes(RecorderCommand.INFO))
    }

    @Test fun productionIsFlagBitSix() {
        assertTrue(RecorderStatus(8, 0, 0x40, 0, 0, 60000).production)
        assertFalse(RecorderStatus(8, 0, 0x3f, 0, 0, 60000).production)
    }
    private fun rawRecord(us: Int): ByteArray = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putInt(us)
        .putShort(100).putShort(200).putShort(981).putShort(10).putShort(20).putShort(30).array()

    @Test fun flagsAreMaskedOnlyForVersionThreeRecords() {
        val raw = rawRecord(0x0000_0105)
        val flagged = FlashRecord.decode(raw, withFlags = true)!!
        assertEquals(0x100L, flagged.timestampUs)
        assertEquals(17, flagged.sample.quality)
        assertEquals(0x100L / 1000, flagged.sample.timestampMs)
        assertArrayEquals(raw, flagged.raw)
        val legacy = FlashRecord.decode(raw, withFlags = false)!!
        assertEquals(0x105L, legacy.timestampUs)
        assertEquals(0, legacy.sample.quality)
        assertEquals(0x105L, FlashRecord.decode(raw)!!.timestampUs)
        assertEquals(2, FlashRecord.decode(rawRecord(0x0000_0102), true)!!.sample.quality)
    }
    @Test fun chunkMarkerSelectsRecordFormat() {
        val body = byteArrayOf(0, 0, 0) + rawRecord(0x105)
        assertTrue(FlashChunk.decode(byteArrayOf(0xd1.toByte()) + body)!!.withFlags)
        assertFalse(FlashChunk.decode(byteArrayOf(0xd0.toByte()) + body)!!.withFlags)
        assertNull(FlashChunk.decode(byteArrayOf(0xd2.toByte()) + body))
        val flagged = FlashChunk.decode(byteArrayOf(0xd1.toByte()) + body)!!
        assertEquals(17, flagged.records.single().sample.quality)
        assertEquals(0x105L, FlashChunk.decode(byteArrayOf(0xd0.toByte()) + body)!!.records.single().timestampUs)
    }
    @Test fun recorderStatusVersions() {
        fun status(version: Int) = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).put(version.toByte()).put(3).put(0).put(5)
            .putInt(10).putInt(1).putInt(60000).array()
        assertEquals(2, RecorderStatus.decode(status(2))!!.version)
        assertEquals(3, RecorderStatus.decode(status(3))!!.version)
        assertNull(RecorderStatus.decode(status(1)))
        assertNull(RecorderStatus.decode(status(4)))
    }
    @Test fun liveFrameVersionsAndQuality() {
        fun frame(size: Int, version: Int, quality: Int = 0): ByteArray {
            val b = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).put(version.toByte()).putShort(7).putInt(1234)
                .putShort(100).putShort(-200).putShort(981).putShort(10).putShort(20).putShort(30)
            if (size == 20) b.put(quality.toByte())
            return b.array()
        }
        val v1 = SensorSample.decode(frame(19, 1))!!
        assertEquals(0, v1.quality)
        assertEquals(1234L, v1.timestampMs)
        assertEquals(0x0a, SensorSample.decode(frame(20, 2, 0x0a))!!.quality)
        assertEquals(0x0f, SensorSample.decode(frame(20, 2, 0xff))!!.quality)
        assertNull(SensorSample.decode(frame(20, 1)))
        assertNull(SensorSample.decode(frame(19, 2)))
        assertNull(SensorSample.decode(frame(20, 3)))
    }
    @Test fun transferRejectsMixedRecordFormats() {
        val transfer = TransferValidator(4)
        assertTrue(transfer.accept(FlashChunk(0, listOf(record(), record()), withFlags = true)))
        assertFalse(transfer.accept(FlashChunk(2, listOf(record()), withFlags = false)))
        assertTrue(transfer.accept(FlashChunk(2, listOf(record()), withFlags = true)))
        assertEquals(3, transfer.next)
    }
}
