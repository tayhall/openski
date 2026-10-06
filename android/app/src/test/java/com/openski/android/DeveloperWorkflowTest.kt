package com.openski.android

import org.junit.Assert.*
import org.junit.Test

class DeveloperWorkflowTest {
    @Test fun liveClockPreservesSampleSpacingAcrossNotificationBurstsAndWrap() {
        val clock=LiveSensorClock()
        assertEquals(10000.0,clock.timestamp(100,10000),0.0)
        assertEquals(10020.0,clock.timestamp(120,10080),0.0)
        assertEquals(10040.0,clock.timestamp(140,10080),0.0)
        assertEquals(20000.0,clock.timestamp(0,20000),0.0)
        val wrapping=LiveSensorClock()
        wrapping.timestamp(4_294_960,10000)
        assertEquals(10020.296,wrapping.timestamp(13,10030),0.001)
    }
    @Test fun demoRunsThroughCalibrationAndSharedStreamingDetectors() {
        val demo=DemoSession.generate("demo",100000)
        assertEquals("synthetic",demo.session.origin)
        assertTrue(demo.health.isEmpty())
        val timeline=SessionAnalysis.build(demo).timeline
        for(side in listOf("L","R")) {
            val calibration=BootOrientation.calibrate(timeline,side,100000.0,0,1)!!
            val offline=BootOrientation.estimate(timeline,calibration)
            val tracker=BootOrientation.Tracker(calibration)
            val streamed=timeline.mapNotNull { tracker.accept(it) }
            assertEquals(offline,streamed)
            val detector=DrySkiAnalysis.Tracker()
            val streamingMovements=offline.mapNotNull { detector.accept(it) }
            assertEquals(DrySkiAnalysis.detect(offline).movements,streamingMovements)
            val detected=DrySkiAnalysis.detectTrials(offline,demo.markers)
            assertEquals(40,detected.movements.size)
            assertTrue(detected.movements.all { it.peakRoll in 28.0..36.0 })
            val assessment=DrySkiAnalysis.evaluate(demo.markers,detected,side,true)
            assertEquals(40,assessment.matched)
        }
    }
    @Test fun guidedSetCountsOnlyCompletedMovementsAfterStartAndStopsAtTarget() {
        val trial=GuidedTrial("L","Slow",1000.0,2)
        assertFalse(trial.accept(DryMovement(900.0,1500.0,true,30.0)))
        assertTrue(trial.accept(DryMovement(1100.0,1600.0,true,30.0)))
        assertFalse(trial.accept(DryMovement(1100.0,1600.0,true,30.0)))
        assertFalse(trial.complete)
        assertTrue(trial.accept(DryMovement(2000.0,2600.0,false,30.0)))
        assertTrue(trial.complete)
        assertFalse(trial.accept(DryMovement(3000.0,3600.0,true,30.0)))
        assertEquals(2,trial.count)
    }
    @Test fun comparisonNormalizesMountingSignAndPairsEachMovementOnce() {
        val left=(0..10).map { BootRoll("L",it*20.0,it.toDouble(),0.0) }
        val right=left.map { it.copy(side="R",degrees=-it.degrees) }
        val l=DryDetection(listOf(DryMovement(1000.0,1800.0,true,30.0)),emptyList())
        val r=DryDetection(listOf(DryMovement(1080.0,1890.0,false,30.0),DryMovement(1100.0,1900.0,false,30.0)),emptyList())
        val comparison=BootComparison.analyse(left,right,l,r,true,false)
        assertEquals(0.0,comparison.meanRollDifferenceDeg!!,0.0)
        assertEquals(1.0,comparison.correlation!!,1e-6)
        assertEquals(1,comparison.movementPairs)
        assertEquals(80.0,comparison.medianOnsetLagMs!!,0.0)
        assertEquals(90.0,comparison.medianReturnLagMs!!,0.0)
        val absent=BootComparison.analyse(left,emptyList(),l,DryDetection(emptyList(),emptyList()))
        assertNull(absent.correlation)
        assertNull(absent.meanRollDifferenceDeg)
        val gap=BootComparison.analyse(listOf(left[5]),listOf(right.first(),right.last()),l,r)
        assertEquals(0,gap.anglePairs)
    }
}
