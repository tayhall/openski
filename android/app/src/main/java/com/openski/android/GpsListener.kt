package com.openski.android

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper

/** One-second GPS updates from the platform `LocationManager`, delivered on the main thread. */
class GpsListener(private val context: Context, private val onFix: (GpsFix) -> Unit) {
    private val manager = context.getSystemService(LocationManager::class.java)
    private var active = false
    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            onFix(RunTrack.fixFrom(
                location.time, location.latitude, location.longitude,
                if (location.hasAltitude()) location.altitude else null,
                if (location.hasSpeed()) location.speed else null,
                if (location.hasAccuracy()) location.accuracy else null,
                if (location.hasVerticalAccuracy()) location.verticalAccuracyMeters else null,
                System.currentTimeMillis()))
        }
        // Before Android 11 these have no default body, so the platform would throw AbstractMethodError without them.
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Never called on Android 12 and later")
        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
    }

    val permitted: Boolean
        get() = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val providerOn: Boolean
        get() = try { manager.isProviderEnabled(LocationManager.GPS_PROVIDER) } catch (_: Exception) { false }

    /** False when it cannot start (no permission, location off, or the platform refused). */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (active) return true
        if (!permitted || !providerOn) return false
        return try {
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
            active = true
            true
        } catch (_: SecurityException) { false }
    }

    fun stop() {
        if (!active) return
        active = false
        try { manager.removeUpdates(listener) } catch (_: Exception) { /* permission revoked or provider gone: nothing to remove */ }
    }
}
