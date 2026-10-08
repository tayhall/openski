package com.openski.android

import org.junit.Assert.*
import org.junit.Test

/** The golden frames are the bytes asserted by tools/test_ski_frames.cpp on the firmware side. */
class SkiEventProtocolTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private fun mutate(source: ByteArray, index: Int, value: Int) = source.copyOf().also { it[index] = value.toByte() }

    private val eventGolden = bytes(0x02, 0x08, 0x01, 0x02, 0x36, 0x89, 0x41, 0x00, 0x26,
        0x07, 0x4e, 0xf3, 0xd2, 0x04, 0x21, 0x07, 0x00, 0x00)
    private val stateGolden = bytes(0x03, 0x03, 0x02, 0x01, 0xc0, 0xc6, 0x2d, 0x00,
        0xd2, 0x04, 0xbf, 0xfe, 0x96, 0x00, 0xc8, 0x01)
    private val tiltGolden = bytes(1, 1, 1, 2, 0, 0, 0, 0, 0x26, 0x07, 0x8a, 0x11, 0x76, 0xee, 98)

    @Test fun decodesTheGoldenEvent() {
        val e = SkiEvent.decode(eventGolden)!!
        assertEquals(513, e.sequence)
        assertEquals(4294966L, e.startMs)
        assertEquals(1830, e.durationMs)
        assertEquals(-32.5f, e.peakRollDegrees, 0.001f)
        assertEquals(123.4f, e.peakRateDps, 0.001f)
        assertEquals(18.25f, e.pitchDegrees, 0.001f)
        assertFalse(e.positive)
        assertFalse(e.outsideEnvelope)
        assertFalse(e.pitchOutside)
        assertTrue(e.skippedSamples)
    }

    @Test fun decodesTheGoldenState() {
        val s = SkiState.decode(stateGolden)!!
        assertEquals(258, s.sequence)
        assertEquals(3_000_000L, s.timeMs)
        assertEquals(12.34f, s.rollDegrees, 0.001f)
        assertEquals(-3.21f, s.pitchDegrees, 0.001f)
        assertEquals(1.5f, s.vibrationMps2, 0.001f)
        assertEquals(45.6f, s.gyroDps, 0.001f)
        assertTrue(s.zeroed)
        assertTrue(s.production)
        assertFalse(s.gapInLastSecond)
    }

    @Test fun keepsFullRangeUnsignedFields() {
        val e = SkiEvent.decode(mutate(mutate(eventGolden, 8, 0xff).also { it[9] = 0xff.toByte() }, 12, 0xff).also { it[13] = 0xff.toByte() })!!
        assertEquals(65535, e.durationMs)
        assertEquals(6553.5f, e.peakRateDps, 0.01f)
        val wrapped = SkiEvent.decode(mutate(mutate(eventGolden, 2, 0xff), 3, 0xff))!!
        assertEquals(65535, wrapped.sequence)
    }

    @Test fun rejectsMalformedEvents() {
        assertNull(SkiEvent.decode(ByteArray(0)))
        assertNull(SkiEvent.decode(eventGolden.copyOf(17)))
        assertNull(SkiEvent.decode(eventGolden + 0))
        assertNull(SkiEvent.decode(mutate(eventGolden, 0, 1)))
        assertNull(SkiEvent.decode(mutate(eventGolden, 0, 3)))
        assertNull(SkiEvent.decode(mutate(eventGolden, 1, 0x18)))   // unknown flag bit
        assertNull(SkiEvent.decode(mutate(eventGolden, 1, 0x09)))   // side says + but roll is negative
        assertNull(SkiEvent.decode(mutate(eventGolden, 16, 1)))     // reserved must be zero
        assertNull(SkiEvent.decode(mutate(mutate(eventGolden, 10, 0), 11, 0)))  // a half-turn has a non-zero roll
    }

    @Test fun rejectsMalformedStates() {
        assertNull(SkiState.decode(stateGolden.copyOf(15)))
        assertNull(SkiState.decode(stateGolden + 0))
        assertNull(SkiState.decode(mutate(stateGolden, 0, 2)))
        assertNull(SkiState.decode(mutate(stateGolden, 1, 0x08)))   // unknown flag bit
        assertNull(SkiState.decode(mutate(mutate(stateGolden, 8, 0x00), 9, 0x7f)))  // roll beyond 90 degrees
    }

    @Test fun dispatchesOnTheVersionByteAndNothingElse() {
        assertTrue(MotionFrames.decode(tiltGolden) is MovementEvent)
        assertTrue(MotionFrames.decode(eventGolden) is SkiEvent)
        assertTrue(MotionFrames.decode(stateGolden) is SkiState)
        assertNull(MotionFrames.decode(ByteArray(0)))
        assertNull(MotionFrames.decode(mutate(eventGolden, 0, 4)))
        assertNull(MotionFrames.decode(eventGolden.copyOf(15)))     // a truncated v2 is not a v1
        assertNull(MotionFrames.decode(mutate(eventGolden, 0, 1)))  // an 18-byte v1 is not valid
        assertNull(MotionFrames.decode(stateGolden.copyOf(18)))
    }

    @Test fun mirroringFlipsTheRollSideAndNothingElse() {
        val event = SkiEvent.decode(eventGolden)!!
        val mirrored = event.mirrored()
        assertEquals(32.5f, mirrored.peakRollDegrees, 0.001f)
        assertTrue(mirrored.positive)
        assertEquals(event.pitchDegrees, mirrored.pitchDegrees, 0f)
        assertEquals(event.peakRateDps, mirrored.peakRateDps, 0f)
        assertEquals(event.durationMs, mirrored.durationMs)
        assertEquals(event, mirrored.mirrored())
        val state = SkiState.decode(stateGolden)!!
        assertEquals(-12.34f, state.mirrored().rollDegrees, 0.001f)
        assertEquals(state.pitchDegrees, state.mirrored().pitchDegrees, 0f)
        assertEquals(state, state.mirrored().mirrored())
    }

    @Test fun onlyTheRightBootIsMirroredIntoTheSkierFrame() {
        val event = SkiEvent.decode(eventGolden)!!
        val state = SkiState.decode(stateGolden)!!
        assertEquals(event, SkierFrame.of("L", event))
        assertEquals(event.mirrored(), SkierFrame.of("R", event))
        assertEquals(state, SkierFrame.of("L", state))
        assertEquals(state.mirrored(), SkierFrame.of("R", state))
    }
}
