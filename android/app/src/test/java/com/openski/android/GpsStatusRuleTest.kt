package com.openski.android

import org.junit.Assert.*
import org.junit.Test

class GpsStatusRuleTest {
    private fun decide(enabled: Boolean = true, permitted: Boolean = true, providerOn: Boolean = true,
                       age: Long? = 1000, accuracy: Float? = 6f) =
        GpsStatusRule.decide(enabled, permitted, providerOn, age, accuracy)

    @Test fun switchedOffWinsOverEverythingElse() =
        assertEquals(GpsState.OFF, decide(enabled = false, permitted = false).state)

    @Test fun missingPermissionBeforeLocationServices() =
        assertEquals(GpsState.NO_PERMISSION, decide(permitted = false, providerOn = false).state)

    @Test fun locationServicesOff() = assertEquals(GpsState.LOCATION_OFF, decide(providerOn = false).state)

    @Test fun noFixYetOrAStaleOrPoorOneIsWaiting() {
        assertEquals(GpsState.WAITING, decide(age = null, accuracy = null).state)
        assertEquals(GpsState.WAITING, decide(age = GpsStatusRule.FRESH_MS + 1).state)
        assertEquals(GpsState.WAITING, decide(accuracy = RunTrack.MAX_ACCURACY_M + 1).state)
    }

    @Test fun aNaNAccuracyIsWaitingNotACrash() =
        assertEquals(GpsState.WAITING, decide(accuracy = Float.NaN).state)

    @Test fun aFreshGoodFixIsReadyWithItsAccuracy() {
        val status = decide(accuracy = 5.6f)
        assertEquals(GpsState.READY, status.state)
        assertEquals(6, status.accuracyM)
        assertEquals("GPS ready · accuracy 6 m", status.label())
    }

    @Test fun labelsAreShortAndPlain() {
        assertEquals("GPS off", GpsStatus(GpsState.OFF).label())
        assertEquals("GPS needs location permission", GpsStatus(GpsState.NO_PERMISSION).label())
        assertEquals("Location is off on this phone", GpsStatus(GpsState.LOCATION_OFF).label())
        assertEquals("GPS · waiting for signal", GpsStatus(GpsState.WAITING).label())
    }

    @Test fun readyRowUsesTheSameGlyphsAsTheBootRows() {
        assertEquals("✓  GPS ready · accuracy 6 m", GpsStatus(GpsState.READY, 6).row())
        assertEquals("○  GPS · waiting for signal", GpsStatus(GpsState.WAITING).row())
        assertEquals("○  GPS off", GpsStatus(GpsState.OFF).row())
    }
}
