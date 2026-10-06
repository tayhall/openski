package com.openski.android

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class DrySkiAnalysisTest {
    private fun points()=(0..700).map { i ->
        BootRoll("L",i*20.0,30*sin(i*20.0*PI/1500),0.0)
    }
    @Test fun detectsAlternatingLeansAndExcludesIncompleteTail() {
        val found=DrySkiAnalysis.detect(points())
        assertEquals(9,found.movements.size)
        assertEquals(found.movements.size,found.transitions.size)
        assertTrue(found.movements.zipWithNext().all { (a,b)->a.positive!=b.positive })
        assertTrue(found.movements.all { it.peakRoll in 29.0..30.1 && it.endMs>it.startMs })
    }
    @Test fun gapsAndSmallJitterDoNotCreateMovements() {
        val found=DrySkiAnalysis.detect(points().filter { it.timeMs !in 800.0..1200.0 })
        assertTrue(found.movements.none { it.startMs<1200 && it.endMs>800 })
        assertTrue(DrySkiAnalysis.detect(points().map { it.copy(degrees=it.degrees/10) }).movements.isEmpty())
        assertTrue(DrySkiAnalysis.detect(listOf(BootRoll("L",0.0,30.0,0.0),BootRoll("L",20.0,30.0,0.0))).movements.isEmpty())
    }
    @Test fun matchesLabelsOnceWithDirectionAndSideConstraints() {
        val detection=DryDetection(listOf(DryMovement(1000.0,1600.0,true,30.0),DryMovement(2500.0,3200.0,false,40.0)),listOf(1600.0,3200.0))
        val labels=listOf(TestMarker(1,900,"LEFT","","video_review",""),TestMarker(2,1100,"LEFT","","observer",""),
            TestMarker(3,2600,"RIGHT","R","observer",""),TestMarker(4,2500,"LEFT","L","observer",""))
        val result=DrySkiAnalysis.evaluate(labels,detection,"L",true)
        assertEquals(3,result.labels)
        assertEquals(2,result.detections)
        assertEquals(1,result.matched)
        assertEquals(100.0,result.meanAbsoluteErrorMs!!,0.0)
        val transitions=DrySkiAnalysis.evaluateTransitions(listOf(TestMarker(5,1500,"TRANSITION","L","video_review","")),detection,"L")
        assertEquals(1,transitions.matched)
        assertEquals(100.0,transitions.meanSignedErrorMs!!,0.0)
        assertNull(DrySkiAnalysis.evaluate(emptyList(),detection,"L",true).meanAbsoluteErrorMs)
    }
    @Test fun sampleRateIncludesGapsAndReportsDuplicates() {
        val points=(0..50).map { TimelinePoint("L",it*20.0,SensorSample(0,0,0f,0f,0f,0f,0f,0f),"live") }
        val regular=DrySkiAnalysis.sampleTiming(points)
        assertEquals(50.0,regular.effectiveHz!!,0.0)
        assertEquals(20.0,regular.medianIntervalMs!!,0.0)
        val modified=DrySkiAnalysis.sampleTiming(points+points.last()+points.last().copy(timeMs=2000.0))
        assertEquals(1,modified.duplicateTimes)
        assertEquals(1,modified.gaps)
        assertEquals(25.5,modified.effectiveHz!!,0.0)
    }
    @Test fun comparesTimingAndRollWithoutInventingASkillScore() {
        val detection=DryDetection(listOf(DryMovement(0.0,800.0,true,30.0),DryMovement(1000.0,2200.0,false,40.0),
            DryMovement(3000.0,3800.0,true,30.0),DryMovement(4000.0,5200.0,false,40.0)),emptyList())
        val balance=DrySkiAnalysis.balance(detection)!!
        assertEquals(800.0,balance.positiveDurationMs,0.0)
        assertEquals(66.6667,balance.durationRatioPercent,0.001)
        assertEquals(75.0,balance.peakRatioPercent,0.0)
        assertNull(DrySkiAnalysis.balance(detection.copy(movements=detection.movements.take(2))))
    }
    @Test fun trialMarkersExcludeCalibrationGesturesAndPauses() {
        val markers=listOf(TestMarker(1,4000,"START_TEST","","observer",""),TestMarker(2,8500,"PAUSE","","observer",""),
            TestMarker(3,11000,"START_TEST","","observer",""))
        val result=DrySkiAnalysis.detectTrials(points(),markers)
        assertTrue(result.movements.isNotEmpty())
        assertTrue(result.movements.all { it.startMs>=4000 && (it.endMs<8500 || it.startMs>=11000) })
        assertEquals(listOf(4000.0 to 8500.0,11000.0 to 14000.0),DrySkiAnalysis.trialRanges(markers,14000.0))
    }
}
