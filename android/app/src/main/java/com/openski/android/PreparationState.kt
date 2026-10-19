package com.openski.android

enum class PreparationAction { CONNECT, RECOVER, AUDIO, STORAGE, RETURN_TO_CAPTURE, MOUNTING, ZERO }
enum class IssueSeverity { BLOCKER, GUIDANCE }
enum class IssueKind { CONNECTION, RETAINED_DATA, AUDIO_ROUTE, STORAGE, CAPTURE_CONFLICT, MOUNTING, ZERO }
data class ReadinessIssue(val kind: IssueKind, val message: String, val action: PreparationAction,
    val side: String? = null, val severity: IssueSeverity = IssueSeverity.BLOCKER)

data class BootPreparation(val side: String, val address: String?, val connected: Boolean,
    val mounting: String?, val onSnow: Boolean?, val zeroed: Boolean, val battery: Int?)
data class PreparationSnapshot(val boots: List<BootPreparation>, val readiness: Readiness,
    val recovery: RecoveryReport, val captureActive: Boolean)

object AssignmentPolicy {
    fun canAssign(side: String, address: String, assignments: Map<String, String?>, captureProtected: Boolean): Boolean {
        if (side !in listOf("L", "R")) return false
        val other = if (side == "L") "R" else "L"
        return assignments[other] != address && (assignments[side] == address || !captureProtected)
    }
}

enum class RecoveryPhase { RECORDING, WAITING, DOWNLOADING, VALIDATING, SAVED_AWAITING_ERASE, SAVED, PAUSED, FAILED, LIVE_ONLY }
data class RecoveryItem(val captureId: String, val sessionId: String, val side: String, val phase: RecoveryPhase,
    val percent: Int?, val validatedCopy: Boolean, val canRetry: Boolean, val detail: String) {
    val pending get() = phase !in listOf(RecoveryPhase.SAVED, RecoveryPhase.LIVE_ONLY) && !(phase == RecoveryPhase.FAILED && !canRetry)
    fun label(): String = "${if (side == "L") "Left" else "Right"} boot · " + when (phase) {
        RecoveryPhase.RECORDING -> "recording"
        RecoveryPhase.WAITING -> "waiting for recovery"
        RecoveryPhase.DOWNLOADING -> "downloading${percent?.let { " · $it%" } ?: ""}"
        RecoveryPhase.VALIDATING -> "checking the phone copy"
        RecoveryPhase.SAVED_AWAITING_ERASE -> "phone copy checked · sensor erase pending"
        RecoveryPhase.SAVED -> "saved and checked · sensor copy cleared"
        RecoveryPhase.PAUSED -> "recovery paused · sensor copy retained"
        RecoveryPhase.FAILED -> "recovery needs attention"
        RecoveryPhase.LIVE_ONLY -> "live recording only · no sensor backup"
    }
}
data class RecoveryReport(val items: List<RecoveryItem>, val error: String? = null) {
    val pending get() = items.any { it.pending }
}

/** A projection of existing capture/transfer state, never a second recovery state machine. */
object RecoverySummary {
    fun item(capture: SensorCapture, transferPercent: Int? = null, validating: Boolean = false,
        paused: Boolean = false, failed: Boolean = false, activeSessionId: String? = null,
        hasCommittedCopy: Boolean = false): RecoveryItem {
        val checked = capture.state in listOf("verified", "erased") || hasCommittedCopy
        val phase = when {
            capture.state == "erased" -> RecoveryPhase.SAVED
            capture.state == "unavailable" -> RecoveryPhase.LIVE_ONLY
            capture.state == "lost" -> RecoveryPhase.FAILED
            capture.sessionId == activeSessionId -> RecoveryPhase.RECORDING
            capture.state == "verified" -> RecoveryPhase.SAVED_AWAITING_ERASE
            paused -> RecoveryPhase.PAUSED
            validating -> RecoveryPhase.VALIDATING
            transferPercent != null -> RecoveryPhase.DOWNLOADING
            failed || capture.error.isNotBlank() -> RecoveryPhase.FAILED
            else -> RecoveryPhase.WAITING
        }
        return RecoveryItem(capture.id, capture.sessionId, capture.side, phase,
            if (phase == RecoveryPhase.DOWNLOADING) transferPercent?.coerceIn(0, 100) else null, checked,
            phase in listOf(RecoveryPhase.WAITING, RecoveryPhase.PAUSED, RecoveryPhase.FAILED, RecoveryPhase.SAVED_AWAITING_ERASE) && capture.state != "lost",
            when {
                capture.state == "lost" -> if (checked) "The sensor copy is missing; a previously validated phone copy is retained."
                    else "The sensor copy is missing. Any live samples already on this phone are retained."
                phase == RecoveryPhase.LIVE_ONLY -> "${capture.error.ifBlank { "No sensor backup was available." }} Live phone samples are retained."
                checked -> (if (phase in listOf(RecoveryPhase.SAVED, RecoveryPhase.SAVED_AWAITING_ERASE)) "A validated phone copy is available."
                    else "A previously validated phone copy is retained; sensor recovery is not yet complete.") +
                    if (capture.error.isBlank()) "" else " ${capture.error}"
                phase == RecoveryPhase.FAILED -> capture.error.ifBlank { "Keep the sensors powered and nearby, check phone storage, then retry recovery." }
                else -> capture.error
            })
    }
}
