package com.openski.android

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Slow gyro-bias estimator that learns only while the boot is demonstrably at rest. Kotlin port of
 * lib/Motion/src/RestBias.h (same rule and constants; time in milliseconds). Inputs are already corrected
 * (accel m/s^2, gyro rad/s with the static calibration bias removed); [estimate] is the additional
 * offset the caller subtracts from the gyro. Rate limited so it cannot chase real motion.
 * Experimental: thresholds are guesses (docs/technique-research-integration.md).
 */
class RestBias {
    private val estimateArr=DoubleArray(3)
    private val sum=DoubleArray(3)
    private val aBuf=DoubleArray(3); private val gBuf=DoubleArray(3)
    private var cached=Vector3(0.0,0.0,0.0)
    private val gMin=DoubleArray(3); private val gMax=DoubleArray(3)
    private val aMin=DoubleArray(3); private val aMax=DoubleArray(3)
    private var startMs=0.0; private var lastMs=0.0; private var n=0
    private var open=false; private var overLimit=false

    var updates=0; private set
    var refused=0; private set
    val estimate: Vector3 get()=cached
    /** True while an eligible window is open and no sample in it reached the gyro limit (not "still for 2 s"). */
    val resting: Boolean get()=open && !overLimit

    fun update(timeMs: Double,accel: Vector3,gyro: Vector3) {
        val total=accel.x+accel.y+accel.z+gyro.x+gyro.y+gyro.z
        if(!total.isFinite()) { closeWindow(); return }
        if(open && (timeMs<lastMs || timeMs-lastMs>MAX_STEP_MS)) closeWindow()
        val a=aBuf; val g=gBuf
        a[0]=accel.x; a[1]=accel.y; a[2]=accel.z
        g[0]=gyro.x; g[1]=gyro.y; g[2]=gyro.z
        val amag=sqrt(a[0]*a[0]+a[1]*a[1]+a[2]*a[2])
        if(abs(amag-GRAVITY)/GRAVITY>=STILL_ACCEL_FRACTION) { closeWindow(); return }
        if(!open) beginWindow(timeMs,a,g)
        else if(!extendWindow(timeMs,a,g)) {
            // Motion inside the window: drop it and restart from this (still) sample.
            closeWindow()
            beginWindow(timeMs,a,g)
        }
        if(open && lastMs-startMs>=WINDOW_MS) finishWindow()
    }

    /** Close any open window without learning (a near-rail sample or other untrusted data splits it). */
    fun interrupt() { closeWindow() }

    private fun beginWindow(timeMs: Double,a: DoubleArray,g: DoubleArray) {
        open=true; startMs=timeMs; lastMs=timeMs; n=1; overLimit=false
        for(i in 0..2) {
            sum[i]=g[i]; gMin[i]=g[i]; gMax[i]=g[i]; aMin[i]=a[i]; aMax[i]=a[i]
        }
        noteMagnitude(g)
    }

    /** Returns false when the window's peak-to-peak limits are exceeded (window not extended). */
    private fun extendWindow(timeMs: Double,a: DoubleArray,g: DoubleArray): Boolean {
        for(i in 0..2) {
            val gLo=minOf(g[i],gMin[i]); val gHi=maxOf(g[i],gMax[i])
            val aLo=minOf(a[i],aMin[i]); val aHi=maxOf(a[i],aMax[i])
            if(gHi-gLo>=GYRO_PEAK_TO_PEAK || aHi-aLo>=ACCEL_PEAK_TO_PEAK) return false
        }
        for(i in 0..2) {
            sum[i]+=g[i]
            gMin[i]=minOf(gMin[i],g[i]); gMax[i]=maxOf(gMax[i],g[i])
            aMin[i]=minOf(aMin[i],a[i]); aMax[i]=maxOf(aMax[i],a[i])
        }
        n++
        lastMs=timeMs
        noteMagnitude(g)
        return true
    }

    private fun noteMagnitude(g: DoubleArray) {
        if(sqrt(g[0]*g[0]+g[1]*g[1]+g[2]*g[2])>=STILL_GYRO_RADPS) overLimit=true
    }

    private fun closeWindow() { open=false; n=0; overLimit=false }

    private fun finishWindow() {
        val seconds=(lastMs-startMs)/1000.0
        val target=DoubleArray(3)
        var refuse=overLimit  // too fast to be rest, e.g. a steady turn or a large offset
        for(i in 0..2) {
            target[i]=sum[i]/n
            if(abs(target[i])>REFUSE_RADPS) refuse=true
        }
        if(refuse) refused++
        else {
            val maxStep=MAX_RATE_RADPS_PER_S*seconds
            for(i in 0..2) {
                val d=(target[i]-estimateArr[i]).coerceIn(-maxStep,maxStep)
                estimateArr[i]=(estimateArr[i]+d).coerceIn(-CLAMP_RADPS,CLAMP_RADPS)
            }
            cached=Vector3(estimateArr[0],estimateArr[1],estimateArr[2])
            updates++
        }
        closeWindow()
    }

    companion object {
        const val GRAVITY=9.80665
        const val STILL_GYRO_RADPS=0.12
        const val STILL_ACCEL_FRACTION=0.03
        const val GYRO_PEAK_TO_PEAK=0.05
        const val ACCEL_PEAK_TO_PEAK=0.5
        const val MAX_STEP_MS=100.0
        const val WINDOW_MS=2000.0
        const val MAX_RATE_RADPS_PER_S=0.002
        const val REFUSE_RADPS=0.15
        const val CLAMP_RADPS=0.15
    }
}
