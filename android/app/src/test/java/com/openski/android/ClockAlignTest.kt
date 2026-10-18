package com.openski.android

import org.junit.Assert.*
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp

class ClockAlignTest {
    private val fs = 100.0
    private val duration = 620.0
    private val sampleMs = 10.0

    /** A stomp: a short damped 18 Hz oscillation starting at [at]. */
    private class Stomp(val at: Double, val amp: Double)

    private fun signal(t: Double, stomps: List<Stomp>): Double {
        var v = 9.8
        for (s in stomps) {
            val u = t - s.at
            if (u >= 0) v += s.amp * (1 - exp(-u / 0.004)) * exp(-u / 0.03) * cos(2 * PI * 18 * u)
        }
        return v
    }

    private fun seriesA(stomps: List<Stomp>, noise: Double, rng: Random): ClockAlign.Series {
        val n = (duration * fs).toInt()
        val t = DoubleArray(n) { it / fs }
        return ClockAlign.Series(t, DoubleArray(n) { signal(t[it], stomps) + rng.nextGaussian() * noise })
    }

    /** B's clock reads offset + t * (1 + ppm) at true time t; each sample is taken at a jittered true time. */
    private fun seriesB(
        stomps: List<Stomp>, offset: Double, ppm: Double, jitterSec: Double, noise: Double, rng: Random,
    ): ClockAlign.Series {
        val n = (duration * fs).toInt()
        val t = DoubleArray(n) { offset + (it / fs) * (1 + ppm * 1e-6) }
        val v = DoubleArray(n) { signal(it / fs + rng.nextGaussian() * jitterSec, stomps) + rng.nextGaussian() * noise }
        return ClockAlign.Series(t, v)
    }

    private fun windows(p: Double, offset: Double, ppm: Double, guessErr: Double) = ClockAlign.EndWindows(
        ClockAlign.Window(p - 3, p + 3),
        ClockAlign.Window(p + offset + p * ppm * 1e-6 + guessErr - 3, p + offset + p * ppm * 1e-6 + guessErr + 3),
    )

    private val both = listOf(Stomp(5.0, 30.0), Stomp(610.0, 30.0))

    private fun run(
        offset: Double, ppm: Double, jitter: Double = 0.0, noise: Double = 0.2, seed: Long = 7,
        stompsA: List<Stomp> = both, stompsB: List<Stomp> = both,
    ): ClockAlign.Result {
        val rng = Random(seed)
        val a = seriesA(stompsA, noise, rng)
        val b = seriesB(stompsB, offset, ppm, jitter, noise, rng)
        return ClockAlign.align(a, b, windows(5.0, offset, ppm, 0.15), windows(610.0, offset, ppm, -0.12))
    }

    private fun assertRecovered(r: ClockAlign.Result, offset: Double, ppm: Double, tolMs: Double, tolPpm: Double) {
        assertTrue("rejected ${r.reject} ncc ${r.start?.ncc} ${r.end?.ncc}", r.ok)
        val truth = offset + r.refTimeSec * ppm * 1e-6
        assertEquals(truth, r.offsetSec, tolMs / 1000)
        assertEquals(ppm, r.driftPpm, tolPpm)
    }

    @Test fun cleanOffsetAndSkewAreRecoveredToAFractionOfASample() {
        val r = run(offset = 1.2, ppm = 50.0)
        assertRecovered(r, 1.2, 50.0, 1.0, 3.0)
        assertEquals(5.0, r.refTimeSec, 0.06) // the rectified peak, a little after the onset
    }

    @Test fun negativeOffsetAndSkew() {
        assertRecovered(run(offset = -0.7, ppm = -80.0), -0.7, -80.0, 1.0, 3.0)
    }

    @Test fun jitterAndNoiseStayWithinTwoSamples() {
        // Heavy jitter and noise may make the aligner refuse a run, but whatever it accepts must be right.
        var accepted = 0
        for (seed in 1L..8L) {
            val r = run(offset = 2.0, ppm = 30.0, jitter = 0.002, noise = 0.5, seed = seed)
            if (!r.ok) {
                assertEquals(ClockAlign.Reject.LOW_CORRELATION, r.reject)
                continue
            }
            accepted++
            assertRecovered(r, 2.0, 30.0, 2 * sampleMs, 2 * sampleMs / 600 * 1e3)
        }
        assertTrue("accepted $accepted of 8", accepted >= 6)
    }

    @Test fun offsetAtFollowsTheDrift() {
        val r = run(offset = 0.5, ppm = 200.0)
        assertTrue(r.ok)
        assertEquals(0.5 + 610.0 * 200e-6, r.offsetAt(610.0), 0.003)
    }

    @Test fun uncertaintyIsReportedAndCoversTheError() {
        val r = run(offset = 1.0, ppm = 40.0, jitter = 0.003, noise = 0.5)
        assertTrue(r.ok)
        assertTrue(r.offsetSigmaSec in 0.001..0.02)
        assertTrue(r.driftSigmaPpm in 1.0..100.0)
        assertTrue(abs(r.driftPpm - 40.0) < 3 * r.driftSigmaPpm)
    }

    @Test fun missingEndTransientIsRejected() {
        val r = run(offset = 1.0, ppm = 40.0, stompsA = listOf(Stomp(5.0, 30.0)), stompsB = listOf(Stomp(5.0, 30.0)))
        assertFalse(r.ok)
        assertEquals(ClockAlign.Reject.NO_TRANSIENT, r.reject)
    }

    @Test fun transientInOnlyOneStreamIsRejected() {
        val r = run(offset = 1.0, ppm = 40.0, stompsB = listOf(Stomp(5.0, 30.0)))
        assertFalse(r.ok)
        assertEquals(ClockAlign.Reject.NO_TRANSIENT, r.reject)
    }

    @Test fun flatSignalIsRejected() {
        val r = run(offset = 1.0, ppm = 40.0, stompsA = emptyList(), stompsB = emptyList())
        assertFalse(r.ok)
        assertEquals(ClockAlign.Reject.NO_TRANSIENT, r.reject)
    }

    @Test fun doubleTransientIsAmbiguous() {
        val dbl = both + listOf(Stomp(5.4, 28.0), Stomp(610.4, 28.0))
        val r = run(offset = 1.0, ppm = 40.0, stompsA = dbl, stompsB = dbl)
        assertFalse(r.ok)
        assertEquals(ClockAlign.Reject.AMBIGUOUS, r.reject)
    }

    @Test fun drowningNoiseIsRejectedNotGuessed() {
        val r = run(offset = 0.5, ppm = 20.0, noise = 8.0)
        assertFalse(r.ok)
        assertNotNull(r.reject)
    }

    @Test fun endsThatImplyAbsurdDriftAreRejected() {
        val r = run(offset = 0.3, ppm = 5000.0)
        assertFalse(r.ok)
        assertEquals(ClockAlign.Reject.DRIFT_IMPLAUSIBLE, r.reject)
        assertNotNull(r.start)
        assertNotNull(r.end)
    }

    @Test fun windowsTooCloseAreRejected() {
        val rng = Random(3)
        val a = seriesA(both, 0.2, rng)
        val b = seriesB(both, 1.0, 0.0, 0.0, 0.2, rng)
        val r = ClockAlign.align(a, b, windows(5.0, 1.0, 0.0, 0.0), windows(5.0, 1.0, 0.0, 0.0))
        assertEquals(ClockAlign.Reject.WINDOWS_TOO_CLOSE, r.reject)
    }

    @Test fun shortWindowIsFlat() {
        val s = ClockAlign.Series(doubleArrayOf(0.0, 0.01), doubleArrayOf(1.0, 1.0))
        val w = ClockAlign.EndWindows(ClockAlign.Window(0.0, 1.0), ClockAlign.Window(0.0, 1.0))
        assertEquals(ClockAlign.Reject.NO_TRANSIENT, ClockAlign.align(s, s, w, w).reject)
    }
}
