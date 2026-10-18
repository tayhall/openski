package com.openski.android

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

class SessionHealthTest {
    private val session = SkiSession("s", 0, 100_000, 0, 0, null, null)
    private fun sample(t: Long, q: Int = 0) = SensorSample(0, t, 0f, 0f, 9.81f, 0f, 0f, 0f, q)
    private fun data(live: List<StoredLive> = emptyList(), flash: List<StoredFlash> = emptyList(),
        captures: List<SensorCapture> = emptyList()) = SessionData(session, live, flash, captures, emptyList())
    /** 50 Hz live samples; [skip] drops sample indices, [skewPpm] makes the phone clock run fast. */
    private fun live(side: String = "L", count: Int = 6000, skip: Set<Int> = emptySet(), skewPpm: Double = 0.0,
        quality: (Int) -> Int = { 0 }) =
        (0 until count).filter { it !in skip }.map {
            StoredLive(side, 1_000_000L + Math.round(it * 20.0 * (1 + skewPpm * 1e-6)), sample(it * 20L, quality(it)))
        }
    private fun capture(side: String = "L", dropped: Int = 0) = SensorCapture("c$side", "s", side, "a", 0, "done", 0, dropped, "")
    private fun flash(c: SensorCapture, count: Int = 3000, skip: Set<Int> = emptySet(), quality: (Int) -> Int = { 0 }) =
        (0 until count).filter { it !in skip }.map {
            StoredFlash(c, it, FlashRecord(it * 10_000L, sample(it * 10L, quality(it)), ByteArray(16)))
        }
    private fun stream(report: SessionHealthReport, side: String, source: String) =
        report.boots.first { it.side == side }.streams.first { it.source == source }

    @Test fun cleanLiveStreamIsUsable() {
        val s = stream(SessionHealth.build(data(live())), "L", "live")
        assertEquals(50.0, s.rateHz!!, 0.01)
        assertEquals(0, s.gapCount)
        assertEquals(0.0, s.intervalSdMs!!, 1e-9)
        assertEquals(0.0, s.accelRailPercent, 0.0)
        assertEquals(HealthVerdict.USABLE, s.verdict)
        assertEquals(0.0, s.clockSlopePpm!!, 5.0)
    }
    @Test fun countsGapsOverOnePointFiveIntervals() {
        // Removing one sample leaves a 40 ms interval (gap, 20 ms lost); removing three leaves 80 ms (60 ms lost).
        val s = stream(SessionHealth.build(data(live(skip = setOf(100, 500, 501, 502)))), "L", "live")
        assertEquals(2, s.gapCount)
        assertEquals(80.0, s.gapTotalMs, 1e-6)
        assertEquals(80.0, s.longestGapMs, 1e-6)
        assertTrue(s.rateHz!! < 50.0)
    }
    @Test fun intervalOfExactlyOnePointFiveIsNotAGap() {
        val times = (0 until 400).map { if (it < 200) it * 20L else it * 20L + 10 }
        val l = times.map { StoredLive("L", 1_000_000 + it, sample(it)) }
        assertEquals(0, stream(SessionHealth.build(data(l)), "L", "live").gapCount)
    }
    @Test fun railFlagsGiveClippingShares() {
        val s = stream(SessionHealth.build(data(live(quality = { if (it < 60) 1 else if (it < 90) 2 else 0 }))), "L", "live")
        assertEquals(1.0, s.accelRailPercent, 1e-9)
        assertEquals(0.5, s.gyroRailPercent, 1e-9)
        assertEquals(HealthVerdict.MARGINAL, s.verdict)
        val bad = stream(SessionHealth.build(data(live(quality = { if (it % 10 == 0) 1 else 0 }))), "L", "live")
        assertEquals(HealthVerdict.POOR, bad.verdict)
    }
    @Test fun flashStaysSeparateAndCountsDroppedFlags() {
        val c = capture(dropped = 7)
        val report = SessionHealth.build(data(live(), flash(c, skip = setOf(10), quality = { if (it == 11) 16 else 0 }), listOf(c)))
        val f = stream(report, "L", "flash")
        assertEquals(1, f.droppedFlagCount)
        assertEquals(1, f.gapCount)
        assertEquals(100.0, f.rateHz!!, 0.1)
        assertEquals(2999, f.samples)
        assertEquals(0, stream(report, "L", "live").droppedFlagCount)
        assertEquals(7, report.boots.first().captureDroppedSamples)
    }
    @Test fun flashTimestampWrapIsNotAGap() {
        val c = capture()
        val base = 0x1_0000_0000L - 5 * 10_000L
        val records = (0 until 300).map { StoredFlash(c, it, FlashRecord((base + it * 10_000L) and 0xffff_ffffL, sample(it * 10L), ByteArray(16))) }
        assertEquals(0, stream(SessionHealth.build(data(flash = records, captures = listOf(c))), "L", "flash").gapCount)
    }
    @Test fun clockSlopeMatchesPhoneClockSkew() {
        val s = stream(SessionHealth.build(data(live(skewPpm = 500.0))), "L", "live")
        assertEquals(500.0, s.clockSlopePpm!!, 15.0)
        assertEquals(HealthVerdict.MARGINAL, s.verdict)
        val poor = stream(SessionHealth.build(data(live(skewPpm = -3000.0))), "L", "live")
        assertEquals(HealthVerdict.POOR, poor.verdict)
    }
    @Test fun shortRecordingHasNoSlopeAndIsPoor() {
        val s = stream(SessionHealth.build(data(live(count = 50))), "L", "live")
        assertNull(s.clockSlopePpm)
        assertEquals(HealthVerdict.POOR, s.verdict)
    }
    @Test fun sessionVerdictIsWorstBootAndBootIsBestSource() {
        val c = capture("R")
        val gappy = (100..1500).toSet()
        val report = SessionHealth.build(data(live("L", skip = gappy) + live("R", skip = gappy), flash(c), listOf(c)))
        assertEquals(HealthVerdict.POOR, stream(report, "L", "live").verdict)
        assertEquals(HealthVerdict.POOR, report.boots.first { it.side == "L" }.verdict)
        assertEquals(HealthVerdict.USABLE, report.boots.first { it.side == "R" }.verdict)
        assertEquals(HealthVerdict.POOR, report.verdict)
    }
    @Test fun emptySessionIsPoorAndRawSamplesAreNotModified() {
        assertEquals(HealthVerdict.POOR, SessionHealth.build(data()).verdict)
        val l = live()
        val copy = l.map { it.copy() }
        SessionHealth.build(data(l))
        assertEquals(copy, l)
    }
    @Test fun jsonParsesAndCarriesFigures() {
        val c = capture()
        val json = JSONObject(SessionHealth.build(data(live(skip = setOf(5)), flash(c), listOf(c))).toJson())
        assertEquals(1, json.getInt("format_version"))
        assertTrue(json.getString("temperature").startsWith("Not available"))
        val live = json.getJSONArray("boots").getJSONObject(0).getJSONArray("streams").getJSONObject(0)
        assertEquals("live", live.getString("source"))
        assertEquals(1, live.getInt("gap_count"))
        assertTrue(json.has("thresholds_starting_values"))
    }
    @Test fun exportZipContainsSessionHealth() {
        val l = live("L", 500) + live("R", 500)
        val out = ByteArrayOutputStream()
        SessionExport.write(SessionData(session.copy(), l, emptyList(), emptyList(), emptyList()), out)
        val names = mutableMapOf<String, String>()
        ZipInputStream(out.toByteArray().inputStream()).use { z ->
            generateSequence { z.nextEntry }.forEach { names[it.name] = z.readBytes().toString(Charsets.UTF_8) }
        }
        assertTrue(names.containsKey("session-health.json"))
        assertTrue(JSONObject(names.getValue("session-health.json")).has("verdict"))
        assertTrue(names.containsKey("analysis-summary.json"))
    }
}
