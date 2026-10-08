package com.openski.android

/**
 * One coaching run: picks the coach boot, feeds its half-turns to a [Coach], and reports each verdict.
 * Pure logic, so it is testable without a phone. Events arrive already mirrored into the skier frame.
 */
class CoachSession(settings: CoachSettings, private val onVerdict: (side: String, verdict: CoachVerdict) -> Unit) {
    private val coach = Coach(settings.target(), settings.windowSize)
    private var boot: String? = when (settings.coachBoot) {
        CoachBoot.LEFT -> "L"
        CoachBoot.RIGHT -> "R"
        CoachBoot.AUTO -> null
    }

    /** The boot being followed, or null until the first event when the setting is automatic. */
    fun bootInUse(): String? = boot

    fun onEvent(side: String, event: SkiEvent) {
        if (boot == null) boot = side  // automatic: follow whichever boot sends a half-turn first
        if (side != boot) return
        coach.onEvent(event)?.let { onVerdict(side, it) }
    }
}
