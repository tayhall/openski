package com.openski.android

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class HeadingTurnDetectorTest {
    private class Series(val name: String) {
        val rates = ArrayList<Double?>()  // null = missing sample
        val turns = ArrayList<DoubleArray>()
    }

    private fun load(): List<Series> {
        val text = javaClass.getResourceAsStream("/heading_turns.txt")!!.bufferedReader().readText()
        val out = ArrayList<Series>()
        var cur: Series? = null
        for (line in text.lines()) {
            when {
                line.startsWith("series ") -> { cur = Series(line.substring(7).trim()); out.add(cur) }
                line.startsWith("rates_dps") ->
                    line.substring(9).trim().split(' ').filter { it.isNotEmpty() }
                        .forEach { cur!!.rates.add(if (it == "x") null else it.toDouble()) }
                line.startsWith("turn ") -> {
                    val p = line.split(' ')
                    cur!!.turns.add(doubleArrayOf(p[1].toDouble(), p[2].toDouble(), p[3].toDouble(),
                        p[4].toDouble(), p[5].toDouble()))
                }
            }
        }
        return out
    }

    private fun run(rates: List<Double?>): List<HeadingTurn> {
        val d = HeadingTurnDetector()
        val out = ArrayList<HeadingTurn>()
        rates.forEachIndexed { i, dps ->
            if (dps == null) return@forEachIndexed
            val rad = (dps.toFloat() / 57.2957795f).toDouble()
            d.accept(1000.0 + i * 10.0, rad)?.let { out.add(it) }
        }
        return out
    }

    @Test fun matchesTheFirmwareFixture() {
        val series = load()
        assertEquals(4, series.size)
        for (s in series) {
            val got = run(s.rates)
            assertEquals("turn count for ${s.name}", s.turns.size, got.size)
            s.turns.forEachIndexed { i, e ->
                val g = got[i]
                val tag = "${s.name} #$i"
                assertEquals("$tag start", e[0], g.startMs, 1.0)
                assertEquals("$tag end", e[1], g.endMs, 1.0)
                assertEquals("$tag sign", e[2] > 0.5, g.positive)
                assertEquals("$tag peak", e[3], g.peakRateDps, 0.2)
                assertEquals("$tag change", e[4], g.changeDegrees, 0.5)
            }
        }
        assertTrue("fixture has turns", series.sumOf { it.turns.size } > 20)
    }

    @Test fun standingStillEmitsNothing() {
        val d = HeadingTurnDetector()
        var seed = 7L
        var count = 0
        for (i in 0 until 6000) {
            seed = (seed * 1664525L + 1013904223L) and 0xffffffffL
            val dps = ((seed shr 16) and 0xff).toInt().minus(128) / 128.0
            if (d.accept(i * 10.0, Math.toRadians(dps)) != null) count++
        }
        assertEquals(0, count)
    }

    @Test fun breakSegmentDoesNotSpanTheBreak() {
        val d = HeadingTurnDetector()
        val turns = ArrayList<HeadingTurn>()
        var t = 0.0
        // 1 s of a rising lobe, then break, then quiet.
        for (i in 0 until 100) {
            d.accept(t, Math.toRadians(50.0)); t += 10.0
        }
        d.breakSegment()
        for (i in 0 until 500) {
            d.accept(t, 0.0)?.let { turns.add(it) }; t += 10.0
        }
        assertTrue(turns.isEmpty())
    }
}
