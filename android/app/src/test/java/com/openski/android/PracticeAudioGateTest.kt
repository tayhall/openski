package com.openski.android

import org.junit.Assert.*
import org.junit.Test

class PracticeAudioGateTest {
    @Test fun routeRestorationRequiresExplicitResume() {
        val gate = PracticeAudioGate(); gate.start(true, true)
        assertTrue(gate.state.usable)
        gate.routeChanged(false)
        assertEquals(PracticeAudioPhase.ROUTE_UNAVAILABLE, gate.state.phase)
        assertFalse(gate.resume())
        gate.routeChanged(true)
        assertEquals(PracticeAudioPhase.PAUSED, gate.state.phase)
        assertTrue(gate.state.canResume)
        assertTrue(gate.resume()); assertTrue(gate.state.usable)
    }
    @Test fun focusDuckingOrLossRequiresResumeAfterGain() {
        val gate = PracticeAudioGate(); gate.start(true, true)
        gate.focusChanged(false)
        assertEquals(PracticeAudioPhase.FOCUS_UNAVAILABLE, gate.state.phase)
        gate.focusChanged(true)
        assertFalse(gate.state.usable)
        assertTrue(gate.resume()); assertTrue(gate.state.usable)
    }
    @Test fun routeRecoveryWhileFocusUnavailableNeverStartsSound() {
        val gate = PracticeAudioGate(); gate.start(false, false)
        gate.routeChanged(true)
        assertEquals(PracticeAudioPhase.FOCUS_UNAVAILABLE, gate.state.phase)
        assertFalse(gate.resume())
        gate.focusChanged(true)
        assertEquals(PracticeAudioPhase.PAUSED, gate.state.phase)
        assertTrue(gate.resume())
    }
    @Test fun repeatedRouteReportsDoNotCancelAnExplicitPause() {
        val gate = PracticeAudioGate(); gate.start(true, true); gate.pause()
        repeat(4) { gate.routeChanged(true); gate.focusChanged(true) }
        assertEquals(PracticeAudioPhase.PAUSED, gate.state.phase)
        assertTrue(gate.resume())
    }
    @Test fun failureCannotResumeAndStopRejectsLateRecovery() {
        val gate = PracticeAudioGate(); gate.start(true, true)
        gate.fail("This phone could not play practice audio.")
        assertEquals(PracticeAudioPhase.FAILED, gate.state.phase)
        gate.focusChanged(true); gate.routeChanged(true)
        assertFalse(gate.resume())
        gate.stop(); gate.focusChanged(true); gate.routeChanged(true)
        assertEquals(PracticeAudioPhase.STOPPED, gate.state.phase)
        assertFalse(gate.resume())
    }
    @Test fun initialFocusDenialHasActionableAvailability() {
        val gate = PracticeAudioGate(); gate.start(true, false)
        assertEquals(PracticeAudioPhase.FOCUS_UNAVAILABLE, gate.state.phase)
        assertFalse(gate.state.canResume)
        assertTrue(gate.state.message.contains("resume"))
    }
}
