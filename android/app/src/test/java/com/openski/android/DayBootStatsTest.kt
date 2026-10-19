package com.openski.android

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

class DayBootStatsTest {
    companion object {
        const val START = 1_768_036_000_000L

        /** The synthetic demo session (labelled synthetic in the app) with saved calibrations, marked as a sensor session for the test. */
        fun calibratedDemo(start: Long = START, origin: String = "sensor"): SessionData {
            val demo = DemoSession.generate("demo-$start", start)
            val timeline = SessionAnalysis.build(demo).timeline
            fun v(x: Vector3) = JSONArray(listOf(x.x, x.y, x.z))
            val json = JSONObject()
            listOf("L", "R").forEach { side ->
                val c = BootOrientation.calibrate(timeline, side, start.toDouble(), 0, 1)!!
                json.put(side, JSONObject().put("time_ms", c.timeMs).put("axis", c.axis).put("sign", c.sign)
                    .put("gravity", v(c.gravity)).put("bias", v(c.bias)).put("forward", c.forward?.let(::v) ?: JSONObject.NULL))
            }
            return demo.copy(session = demo.session.copy(calibrationJson = json.toString(), origin = origin))
        }
    }

    private fun session(id: String, start: Long, end: Long?, origin: String = "sensor") =
        SkiSession(id, start, end, 0, 0, null, null, origin = origin)

    @Test fun onlyRealOverlappingSessionsAreUsed() {
        val sessions = listOf(session("before", 0, 999), session("edge", 0, 1000), session("inside", 1500, 1800),
            session("open", 1900, null), session("demo", 1100, 1200, "synthetic"), session("after", 2000, 3000))
        assertEquals(listOf("inside", "open"), DayBootStats.overlapping(sessions, 1000, 2000, nowMs = 5000).map { it.id })
    }

    private fun events(id: String, start: Long, end: Long, movements: Map<String, List<Double>> = emptyMap(),
                       heading: Map<String, List<Double>> = emptyMap(), half: Map<String, List<Long>> = emptyMap(),
                       pairs: List<BootAgreementPair> = emptyList(), torso: List<TorsoTurnRow> = emptyList(), refusal: String? = null) =
        SessionBootEvents(id, start, end, movements, heading, half, pairs, torso, refusal)

    private fun torso(start: Double, gyro: Double?) = TorsoTurnRow("L", "LEFT", start, start + 500, 1.0, 1.0, gyro, gyro?.let { it / 100 },
        null, 20.0, null, null, null, null, TorsoTiming(0.0, 50.0, false), null, null)

    @Test fun aRangeCountsEventsStartingInsideIt() {
        val a = events("a", 0, 120_000,
            movements = mapOf("L" to listOf(5_000.0, 10_000.0, 59_999.0, 60_000.0), "R" to listOf(70_000.0)),
            heading = mapOf("L" to listOf(9_000.0)),
            half = mapOf("R" to listOf(1_000L, 30_000L, 60_000L)),
            pairs = listOf(BootAgreementPair(5_000.0, 6_000.0, 0.8, 0.9, 0.1), BootAgreementPair(8_000.0, 9_000.0, null, 0.7, Double.NaN),
                BootAgreementPair(70_000.0, 71_000.0, 0.1, 0.1, 0.1)),
            torso = listOf(torso(5_000.0, 40.0), torso(6_000.0, null), torso(7_000.0, 60.0), torso(90_000.0, 999.0)),
            refusal = null)
        val s = DayBootStats.forRange(listOf(a), 0, 60_000)!!
        assertEquals(1, s.sessions)
        assertEquals(60_000L, s.overlapMs)
        assertEquals(mapOf("L" to 3, "R" to 0), s.movements)   // the right boot has data, so 0, not missing
        assertEquals(mapOf("L" to 1), s.headingTurns)
        assertEquals(mapOf("R" to 2), s.halfTurns)
        assertEquals(2, s.agreementPairs)
        assertEquals(0.8, s.meanDerivativeSimilarity!!, 1e-12)
        assertEquals(0.8, s.meanRollCorrelation!!, 1e-12)
        assertEquals(0.1, s.meanWedgeFraction!!, 1e-12)   // NaN is not averaged in
        assertEquals(2, s.torsoTurns)
        assertEquals(50.0, s.meanTorsoGyroRmsDps!!, 1e-12)
        assertEquals(3.0, s.perMinute(3)!!, 1e-12)
        assertEquals(mapOf("R" to 2), s.turnCounts)
        assertEquals("half-turns", s.turnSource)
        assertTrue(s.hasNumbers)
    }

    @Test fun nothingOverlappingGivesNullAndNoNumbersAreInvented() {
        val a = events("a", 0, 10_000, movements = mapOf("L" to listOf(1_000.0)))
        assertNull(DayBootStats.forRange(listOf(a), 10_000, 20_000))
        assertNull(DayBootStats.forRange(emptyList(), 0, 20_000))
        assertNull(DayBootStats.forRange(listOf(a), 5_000, 5_000))
        // A session that overlaps but has no boot roll and refused torso metrics: no counts, no means, only notes.
        val bare = events("b", 0, 10_000, refusal = "refused: no phone motion rows")
        val s = DayBootStats.forRange(listOf(SessionBootEvents.failed("c", 0, 10_000, "boot analysis failed: x"), bare), 0, 10_000)!!
        assertEquals(2, s.sessions)
        assertTrue(s.movements.isEmpty() && s.headingTurns.isEmpty() && s.halfTurns.isEmpty())
        assertNull(s.meanRollCorrelation); assertNull(s.meanTorsoGyroRmsDps)
        assertEquals(listOf("boot analysis failed: x", "torso metrics: refused: no phone motion rows"), s.notes)
        assertTrue(!s.hasNumbers)
        assertNull("too little time for a cadence", s.perMinute(1).takeIf { s.overlapMs < DayBootStats.MIN_CADENCE_MS })
    }

    @Test fun coverageIsTheUnionOfSessionSpans() {
        val spans = listOf(events("a", 0, 30_000), events("b", 20_000, 50_000), events("c", 70_000, 200_000))
        assertEquals(50_000L + 30_000L, DayBootStats.coveredMs(spans, 0, 100_000))
        assertEquals(0L, DayBootStats.coveredMs(spans, 50_000, 70_000))
        val s = DayBootStats.forRange(spans, 10_000, 25_000)!!
        assertEquals(15_000L, s.overlapMs)
        assertNull(s.perMinute(5))   // under 20 s of coverage
    }

    @Test fun aRealAnalysisPipelineFeedsTheDayStats() {
        val data = calibratedDemo()
        val whole = SessionBootAnalysis.build(data, detect = DayBootStats::detectWholeSession)
        val trials = SessionBootAnalysis.build(data)
        // The demo's movements all lie inside its test-marker window, so both detectors agree on it.
        listOf("L", "R").forEach { side ->
            assertEquals(40, trials.detections.getValue(side).movements.size)
            assertEquals(trials.detections.getValue(side).movements, whole.detections.getValue(side).movements)
        }
        val ev = SessionBootEvents.from(whole, nowMs = START + 100_000)
        assertEquals(START + 66_000, ev.endMs)
        assertNotNull(ev.torsoRefusal)   // the demo has no phone motion rows
        val half = START + 34_000
        val first = DayBootStats.forRange(listOf(ev), START, half)!!
        val second = DayBootStats.forRange(listOf(ev), half, START + 66_000)!!
        listOf("L", "R").forEach { side ->
            val starts = whole.detections.getValue(side).movements.map { it.startMs }
            assertEquals(starts.count { it < half }, first.movements[side])
            assertEquals(40, first.movements.getValue(side) + second.movements.getValue(side))
            assertEquals(whole.headingTurns.getValue(side).size, first.headingTurns.getValue(side) + second.headingTurns.getValue(side))
        }
        assertEquals(whole.agreementPairs.size, first.agreementPairs + second.agreementPairs)
        assertTrue("left and right demo boots agree", first.meanRollCorrelation!! > 0.9)
        assertEquals(0, first.torsoTurns)
        assertTrue(first.notes.any { it.startsWith("torso metrics: refused") })
    }

    @Test fun aSessionWithoutCalibrationHasNoRollCountsButKeepsHalfTurns() {
        val demo = DemoSession.generate("plain", START)
        val run = RunData(listOf(RunEventRow("L", 1, 0, 800, 20f, 90f, 0f, 1, START + 5_000), RunEventRow("L", 2, 0, 800, -20f, 90f, 0f, 0, START + 6_000)),
            emptyList(), null)
        val ev = SessionBootEvents.from(SessionBootAnalysis.build(demo.copy(run = run), detect = DayBootStats::detectWholeSession), START + 100_000)
        val s = DayBootStats.forRange(listOf(ev), START, START + 66_000)!!
        assertTrue(s.movements.isEmpty())
        assertEquals(mapOf("L" to 2), s.halfTurns)
        assertTrue(s.notes.contains("no saved boot calibration, so no boot roll"))
    }

    @Test fun theSessionExportStillReadsTheSharedAnalysis() {
        val data = calibratedDemo()
        val out = ByteArrayOutputStream()
        SessionExport.write(data, out)
        val files = HashMap<String, String>()
        ZipInputStream(out.toByteArray().inputStream()).use { zip ->
            while (true) { val e = zip.nextEntry ?: break; files[e.name] = zip.readBytes().toString(Charsets.UTF_8) }
        }
        assertEquals(81, files.getValue("detected-movements.csv").trim().lines().size)
        val boot = SessionBootAnalysis.build(data)
        val agreement = JSONObject(files.getValue("analysis-summary.json")).getJSONObject("turn_metrics").getJSONObject("boot_agreement")
        assertEquals(boot.agreementPairs.size, agreement.getInt("pairs"))
        assertEquals(boot.turnMetricRows.values.sumOf { it.size } + 3, files.getValue("turn-metrics.csv").trim().lines().size)
    }
}
