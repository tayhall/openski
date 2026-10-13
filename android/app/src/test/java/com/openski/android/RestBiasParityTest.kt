package com.openski.android

import org.junit.Assert.*
import org.junit.Test

/**
 * Replays the samples in rest_bias.txt (written by `tools/test_rest_bias.cpp --emit`) through the Kotlin
 * [RestBias] and compares its estimate and counters with the firmware's at each checkpoint: every 100th sample
 * and every sample on which the update or refusal count changed, so the timing of each decision is compared
 * exactly. Regenerate with:
 * `g++ -std=gnu++17 -Ilib/Motion/src tools/test_rest_bias.cpp -o rb && ./rb --emit android/app/src/test/resources/rest_bias.txt`.
 * The fixture is only as current as that command: run `./rb --check <file>` after any change to `RestBias.h`.
 */
class RestBiasParityTest {
    private class Checkpoint(val index: Int, val bias: DoubleArray, val updates: Int, val refused: Int)
    private class Series(val name: String) {
        val samples = ArrayList<DoubleArray?>()  // null = missing sample
        val checkpoints = ArrayList<Checkpoint>()
        var ended = false
    }

    private fun load(): List<Series> {
        val text = javaClass.getResourceAsStream("/rest_bias.txt")!!.bufferedReader().readText()
        val out = ArrayList<Series>()
        var cur: Series? = null
        for (raw in text.lineSequence()) {
            val line = raw.trimEnd('\r')
            if (line.isEmpty()) continue
            when {
                line.startsWith("series ") -> {
                    check(cur == null || cur.ended) { "series ${cur?.name} has no end line" }
                    cur = Series(line.substring(7).trim()); out.add(cur)
                }
                line == "end" -> cur!!.ended = true
                line == "x" -> cur!!.samples.add(null)
                line.startsWith("s ") -> {
                    val v = line.split(' ').drop(1).map { it.toDouble() }
                    check(v.size == 6) { "bad sample line: $line" }
                    cur!!.samples.add(v.toDoubleArray())
                }
                line.startsWith("c ") -> {
                    val p = line.split(' ')
                    check(p.size == 7) { "bad checkpoint line: $line" }
                    cur!!.checkpoints.add(Checkpoint(p[1].toInt(),
                        doubleArrayOf(p[2].toDouble(), p[3].toDouble(), p[4].toDouble()), p[5].toInt(), p[6].toInt()))
                }
                else -> fail("unexpected line in rest_bias.txt: $line")
            }
        }
        check(cur == null || cur.ended) { "series ${cur?.name} has no end line" }
        return out
    }

    @Test fun matchesTheFirmwareFixture() {
        val series = load()
        assertEquals(13, series.size)
        for (s in series) {
            val r = RestBias()
            val got = ArrayList<Checkpoint>()
            var lastUpdates = 0
            var lastRefused = 0
            s.samples.forEachIndexed { i, m ->
                if (m != null) r.update(1000.0 + i * 10.0, Vector3(m[0], m[1], m[2]), Vector3(m[3], m[4], m[5]))
                val changed = r.updates != lastUpdates || r.refused != lastRefused
                lastUpdates = r.updates; lastRefused = r.refused
                if (i % 100 == 99 || changed)
                    got.add(Checkpoint(i, doubleArrayOf(r.estimate.x, r.estimate.y, r.estimate.z), r.updates, r.refused))
            }
            assertEquals("checkpoint count for ${s.name}", s.checkpoints.size, got.size)
            s.checkpoints.forEachIndexed { i, e ->
                val g = got[i]
                val tag = "${s.name} @${e.index}"
                assertEquals("$tag index", e.index, g.index)
                assertEquals("$tag updates", e.updates, g.updates)
                assertEquals("$tag refused", e.refused, g.refused)
                for (axis in 0..2) assertEquals("$tag bias[$axis]", e.bias[axis], g.bias[axis], 1e-5)
            }
        }
        fun last(name: String) = series.first { it.name == name }.checkpoints.last()
        // The fixture must exercise learning, refusal and a window that is never learned.
        assertTrue(series.any { s -> s.checkpoints.last().updates > 0 })
        assertTrue(series.any { s -> s.checkpoints.last().refused > 0 })
        assertTrue(series.any { s -> s.checkpoints.last().updates == 0 && s.checkpoints.last().refused == 0 })
        // Each limit has a series just inside it that learns and one just outside it that does not.
        for (limit in listOf("offset", "gyro_p2p", "accel_frac", "accel_p2p")) {
            val inside = if (limit == "offset") "offset_inside" else "${limit}_inside"
            val over = if (limit == "offset") "offset_over" else "${limit}_over"
            assertTrue("$inside should learn", last(inside).updates > 0)
            assertEquals("$over should not learn", 0, last(over).updates)
        }
        assertTrue("the gap must keep gap_in_window from completing a 2 s window early",
            last("gap_in_window").updates <= last("converge").updates)
    }
}
