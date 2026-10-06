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
}
