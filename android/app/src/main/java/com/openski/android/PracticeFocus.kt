package com.openski.android

/** An explicit descriptive focus; every choice retains the confirmed target and legacy four-component score. */
enum class PracticeFocus(val label: String, val cue: String) {
    RHYTHM("Rhythm", "Count a steady beat and return to neutral between rolls."),
    DIRECTIONAL_CONSISTENCY("Directional consistency", "Observe whether cuff-roll size is similar in both directions. This does not measure strength or pressure."),
    ROLL_RANGE("Roll range", "Compare cuff-roll peaks with the confirmed target. A larger roll is not automatically better.");

    companion object {
        fun decode(value: String?): PracticeFocus? = entries.firstOrNull { it.name == value }
        const val SCORING_NOTICE = "Focus pilot · the existing four-component score (version 1) is unchanged. Focus does not create a new score or increase your target."
    }
}

/** Phone-aligned roll timeline bounds; unrelated to monotonic UI elapsed time. */
data class PracticeInterval(val startedAtMs: Double, val endedAtMs: Double) {
    init { require(startedAtMs.isFinite() && endedAtMs.isFinite() && endedAtMs >= startedAtMs) }
}

object PracticeTargetSource {
    const val CONFIRMED_PRESET = "confirmed_preset"
    const val SAVED_TARGET = "saved_target"
    fun label(value: String?): String = when (value) {
        CONFIRMED_PRESET -> "confirmed preset"
        SAVED_TARGET -> "confirmed saved target"
        else -> "not recorded"
    }
}
