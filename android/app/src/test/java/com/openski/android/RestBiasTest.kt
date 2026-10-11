package com.openski.android

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

/** Ports tools/test_rest_bias.cpp: same rule, same thresholds, time in ms. */
class RestBiasTest {
    private val g=9.80665
    private var seed=12345L
    private fun noise(amp: Double): Double {
        seed=(seed*1664525L+1013904223L) and 0xFFFFFFFFL
        return (((seed shr 16) and 0xff).toInt()-128)/128.0*amp
    }
    @Test fun interruptingTheWindowStopsAnUpdateAcrossIt() {
        val plain=RestBias()
        feedRest(plain,0.0,2.5,0.02)
        assertEquals(1,plain.updates)
        val broken=RestBias()
        var t=feedRest(broken,0.0,1.0,0.02)
        broken.interrupt()
        feedRest(broken,t,1.5,0.02)
        assertEquals(0,broken.updates)
        assertEquals(0.0,broken.estimate.x,0.0)
    }
    private fun v(x: Double,y: Double=0.0,z: Double=0.0)=Vector3(x,y,z)
    /** 100 Hz at rest (gravity on z) with a constant gyro offset on x and optional noise; returns the new time. */
    private fun feedRest(r: RestBias,t: Double,seconds: Double,offsetX: Double,noiseAmp: Double=0.0): Double {
        var time=t
        repeat((seconds*100).toInt()) {
            r.update(time,v(0.0,0.0,g),v(offsetX+noise(noiseAmp),noise(noiseAmp),noise(noiseAmp)))
            time+=10.0
        }
        return time
    }

    @Test fun constantOffsetConvergesAndNeverFasterThanRateLimit() {
        val r=RestBias(); var t=1000.0; val t0=t
        var at5=-1.0
        for(i in 0 until 3000) {
            r.update(t,v(0.0,0.0,g),v(0.04))
            val elapsed=(t-t0)/1000
            assertTrue("rate bound at $elapsed s",r.estimate.x<=0.002*elapsed+1e-5)
            if(i==500) at5=r.estimate.x
            t+=10.0
        }
        assertTrue(at5<=0.010); assertTrue(at5>0.004)
        assertEquals(0.04,r.estimate.x,0.005)
        assertEquals(0.0,r.estimate.y,1e-9); assertEquals(0.0,r.estimate.z,1e-9)
        assertTrue(r.updates>=10); assertEquals(0,r.refused)
    }

    @Test fun steadyRotationWithTurningGravityIsNotAbsorbed() {
        val r=RestBias(); var t=0.0; val w=0.10
        for(i in 0 until 6000) {
            val a=w*i*0.01
            r.update(t,v(0.0,g*sin(a),g*cos(a)),v(w)); t+=10.0
        }
        assertEquals(0,r.updates)
        assertEquals(0.0,r.estimate.x,1e-9)
    }

    @Test fun largeOffsetIsRefused() {
        val r=RestBias(); feedRest(r,0.0,20.0,0.2)
        assertTrue(r.refused>0); assertEquals(0,r.updates); assertEquals(0.0,r.estimate.x,0.0)
    }

    @Test fun offsetOf013IsRefusedByTheOverLimitRule() {
        val r=RestBias(); feedRest(r,0.0,20.0,0.13)
        assertTrue(r.refused>0); assertEquals(0,r.updates); assertEquals(0.0,r.estimate.x,0.0)
    }

    @Test fun steadyVerticalTurnOf011IsBoundedAndNotRefused() {
        val r=RestBias(); var t=0.0; val t0=t
        for(i in 0 until 12000) {
            r.update(t,v(0.0,0.0,g),v(0.0,0.0,0.11))
            val elapsed=(t-t0)/1000
            assertTrue(abs(r.estimate.z)<=0.002*elapsed+1e-5)
            assertTrue(abs(r.estimate.z)<=0.11+1e-5)
            t+=10.0
        }
        assertEquals(0,r.refused); assertTrue(r.updates>0)
    }

    @Test fun nonFiniteInputsEndTheWindowAndRecover() {
        val r=RestBias(); var t=feedRest(r,0.0,1.5,0.04)
        assertTrue(r.resting)
        r.update(t,v(0.0,0.0,g),v(Double.NaN)); t+=10.0
        assertFalse(r.resting)
        r.update(t,v(Double.POSITIVE_INFINITY,0.0,g),v(0.04)); t+=10.0
        t=feedRest(r,t,1.5,0.04)
        assertEquals(0,r.updates)
        feedRest(r,t,1.0,0.04)
        assertEquals(1,r.updates); assertTrue(r.estimate.x.isFinite())
    }

    @Test fun accelOf15gNeverOpensAWindow() {
        val r=RestBias(); var t=0.0; var ever=false
        for(i in 0 until 3000) {
            r.update(t,v(0.0,0.0,1.5*g),v(0.03)); ever=ever||r.resting; t+=10.0
        }
        assertFalse(ever); assertEquals(0,r.updates); assertEquals(0,r.refused)
    }

    @Test fun gapOf150MsResetsTheWindow() {
        val r=RestBias(); var t=feedRest(r,0.0,1.5,0.04)
        assertTrue(r.resting)
        t+=150.0
        t=feedRest(r,t,1.5,0.04)
        assertEquals(0,r.updates)
        feedRest(r,t,1.0,0.04)
        assertEquals(1,r.updates)
    }

    @Test fun noiseDoesNotStopConvergence() {
        val r=RestBias(); feedRest(r,0.0,40.0,0.04,0.01)
        assertTrue(r.updates>=10)
        assertEquals(0.04,r.estimate.x,0.005)
        assertTrue(abs(r.estimate.y)<0.005)
    }
}
