package com.openski.android

/** Keep sensor intervals intact when BLE notifications arrive in bursts. Wall-clock alignment is approximate. */
class LiveSensorClock {
    private var previous: Long? = null
    private var lastReceipt = 0L
    private var wrap = 0.0
    private var anchor = 0.0
    fun timestamp(sensorMs: Long, receivedMs: Long): Double {
        val old = previous
        val rollover = old != null && sensorMs < old && old > 4_200_000 && sensorMs < 100_000
        if (rollover) wrap += 4_294_967.296
        if (old == null || (sensorMs < old && !rollover) || receivedMs - lastReceipt > 30_000) {
            wrap = 0.0
            anchor = receivedMs - sensorMs.toDouble()
        }
        previous = sensorMs
        lastReceipt = receivedMs
        return anchor + sensorMs + wrap
    }
}
