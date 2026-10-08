package com.openski.android

/**
 * Knows when a boot has really zeroed after a zero command. The boot reports `zeroed` once a second, and a report
 * already in flight when the command was sent still says "zeroed" about the old pose. Zeroing takes at least one
 * second of stillness, so only a report that arrives that long after the command counts.
 */
class ZeroTracker(private val minimumMs: Long = 1000) {
    private val requestedAt = mutableMapOf<String, Long>()
    private val zeroedAt = mutableMapOf<String, Long>()

    fun requested(side: String, nowMs: Long) {
        requestedAt[side] = nowMs
        zeroedAt.remove(side)
    }

    /** Record a state frame from the boot. */
    fun frame(side: String, zeroed: Boolean, nowMs: Long) {
        if (zeroed) zeroedAt[side] = nowMs else zeroedAt.remove(side)
    }

    fun isZeroed(side: String): Boolean {
        val reported = zeroedAt[side] ?: return false
        val asked = requestedAt[side] ?: return true  // never asked: the boot zeroed on its own earlier
        return reported - asked >= minimumMs
    }
}
