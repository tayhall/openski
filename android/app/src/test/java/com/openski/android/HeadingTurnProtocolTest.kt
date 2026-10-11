package com.openski.android

import org.junit.Assert.*
import org.junit.Test

/** The golden frame is the bytes asserted by tools/test_ski_frames.cpp on the firmware side. */
class HeadingTurnProtocolTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private val golden = bytes(0x04, 0x02, 0x01, 0x02, 0x36, 0x89, 0x41, 0x00,
        0x26, 0x07, 0x99, 0xfd, 0x6f, 0xe1, 0x00, 0x00)

    @Test fun decodesTheGoldenFrame() {
        val t = HeadingTurnFrame.decode(golden)!!
        assertEquals(513, t.sequence)
        assertEquals(4294966L, t.startMs)
        assertEquals(1830, t.durationMs)
        assertEquals(-61.5f, t.peakRateDps, 0.001f)
        assertEquals(-78.25f, t.changeDegrees, 0.001f)
        assertFalse(t.positive)
        assertTrue(t.skippedSamples)
        assertFalse(t.nearRail)
    }
    @Test fun dispatchesByVersionAndIgnoresUnknownOnes() {
        assertTrue(MotionFrames.decode(golden) is HeadingTurnFrame)
        assertNull(MotionFrames.decode(golden.copyOf().also { it[0] = 9 }))
    }
    @Test fun rejectsMalformedFrames() {
        assertNull(HeadingTurnFrame.decode(golden.copyOf(15)))
        assertNull(HeadingTurnFrame.decode(golden.copyOf().also { it[1] = 0x10 }))  // reserved flag bit
        assertNull(HeadingTurnFrame.decode(golden.copyOf().also { it[1] = (it[1].toInt() or 0x08).toByte() }))  // reserved flag bit 3
        assertNull(HeadingTurnFrame.decode(golden.copyOf().also { it[15] = 1 }))    // reserved bytes
        assertNull(HeadingTurnFrame.decode(golden.copyOf().also { it[1] = 0x03 }))  // sign flag disagrees with the rate
    }
}
