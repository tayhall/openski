package com.openski.android

/**
 * The boot's live frames carry its 32-bit microsecond clock divided by 1000, so the millisecond value wraps every
 * 4294.967296 s (71.6 minutes). One rule for what counts as that wrap, shared by every place that unwraps or checks
 * the live clock, so they cannot disagree about whether a backwards step is a rollover or a reset.
 */
object BootClockWrap {
    /** Length of one wrap in seconds. */
    const val WRAP_SEC = 4294.967296
    /** A step back from above this many ms to below [LOW_MS] is the wrap, not a reset. */
    const val HIGH_MS = 4_200_000L
    const val LOW_MS = 100_000L

    fun isRollover(previousMs: Long, nextMs: Long) = nextMs < previousMs && previousMs > HIGH_MS && nextMs < LOW_MS
}
