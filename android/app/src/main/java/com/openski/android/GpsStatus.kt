package com.openski.android

import android.content.Context
import kotlin.math.roundToInt

enum class GpsState { OFF, NO_PERMISSION, LOCATION_OFF, WAITING, READY }

data class GpsStatus(val state: GpsState, val accuracyM: Int? = null) {
    fun label(): String = when (state) {
        GpsState.OFF -> "GPS off"
        GpsState.NO_PERMISSION -> "GPS needs location permission"
        GpsState.LOCATION_OFF -> "Location is off on this phone"
        GpsState.WAITING -> "GPS · waiting for signal"
        GpsState.READY -> "GPS ready · accuracy ${accuracyM ?: "?"} m"
    }

    /** The Ready checklist row: the same tick or circle glyphs as the boots and earbuds rows. GPS never blocks a run. */
    fun row(): String = "${if (state == GpsState.READY) "✓" else "○"}  ${label()}"
}

/** What the Ready screen's GPS row says. Never blocks a run: every state still allows starting. */
object GpsStatusRule {
    const val FRESH_MS = 10_000L

    fun decide(enabled: Boolean, permitted: Boolean, providerOn: Boolean, lastFixAgeMs: Long?, accuracyM: Float?): GpsStatus = when {
        !enabled -> GpsStatus(GpsState.OFF)
        !permitted -> GpsStatus(GpsState.NO_PERMISSION)
        !providerOn -> GpsStatus(GpsState.LOCATION_OFF)
        lastFixAgeMs == null || accuracyM == null || accuracyM.isNaN() || lastFixAgeMs > FRESH_MS || accuracyM > RunTrack.MAX_ACCURACY_M ->
            GpsStatus(GpsState.WAITING)
        else -> GpsStatus(GpsState.READY, accuracyM.roundToInt())
    }
}

/** The skier's GPS switch and whether the permission prompt has been shown. On by default. */
class GpsSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("openski-gps", Context.MODE_PRIVATE)
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", true)
        set(value) { prefs.edit().putBoolean("enabled", value).apply() }
    var asked: Boolean
        get() = prefs.getBoolean("asked", false)
        set(value) { prefs.edit().putBoolean("asked", value).apply() }
}
