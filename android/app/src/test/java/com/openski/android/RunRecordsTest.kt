package com.openski.android

import org.junit.Assert.*
import org.junit.Test

class RunRecordsTest {
    private fun event(positive: Boolean = true, outside: Boolean = false, pitchOutside: Boolean = false, skipped: Boolean = false) =
        SkiEvent(7, 5000, 1800, if (positive) 20f else -20f, 100f, 15f, positive, outside, pitchOutside, skipped)

    @Test fun flagsUseTheSameBitsAsTheWireFrame() {
        assertEquals(0, skiEventFlags(event(positive = false)))
        assertEquals(1, skiEventFlags(event()))
        assertEquals(1 or 2 or 4 or 8, skiEventFlags(event(outside = true, pitchOutside = true, skipped = true)))
        assertEquals(2 or 8, skiEventFlags(event(positive = false, outside = true, skipped = true)))
    }

    @Test fun halfTurnCsvHasAHeaderElapsedTimeAndDecodedFlags() {
        val row = RunEventRow("R", 7, 5000, 1800, -20f, 123.4f, 15.25f, 2 or 8, 100_500)
        val lines = RunCsv.halfTurns(listOf(row), sessionStartedAtMs = 100_000).trim().lines()
        assertEquals(2, lines.size)
        assertTrue(lines[0].startsWith("side,sequence,boot_start_ms,received_at_ms,session_elapsed_ms,duration_ms,peak_roll_deg"))
        assertEquals("R,7,5000,100500,500,1800,-20.00,123.40,15.25,false,true,false,true", lines[1])
    }

    @Test fun verdictCsvLeavesAnAbsentBalanceEmpty() {
        val row = RunVerdictRow(103_000, "L", "POSITIVE", 91, 90, 92, 93, null, 2.0, 20.0, false)
        val lines = RunCsv.verdicts(listOf(row), sessionStartedAtMs = 100_000).trim().lines()
        assertEquals("time_ms,session_elapsed_ms,side,verdict,score,tempo,steadiness,depth,balance,mean_beat_s,mean_depth_deg,outside_envelope", lines[0])
        assertEquals("103000,3000,L,POSITIVE,91,90,92,93,,2.000,20.000,false", lines[1])
    }

    @Test fun anEmptyRunStillWritesTheHeaders() {
        assertEquals(1, RunCsv.halfTurns(emptyList(), 0).trim().lines().size)
        assertEquals(1, RunCsv.verdicts(emptyList(), 0).trim().lines().size)
    }
}
