package com.openski.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** GPX read and write. Not covered: very large files (the DOM parser holds the whole file), GPX 1.0 routes and waypoints. */
class GpxCodecTest {
    private fun expectFormatError(text: String) {
        try { GpxCodec.read(text); fail("expected GpxFormatException") } catch (_: GpxFormatException) {}
    }

    @Test fun rawFixesSurviveARoundTripUnchanged() {
        val fixes = listOf(
            GpsFix(1_768_035_600_000, 46.123456789, 7.000000001, 2400.25, 9.5, 4.5f, 6f),
            GpsFix(1_768_035_601_250, 46.12346, 7.0000123, null, null, 12f, null),
            GpsFix(1_768_035_602_000, -0.00001, -179.99999, -3.5, 0.0, 3f, 2.5f),
        )
        val read = GpxCodec.read(GpxCodec.write(fixes, "Day <one> & \"two\""))
        assertEquals(fixes, read.fixes)
        assertEquals("Day <one> & \"two\"", read.name)
        assertEquals(0, read.skippedPoints)
    }

    @Test fun aSyntheticDayRoundTripsAndSplitsTheSame() {
        val day = SyntheticSkiDay.build(SyntheticSkiDay.FULL_DAY)
        val back = GpxCodec.read(GpxCodec.write(day.fixes)).fixes
        assertEquals(day.fixes, back)
        assertEquals(SkiDaySplitter.split(day.fixes).segments, SkiDaySplitter.split(back).segments)
    }

    @Test fun aForeignFileWithPrefixesExtensionsAndNoElevationIsRead() {
        val text = """<?xml version="1.0" encoding="UTF-8"?>
            <g:gpx xmlns:g="http://www.topografix.com/GPX/1/1" xmlns:x="urn:example:ext" version="1.1" creator="someone">
              <g:metadata><g:name>ignored</g:name></g:metadata>
              <g:trk><g:name>Morning</g:name>
                <g:trkseg>
                  <g:trkpt lat="46.5" lon="7.5"><g:ele>2000.5</g:ele><g:time>2026-01-10T10:00:00+01:00</g:time>
                    <g:extensions><x:hr>120</x:hr><x:speed>99</x:speed></g:extensions></g:trkpt>
                  <g:trkpt lat="46.5001" lon="7.5"><g:time>2026-01-10T09:00:01.500Z</g:time></g:trkpt>
                </g:trkseg>
                <g:trkseg>
                  <g:trkpt lat=" 46.5002 " lon="7.5"><g:ele>not a number</g:ele><g:time>2026-01-10T09:00:03</g:time></g:trkpt>
                  <g:trkpt lat="46.5003" lon="7.5"></g:trkpt>
                  <g:trkpt lat="200" lon="7.5"><g:time>2026-01-10T09:00:05Z</g:time></g:trkpt>
                  <g:trkpt lon="7.5"><g:time>2026-01-10T09:00:06Z</g:time></g:trkpt>
                </g:trkseg>
              </g:trk>
            </g:gpx>"""
        val read = GpxCodec.read(text)
        assertEquals("Morning", read.name)
        assertEquals(3, read.fixes.size)
        assertEquals(3, read.skippedPoints)
        assertEquals(1, read.assumedUtcPoints)
        val t0 = 1_768_035_600_000L
        assertEquals(listOf(t0, t0 + 1500, t0 + 3000), read.fixes.map { it.timeMs })
        assertEquals(2000.5, read.fixes[0].altM!!, 1e-9)
        assertNull(read.fixes[1].altM)
        assertNull(read.fixes[2].altM)
        // A foreign extension's speed is not ours: ignored.
        assertNull(read.fixes[0].speedMps)
        assertEquals(GpxCodec.ASSUMED_ACCURACY_M, read.fixes[0].accuracyM, 0f)
        // Imported points pass the usual filter.
        assertEquals(3, RunTrack.usable(read.fixes).size)
    }

    @Test fun gpx10WithoutNamespacePrefixAndWithSpeedIsRead() {
        val text = """<gpx version="1.0" xmlns="http://www.topografix.com/GPX/1/0"><trk><trkseg>
            <trkpt lat="1" lon="2"><time>2026-01-10T09:00:00Z</time><speed>4.5</speed></trkpt></trkseg></trk></gpx>"""
        val f = GpxCodec.read(text).fixes.single()
        assertEquals(4.5, f.speedMps!!, 1e-9)
    }

    @Test fun malformedOrUselessFilesAreRejected() {
        expectFormatError("")
        expectFormatError("not xml at all")
        expectFormatError("<gpx><trk><trkseg><trkpt lat=\"1\" lon=\"2\">")
        expectFormatError("<kml xmlns=\"http://www.opengis.net/kml/2.2\"/>")
        expectFormatError("<gpx version=\"1.1\"><trk><trkseg><trkpt lat=\"1\" lon=\"2\"><ele>3</ele></trkpt></trkseg></trk></gpx>")
        expectFormatError("<gpx version=\"1.1\"><trk><trkseg><trkpt lat=\"1\" lon=\"2\"><time>yesterday</time></trkpt></trkseg></trk></gpx>")
        expectFormatError("<gpx version=\"1.1\"/>")
    }

    @Test fun documentTypeDeclarationsAreRefused() {
        // An external entity must never be resolved; the JDK parser refuses the declaration outright.
        expectFormatError("""<?xml version="1.0"?><!DOCTYPE gpx [<!ENTITY e SYSTEM "file:///etc/hosts">]>
            <gpx version="1.1"><trk><name>&e;</name><trkseg><trkpt lat="1" lon="2"><time>2026-01-10T09:00:00Z</time></trkpt></trkseg></trk></gpx>""")
    }

    @Test fun writeSkipsNonFiniteOptionalFields() {
        val text = GpxCodec.write(listOf(GpsFix(1000, 1.0, 2.0, Double.NaN, Double.POSITIVE_INFINITY, Float.NaN, Float.NaN)))
        assertTrue(!text.contains("NaN") && !text.contains("Infinity"))
        val f = GpxCodec.read(text).fixes.single()
        assertNull(f.altM)
        assertNull(f.speedMps)
        assertNull(f.verticalAccuracyM)
    }
}
