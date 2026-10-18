package com.openski.android

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class TurnMetricsExportTest {
    private val leanMs = 1200.0

    private fun boot(side: String, peak: Double = 30.0, sign: Double = 1.0, gapFrom: Double = -1.0, gapTo: Double = -1.0): List<BootRoll> =
        (0..200).map { i ->
            val t = i * 10.0
            val u = t - 300.0
            val inLean = u in 0.0..leanMs
            val deg = if (inLean) peak * sin(PI * u / leanMs) else 0.0
            val rate = if (inLean) Math.toRadians(peak * PI / leanMs * 1000 * cos(PI * u / leanMs)) else 0.0
            BootRoll(side, t, sign * deg, 0.0, rollRateRadps = sign * rate)
        }.filterNot { it.timeMs in gapFrom..gapTo }

    private val move = DryMovement(350.0, 1450.0, true, 30.0)

    @Test fun rowsCarryDirectionAndUsableFlag() {
        val rows = TurnMetricsExport.rows("L", boot("L"), listOf(move), emptyList(), positiveLeft = true)
        assertEquals(1, rows.size)
        assertEquals("LEFT", rows[0].direction)
        assertTrue(rows[0].usable)
        assertNotNull(rows[0].metrics.buildMs)
        assertEquals("RIGHT", TurnMetricsExport.rows("L", boot("L"), listOf(move), emptyList(), positiveLeft = false)[0].direction)
    }

    @Test fun gapMakesRowUnusableAndBlankInCsv() {
        val rows = TurnMetricsExport.rows("R", boot("R", gapFrom = 700.0, gapTo = 900.0), listOf(move), emptyList(), true)
        assertFalse(rows[0].usable)
        assertNull(rows[0].metrics.buildMs)
        val line = TurnMetricsExport.csv(rows).lines().first { !it.startsWith("#") && it.startsWith("R,") }
        val cells = line.split(",")
        assertEquals(TurnMetricsExport.HEADER.size, cells.size)
        assertEquals("false", cells[TurnMetricsExport.HEADER.indexOf("usable")])
        assertEquals("", cells[TurnMetricsExport.HEADER.indexOf("build_ms")])
        assertEquals("experimental_turn_metrics", cells.last())
    }

    /** [boot] with a yaw trace that turns during the lean, so the yaw descriptors have something to measure. */
    private fun withYaw(rolls: List<BootRoll>): List<BootRoll> {
        var yaw = 0.0
        return rolls.mapIndexed { i, r ->
            val u = r.timeMs - 300.0
            val rate = if (u in 0.0..leanMs) 0.8 * sin(PI * u / leanMs) else 0.0
            if (i > 0) yaw += Math.toDegrees(rate) * (r.timeMs - rolls[i - 1].timeMs) / 1000.0
            r.copy(yawDegrees = yaw, yawRateRadps = rate, headingRateRadps = rate)
        }
    }

    @Test fun yawDescriptorsAreBlankWhenTheRowIsNotUsable() {
        val usable = TurnMetricsExport.rows("L", withYaw(boot("L")), listOf(move), emptyList(), true)[0]
        assertTrue(usable.usable)
        assertNotNull(usable.yawTurnShape)
        val gap = TurnMetricsExport.rows("R", withYaw(boot("R", gapFrom = 700.0, gapTo = 900.0)), listOf(move), emptyList(), true)[0]
        assertFalse(gap.usable)
        assertNull(gap.yawTurnShape)
        assertNull(gap.yawTurnClosureDegrees)
        assertNull(gap.yawLeadMs)
    }

    @Test fun csvStartsWithExperimentalCommentsThenHeader() {
        val lines = TurnMetricsExport.csv(emptyList()).lines()
        assertTrue(lines[0].startsWith("# EXPERIMENTAL"))
        assertEquals(TurnMetricsExport.HEADER.joinToString(","), lines.first { !it.startsWith("#") })
    }

    @Test fun identicalBootsAgreeAndMirroredPolarityIsNormalised() {
        val l = boot("L")
        val same = TurnMetricsExport.agreementPairs(l, boot("R"), listOf(move), listOf(move), true, true)
        assertEquals(1, same.size)
        assertEquals(1.0, same[0].derivativeSimilarity!!, 1e-9)
        assertEquals(1.0, same[0].rollCorrelation!!, 1e-9)
        assertEquals(0.0, same[0].wedgeFraction!!, 1e-9)
        // Right boot recorded with opposite polarity and saved as positive_left=false: same physical lean.
        val flipped = TurnMetricsExport.agreementPairs(l, boot("R", sign = -1.0), listOf(move), listOf(move), true, false)
        assertEquals(1.0, flipped[0].rollCorrelation!!, 1e-9)
        assertEquals(0.0, flipped[0].wedgeFraction!!, 1e-9)
    }

    @Test fun unpairedOrGappyBootsGiveNoDataNotCrash() {
        assertTrue(TurnMetricsExport.agreementPairs(boot("L"), boot("R"), listOf(move), emptyList(), true, true).isEmpty())
        val gappy = TurnMetricsExport.agreementPairs(boot("L"), boot("R", gapFrom = 700.0, gapTo = 900.0), listOf(move), listOf(move), true, true)
        assertNull(gappy[0].rollCorrelation)
        val block = TurnMetricsExport.block(mapOf("L" to emptyList(), "R" to emptyList()), gappy)
        @Suppress("UNCHECKED_CAST") val agreement = block["boot_agreement"] as Map<String, Any?>
        assertEquals(1, agreement["pairs"]); assertEquals(0, agreement["pairs_with_data"])
        assertNull(agreement["mean_wedge_fraction"])
    }

    @Test fun blockIsLabelledExperimentalAndSummarisesPerBoot() {
        val moves = listOf(move, DryMovement(350.0, 1450.0, true, 30.0))
        val rows = mapOf("L" to TurnMetricsExport.rows("L", boot("L"), moves, emptyList(), true), "R" to emptyList())
        val block = TurnMetricsExport.block(rows, emptyList())
        assertEquals(true, block["experimental"])
        assertTrue((block["note"] as String).contains("unvalidated"))
        @Suppress("UNCHECKED_CAST") val perBoot = block["per_boot"] as Map<String, Map<String, Any?>>
        assertEquals(2, perBoot.getValue("L")["candidates"])
        assertEquals(0, perBoot.getValue("R")["candidates"])
        assertNull(perBoot.getValue("R")["peak_deg"])
        assertNotNull(perBoot.getValue("L")["peak_deg"])
    }
}
