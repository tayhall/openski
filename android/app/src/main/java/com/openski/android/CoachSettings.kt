package com.openski.android

import android.content.Context

enum class CoachBoot { AUTO, LEFT, RIGHT }

/** Everything the skier can change about coaching. [normalised] clamps values that came from storage or a text box. */
data class CoachSettings(
    val metronome: Boolean = true,
    val countIn: Boolean = true,
    val chirps: Boolean = true,
    val windowSize: Int = Coach.DEFAULT_WINDOW,
    val coachBoot: CoachBoot = CoachBoot.AUTO,
    /** A drill id from [Programme], or null to use the custom numbers. */
    val presetDrillId: String? = "steady-rhythm",
    val customBeat: Double = 2.0,
    val customDepth: Double = 20.0,
    val gainPercent: Int = 60,
    val allowPhoneSpeaker: Boolean = false,
) {
    fun normalised() = copy(
        windowSize = windowSize.coerceIn(Coach.MIN_WINDOW, Coach.MAX_WINDOW),
        customBeat = customBeat.coerceIn(CoachTarget.MIN_BEAT, CoachTarget.MAX_BEAT),
        customDepth = customDepth.coerceIn(CoachTarget.MIN_DEPTH, CoachTarget.MAX_DEPTH),
        gainPercent = gainPercent.coerceIn(0, 100),
    )

    /** The preset drill's pace and depth, or the custom numbers if there is no such drill. */
    fun target(): CoachTarget {
        val drill = presetDrillId?.let { Programme.drill(it) }
        return if (drill != null) CoachTarget.of(drill) else CoachTarget.custom(customBeat, customDepth)
    }

    /** One line saying what the skier is aiming for. */
    fun targetLabel(): String {
        val drill = presetDrillId?.let { Programme.drill(it) }
        val target = target()
        val numbers = "%.1f s beat, %d° roll".format(java.util.Locale.US, target.beatSeconds, target.depthDegrees.toInt())
        return if (drill != null) "${drill.title} · $numbers" else "Custom · $numbers"
    }
}

class CoachSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("openski-coach", Context.MODE_PRIVATE)

    var settings: CoachSettings
        get() {
            val defaults = CoachSettings()
            return CoachSettings(
                metronome = prefs.getBoolean("metronome", defaults.metronome),
                countIn = prefs.getBoolean("count_in", defaults.countIn),
                chirps = prefs.getBoolean("chirps", defaults.chirps),
                windowSize = prefs.getInt("window", defaults.windowSize),
                coachBoot = CoachBoot.entries.firstOrNull { it.name == prefs.getString("boot", null) } ?: defaults.coachBoot,
                presetDrillId = if (prefs.contains("preset")) prefs.getString("preset", null) else defaults.presetDrillId,
                customBeat = prefs.getFloat("custom_beat", defaults.customBeat.toFloat()).toDouble(),
                customDepth = prefs.getFloat("custom_depth", defaults.customDepth.toFloat()).toDouble(),
                gainPercent = prefs.getInt("gain", defaults.gainPercent),
                allowPhoneSpeaker = prefs.getBoolean("phone_speaker", defaults.allowPhoneSpeaker),
            ).normalised()
        }
        set(value) {
            val v = value.normalised()
            prefs.edit()
                .putBoolean("metronome", v.metronome).putBoolean("count_in", v.countIn).putBoolean("chirps", v.chirps)
                .putInt("window", v.windowSize).putString("boot", v.coachBoot.name).putString("preset", v.presetDrillId)
                .putFloat("custom_beat", v.customBeat.toFloat()).putFloat("custom_depth", v.customDepth.toFloat())
                .putInt("gain", v.gainPercent).putBoolean("phone_speaker", v.allowPhoneSpeaker)
                .apply()
        }
}
