package com.openski.android

import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object SessionExport {
    fun write(data: SessionData, output: OutputStream) {
        val analysis = SessionAnalysis.build(data)
        val session = data.session
        val metadata = JSONObject().put("format_version", 1).put("session_id", session.id)
            .put("title", session.title).put("notes", session.notes).put("started_at_ms", session.startedAtMs)
            .put("ended_at_ms", session.endedAtMs ?: JSONObject.NULL)
            .put("left_offset_ms", session.leftOffsetMs).put("right_offset_ms", session.rightOffsetMs)
            .put("video_offset_ms", session.videoOffsetMs).put("video_name", session.videoName ?: JSONObject.NULL)
            .put("alignment", analysis.alignment)
            .put("raw_sources", "Live and flash may overlap; samples.csv preserves both. Video is referenced, not bundled.")
            .put("captures", JSONArray().apply { data.captures.forEach { c -> put(JSONObject()
                .put("id", c.id).put("side", c.side).put("address", c.address).put("state", c.state)
                .put("expected_samples", c.expected).put("dropped_samples", c.dropped).put("error", c.error)) } })
            .put("quality", JSONArray().apply { analysis.quality.forEach { q -> put(JSONObject().put("side", q.side)
                .put("live_samples", q.liveSamples).put("flash_samples", q.flashSamples)
                .put("estimated_coverage", q.coverage).put("gaps_over_100ms", q.gaps).put("longest_gap_ms", q.longestGapMs)) } })
        ZipOutputStream(output).use { zip ->
            fun entry(name: String, body: (java.io.Writer) -> Unit) {
                zip.putNextEntry(ZipEntry(name))
                val writer = zip.writer(Charsets.UTF_8)
                body(writer); writer.flush(); zip.closeEntry()
            }
            entry("session.json") { it.write(metadata.toString(2)) }
            entry("samples.csv") { w ->
                w.write("side,source,capture_id,record_index,session_elapsed_ms,aligned_time_ms,received_at_ms,sensor_time_ms,sensor_time_us,sequence,ax_mps2,ay_mps2,az_mps2,gx_radps,gy_radps,gz_radps\n")
                analysis.raw.forEach { p -> val s = p.sample
                    w.write(listOf(p.side,p.source,p.captureId ?: "",p.recordIndex ?: "",p.timeMs-session.startedAtMs,p.timeMs,
                        p.receivedAtMs ?: "",s.timestampMs,p.sensorTimeUs ?: "",if (s.sequence < 0) "" else s.sequence,
                        s.accelX,s.accelY,s.accelZ,s.gyroX,s.gyroY,s.gyroZ).joinToString(",") + "\n")
                }
            }
            entry("events.csv") { w ->
                w.write("time_ms,message\n")
                data.events.forEach { (time, message) -> w.write("$time," + quote(message) + "\n") }
            }
        }
    }
    private fun quote(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
}
