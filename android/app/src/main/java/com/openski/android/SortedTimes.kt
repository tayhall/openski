package com.openski.android

/** Binary searches over time-ordered data, shared by the clock aligner and the turn-metric windows. */
internal object SortedTimes {
    /** First index in 0 until [size] whose key is >= [t]. [key] must be non-decreasing in the index. */
    inline fun firstAtOrAfter(size: Int, t: Double, key: (Int) -> Double): Int {
        var lo = 0
        var hi = size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (key(mid) < t) lo = mid + 1 else hi = mid }
        return lo
    }

    /** First index in 0 until [size] whose key is > [t]. [key] must be non-decreasing in the index. */
    inline fun firstAfter(size: Int, t: Double, key: (Int) -> Double): Int {
        var lo = 0
        var hi = size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (key(mid) <= t) lo = mid + 1 else hi = mid }
        return lo
    }
}
