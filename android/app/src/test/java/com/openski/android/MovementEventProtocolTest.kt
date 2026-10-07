package com.openski.android

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MovementEventProtocolTest {
    private fun frame(version: Int = 1, axis: Int = 1, sequence: Int = 513, startMs: Long = 0xffff_fff0L,
        duration: Int = 1830, peak: Int = 4490, about: Int = -4490, fraction: Int = 98): ByteArray =
        ByteBuffer.allocate(15).order(ByteOrder.LITTLE_ENDIAN).put(version.toByte()).put(axis.toByte())
            .putShort(sequence.toShort()).putInt(startMs.toInt()).putShort(duration.toShort())
            .putShort(peak.toShort()).putShort(about.toShort()).put(fraction.toByte()).array()

    @Test fun decodesSignedAnglesAndUnsignedFields() {
        val event = MovementEvent.decode(frame())!!
        assertEquals('y', event.axisName)
        assertEquals(513, event.sequence)
        assertEquals(0xffff_fff0L, event.startMs)
        assertEquals(1830, event.durationMs)
        assertEquals(44.9f, event.peakTiltDegrees, 0.001f)
        assertEquals(-44.9f, event.aboutAxisDegrees, 0.001f)
        assertEquals(0.98f, event.axisFraction, 0.001f)
    }
    @Test fun keepsFullRangeSequenceAndDuration() {
        val event = MovementEvent.decode(frame(sequence = 0xfffe, duration = 65535))!!
        assertEquals(0xfffe, event.sequence)
        assertEquals(65535, event.durationMs)
    }
    @Test fun rejectsMalformedFrames() {
        assertNull(MovementEvent.decode(ByteArray(14)))
        assertNull(MovementEvent.decode(frame() + 0))
        assertNull(MovementEvent.decode(frame(version = 2)))
        assertNull(MovementEvent.decode(frame(axis = 3)))
        assertNull(MovementEvent.decode(frame(peak = -1)))
        assertNull(MovementEvent.decode(frame(fraction = 101)))
    }
}
