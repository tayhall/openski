package com.openski.android

/** Repeating uses persisted targets, while cue/title remain current catalogue metadata. */
object PracticeRepeat {
    fun drill(attempt: Attempt): Drill {
        require(attempt.status != PracticeStatus.ACTIVE) { "Return to active practice before repeating it." }
        require(attempt.scoringVersion == 1) { "This scoring version cannot be repeated by this app." }
        val preset = requireNotNull(Programme.drill(attempt.drillId)) { "This drill is no longer available." }
        val target = requireNotNull(attempt.target) { "The historical target was not recorded. Choose a current preset." }
        return preset.copy(movements = target.movements, paceSeconds = target.beatSeconds, depthDegrees = target.depthDegrees)
    }
}
