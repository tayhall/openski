package com.openski.android

import org.junit.Assert.*
import org.junit.Test

/** Frame version 5 on the movement characteristic; layout is in docs/ble-protocol.md and MagnetometerProtocol.kt. */
class MagnetometerProtocolTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    // flags 0x03, sequence 0x0201, time 0x00010000 ms, X = 6842 (1 gauss), Y = -3421, Z = 32767, reserved 0.
    private val golden = bytes(0x05, 0x03, 0x01, 0x02, 0x00, 0x00, 0x01, 0x00,
        0xba, 0x1a, 0xa3, 0xf2, 0xff, 0x7f, 0x00, 0x00)

    @Test fun decodesTheGoldenFrame() {
        val m = MagnetometerFrame.decode(golden)!!
        assertEquals(513, m.sequence)
        assertEquals(65_536L, m.timeMs)
        assertEquals(6842, m.x)
        assertEquals(-3421, m.y)
        assertEquals(32767, m.z)
        assertTrue(m.nearRange)
        assertTrue(m.missedSamples)
        assertEquals(1.0, m.gaussX, 1e-9)
        assertEquals(-0.5, m.gaussY, 1e-3)
        assertEquals(3, m.flags)
    }
    @Test fun flagsAreIndependentAndTheClockIsUnsigned() {
        val m = MagnetometerFrame.decode(golden.copyOf().also { it[1] = 1; it[4] = 0xff.toByte(); it[7] = 0xff.toByte() })!!
        assertTrue(m.nearRange)
        assertFalse(m.missedSamples)
        assertEquals(0xff01_00ffL, m.timeMs)
        assertFalse(MagnetometerFrame.decode(golden.copyOf().also { it[1] = 0 })!!.nearRange)
    }
    @Test fun dispatchesByVersionAndStillIgnoresUnknownOnes() {
        assertTrue(MotionFrames.decode(golden) is MagnetometerFrame)
        assertNull(MotionFrames.decode(golden.copyOf().also { it[0] = 6 }))
        assertNull(MotionFrames.decode(golden.copyOf().also { it[0] = 9 }))
    }
    @Test fun rejectsMalformedFrames() {
        assertNull(MagnetometerFrame.decode(golden.copyOf(15)))
        assertNull(MagnetometerFrame.decode(golden.copyOf(17)))
        assertNull(MagnetometerFrame.decode(golden.copyOf().also { it[0] = 4 }))
        assertNull(MagnetometerFrame.decode(ByteArray(0)))
    }
    @Test fun reservedBitsAndBytesAreIgnoredNotRejected() {
        assertNotNull(MagnetometerFrame.decode(golden.copyOf().also { it[1] = 0xfc.toByte(); it[14] = 1; it[15] = 2 }))
    }
}
