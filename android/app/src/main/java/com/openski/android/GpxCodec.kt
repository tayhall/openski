package com.openski.android

import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.StringReader
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/** A GPX file that cannot be read as a track: too large, not XML, a DTD, or no track point with a usable time. */
class GpxFormatException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

/** What a GPX read produced. [skippedPoints] lacked a valid position or time; [assumedUtcPoints] had a time with no zone. */
data class GpxRead(val fixes: List<GpsFix>, val name: String?, val skippedPoints: Int, val assumedUtcPoints: Int)

/**
 * Reads and writes GPX 1.1 tracks with the JDK/Android DOM parser (available to plain JVM unit tests and on Android).
 * Reading tolerates namespaces, missing elevation and unknown extensions. Writing keeps every raw fix as it was,
 * with the phone's accuracy and speed in an OpenSki extension so a round trip loses nothing.
 *
 * Hardening: input over [MAX_INPUT_BYTES] is refused before parsing, and so is any text containing a document type
 * or entity declaration, so the parser never sees a DTD (no external entities, no entity expansion). The text is
 * decoded here and handed to the parser as characters, so that check sees exactly what the parser sees. The
 * parser's own DTD and external-access switches are also set wherever the platform supports them ([HARDENING]); the
 * JDK supports all of them (unit-tested), while Android's parser rejects unknown switches, so there the pre-parse
 * refusal is what protects it (checked on a phone, docs/android-acceptance.md).
 */
object GpxCodec {
    const val NS_GPX = "http://www.topografix.com/GPX/1/1"
    const val NS_OPENSKI = "https://github.com/tayhall/openski/gpx/1"
    /** Accuracy given to imported points that carry none, so [RunTrack.usable] keeps them (GPX has no accuracy field). */
    const val ASSUMED_ACCURACY_M = 10f
    /** Larger files are refused before reading (a whole day at 1 Hz is a few megabytes). */
    const val MAX_INPUT_BYTES = 20 * 1024 * 1024

    /** Parser switches applied where supported: feature name to value. */
    val HARDENING: List<Pair<String, Boolean>> = listOf(
        "http://apache.org/xml/features/disallow-doctype-decl" to true,
        "http://xml.org/sax/features/external-general-entities" to false,
        "http://xml.org/sax/features/external-parameter-entities" to false,
        "http://apache.org/xml/features/nonvalidating/load-external-dtd" to false,
        XMLConstants.FEATURE_SECURE_PROCESSING to true,
    )

    /** A namespace-aware factory with every hardening switch the platform accepts, and the names of those it refused. */
    internal fun factory(): Pair<DocumentBuilderFactory, List<String>> {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        factory.isExpandEntityReferences = false
        factory.isXIncludeAware = false
        val refused = ArrayList<String>()
        for ((feature, value) in HARDENING) {
            try { factory.setFeature(feature, value) } catch (_: Exception) { refused.add(feature) }
        }
        // XMLConstants.ACCESS_EXTERNAL_DTD and ACCESS_EXTERNAL_SCHEMA by value: Android's XMLConstants lacks them.
        for (attribute in listOf("http://javax.xml.XMLConstants/property/accessExternalDTD",
                "http://javax.xml.XMLConstants/property/accessExternalSchema")) {
            try { factory.setAttribute(attribute, "") } catch (_: Exception) { refused.add(attribute) }
        }
        return factory to refused
    }

    /** Reads at most [MAX_INPUT_BYTES] from [input] (refusing anything longer), decodes it and parses it. */
    fun read(input: InputStream): GpxRead {
        val bytes = try {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                out.write(buffer, 0, count)
                if (out.size() > MAX_INPUT_BYTES) throw GpxFormatException("File too large (over ${MAX_INPUT_BYTES / 1024 / 1024} MB)")
            }
            out.toByteArray()
        } catch (e: OutOfMemoryError) {
            throw GpxFormatException("File too large to read", e)
        }
        return read(decode(bytes))
    }

    /** UTF-16 when the file starts with a UTF-16 byte order mark, otherwise UTF-8 (a UTF-8 mark is dropped). */
    private fun decode(b: ByteArray): String = when {
        b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte() -> String(b, 2, b.size - 2, Charsets.UTF_16BE)
        b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte() -> String(b, 2, b.size - 2, Charsets.UTF_16LE)
        b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte() -> String(b, 3, b.size - 3, Charsets.UTF_8)
        else -> String(b, Charsets.UTF_8)
    }

    fun read(text: String): GpxRead {
        // Characters, not bytes: a UTF-8 file of MAX_INPUT_BYTES never has more characters than that.
        if (text.length > MAX_INPUT_BYTES) throw GpxFormatException("File too large (over ${MAX_INPUT_BYTES / 1024 / 1024} MB)")
        if (text.contains("<!DOCTYPE", ignoreCase = true) || text.contains("<!ENTITY", ignoreCase = true)) {
            throw GpxFormatException("Not accepted: the file declares a document type (DTD)")
        }
        val doc = try {
            factory().first.newDocumentBuilder().parse(InputSource(StringReader(text)))
        } catch (e: OutOfMemoryError) {
            throw GpxFormatException("File too large to read", e)
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
                // Negative accuracies and speeds are meaningless: treated as missing.
                fun extValue(n: String) =
                    ext.firstOrNull { localName(it) == n }?.textContent?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
                val speed = extValue("speed")
                    ?: children(pt, "speed").firstOrNull()?.textContent?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
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

    /** XML-escaped text without the control characters XML 1.0 forbids (tab, newline and return are kept). */
    private fun escape(s: String) = s.filter { ch ->
        ch == '\t' || ch == '\n' || ch == '\r' || (ch >= ' ' && ch != '\uFFFE' && ch != '\uFFFF')
    }.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

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
            if (f.accuracyM.isFinite()) sb.append("<openski:accuracy>").append(plain(f.accuracyM)).append("</openski:accuracy>")
            f.verticalAccuracyM?.takeIf { it.isFinite() }?.let { sb.append("<openski:vaccuracy>").append(plain(it)).append("</openski:vaccuracy>") }
            f.speedMps?.takeIf { it.isFinite() }?.let { sb.append("<openski:speed>").append(plain(it)).append("</openski:speed>") }
            sb.append("</extensions></trkpt>\n")
        }
        sb.append("    </trkseg>\n  </trk>\n</gpx>\n")
        return sb.toString()
    }

    /** Exact decimal text of a double (no exponent), so the value reads back identically. */
    private fun plain(v: Double): String = java.math.BigDecimal(v.toString()).toPlainString()
    private fun plain(v: Float): String = java.math.BigDecimal(v.toString()).toPlainString()
}
