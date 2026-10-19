package com.openski.android

import org.json.JSONObject

/**
 * The boot analyses of one loaded session, computed once: orientation, detected movements, heading-rate turn
 * candidates, turn-metric rows, the boot-to-boot clock alignment and agreement pairs, and the torso metrics. The
 * session export ([SessionExport]) and the ski day report ([DayBootStats]) both read these, so they always run the
 * same pure functions in the same way. Pure (no Android types); raw samples are never altered. Everything here is
 * EXPERIMENTAL dry-ski boot roll, not ski turn classification.
 */
class SessionBootAnalysis private constructor(
    val data: SessionData,
    val analysis: AnalysisResult,
    /** The session's saved review settings (per-boot roll polarity). */
    val settings: JSONObject,
    val calibrations: List<BootCalibration>,
    /** The timeline with any saved accelerometer correction applied (orientation only; raw samples stay uncorrected). */
    val correctedTimeline: List<TimelinePoint>,
    val orientation: List<BootRoll>,
    val rollBySide: Map<String, List<BootRoll>>,
    val clockSyncs: Map<String, ClockSync>,
    val gesture: Result<GestureSync.Report>,
    /** Best available boot-to-boot alignment, applied only to the right boot inside the agreement stage. */
    val alignment: ClockAlignment.Result,
    val detections: Map<String, DryDetection>,
    val headingTurns: Map<String, List<HeadingTurn>>,
    val turnMetricRows: Map<String, List<TurnMetricsRow>>,
    /** The right boot's roll and movements on the left boot's timeline (identity when not aligned). */
    val alignedRight: List<BootRoll>,
    val alignedRightDetection: DryDetection,
    val agreementPairs: List<BootAgreementPair>,
    val torso: TorsoMetricsExport.Result,
) {
    /** The saved roll polarity of [side]: true (the default) means positive roll is LEFT. */
    fun positiveLeft(side: String): Boolean = settings.optJSONObject(side)?.optBoolean("positive_left", true) ?: true

    companion object {
        val SIDES = listOf("L", "R")

        /** The calibrations saved in [session].calibrationJson, left then right. */
        fun calibrations(session: SkiSession): List<BootCalibration> = JSONObject(session.calibrationJson).let { json ->
            SIDES.mapNotNull { side -> json.optJSONObject(side)?.let { c ->
                fun vector(key: String) = c.getJSONArray(key).let { Vector3(it.getDouble(0), it.getDouble(1), it.getDouble(2)) }
                BootCalibration(side, c.getDouble("time_ms"), c.getInt("axis"), c.getInt("sign"), vector("gravity"), vector("bias"),
                    if (c.has("forward") && !c.isNull("forward")) vector("forward") else null)
            } }
        }

        /**
         * Runs every boot analysis on [data]. [correction] is the per-boot accelerometer correction for orientation.
         * [detect] finds dry-roll movements in one boot's roll; the export uses [DrySkiAnalysis.detectTrials], which
         * counts only inside START_TEST..PAUSE marker windows, and the ski day report the whole session.
         * [nowMs] stands in for the end of a session that is still open.
         */
        fun build(
            data: SessionData,
            correction: (String) -> AccelCorrection? = { null },
            detect: (List<BootRoll>, List<TestMarker>) -> DryDetection = DrySkiAnalysis::detectTrials,
            nowMs: () -> Long = System::currentTimeMillis,
        ): SessionBootAnalysis {
            val session = data.session
            val analysis = SessionAnalysis.build(data)
            val settings = JSONObject(session.analysisJson)
            fun positiveLeft(side: String) = settings.optJSONObject(side)?.optBoolean("positive_left", true) ?: true
            val calibrations = calibrations(session)
            val correctedTimeline = analysis.timeline.corrected(correction)
            val orientation = calibrations.flatMap { BootOrientation.estimate(correctedTimeline, it) }
            val clockSyncs = ClockSyncExport.replay(data)
            val gesture = runCatching { GestureSync.analyse(data, clockSyncs) }
            val alignment = runCatching { ClockAlignment.estimate(analysis.timeline, gesture.getOrNull(), clockSyncs) }.getOrElse { e ->
                ClockAlignment.Result(ClockAlignment.Source.NONE, null, null, null, null, "not aligned: alignment failed (${e.message ?: "error"})",
                    null, null, null, false, gesture.getOrNull()?.status?.name?.lowercase()) }
            val rollBySide = orientation.groupBy { it.side }
            val detections = SIDES.associateWith { side -> detect(rollBySide[side].orEmpty(), data.markers) }
            val headingTurns = SIDES.associateWith { side -> HeadingTurnAnalysis.detect(rollBySide[side].orEmpty()) }
            val turnMetricRows = SIDES.associateWith { side -> TurnMetricsExport.rows(side, rollBySide[side].orEmpty(),
                detections.getValue(side).movements, headingTurns.getValue(side), positiveLeft(side)) }
            // Experimental torso metrics: the phone (jacket pocket) against each boot turn candidate, on each boot's own timeline.
            val clockRef = ClockSyncExport.reference(data.events)
            val userOffsets = mapOf("L" to session.leftOffsetMs.toDouble(), "R" to session.rightOffsetMs.toDouble())
            // Exception only: an OutOfMemoryError or other Error must not be turned into a refusal.
            val torso = try {
                TorsoMetricsExport.analyse(data.phone, PhoneMotionExport.clockOffsetMs(data.events), clockRef,
                    TorsoMetricsExport.candidates(turnMetricRows.values.flatten()), rollBySide,
                    SIDES.associateWith { side -> TorsoMetricsExport.SideTiming(ClockAlignment.anchor(analysis.timeline, side),
                        clockSyncs[side], clockRef, userOffsets.getValue(side)) },
                    session.startedAtMs.toDouble(), (session.endedAtMs ?: nowMs()).toDouble(),
                    alignment.source.name.lowercase(), userOffsets)
            } catch (e: Exception) { TorsoMetricsExport.failed("refused: torso metrics failed (${e.message ?: "error"})") }
            val alignedRight = ClockAlignment.alignRolls(rollBySide["R"].orEmpty(), alignment)
            val alignedRightDetection = ClockAlignment.alignDetection(detections.getValue("R"), alignment)
            val agreementPairs = TurnMetricsExport.agreementPairs(rollBySide["L"].orEmpty(), alignedRight,
                detections.getValue("L").movements, alignedRightDetection.movements, positiveLeft("L"), positiveLeft("R"),
                alignment.uncertaintyMs)
            return SessionBootAnalysis(data, analysis, settings, calibrations, correctedTimeline, orientation, rollBySide,
                clockSyncs, gesture, alignment, detections, headingTurns, turnMetricRows, alignedRight, alignedRightDetection,
                agreementPairs, torso)
        }
    }
}
