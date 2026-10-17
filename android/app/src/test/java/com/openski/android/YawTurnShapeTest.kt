package com.openski.android

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class YawTurnShapeTest {
    private val dtMs = 10.0

    /** 100 Hz samples over 0..[durationMs]; yaw degrees are the integral of [yawRate] (rad/s). */
    private fun rolls(durationMs: Double, yawRate: (Double) -> Double, roll: (Double) -> Double = { 0.0 }): List<BootRoll> {
        var yaw = 0.0
        return (0..(durationMs / dtMs).toInt()).map { i ->
            val t = i * dtMs
            val r = yawRate(t)
            yaw += Math.toDegrees(r) * dtMs / 1000
            BootRoll("L", t, roll(t), 0.0, yawDegrees = yaw, yawRateRadps = r)
        }
    }

    private fun gauss(t: Double, centre: Double, sd: Double) = exp(-((t - centre) / sd).pow(2) / 2)

    @Test fun constantYawRateGivesShapeNearOne() {
        val r = rolls(1000.0, { 1.0 })
        assertEquals(1.0, YawTurnShape.turnShape(r, 0.0, 1000.0)!!, 0.01)
    }

    @Test fun singleNarrowPulseGivesMuchLargerShape() {
        val r = rolls(1000.0, { gauss(it, 500.0, 30.0) })
        assertTrue(YawTurnShape.turnShape(r, 0.0, 1000.0)!! > 4.0)
    }

    @Test fun zeroYawRateGivesNullShape() {
        assertNull(YawTurnShape.turnShape(rolls(1000.0, { 0.0 }), 0.0, 1000.0))
    }

    @Test fun frontLoadedYawHasLargerClosureThanBackLoaded() {
        val front = rolls(1000.0, { if (it < 500.0) 2.0 else 0.2 })
        val back = rolls(1000.0, { if (it < 500.0) 0.2 else 2.0 })
        val f = YawTurnShape.turnClosure(front, 0.0, 1000.0)!!
        val b = YawTurnShape.turnClosure(back, 0.0, 1000.0)!!
        assertTrue("$f vs $b", f > b * 3)
    }

    @Test fun yawLeadIsNegativeWhenRollPeaksFirst() {
        val r = rolls(1000.0, { gauss(it, 600.0, 80.0) }, { 30 * gauss(it, 400.0, 80.0) })
        assertEquals(-200.0, YawTurnShape.yawLeadMs(r, 0.0, 1000.0)!!, 15.0)
    }

    @Test fun yawLeadIsPositiveWhenRollPeaksLater() {
        val r = rolls(1000.0, { gauss(it, 400.0, 80.0) }, { 30 * gauss(it, 600.0, 80.0) })
        assertEquals(200.0, YawTurnShape.yawLeadMs(r, 0.0, 1000.0)!!, 15.0)
    }

    @Test fun gapsAndEmptyGiveNull() {
        val r = rolls(1000.0, { 1.0 })
        val gapped = r.filter { it.timeMs !in 300.0..500.0 }
        assertNull(YawTurnShape.turnShape(gapped, 0.0, 1000.0))
        assertNull(YawTurnShape.turnClosure(gapped, 0.0, 1000.0))
        assertNull(YawTurnShape.yawLeadMs(gapped, 0.0, 1000.0))
        assertNull(YawTurnShape.turnShape(emptyList(), 0.0, 1000.0))
        assertNull(YawTurnShape.turnClosure(r, 0.0, 50.0))
    }

    @Test fun windowMissingItsStartOrEndIsNull() {
        val rolls = (0..100).map { BootRoll("L", it * 10.0, 0.0, 0.0, yawRateRadps = 1.0, yawDegrees = it.toDouble()) }
        assertNull(YawTurnShape.turnShape(rolls, 0.0, 2000.0))
        assertNull(YawTurnShape.turnClosure(rolls, -500.0, 1000.0))
        assertNotNull(YawTurnShape.turnShape(rolls, 0.0, 1000.0))
    }

    @Test fun closureAcrossASegmentChangeIsNull() {
        val rolls = (0..100).map { BootRoll("L", it * 10.0, 0.0, 0.0, segment = if (it < 50) 0 else 1, yawDegrees = it.toDouble()) }
        assertNull(YawTurnShape.turnClosure(rolls, 0.0, 1000.0))
        assertNotNull(YawTurnShape.turnClosure(rolls.filter { it.segment == 0 }, 0.0, 490.0))
    }
}
