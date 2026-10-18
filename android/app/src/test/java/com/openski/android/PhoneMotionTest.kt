package com.openski.android

import org.junit.Assert.*
import org.junit.Test

class PhoneMotionTest {
    private fun row(n: Long) = StoredPhone(n, 0f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f)

    @Test fun gyroRowsWaitForAccelAndRotationThenHoldLatest() {
        val a = PhoneMotionAssembler()
        assertNull(a.onGyroscope(1, 0f, 0f, 0f))
        a.onAccelerometer(1f, 2f, 3f)
        assertNull(a.onGyroscope(2, 0f, 0f, 0f))
        a.onRotation(0.1f, 0.2f, 0.3f, 0.9f)
        val r = a.onGyroscope(3, 4f, 5f, 6f)!!
        assertEquals(3L, r.phoneNs)
        assertEquals(listOf(4f, 5f, 6f, 1f, 2f, 3f), listOf(r.gx, r.gy, r.gz, r.ax, r.ay, r.az))
        assertEquals(listOf(0.9f, 0.1f, 0.2f, 0.3f), listOf(r.qw, r.qx, r.qy, r.qz))
        a.onAccelerometer(7f, 8f, 9f)
        val next = a.onGyroscope(4, 0f, 0f, 0f)!!
        assertEquals(7f, next.ax, 0f); assertEquals(0.9f, next.qw, 0f)
        assertEquals(3L, a.firstEventNs)
    }

    @Test fun bufferDrainsAndRestoresInOrder() {
        val b = PhoneMotionBuffer()
        b.add(row(1)); b.add(row(2))
        val first = b.drain()
        assertEquals(2, first.size); assertTrue(b.drain().isEmpty())
        b.add(row(3)); b.restore(first)
        assertEquals(listOf(1L, 2L, 3L), b.drain().map { it.phoneNs })
    }

    @Test fun clockCheckEventRoundTrips() {
        val line = PhoneMotionExport.clockCheckEvent(5_000_000_000L, 4_999_000_000L)
        assertEquals("phone_clock_check offset_ms=1.000", line)
        assertEquals(1.0, PhoneMotionExport.clockOffsetMs(listOf(0L to "x", 1L to line))!!, 1e-9)
        assertNull(PhoneMotionExport.clockOffsetMs(listOf(0L to "x")))
    }

    @Test fun csvHasHeaderNotesAndUnalteredValues() {
        val r = StoredPhone(10_000_000_000L, 0.5f, -0.25f, 0f, 0f, 0f, 9.81f, 1f, 0f, 0f, 0f)
        val lines = PhoneMotionExport.csv(listOf(r), listOf(0L to "phone_clock_check offset_ms=0.000"), 9_000_000_000L).trim().lines()
        assertTrue(lines[0].startsWith("# ") && lines[0].contains("uncalibrated"))
        assertTrue(lines[1].contains("offset_ms=0.000"))
        assertEquals(PhoneMotionExport.COLUMNS.trim(), lines[3])
        assertEquals("10000000000,1000.000,0.5,-0.25,0.0,0.0,0.0,9.81,1.0,0.0,0.0,0.0", lines[4])
    }

    @Test fun csvWithoutClockCheckLeavesElapsedBlank() {
        val lines = PhoneMotionExport.csv(listOf(row(1)), emptyList(), 5L).trim().lines()
        assertTrue(lines[1].contains("unknown"))
        assertTrue(lines.last().startsWith("1,,"))
    }
}
