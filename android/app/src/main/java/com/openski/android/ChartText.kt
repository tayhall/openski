package com.openski.android

import java.util.Locale

/** Spoken summaries for the hand-drawn charts, so TalkBack gets the numbers and not just a title. */
object ChartText {
    val MOTION_CHANNELS = listOf(
        "Acceleration magnitude (m/s²)", "Gyro X (rad/s)", "Gyro Y (rad/s)", "Gyro Z (rad/s)", "Gyro magnitude (rad/s)",
        "Estimated boot roll (degrees)", "Estimated boot pitch (degrees)", "Relative yaw (degrees, drifts)")

    private fun number(value: Double) = String.format(Locale.US, "%.1f", value)

    /** One boot's samples in the visible window. */
    data class BootRange(val side: String, val samples: Int, val low: Double, val high: Double)

    fun motion(channel: Int, startS: Double, endS: Double, boots: List<BootRange>): String {
        val name = MOTION_CHANNELS.getOrElse(channel) { "Motion" }
        val span = "from ${number(startS)} to ${number(endS)} seconds"
        val shown = boots.filter { it.samples > 0 }
        val range = if (shown.isEmpty()) "No samples in this window."
            else shown.joinToString(" ") { "${if (it.side == "L") "Left" else "Right"} boot: ${it.samples} samples, lowest ${number(it.low)}, highest ${number(it.high)}." }
        return "$name $span. $range Drag to seek, or use left and right arrow keys."
    }

    fun turnProfile(profiles: List<RollProfile>, positiveLeft: Boolean): String {
        if (profiles.isEmpty()) return "Mean boot roll profiles. No complete calibrated intervals."
        val parts = profiles.joinToString(" ") { profile ->
            val side = if (profile.positive == positiveLeft) "Left direction, solid line" else "Right direction, dashed line"
            "$side: ${profile.count} turns, mean peak ${number(profile.peakDegrees)} degrees at ${profile.peakPercent} percent of the turn."
        }
        return "Mean boot roll profiles over the turn. $parts"
    }
}
