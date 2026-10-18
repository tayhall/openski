package com.openski.android

import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object SessionExport {
    fun write(data: SessionData, output: OutputStream, correction: (String) -> AccelCorrection? = { null }) {
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
            .put("accel_correction_applied_to_orientation", JSONObject().apply { listOf("L","R").forEach { side -> correction(side)?.let { put(side, it.toStorage()) } } })
            .put("accel_correction_format", "offset x,y,z then scale x,y,z; corrected = (raw - offset) / scale. Raw samples in this export are uncorrected.")
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
                .put("estimated_coverage", q.coverage).put("gaps_over_100ms", q.gaps).put("longest_gap_ms", q.longestGapMs)
                .put("rail_samples", q.railSamples).put("rail_percent", q.railPercent)
                .put("dropped_before_samples", q.droppedBefore)) } })
        val correctedTimeline=analysis.timeline.corrected(correction)
        val orientation=calibrations.flatMap { BootOrientation.estimate(correctedTimeline,it) }
        ZipOutputStream(output).use { zip ->
            fun entry(name: String, body: (java.io.Writer) -> Unit) {
                zip.putNextEntry(ZipEntry(name))
                val writer = zip.writer(Charsets.UTF_8)
                body(writer); writer.flush(); zip.closeEntry()
            }
            data.run?.info?.let { info ->
                metadata.put("kind", session.kind).put("run", JSONObject().put("target_beat_s", info.targetBeat)
                    .put("target_depth_deg", info.targetDepth).put("target_label", info.targetLabel).put("window_size", info.windowSize)
                    .put("coach_boot", info.coachBoot).put("mode_before", JSONObject(info.modeBeforeJson)).put("ended_by", info.endedBy)
                    .put("first_turn_ms", info.firstTurnMs ?: JSONObject.NULL).put("last_turn_ms", info.lastTurnMs ?: JSONObject.NULL)
                    .put("gaps", JSONArray(info.gapsJson)).put("roll_frame", "boot frame, right boot not mirrored"))
            }
            entry("session.json") { it.write(metadata.toString(2)) }
            data.run?.let { run ->
                entry("half-turns.csv") { w -> w.write(RunCsv.halfTurns(run.events, session.startedAtMs)) }
                entry("coach-verdicts.csv") { w -> w.write(RunCsv.verdicts(run.verdicts, session.startedAtMs)) }
            }
            entry("samples.csv") { w ->
                w.write(SampleCsv.HEADER)
                analysis.raw.forEach { p -> w.write(SampleCsv.row(p,session.startedAtMs)) }
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
            val clockSyncs=ClockSyncExport.replay(data)
            entry("clock-sync.csv") { w -> w.write(ClockSyncExport.clockCsv(data,clockSyncs)) }
            entry("clock-sync-fit.json") { it.write(ClockSyncExport.fitJson(data,clockSyncs).toString(2)) }
            entry("gesture-sync.json") { it.write(runCatching { GestureSync.toJson(GestureSync.analyse(data,clockSyncs)).toString(2) }.getOrElse { e -> JSONObject().put("format_version", 1).put("experimental", true).put("status", "error").put("reason", e.message ?: "analysis failed").toString(2) }) }
            entry("magnetometer.csv") { w -> w.write(ClockSyncExport.magnetometerCsv(data)) }
            entry("phone-motion.csv") { w -> PhoneMotionExport.write(w, data.phone, data.events, ClockSyncExport.reference(data.events)?.elapsedNs) }
            val rollBySide=orientation.groupBy { it.side }
            val detections=listOf("L","R").associateWith { side -> DrySkiAnalysis.detectTrials(rollBySide[side].orEmpty(),data.markers) }
            fun positiveLeft(side: String)=settings.optJSONObject(side)?.optBoolean("positive_left",true) ?: true
            entry("detected-movements.csv") { w ->
                w.write("side,direction,positive_roll,start_ms,end_ms,edged_duration_ms,peak_estimated_boot_roll_deg,detector\n")
                detections.forEach { (side,detection)->detection.movements.forEach { m ->
                    val direction=if(m.positive==positiveLeft(side)) "LEFT" else "RIGHT"
                    w.write("$side,$direction,${m.positive},${m.startMs},${m.endMs},${m.endMs-m.startMs},${m.peakRoll},experimental_dry_roll\n")
                } }
            }
            val headingTurns=listOf("L","R").associateWith { side -> HeadingTurnAnalysis.detect(rollBySide[side].orEmpty()) }
            entry("heading-turns.csv") { w ->
                w.write("side,direction,positive_heading_rate,start_ms,end_ms,duration_ms,peak_heading_rate_dps,heading_change_deg,detector\n")
                headingTurns.forEach { (side,turns)->turns.forEach { t ->
                    val direction=HeadingDirection.label(t.positive)
                    w.write("$side,$direction,${t.positive},${t.startMs},${t.endMs},${t.endMs-t.startMs},${t.peakRateDps},${t.changeDegrees},experimental_heading_rate\n")
                } }
            }
            val turnMetricRows=listOf("L","R").associateWith { side -> TurnMetricsExport.rows(side,rollBySide[side].orEmpty(),
                detections.getValue(side).movements,headingTurns.getValue(side),positiveLeft(side)) }
            entry("turn-metrics.csv") { w -> w.write(TurnMetricsExport.csv(turnMetricRows.values.flatten())) }
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
                val heading=HeadingTurnAnalysis.summarise(headingTurns.getValue(side))
                summary.getJSONObject(side).put("heading_turns",JSONObject().put("count",heading.count)
                    .put("median_duration_ms",heading.medianDurationMs ?: JSONObject.NULL)
                    .put("cadence_per_minute",heading.cadencePerMinute ?: JSONObject.NULL))
            }
            val slips=MountSlipMonitor.checkSides(correctedTimeline,calibrations)
            listOf("L","R").forEach { side ->
                val slip=slips[side]
                val sideJson=summary.optJSONObject(side) ?: JSONObject().also { summary.put(side,it) }
                // Always present, so a consumer can tell "no calibration" or "no baseline" from "no slip".
                sideJson.put("mount_slip",if(slip==null) JSONObject().put("available",false).put("reason","no boot calibration saved")
                    else JSONObject().put("available",slip.available).put("reason",slip.reason ?: JSONObject.NULL)
                        .put("calibration_ms",slip.calibrationMs)
                        .put("baseline_windows",slip.baselineWindows).put("windows_checked",slip.windowsChecked)
                        .put("windows_skipped_other_activity",slip.windowsSkipped)
                        .put("flagged_windows",slip.flags.size).put("first_flag_ms",slip.firstFlagMs ?: JSONObject.NULL)
                        .put("first_flag_after_calibration_ms",slip.firstFlagAfterCalibrationMs ?: JSONObject.NULL)
                        .put("max_axis_deviation_deg",slip.maxAxisDeviationDeg ?: JSONObject.NULL)
                        .put("max_force_shift_g",slip.maxForceShiftG ?: JSONObject.NULL))
            }
            summary.put("mount_slip_note","Replay check of the latest calibration of each boot against the run's own baseline (gyro roll axis and " +
                "mean horizontal force); needs two consecutive moving windows to flag and ignores windows whose motion is not turning-like. " +
                "Simulation only: flags yaw slip of 8 to 45 degrees reliably and tilt slip under 15 degrees unreliably. A flag means re-calibrate, not a measured slip angle.")
            summary.put("heading_detector","Experimental heading-rate turn candidates (rotation about the current vertical), not turn classification. " +
                "The detector was benchmarked on calf-phone data at 10 Hz; transfer to boot-mounted sensors is unproven. Direction: positive heading rate (counter-clockwise from above) = LEFT, independent of per-boot roll settings.")
            val comparison=BootComparison.analyse(rollBySide["L"].orEmpty(),rollBySide["R"].orEmpty(),
                detections.getValue("L"),detections.getValue("R"),positiveLeft("L"),positiveLeft("R"))
            summary.put("boot_comparison",JSONObject().put("angle_pairs",comparison.anglePairs)
                .put("mean_roll_difference_deg",comparison.meanRollDifferenceDeg ?: JSONObject.NULL)
                .put("correlation",comparison.correlation ?: JSONObject.NULL).put("movement_pairs",comparison.movementPairs)
                .put("median_right_minus_left_onset_ms",comparison.medianOnsetLagMs ?: JSONObject.NULL)
                .put("median_right_minus_left_return_ms",comparison.medianReturnLagMs ?: JSONObject.NULL))
            summary.put("quality",JSONArray().apply { analysis.quality.forEach { q -> put(JSONObject().put("side",q.side)
                .put("rail_samples",q.railSamples).put("rail_percent",q.railPercent)
                .put("rail_note",RAIL_NOTE)
                .put("dropped_before_samples",q.droppedBefore)) } })
            summary.put("turn_metrics",TurnMetricsExport.toJson(TurnMetricsExport.block(turnMetricRows,
                TurnMetricsExport.agreementPairs(rollBySide["L"].orEmpty(),rollBySide["R"].orEmpty(),
                    detections.getValue("L").movements,detections.getValue("R").movements,positiveLeft("L"),positiveLeft("R")))))
            entry("analysis-summary.json") { it.write(summary.toString(2)) }
            entry("session-health.json") { it.write(SessionHealth.build(data).toJson()) }
        }
    }
    private fun quote(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
}
