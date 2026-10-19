package com.openski.android

import org.junit.Assert.*
import org.junit.Test

class PracticeRecordsTest {
    @Test fun legacyImportRetainsIdenticalLookingAttemptsWithStableDistinctIds() {
        val row = """{"drill":"slow-rolls","time":1000,"score":90,"stars":3,"vert":180,"demo":true}"""
        val first = LegacyPractice.decode("[$row,$row]")
        assertEquals(first, LegacyPractice.decode("[$row,$row]"))
        assertNotEquals(first[0].id, first[1].id)
        assertTrue(first.all { it.demo && it.score == 90 && it.vert == 180 && it.target == null && it.sessionId == null })
    }

    @Test fun malformedLegacyHistoryIsNotSilentlyDropped() {
        listOf("not json", "[null]", "[{}]", "[42]").forEach { json ->
            assertTrue(json, runCatching { LegacyPractice.decode(json) }.isFailure)
        }
    }

    @Test fun newTargetsAreSnapshotsAndLegacyTargetsAreUnknown() {
        val drill = Programme.drills.first()
        assertEquals(PracticeTarget(drill.movements, drill.paceSeconds, drill.depthDegrees), PracticeTarget.of(drill))
        assertNull(Attempt(drill.id, 1, 90, 3, 180, false).target)
        assertTrue(runCatching { PracticeTarget(10, Double.NaN, 20.0) }.isFailure)
    }
}
