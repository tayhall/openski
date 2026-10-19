package com.openski.android

/** Tracks explicit mode-command acknowledgements; periodic state reports cannot acknowledge writes. */
class ModeRequestTracker(private val throttleMs: Long = 2000) {
    private data class Requests(val targets: MutableList<Boolean>, var desired: Boolean, var queuedAt: Long)
    private val requests = mutableMapOf<String, Requests>()

    fun pending(side: String): Boolean = side in requests

    fun shouldSend(side: String, desired: Boolean, now: Long): Boolean {
        val existing = requests[side] ?: return true
        return existing.desired != desired || now - existing.queuedAt >= throttleMs
    }

    fun queued(side: String, desired: Boolean, now: Long) {
        val existing = requests[side]
        if (existing == null) requests[side] = Requests(mutableListOf(desired), desired, now)
        else {
            existing.targets.add(desired)
            existing.desired = desired
            existing.queuedAt = now
        }
    }

    fun acknowledged(side: String, result: Int, reported: Boolean) {
        val existing = requests[side] ?: return
        if (existing.targets.isEmpty()) return
        if (result != 0) {
            existing.targets.removeAt(0)
            return // A rejected command never satisfies the desired mode.
        }
        val matched = existing.targets.indexOf(reported)
        if (matched < 0) return
        repeat(matched + 1) { existing.targets.removeAt(0) }
        if (reported == existing.desired && existing.targets.all { it == existing.desired }) requests.remove(side)
    }

    fun clear(side: String) { requests.remove(side) }
}
