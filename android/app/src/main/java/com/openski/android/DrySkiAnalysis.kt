package com.openski.android

import kotlin.math.abs
import kotlin.math.exp

data class DryMovement(val startMs: Double, val endMs: Double, val positive: Boolean, val peakRoll: Double)
data class DryDetection(val movements: List<DryMovement>, val transitions: List<Double>)
data class LabelEvaluation(val labels: Int, val detections: Int, val matched: Int,
    val meanAbsoluteErrorMs: Double?, val meanSignedErrorMs: Double?)
data class SampleTiming(val samples: Int, val medianIntervalMs: Double?, val effectiveHz: Double?,
    val gaps: Int, val duplicateTimes: Int)
data class MovementBalance(val positiveDurationMs: Double, val negativeDurationMs: Double,
    val positivePeak: Double, val negativePeak: Double, val durationRatioPercent: Double, val peakRatioPercent: Double)

object DrySkiAnalysis {
    fun trialRanges(markers: List<TestMarker>, endMs: Double): List<Pair<Double,Double>> {
        if(markers.none { it.label=="START_TEST" }) return listOf(Double.NEGATIVE_INFINITY to endMs)
        val ranges=mutableListOf<Pair<Double,Double>>()
        var start: Double?=null
        for(marker in markers.sortedBy { it.timeMs }) when(marker.label) {
            "START_TEST" -> if(start==null) start=marker.timeMs.toDouble()
            "PAUSE" -> start?.let { ranges.add(it to marker.timeMs.toDouble()); start=null }
        }
        start?.let { ranges.add(it to endMs) }
        return ranges
    }

    fun detectTrials(points: List<BootRoll>, markers: List<TestMarker>): DryDetection {
        val ranges=trialRanges(markers,points.maxOfOrNull { it.timeMs } ?: 0.0)
        val detections=ranges.map { (start,end)->detect(points.filter { it.timeMs>=start && it.timeMs<end }) }
        return DryDetection(detections.flatMap { it.movements },detections.flatMap { it.transitions })
    }

    fun balance(detection: DryDetection): MovementBalance? {
        val positive=detection.movements.filter { it.positive }
        val negative=detection.movements.filter { !it.positive }
        if(positive.size<2 || negative.size<2) return null
        val positiveTime=positive.map { it.endMs-it.startMs }.average()
        val negativeTime=negative.map { it.endMs-it.startMs }.average()
        val positivePeak=positive.map { it.peakRoll }.average()
        val negativePeak=negative.map { it.peakRoll }.average()
        return MovementBalance(positiveTime,negativeTime,positivePeak,negativePeak,
            minOf(positiveTime,negativeTime)/maxOf(positiveTime,negativeTime)*100,
            minOf(positivePeak,negativePeak)/maxOf(positivePeak,negativePeak)*100)
    }

    fun sampleTiming(points: List<TimelinePoint>): SampleTiming {
        val times=points.map { it.timeMs }.sorted()
        val deltas=times.zipWithNext().map { (a,b)->b-a }
        val regular=deltas.filter { it>0 && it<=100 }.sorted()
        val median=if(regular.isEmpty()) null else (regular[(regular.size-1)/2]+regular[regular.size/2])/2
        val span=if(times.size<2) 0.0 else times.last()-times.first()
        return SampleTiming(times.size,median,if(span>0) (times.distinct().size-1)*1000/span else null,
            deltas.count { it>100 },deltas.count { it==0.0 })
    }

    /** Indoor lean excursions, not carved turns. Hysteresis thresholds are engineering settings. */
    fun detect(points: List<BootRoll>): DryDetection {
        val tracker=Tracker()
        val movements=mutableListOf<DryMovement>()
        val transitions=mutableListOf<Double>()
        for(p in points.sortedBy { it.timeMs }) tracker.accept(p)?.let {
            movements.add(it); transitions.add(it.endMs)
        }
        return DryDetection(movements,transitions)
    }

    class Tracker {
        private var start: Double?=null; private var positive=false; private var peak=0.0
        private var previous: Double?=null; private var filtered=0.0; private var neutralSeen=false
        fun accept(p: BootRoll): DryMovement? {
            if(previous?.let { p.timeMs<=it }==true) return null
            val dt=previous?.let { p.timeMs-it } ?: 0.0
            if(previous==null || dt>100 || dt<0) {
                start=null; filtered=p.degrees; neutralSeen=false
            } else filtered+=(1-exp(-dt/100))*(p.degrees-filtered)
            previous=p.timeMs
            var completed: DryMovement?=null
            if(abs(filtered)<=3) {
                start?.let { began ->
                    if(p.timeMs-began in 300.0..20_000.0) {
                        completed=DryMovement(began,p.timeMs,positive,peak)
                    }
                }
                start=null; neutralSeen=true
            } else if(start==null && neutralSeen && abs(filtered)>=8) {
                start=p.timeMs; positive=filtered>0; peak=abs(p.degrees); neutralSeen=false
            } else if(start!=null) {
                if((filtered>0)!=positive) { start=null; neutralSeen=false }
                else peak=maxOf(peak,abs(p.degrees))
            }
            return completed
        }
    }

    /** Match direction labels one-to-one in the annotated time window, with an explicit tolerance. */
    fun evaluate(markers: List<TestMarker>, detection: DryDetection, side: String,
        positiveLeft: Boolean, toleranceMs: Double=500.0): LabelEvaluation {
        val labels=markers.filter { (it.side.isEmpty() || it.side==side) && it.label in listOf("LEFT","RIGHT") }.sortedBy { it.timeMs }
        val events=detection.movements.map { it.startMs to if(it.positive==positiveLeft) "LEFT" else "RIGHT" }
        return evaluateEvents(labels,events,toleranceMs)
    }

    fun evaluateTransitions(markers: List<TestMarker>, detection: DryDetection, side: String,
        toleranceMs: Double=500.0): LabelEvaluation = evaluateEvents(
        markers.filter { (it.side.isEmpty() || it.side==side) && it.label=="TRANSITION" }.sortedBy { it.timeMs },
        detection.transitions.map { it to "TRANSITION" },toleranceMs)

    private fun evaluateEvents(labels: List<TestMarker>, events: List<Pair<Double,String>>, toleranceMs: Double): LabelEvaluation {
        if(labels.isEmpty()) return LabelEvaluation(0,0,0,null,null)
        val movements=events.filter { it.first>=labels.first().timeMs-toleranceMs && it.first<=labels.last().timeMs+toleranceMs }
        val options=labels.flatMapIndexed { li,label -> movements.mapIndexedNotNull { di,movement ->
            val name=movement.second
            val error=movement.first-label.timeMs
            if(name==label.label && abs(error)<=toleranceMs) Triple(li,di,error) else null
        } }.sortedBy { abs(it.third) }
        val usedLabels=mutableSetOf<Int>(); val usedDetections=mutableSetOf<Int>(); val errors=mutableListOf<Double>()
        for((li,di,error) in options) if(li !in usedLabels && di !in usedDetections) {
            usedLabels.add(li); usedDetections.add(di); errors.add(error)
        }
        return LabelEvaluation(labels.size,movements.size,errors.size,
            errors.takeIf { it.isNotEmpty() }?.map(::abs)?.average(),errors.takeIf { it.isNotEmpty() }?.average())
    }
}
