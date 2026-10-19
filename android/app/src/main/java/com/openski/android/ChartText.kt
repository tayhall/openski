package com.openski.android

import java.util.Locale

/** Spoken summaries for the hand-drawn charts, so TalkBack gets the numbers and not just a title. */
object ChartText {
    val MOTION_CHANNELS = listOf(
        "Acceleration magnitude (m/s²)", "Gyro X (rad/s)", "Gyro Y (rad/s)", "Gyro Z (rad/s)", "Gyro magnitude (rad/s)",
        "Estimated boot roll (degrees)", "Estimated boot pitch (degrees)", "Relative yaw (degrees, drifts)")

    private fun number(value: Double) = String.format(Locale.US, "%.1f", value)

    fun motion(channel: Int, samples: Int, startS: Double, endS: Double, low: Double?, high: Double?): String {
        val name = MOTION_CHANNELS.getOrElse(channel) { "Motion" }
        val span = "from ${number(startS)} to ${number(endS)} seconds"
        val range = if (samples == 0 || low == null || high == null) "No samples in this window."
            else "$samples samples, lowest ${number(low)}, highest ${number(high)}."
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
