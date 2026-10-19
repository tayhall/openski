package com.openski.android

enum class PracticeAudioPhase { STOPPED, READY, PAUSED, ROUTE_UNAVAILABLE, FOCUS_UNAVAILABLE, FAILED }

data class PracticeAudioAvailability(val phase: PracticeAudioPhase, val message: String, val canResume: Boolean = false) {
    val usable: Boolean get() = phase == PracticeAudioPhase.READY
}

/** Main-thread policy only. Route/focus restoration never resumes an interrupted practice automatically. */
class PracticeAudioGate {
    private var started = false
    private var route = false
    private var focus = false
    private var needsResume = false
    private var failure: String? = null
    val state: PracticeAudioAvailability get() = when {
        !started -> PracticeAudioAvailability(PracticeAudioPhase.STOPPED, "Practice audio is stopped.")
        failure != null -> PracticeAudioAvailability(PracticeAudioPhase.FAILED, failure!!)
        !route -> PracticeAudioAvailability(PracticeAudioPhase.ROUTE_UNAVAILABLE, "Reconnect earbuds or your permitted audio route, then resume practice.")
        !focus -> PracticeAudioAvailability(PracticeAudioPhase.FOCUS_UNAVAILABLE, "Audio is unavailable while another app or call holds it. Return here and resume when ready.")
        needsResume -> PracticeAudioAvailability(PracticeAudioPhase.PAUSED, "Audio is ready. Resume practice when standing neutral.", canResume = true)
        else -> PracticeAudioAvailability(PracticeAudioPhase.READY, "Practice audio is ready.")
    }
    fun start(routeAvailable: Boolean, focusGranted: Boolean) {
        started = true; route = routeAvailable; focus = focusGranted
        needsResume = !routeAvailable || !focusGranted; failure = null
    }
    fun routeChanged(available: Boolean) {
        if (started && !available) needsResume = true
        route = available
    }
    fun focusChanged(granted: Boolean) {
        if (started && !granted) needsResume = true
        focus = granted
    }
    fun pause() { if (started) needsResume = true }
    fun resume(): Boolean {
        if (!started || !route || !focus || failure != null) return false
        needsResume = false
        return true
    }
    fun fail(message: String) { if (started) { failure = message; needsResume = true } }
    fun stop() { started = false; route = false; focus = false; needsResume = false; failure = null }
}
