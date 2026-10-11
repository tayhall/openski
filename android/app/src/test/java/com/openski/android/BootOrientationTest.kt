package com.openski.android

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class BootOrientationTest {
    private fun point(t: Double, gx: Float=0f, ay: Float=0f, az: Float=9.81f) =
        TimelinePoint("L",t,SensorSample(0,t.toLong(),0f,ay,az,gx,0f,0f),"live")
    private val rest=(0..100).map { point(it*20.0) }
    @Test fun calibrationRejectsMotionGapsAndVerticalForwardAxis() {
        assertNotNull(BootOrientation.calibrate(rest,"L",0.0,0,1))
        assertNull(BootOrientation.calibrate(rest,"L",0.0,2,1))
        assertNull(BootOrientation.calibrate(rest.map { it.copy(sample=it.sample.copy(gyroX=0.5f)) },"L",0.0,0,1))
        assertNull(BootOrientation.calibrate(rest.filter { it.timeMs !in 500.0..900.0 },"L",0.0,0,1))
        assertNull(BootOrientation.calibrate(rest,"R",0.0,0,1))
    }
    @Test fun integratesRotationWithoutTreatingDynamicAccelerationAsGravity() {
        val calibration=BootOrientation.calibrate(rest,"L",0.0,0,1)!!
        val moving=(1..100).map { point(2000+it*20.0,(PI/6).toFloat(),12f,12f) }
        val roll=BootOrientation.estimate(rest+moving,calibration)
        assertEquals(60.0,roll.last().degrees,0.05)
        val reversed=BootOrientation.estimate(rest+moving,calibration.copy(sign=-1))
        assertEquals(-60.0,reversed.last().degrees,0.05)
    }
    @Test fun stationaryBiasDoesNotAccumulateAndGapsRequireRest() {
        val biased=rest.map { it.copy(sample=it.sample.copy(gyroX=0.02f)) }
        val calibration=BootOrientation.calibrate(biased,"L",0.0,0,1)!!
        assertEquals(0.0,BootOrientation.estimate(biased,calibration).last().degrees,1e-6)
        val moving=(1..20).map { point(5000+it*20.0,0.5f) }
        assertTrue(BootOrientation.estimate(biased+moving,calibration).none { it.timeMs>2000 })
        val recovery=(0..120).map { point(6000+it*20.0,0.02f) }
        val estimated=BootOrientation.estimate(biased+moving+recovery,calibration)
        assertTrue(estimated.any { it.timeMs>=8000 })
    }
    @Test fun gyroNearRailSampleRestartsTheRestTimerSoCorrectionIsDelayed() {
        val calibration=BootOrientation.calibrate(rest,"L",0.0,0,1)!!
        // 0.2 rad of gyro-only roll while the accelerometer still reports level, then stillness.
        val turn=(1..20).map { point(2000+it*20.0,0.5f) }
        fun still(railIndex: Int?)=(1..160).map { i ->
            val p=point(2400+i*20.0)
            if(i==railIndex) p.copy(sample=p.sample.copy(quality=2)) else p
        }
        fun rollAt(stream: List<TimelinePoint>,t: Double)=
            BootOrientation.Tracker(calibration).let { tr -> stream.mapNotNull(tr::accept) }.first { it.timeMs>=t }.degrees
        val plain=rest+turn+still(null)
        val railed=rest+turn+still(50)
        val integrated=Math.toDegrees(0.2)
        // Sample 110 (t=4600): 2 s of rest has passed without the flag, 1.2 s since it with the flag.
        assertEquals(0.0,rollAt(plain,4600.0),1e-6)
        assertEquals(integrated,rollAt(railed,4600.0),0.05)
        // By sample 160 the restarted timer has also expired and the correction is applied.
        assertEquals(0.0,rollAt(railed,5600.0),1e-6)
    }
    @Test fun accelNearRailSampleAlsoRestartsTheRestTimer() {
        val calibration=BootOrientation.calibrate(rest,"L",0.0,0,1)!!
        val turn=(1..20).map { point(2000+it*20.0,0.5f) }
        val still=(1..130).map { i -> point(2400+i*20.0).let { p ->
            if(i==60) p.copy(sample=p.sample.copy(quality=1)) else p } }
        val tr=BootOrientation.Tracker(calibration)
        val roll=(rest+turn+still).mapNotNull(tr::accept).first { it.timeMs>=2400+110*20.0 }
        assertEquals(Math.toDegrees(0.2),roll.degrees,0.05)
    }
    @Test fun learnedRestBiasShrinksDriftFromAnOffsetThatAppearedAfterCalibration() {
        val calibration=BootOrientation.calibrate(rest,"L",0.0,0,1)!!
        // Offset on all three axes (norm 0.052, still "stationary" to the tracker) that was not present at
        // calibration. Gravity re-alignment pins roll and pitch, so the visible drift is heading (yaw).
        val still=(1..3000).map { i -> point(2000+i*20.0).let { p ->
            p.copy(sample=p.sample.copy(gyroX=0.03f,gyroY=0.03f,gyroZ=0.03f)) } }
        val learned=BootOrientation.Tracker(calibration).let { t -> still.mapNotNull(t::accept) }
        val fixed=BootOrientation.Tracker(calibration,learnRestBias=false).let { t -> still.mapNotNull(t::accept) }
        val learnedDrift=abs(learned.last().yawDegrees)
        val fixedDrift=abs(fixed.last().yawDegrees)
        assertTrue("without learning yaw drifts ($fixedDrift)",fixedDrift>50.0)
        assertTrue("learned $learnedDrift vs fixed $fixedDrift",learnedDrift*5<=fixedDrift)
    }
    @Test fun learnerSeesTheUnsubtractedGyroSoYawDriftFlattensAtTheFullOffset() {
        val calibration=BootOrientation.calibrate(rest,"L",0.0,0,1)!!
        val still=(1..3000).map { i -> point(2000+i*20.0).let { p ->
            p.copy(sample=p.sample.copy(gyroZ=0.03f)) } }
        val tracker=BootOrientation.Tracker(calibration)
        val yaw=still.mapNotNull(tracker::accept)
        // If the learner saw its own corrected output it would settle near half the offset (0.015 rad/s,
        // about 26 degrees over 30 s). Converged to the full offset, yaw is flat after about 20 s.
        val at30=yaw.first { it.timeMs>=32000.0 }.yawDegrees
        val at60=yaw.last().yawDegrees
        assertTrue("yaw still moving: $at30 -> $at60",abs(at60-at30)<2.0)
    }
    @Test fun estimatesPitchAndRelativeYawInTheBootFrame() {
        val calibration=BootOrientation.calibrate(rest,"L",0.0,0,1)!!
        val pitch=(1..100).map { point(2000+it*20.0).let { p -> p.copy(sample=p.sample.copy(gyroY=(PI/6).toFloat(),accelZ=12f)) } }
        assertEquals(60.0,BootOrientation.estimate(rest+pitch,calibration).last().pitchDegrees,0.05)
        val yaw=(1..100).map { point(2000+it*20.0).let { p -> p.copy(sample=p.sample.copy(gyroZ=(PI/6).toFloat())) } }
        val result=BootOrientation.estimate(rest+yaw,calibration).last()
        assertEquals(60.0,result.yawDegrees,0.05)
        assertEquals(0.0,result.degrees,0.05)
        assertEquals(PI/6,result.yawRateRadps,0.001)
    }
    @Test fun learnsAnObliqueForwardAxisFromPureRoll() {
        val calibration=BootOrientation.calibrate(rest,"L",0.0,0,1)!!
        val axis=Vector3(0.8,0.6,0.0)
        val gesture=(0..250).map { i ->
            val speed=sin(i*20.0*PI/1500)*0.7
            point(2000+i*20.0).let { p -> p.copy(sample=p.sample.copy(gyroX=(speed*axis.x).toFloat(),gyroY=(speed*axis.y).toFloat())) }
        }
        val learned=BootOrientation.learnForward(gesture,calibration,2000.0)!!
        assertEquals(0.8,learned.forward!!.x,0.001)
        assertEquals(0.6,learned.forward.y,0.001)
        val moving=(1..100).map { i -> point(2000+i*20.0).let { p -> p.copy(sample=p.sample.copy(
            gyroX=(PI/6*axis.x).toFloat(),gyroY=(PI/6*axis.y).toFloat(),accelZ=12f)) } }
        assertEquals(60.0,BootOrientation.estimate(moving,learned).last().degrees,0.05)
        assertEquals(0.0,BootOrientation.estimate(moving,learned).last().pitchDegrees,0.05)
        assertNull(BootOrientation.learnForward(rest,calibration,0.0))
        assertNull(BootOrientation.learnForward(gesture.map { it.copy(sample=it.sample.copy(gyroX=0f,gyroY=0f,gyroZ=0.8f)) },calibration,2000.0))
    }
    @Test fun profilesInterpolateUnequalDurationsAndRejectGaps() {
        val roll=(0..300).map { val t=it*10.0
            BootRoll("L",t,if(t<=1000) t/1000*40 else (t-1000)/2000*60,0.0) }
        val intervals=listOf(CandidateInterval(0.0,1000.0,true),CandidateInterval(1000.0,3000.0,false))
        val profiles=BootOrientation.profiles(roll,intervals)
        assertEquals(2,profiles.size)
        assertEquals(20.0,profiles.first().degrees[10],1e-6)
        assertEquals(30.0,profiles.last().degrees[10],1e-6)
        assertEquals(100,profiles.last().peakPercent)
        assertEquals(1,BootOrientation.profiles(roll.filter { it.timeMs !in 400.0..700.0 },intervals).size)
    }
}
