package com.openski.android

import java.util.Locale

/** One stored half-turn, as the boot sent it (before the right-boot mirroring). */
data class RunEventRow(
    val side: String, val sequence: Int, val startMs: Long, val durationMs: Int,
    val peakRoll: Float, val peakRate: Float, val pitch: Float, val flags: Int, val receivedMs: Long,
)

data class RunVerdictRow(
    val timeMs: Long, val side: String, val verdict: String, val score: Int, val tempo: Int, val steadiness: Int,
    val depth: Int, val balance: Int?, val meanBeat: Double, val meanDepth: Double, val outside: Boolean,
)

data class RunInfoRow(
    val targetBeat: Double, val targetDepth: Double, val targetLabel: String, val windowSize: Int, val coachBoot: String,
    val modeBeforeJson: String, val endedBy: String, val firstTurnMs: Long?, val lastTurnMs: Long?, val gapsJson: String,
)

/** Everything a run stored beyond the ordinary session data. */
data class RunData(val events: List<RunEventRow>, val verdicts: List<RunVerdictRow>, val info: RunInfoRow?)

/** The same flag bits as the ski_v0 wire frame: 1 side positive, 2 outside envelope, 4 pitch outside, 8 samples skipped. */
fun skiEventFlags(e: SkiEvent): Int = (if (e.positive) 1 else 0) or (if (e.outsideEnvelope) 2 else 0) or
    (if (e.pitchOutside) 4 else 0) or (if (e.skippedSamples) 8 else 0)

object RunCsv {
    private fun number(value: Double) = String.format(Locale.US, "%.3f", value)
    private fun number(value: Float) = String.format(Locale.US, "%.2f", value)

    /** Roll values are in the boot's own frame; the right boot's roll is mirrored only when shown in the app. */
    fun halfTurns(rows: List<RunEventRow>, sessionStartedAtMs: Long): String = buildString {
        append("side,sequence,boot_start_ms,received_at_ms,session_elapsed_ms,duration_ms,peak_roll_deg,peak_rate_dps,pitch_deg,")
        append("positive,outside_envelope,pitch_outside,skipped_samples\n")
        rows.forEach { r ->
            append(listOf(r.side, r.sequence, r.startMs, r.receivedMs, r.receivedMs - sessionStartedAtMs, r.durationMs,
                number(r.peakRoll), number(r.peakRate), number(r.pitch),
                r.flags and 1 != 0, r.flags and 2 != 0, r.flags and 4 != 0, r.flags and 8 != 0).joinToString(",")).append('\n')
        }
    }

    fun verdicts(rows: List<RunVerdictRow>, sessionStartedAtMs: Long): String = buildString {
        append("time_ms,session_elapsed_ms,side,verdict,score,tempo,steadiness,depth,balance,mean_beat_s,mean_depth_deg,outside_envelope\n")
        rows.forEach { r ->
            append(listOf(r.timeMs, r.timeMs - sessionStartedAtMs, r.side, r.verdict, r.score, r.tempo, r.steadiness, r.depth,
                r.balance ?: "", number(r.meanBeat), number(r.meanDepth), r.outside).joinToString(",")).append('\n')
        }
    }
}
