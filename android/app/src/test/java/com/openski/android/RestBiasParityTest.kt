package com.openski.android

import org.junit.Assert.*
import org.junit.Test

/**
 * Replays the samples in rest_bias.txt (written by `tools/test_rest_bias.cpp --emit`) through the Kotlin
 * [RestBias] and compares its estimate and counters with the firmware's at each checkpoint. Regenerate with:
 * `g++ -std=gnu++17 -Ilib/Motion/src tools/test_rest_bias.cpp -o rb && ./rb --emit android/app/src/test/resources/rest_bias.txt`.
 */
class RestBiasParityTest {
    private class Checkpoint(val index: Int, val bias: DoubleArray, val updates: Int, val refused: Int)
    private class Series(val name: String) {
        val samples = ArrayList<DoubleArray?>()  // null = missing sample
        val checkpoints = ArrayList<Checkpoint>()
    }

    private fun load(): List<Series> {
        val text = javaClass.getResourceAsStream("/rest_bias.txt")!!.bufferedReader().readText()
        val out = ArrayList<Series>()
        var cur: Series? = null
        for (line in text.lineSequence()) {
            when {
                line.startsWith("series ") -> { cur = Series(line.substring(7).trim()); out.add(cur) }
                line == "x" -> cur!!.samples.add(null)
                line.startsWith("s ") ->
                    cur!!.samples.add(line.split(' ').drop(1).map { it.toDouble() }.toDoubleArray())
                line.startsWith("c ") -> {
                    val p = line.split(' ')
                    cur!!.checkpoints.add(Checkpoint(p[1].toInt(),
                        doubleArrayOf(p[2].toDouble(), p[3].toDouble(), p[4].toDouble()), p[5].toInt(), p[6].toInt()))
                }
            }
        }
        return out
    }

    @Test fun matchesTheFirmwareFixture() {
        val series = load()
        assertEquals(5, series.size)
        for (s in series) {
            val r = RestBias()
            val got = ArrayList<Checkpoint>()
            s.samples.forEachIndexed { i, m ->
                if (m != null) r.update(1000.0 + i * 10.0, Vector3(m[0], m[1], m[2]), Vector3(m[3], m[4], m[5]))
                if (i % 100 == 99)
                    got.add(Checkpoint(i, doubleArrayOf(r.estimate.x, r.estimate.y, r.estimate.z), r.updates, r.refused))
            }
            assertEquals("checkpoint count for ${s.name}", s.checkpoints.size, got.size)
            s.checkpoints.forEachIndexed { i, e ->
                val g = got[i]
                val tag = "${s.name} @${e.index}"
                assertEquals("$tag index", e.index, g.index)
                assertEquals("$tag updates", e.updates, g.updates)
                assertEquals("$tag refused", e.refused, g.refused)
                for (axis in 0..2) assertEquals("$tag bias[$axis]", e.bias[axis], g.bias[axis], 1e-4)
            }
        }
        // The fixture must exercise learning, refusal and a window that is never learned.
        assertTrue(series.any { s -> s.checkpoints.last().updates > 0 })
        assertTrue(series.any { s -> s.checkpoints.last().refused > 0 })
        assertTrue(series.any { s -> s.checkpoints.last().updates == 0 && s.checkpoints.last().refused == 0 })
    }
}
