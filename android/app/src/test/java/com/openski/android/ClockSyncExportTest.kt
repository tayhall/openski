package com.openski.android

import org.junit.Assert.*
import org.junit.Test

class ClockSyncExportTest {
    private val ms = ClockSyncDefaults.MS
    private val t0 = 5_000_000_000_000L
    private fun session() = SkiSession("s", 1_000_000L, 1_100_000L, 0, 0, null, null)
    private fun pairs(side: String, offsetNs: Long) = (0 until 8).map {
        val send = t0 + it * 100 * ms
        StoredClockPair(side, it, send, send + 30 * ms, (send + 15 * ms - offsetNs) / 1000, 30 * ms, true)
    }
    private fun data(pairs: List<StoredClockPair>, mag: List<StoredMag> = emptyList(), events: List<Pair<Long, String>> = emptyList()) =
        SessionData(session(), emptyList(), emptyList(), emptyList(), events, clockPairs = pairs, mag = mag)
    private val header = "side,epoch,token,send_ns,recv_ns,sensor_us,rtt_ns,accepted,residual_ns,clock_uncertain"

    @Test fun csvKeepsRawPairsAndAddsDerivedColumns() {
        val rows = ClockSyncExport.clockCsv(data(pairs("L", t0 - 50_000_000_000L))).trim().lines()
        assertEquals(header, rows[0])
        assertEquals(9, rows.size)
        val first = rows[1].split(",")
        assertEquals(listOf("L", "0", "0", "$t0", "${t0 + 30 * ms}"), first.take(5))
        assertEquals("30000000", first[6])
        assertEquals("true", first[7])
        assertEquals("", first[8])   // nothing to compare the first pair with
        assertEquals("false", first[9])
    }

    @Test fun fitJsonReportsDriftUncertaintyAndBootToBoot() {
        val json = ClockSyncExport.fitJson(data(pairs("L", t0 - 50_000_000_000L) + pairs("R", t0 - 120_000_000_000L)))
        val left = json.getJSONObject("L")
        assertTrue(left.getBoolean("available"))
        assertEquals(0.0, left.getJSONArray("epochs").getJSONObject(0).getDouble("drift_ppm"), 1e-9)
        assertTrue(left.getJSONArray("epochs").getJSONObject(0).getLong("uncertainty_at_anchor_ns") >= 15 * ms)
        assertEquals(0, left.getJSONArray("clock_uncertain_stretches").length())
        assertTrue(json.getJSONObject("boot_to_boot").getBoolean("available"))
        // The right boot started 70 s earlier than the left one, so its sensor clock reads 70 s more.
        assertEquals(70_000_000.0, json.getJSONObject("boot_to_boot").getDouble("right_minus_left_us"), 5_000.0)
    }

    @Test fun missingPairsAreReportedAsUnavailable() {
        val json = ClockSyncExport.fitJson(data(emptyList()))
        assertFalse(json.getJSONObject("L").getBoolean("available"))
        assertFalse(json.getJSONObject("boot_to_boot").getBoolean("available"))
        assertEquals(header, ClockSyncExport.clockCsv(data(emptyList())).trim())
    }

    @Test fun magnetometerCsvWritesRawCountsAndGaussAndMarksThemUncalibrated() {
        val csv = ClockSyncExport.magnetometerCsv(data(emptyList(), listOf(StoredMag("L", 500, 1_000_250, 6842, -3421, 0, 1)))).trim().lines()
        assertEquals(ClockSyncExport.MAG_HEADER.trim(), csv[0])
        val row = csv[1].split(",")
        assertEquals(listOf("L", "500", "1000250", "250", "6842", "-3421", "0"), row.take(7))
        assertEquals(1.0, row[7].toDouble(), 1e-9)
        assertEquals(-0.5, row[8].toDouble(), 1e-3)
        assertEquals(listOf("1", "true", "false", "uncalibrated"), row.takeLast(4))
    }

    @Test fun clockReferenceIsReadFromTheSessionEvent() {
        val ref = ClockSyncExport.reference(listOf(5L to "other", 6L to "clock_ref wall_ms=1700000000000 elapsed_ns=123456789"))!!
        assertEquals(1_700_000_000_000L, ref.wallMs)
        assertEquals(123_456_789L + 5_000_000L, ref.wallToElapsedNs(1_700_000_000_005L))
        assertEquals(1_700_000_000_005L, ref.elapsedToWallMs(123_456_789L + 5_000_000L))
        assertNull(ClockSyncExport.reference(emptyList()))
    }

    @Test fun liveGapsFromStoredSamplesMarkStretchesWhenAReferenceExists() {
        val ref = "clock_ref wall_ms=1000000 elapsed_ns=$t0"
        val live = (0 until 30).map { i ->
            // 100 samples lost after sample 14, with 600 ms of silence.
            val seq = if (i < 15) i else i + 100
            val wall = 1_000_000L + 2_000 + i * 20 + (if (i >= 15) 600 else 0)
            StoredLive("L", wall, SensorSample(seq, wall, 0f, 0f, 9.8f, 0f, 0f, 0f))
        }
        val base = data(pairs("L", t0 - 50_000_000_000L), events = listOf(1L to ref))
        val syncs = ClockSyncExport.replay(base.copy(live = live))
        assertEquals(1, syncs.getValue("L").stretches.size)
        assertTrue(syncs.getValue("L").stretches.single().startNs > t0 + 2 * ClockSyncDefaults.S)
        assertEquals(0, ClockSyncExport.replay(base).getValue("L").stretches.size)
    }
}
