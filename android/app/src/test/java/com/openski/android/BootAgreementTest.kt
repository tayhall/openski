package com.openski.android

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class BootAgreementTest {
    private val leanMs = 1200.0

    /** A half-sine lean of [peak] degrees starting at 300 ms (shifted by [lagMs]), sampled at 100 Hz for 2 s. */
    private fun boot(side: String, peak: Double = 30.0, lagMs: Double = 0.0, sign: Double = 1.0): List<BootRoll> =
        (0..200).map { i ->
            val t = i * 10.0
            val u = t - 300.0 - lagMs
            val inLean = u in 0.0..leanMs
            val deg = if (inLean) peak * sin(PI * u / leanMs) else 0.0
            val rate = if (inLean) Math.toRadians(peak * PI / leanMs * 1000 * cos(PI * u / leanMs)) else 0.0
            BootRoll(side, t, sign * deg, 0.0, rollRateRadps = sign * rate)
        }

    @Test fun identicalBootsAgreeFully() {
        val s = BootAgreement.derivativeSimilarity(boot("L"), boot("R"), 300.0, 1500.0)!!
        assertEquals(1.0, s, 1e-9)
    }

    @Test fun laggedBootAgreesLess() {
        val same = BootAgreement.derivativeSimilarity(boot("L"), boot("R"), 300.0, 1500.0)!!
        val lagged = BootAgreement.derivativeSimilarity(boot("L"), boot("R", lagMs = 250.0), 300.0, 1500.0)!!
        assertTrue("lagged $lagged vs $same", lagged < same - 0.2)
    }

    @Test fun mirroredBootGivesMinusOne() {
        val s = BootAgreement.derivativeSimilarity(boot("L"), boot("R", sign = -1.0), 300.0, 1500.0)!!
        assertEquals(-1.0, s, 1e-9)
    }

    @Test fun gapsAndEmptyGiveNull() {
        val gappy = boot("R").filter { it.timeMs < 800.0 || it.timeMs > 1000.0 }
        assertNull(BootAgreement.derivativeSimilarity(boot("L"), gappy, 300.0, 1500.0))
        assertNull(BootAgreement.wedgeFraction(boot("L"), gappy, 300.0, 1500.0))
        assertNull(BootAgreement.derivativeSimilarity(boot("L"), emptyList(), 300.0, 1500.0))
        assertNull(BootAgreement.wedgeFraction(emptyList(), boot("R"), 300.0, 1500.0))
        assertNull(BootAgreement.derivativeSimilarity(boot("L"), boot("R"), 0.0, 40.0))
    }

    @Test fun flatRateGivesNullSimilarity() {
        assertNull(BootAgreement.derivativeSimilarity(boot("L"), boot("R", peak = 0.0), 300.0, 1500.0))
    }

    @Test fun wedgeIsZeroForSameSignAndOneForOpposite() {
        assertEquals(0.0, BootAgreement.wedgeFraction(boot("L"), boot("R"), 400.0, 1400.0)!!, 1e-9)
        assertEquals(1.0, BootAgreement.wedgeFraction(boot("L"), boot("R", sign = -1.0), 400.0, 1400.0)!!, 1e-9)
    }

    @Test fun wedgeIsNullWhenBothStayNeutral() {
        assertNull(BootAgreement.wedgeFraction(boot("L", peak = 1.0), boot("R", peak = 1.0), 300.0, 1500.0))
    }
}
