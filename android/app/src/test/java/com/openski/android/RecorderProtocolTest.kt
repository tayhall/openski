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
        assertEquals(true, transfer.withFlags)
        assertEquals(3, transfer.recordFormat)
    }
    @Test fun transferExposesLegacyFormatAndDefaultsToTwo() {
        val transfer = TransferValidator(2)
        assertNull(transfer.withFlags)
        assertEquals(2, transfer.recordFormat)
        assertTrue(transfer.accept(FlashChunk(0, listOf(record(), record()), withFlags = false)))
        assertEquals(false, transfer.withFlags)
        assertEquals(2, transfer.recordFormat)
    }

    private fun pong(token: Int = 0x1234, sensorUs: Long = 0x0000_0102_0304_0506L, result: Int = 0) =
        ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).put(3).put(9).put(result.toByte()).put(0)
            .putShort(token.toShort()).putLong(sensorUs).putShort(0).array()

    @Test fun encodesTheTimeSyncRequest() {
        assertArrayEquals(byteArrayOf(9, 0x34, 0x12), RecorderCommand.timeSync(0x1234))
        assertArrayEquals(byteArrayOf(9, 0xff.toByte(), 0xff.toByte()), RecorderCommand.timeSync(0xffff))
        assertArrayEquals(byteArrayOf(9, 0, 0), RecorderCommand.timeSync(0))
    }
    @Test fun decodesATimeSyncReplyWithA64BitClock() {
        val reply = ClockPong.decode(pong())!!
        assertTrue(reply.ok)
        assertEquals(0x1234, reply.token)
        assertEquals(0x0000_0102_0304_0506L, reply.sensorUs)
        // Beyond 2^32 microseconds (71.6 minutes), where a 32-bit stamp would have wrapped.
        assertEquals(5_000_000_000L, ClockPong.decode(pong(sensorUs = 5_000_000_000L))!!.sensorUs)
        assertEquals(0xffff, ClockPong.decode(pong(token = 0xffff))!!.token)
    }
    @Test fun aTimeSyncReplyWithAnotherTokenOrVersionIsRejected() {
        assertNotNull(ClockPong.decode(pong(), expectedToken = 0x1234))
        assertNull(ClockPong.decode(pong(), expectedToken = 0x1235))
        assertNull(ClockPong.decode(pong().also { it[0] = 2 }))
        assertNull(ClockPong.decode(pong().also { it[1] = 3 }))
        assertNull(ClockPong.decode(pong().copyOf(15)))
        assertNull(ClockPong.decode(pong().copyOf(17)))
        assertNull(ClockPong.decode(pong(sensorUs = -1)))
    }
    @Test fun resultSixMeansTheFirmwareHasNoTimeSync() {
        val reply = ClockPong.decode(pong(sensorUs = 0, result = 6))!!
        assertTrue(reply.unsupported)
        assertFalse(reply.ok)
        assertEquals(0x1234, reply.token)
    }
    @Test fun anyFailureResultToATimeSyncRequestIsNotOkWhateverTheTokenBytesHold() {
        // Old firmware answers opcode 09 with an ordinary recorder status: result 6 (invalid command) or 1 (flash
        // not ready), with bytes 4 to 5 holding a sample count, not our token.
        for (result in listOf(1, 6)) {
            val reply = ClockPong.decode(pong(token = 0x7f31, sensorUs = 123_456, result = result))!!
            assertFalse(reply.ok)
        }
    }
    @Test fun timeSyncRepliesAreNotMistakenForRecorderStatus() {
        assertTrue(ClockPong.isTimeSyncReply(pong()))
        assertFalse(ClockPong.isTimeSyncReply(ByteArray(16).also { it[0] = 3; it[1] = 0x85.toByte() }))
        assertFalse(ClockPong.isTimeSyncReply(ByteArray(1)))
    }
}
