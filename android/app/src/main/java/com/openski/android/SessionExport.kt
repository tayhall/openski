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
        val settings=JSONObject(session.analysisJson)
        val calibrations = org.json.JSONObject(session.calibrationJson).let { json ->
            listOf("L","R").mapNotNull { side -> json.optJSONObject(side)?.let { c ->
                fun vector(key: String)=c.getJSONArray(key).let { Vector3(it.getDouble(0),it.getDouble(1),it.getDouble(2)) }
                BootCalibration(side,c.getDouble("time_ms"),c.getInt("axis"),c.getInt("sign"),vector("gravity"),vector("bias"),
                    if(c.has("forward") && !c.isNull("forward")) vector("forward") else null)
            } }
        }
        val metadata = JSONObject().put("format_version", 1).put("session_id", session.id)
            .put("test_session",session.testSession)
            .put("data_origin",session.origin).put("analysis_settings",settings)
            .put("title", session.title).put("notes", session.notes).put("started_at_ms", session.startedAtMs)
            .put("equipment", session.equipment)
            .put("boot_calibration", JSONObject(session.calibrationJson))
            .put("boot_roll_reference", "Experimental neutral-relative boot roll; not snow-relative ski edge angle. Drift age is time since calibration or detected rest.")
            .put("relative_yaw_reference", "Gyro-integrated heading relative to calibration; no absolute heading reference. Rest corrects tilt, not yaw. A new orientation segment resets yaw origin after a gap.")
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
        val orientation=calibrations.flatMap { BootOrientation.estimate(analysis.timeline,it) }
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
            entry("boot-roll.csv") { w ->
                w.write("side,aligned_time_ms,session_elapsed_ms,estimated_boot_roll_deg,seconds_since_rest_reference,estimated_pitch_deg,relative_yaw_deg,orientation_segment,roll_rate_radps,pitch_rate_radps,yaw_rate_radps\n")
                orientation.forEach { p ->
                    w.write("${p.side},${p.timeMs},${p.timeMs-session.startedAtMs},${p.degrees},${p.driftSeconds},${p.pitchDegrees},${p.yawDegrees},${p.segment},${p.rollRateRadps},${p.pitchRateRadps},${p.yawRateRadps}\n")
                }
            }
            entry("labels.csv") { w ->
                w.write("id,time_ms,session_elapsed_ms,label,side,source,note\n")
                data.markers.forEach { m -> w.write("${m.id},${m.timeMs},${m.timeMs-session.startedAtMs},${m.label},${m.side},${m.source},${quote(m.note)}\n") }
            }
            entry("sensor-health.csv") { w ->
                w.write("side,time_ms,battery_percent,battery_observed_at_ms,rssi_dbm,rssi_observed_at_ms\n")
                data.health.forEach { h -> w.write("${h.side},${h.timeMs},${h.battery ?: ""},${h.batteryAtMs ?: ""},${h.rssi ?: ""},${h.rssiAtMs ?: ""}\n") }
            }
            val detections=listOf("L","R").associateWith { side -> DrySkiAnalysis.detectTrials(orientation.filter { it.side==side },data.markers) }
            fun positiveLeft(side: String)=settings.optJSONObject(side)?.optBoolean("positive_left",true) ?: true
            entry("detected-movements.csv") { w ->
                w.write("side,direction,positive_roll,start_ms,end_ms,edged_duration_ms,peak_estimated_boot_roll_deg,detector\n")
                detections.forEach { (side,detection)->detection.movements.forEach { m ->
                    val direction=if(m.positive==positiveLeft(side)) "LEFT" else "RIGHT"
                    w.write("$side,$direction,${m.positive},${m.startMs},${m.endMs},${m.endMs-m.startMs},${m.peakRoll},experimental_dry_roll\n")
                } }
            }
            val summary=JSONObject().put("data_origin",session.origin)
                .put("detector","experimental_dry_roll").put("entry_deg",8).put("neutral_deg",3)
                .put("filter_time_constant_ms",100).put("label_tolerance_ms",500)
                .put("note","Dry lean movements; not validated ski turns. Direction uses saved per-boot settings, default positive roll=LEFT.")
            val ranges=DrySkiAnalysis.trialRanges(data.markers,(session.endedAtMs ?: System.currentTimeMillis()).toDouble())
            val labels=data.markers.filter { m->ranges.any { (start,end)->m.timeMs>=start && m.timeMs<end } }
            detections.forEach { (side,detection)->
                val evaluation=DrySkiAnalysis.evaluate(labels,detection,side,positiveLeft(side))
                summary.put(side,JSONObject().put("completed_movements",detection.movements.size)
                    .put("direction_labels",evaluation.labels).put("matched_labels",evaluation.matched)
                    .put("unmatched_detections_in_label_window",evaluation.detections-evaluation.matched)
                    .put("mean_absolute_onset_error_ms",evaluation.meanAbsoluteErrorMs ?: JSONObject.NULL)
                    .put("mean_signed_onset_error_ms",evaluation.meanSignedErrorMs ?: JSONObject.NULL))
            }
            val comparison=BootComparison.analyse(orientation.filter { it.side=="L" },orientation.filter { it.side=="R" },
                detections.getValue("L"),detections.getValue("R"),positiveLeft("L"),positiveLeft("R"))
            summary.put("boot_comparison",JSONObject().put("angle_pairs",comparison.anglePairs)
                .put("mean_roll_difference_deg",comparison.meanRollDifferenceDeg ?: JSONObject.NULL)
                .put("correlation",comparison.correlation ?: JSONObject.NULL).put("movement_pairs",comparison.movementPairs)
                .put("median_right_minus_left_onset_ms",comparison.medianOnsetLagMs ?: JSONObject.NULL)
                .put("median_right_minus_left_return_ms",comparison.medianReturnLagMs ?: JSONObject.NULL))
            entry("analysis-summary.json") { it.write(summary.toString(2)) }
        }
    }
    private fun quote(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
}
