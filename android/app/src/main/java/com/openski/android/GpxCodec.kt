package com.openski.android

import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import javax.xml.parsers.DocumentBuilderFactory

/** A GPX file that cannot be read as a track: not XML, or no track point with a usable time. */
class GpxFormatException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

/** What a GPX read produced. [skippedPoints] lacked a valid position or time; [assumedUtcPoints] had a time with no zone. */
data class GpxRead(val fixes: List<GpsFix>, val name: String?, val skippedPoints: Int, val assumedUtcPoints: Int)

/**
 * Reads and writes GPX 1.1 tracks with the JDK/Android DOM parser (available to plain JVM unit tests and on Android).
 * Reading tolerates namespaces, missing elevation and unknown extensions. Writing keeps every raw fix as it was,
 * with the phone's accuracy and speed in an OpenSki extension so a round trip loses nothing.
 */
object GpxCodec {
    const val NS_GPX = "http://www.topografix.com/GPX/1/1"
    const val NS_OPENSKI = "https://github.com/tayhall/openski/gpx/1"
    /** Accuracy given to imported points that carry none, so [RunTrack.usable] keeps them (GPX has no accuracy field). */
    const val ASSUMED_ACCURACY_M = 10f

    fun read(text: String): GpxRead {
        val doc = try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = true
            factory.isExpandEntityReferences = false
            // No document type declarations: no external entities and no entity expansion. Not every parser supports it.
            try { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) } catch (_: Exception) {}
            factory.newDocumentBuilder().parse(InputSource(StringReader(text)))
        } catch (e: Exception) {
            throw GpxFormatException("Not a readable GPX file (malformed XML)", e)
        }
        val root = doc.documentElement ?: throw GpxFormatException("Not a GPX file (empty document)")
        if (localName(root) != "gpx") throw GpxFormatException("Not a GPX file (root element is ${localName(root)})")
        val fixes = ArrayList<GpsFix>()
        var skipped = 0
        var assumedUtc = 0
        var name: String? = null
        for (trk in children(root, "trk")) {
            if (name == null) name = children(trk, "name").firstOrNull()?.textContent?.trim()?.takeIf { it.isNotEmpty() }
            for (seg in children(trk, "trkseg")) for (pt in children(seg, "trkpt")) {
                val lat = pt.getAttribute("lat").trim().toDoubleOrNull()
                val lon = pt.getAttribute("lon").trim().toDoubleOrNull()
                val timeText = children(pt, "time").firstOrNull()?.textContent?.trim()
                val time = timeText?.let { parseTime(it) }
                if (lat == null || lon == null || !lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 ||
                    lon !in -180.0..180.0 || time == null) { skipped++; continue }
                if (time.second) assumedUtc++
                val ele = children(pt, "ele").firstOrNull()?.textContent?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() }
                val ext = children(pt, "extensions").flatMap { e -> children(e, null).filter { it.namespaceURI == NS_OPENSKI } }
                fun extValue(n: String) = ext.firstOrNull { localName(it) == n }?.textContent?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() }
                val speed = extValue("speed") ?: children(pt, "speed").firstOrNull()?.textContent?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() }
                fixes.add(GpsFix(time.first, lat, lon, ele, speed,
                    extValue("accuracy")?.toFloat() ?: ASSUMED_ACCURACY_M, extValue("vaccuracy")?.toFloat()))
            }
        }
        if (fixes.isEmpty()) throw GpxFormatException("No track point with a position and a usable time")
        return GpxRead(fixes, name, skipped, assumedUtc)
    }

    /** Epoch milliseconds, and whether the time had no zone and was taken as UTC. Null when unreadable. */
    private fun parseTime(s: String): Pair<Long, Boolean>? {
        try { return Instant.parse(s).toEpochMilli() to false } catch (_: Exception) {}
        try { return OffsetDateTime.parse(s).toInstant().toEpochMilli() to false } catch (_: Exception) {}
        try { return LocalDateTime.parse(s).toInstant(ZoneOffset.UTC).toEpochMilli() to true } catch (_: Exception) {}
        return null
    }

    private fun localName(e: Element): String = e.localName ?: e.tagName.substringAfter(':')

    /** Direct child elements with local name [name] (any namespace); all child elements when [name] is null. */
    private fun children(parent: Element, name: String?): List<Element> {
        val out = ArrayList<Element>()
        val nodes = parent.childNodes
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            if (node is Element && (name == null || localName(node) == name)) out.add(node)
        }
        return out
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** The raw fixes as one GPX 1.1 track, in the order given, untouched. */
    fun write(fixes: List<GpsFix>, name: String? = null): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"OpenSki\" xmlns=\"").append(NS_GPX)
            .append("\" xmlns:openski=\"").append(NS_OPENSKI).append("\">\n")
        sb.append("  <trk>\n")
        if (name != null) sb.append("    <name>").append(escape(name)).append("</name>\n")
        sb.append("    <trkseg>\n")
        for (f in fixes) {
            sb.append("      <trkpt lat=\"").append(plain(f.lat)).append("\" lon=\"").append(plain(f.lon)).append("\">")
            f.altM?.takeIf { it.isFinite() }?.let { sb.append("<ele>").append(plain(it)).append("</ele>") }
            sb.append("<time>").append(Instant.ofEpochMilli(f.timeMs)).append("</time>")
            sb.append("<extensions>")
            if (f.accuracyM.isFinite()) sb.append("<openski:accuracy>").append(f.accuracyM).append("</openski:accuracy>")
            f.verticalAccuracyM?.takeIf { it.isFinite() }?.let { sb.append("<openski:vaccuracy>").append(it).append("</openski:vaccuracy>") }
            f.speedMps?.takeIf { it.isFinite() }?.let { sb.append("<openski:speed>").append(it).append("</openski:speed>") }
            sb.append("</extensions></trkpt>\n")
        }
        sb.append("    </trkseg>\n  </trk>\n</gpx>\n")
        return sb.toString()
    }

    /** Exact decimal text of a double (no exponent), so the value reads back identically. */
    private fun plain(v: Double): String = java.math.BigDecimal(v.toString()).toPlainString()
}
