package com.openski.android

import kotlin.math.*

data class Vector3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(v: Vector3) = Vector3(x+v.x,y+v.y,z+v.z)
    operator fun minus(v: Vector3) = Vector3(x-v.x,y-v.y,z-v.z)
    operator fun times(n: Double) = Vector3(x*n,y*n,z*n)
    fun dot(v: Vector3) = x*v.x+y*v.y+z*v.z
    fun cross(v: Vector3) = Vector3(y*v.z-z*v.y,z*v.x-x*v.z,x*v.y-y*v.x)
    fun norm() = sqrt(dot(this))
    fun unit() = this * (1.0/norm())
}

data class BootCalibration(val side: String, val timeMs: Double, val axis: Int, val sign: Int,
    val gravity: Vector3, val bias: Vector3, val forward: Vector3?=null)
data class BootRoll(val side: String, val timeMs: Double, val degrees: Double, val driftSeconds: Double,
    val pitchDegrees: Double=0.0, val yawDegrees: Double=0.0, val segment: Int=0,
    val rollRateRadps: Double=0.0, val pitchRateRadps: Double=0.0, val yawRateRadps: Double=0.0)

private data class OrientationQuaternion(val w: Double, val x: Double, val y: Double, val z: Double) {
    operator fun times(q: OrientationQuaternion)=OrientationQuaternion(
        w*q.w-x*q.x-y*q.y-z*q.z,w*q.x+x*q.w+y*q.z-z*q.y,
        w*q.y-x*q.z+y*q.w+z*q.x,w*q.z+x*q.y-y*q.x+z*q.w)
    fun unit(): OrientationQuaternion { val n=sqrt(w*w+x*x+y*y+z*z); return OrientationQuaternion(w/n,x/n,y/n,z/n) }
    fun conjugate()=OrientationQuaternion(w,-x,-y,-z)
    fun rotate(v: Vector3): Vector3 {
        val q=this*OrientationQuaternion(0.0,v.x,v.y,v.z)*conjugate()
        return Vector3(q.x,q.y,q.z)
    }
    companion object {
        val identity=OrientationQuaternion(1.0,0.0,0.0,0.0)
        fun increment(omega: Vector3, seconds: Double): OrientationQuaternion {
            val speed=omega.norm()
            if(speed<1e-9) return identity
            val half=speed*seconds/2; val factor=sin(half)/speed
            return OrientationQuaternion(cos(half),omega.x*factor,omega.y*factor,omega.z*factor)
        }
        fun align(from: Vector3,to: Vector3): OrientationQuaternion {
            val dot=from.dot(to).coerceIn(-1.0,1.0)
            if(dot < -0.999999) {
                val orthogonal=from.cross(if(abs(from.x)<0.8) Vector3(1.0,0.0,0.0) else Vector3(0.0,1.0,0.0)).unit()
                return OrientationQuaternion(0.0,orthogonal.x,orthogonal.y,orthogonal.z)
            }
            val axis=from.cross(to)
            return OrientationQuaternion(1+dot,axis.x,axis.y,axis.z).unit()
        }
    }
}
data class RollProfile(val positive: Boolean, val count: Int, val degrees: List<Double>,
    val peakDegrees: Double, val peakPercent: Int)

object BootOrientation {
    private fun acceleration(s: SensorSample) = Vector3(s.accelX.toDouble(),s.accelY.toDouble(),s.accelZ.toDouble())
    private fun rotation(s: SensorSample) = Vector3(s.gyroX.toDouble(),s.gyroY.toDouble(),s.gyroZ.toDouble())
    private fun axis(n: Int) = when(n) { 0->Vector3(1.0,0.0,0.0); 1->Vector3(0.0,1.0,0.0); else->Vector3(0.0,0.0,1.0) }

    /** Two seconds standing still in the neutral boot position selected by the user. */
    fun calibrate(points: List<TimelinePoint>, side: String, startMs: Double, axis: Int, sign: Int): BootCalibration? {
        if(axis !in 0..2 || sign !in listOf(-1,1)) return null
        val window=points.filter { it.side==side && it.timeMs in startMs..startMs+2000 }.sortedBy { it.timeMs }
        if(window.size<40 || window.last().timeMs-window.first().timeMs<1800 ||
            window.zipWithNext().any { (a,b)->b.timeMs-a.timeMs>100 }) return null
        val accelerations=window.map { acceleration(it.sample) }
        val gyros=window.map { rotation(it.sample) }
        val gravity=accelerations.reduce { a,b->a+b }*(1.0/window.size)
        val bias=gyros.reduce { a,b->a+b }*(1.0/window.size)
        if(gravity.norm() !in 9.0..10.6 || gyros.any { it.norm()>0.12 } ||
            accelerations.any { (it-gravity).norm()>0.5 } || abs(gravity.unit().dot(axis(axis)))>0.8) return null
        return BootCalibration(side,window.last().timeMs,axis,sign,gravity.unit(),bias)
    }

    /** Learn an oblique mounting axis from a deliberately pure roll gesture; sign comes from the hint. */
    fun learnForward(points: List<TimelinePoint>, calibration: BootCalibration, startMs: Double, durationMs: Double=5000.0): BootCalibration? {
        val window=points.filter { it.side==calibration.side && it.timeMs in startMs..startMs+durationMs }.sortedBy { it.timeMs }
        if(window.size<40 || window.last().timeMs-window.first().timeMs<1000 ||
            window.zipWithNext().any { (a,b)->b.timeMs-a.timeMs>100 }) return null
        val vectors=window.map { rotation(it.sample)-calibration.bias }
        val energy=vectors.sumOf { it.dot(it) }
        if(energy/window.size<0.01) return null
        val hint=axis(calibration.axis)*calibration.sign.toDouble()
        var direction=hint
        repeat(30) {
            val next=vectors.fold(Vector3(0.0,0.0,0.0)) { sum,v -> sum+v*v.dot(direction) }
            if(next.norm()<1e-6) return null
            direction=next.unit()
        }
        if(vectors.sumOf { it.dot(direction).pow(2) }/energy<0.9 ||
            abs(direction.dot(calibration.gravity))>0.3 || abs(direction.dot(hint))<0.3) return null
        if(direction.dot(hint)<0) direction=direction*(-1.0)
        return calibration.copy(forward=(direction-calibration.gravity*direction.dot(calibration.gravity)).unit())
    }

    /** Propagate gravity in sensor coordinates using all gyro axes. Correct only at detected rest. */
    fun estimate(points: List<TimelinePoint>, calibration: BootCalibration): List<BootRoll> {
        val tracker=Tracker(calibration)
        return points.filter { it.side==calibration.side && it.timeMs>=calibration.timeMs }.sortedBy { it.timeMs }
            .mapNotNull(tracker::accept)
    }

    /** The same state machine powers real-time telemetry and saved-session replay. */
    class Tracker(private val calibration: BootCalibration, awaitRest: Boolean=false) {
        private val up=calibration.gravity
        private val forward=calibration.forward ?: (axis(calibration.axis)-up*up.dot(axis(calibration.axis))).unit()*calibration.sign.toDouble()
        private val lateral=up.cross(forward)
        private var orientation: OrientationQuaternion?=if(awaitRest) null else OrientationQuaternion.identity
        private var previous=calibration.timeMs
        private var restedMs=0.0
        private var anchor=previous
        private var segment=0
        private var yawZero=0.0
        private var seen=false
        fun accept(p: TimelinePoint): BootRoll? {
            if(p.side!=calibration.side || p.timeMs<previous || (seen && p.timeMs==previous)) return null
            val dt=p.timeMs-previous
            seen=true
            previous=p.timeMs
            if(dt>250 || dt<0) { orientation=null; restedMs=0.0; segment++ }
            val omega=rotation(p.sample)-calibration.bias
            val accel=acceleration(p.sample)
            val stationary=omega.norm()<0.08 && accel.norm() in 9.3..10.3
            restedMs=if(stationary) restedMs+dt.coerceIn(0.0,100.0) else 0.0
            orientation=orientation?.let { (it*OrientationQuaternion.increment(omega,dt.coerceIn(0.0,250.0)/1000)).unit() }
            if(restedMs>=2000) {
                val current=orientation
                orientation=if(current==null) OrientationQuaternion.align(accel.unit(),up)
                    else (OrientationQuaternion.align(current.rotate(accel.unit()),up)*current).unit()
                if(current==null) {
                    val heading=orientation!!.rotate(forward)
                    yawZero=atan2(heading.dot(lateral),heading.dot(forward))
                }
                anchor=p.timeMs
            }
            return orientation?.let { q ->
                val g=q.conjugate().rotate(up)
                val heading=q.rotate(forward)
                val yaw=atan2(heading.dot(lateral),heading.dot(forward))-yawZero
                if(hypot(g.dot(lateral),g.dot(up))>0.1)
                    BootRoll(p.side,p.timeMs,Math.toDegrees(atan2(g.dot(lateral),g.dot(up))),
                        (p.timeMs-anchor)/1000,Math.toDegrees(atan2(-g.dot(forward),hypot(g.dot(lateral),g.dot(up)))),
                        Math.toDegrees(atan2(sin(yaw),cos(yaw))),segment,
                        omega.dot(forward),omega.dot(lateral),omega.dot(up))
                else null
            }
        }
    }

    /** Absolute roll magnitude at 21 phase bins, averaged separately by candidate direction. */
    fun profiles(roll: List<BootRoll>, intervals: List<CandidateInterval>): List<RollProfile> {
        val ordered=roll.sortedBy { it.timeMs }
        fun bound(time: Double, inclusive: Boolean): Int {
            var low=0; var high=ordered.size
            while(low<high) {
                val middle=(low+high)/2
                if(ordered[middle].timeMs<time || (inclusive && ordered[middle].timeMs==time)) low=middle+1
                else high=middle
            }
            return low
        }
        val curves=intervals.mapNotNull { interval ->
            val samples=ordered.subList(bound(interval.startMs,false),bound(interval.endMs,true))
            if(samples.size<2 || samples.first().timeMs-interval.startMs>30 ||
                interval.endMs-samples.last().timeMs>30 ||
                samples.zipWithNext().any { (a,b)->b.timeMs-a.timeMs>100 }) return@mapNotNull null
            val curve=(0..20).map { bin ->
                val time=interval.startMs+interval.durationMs*bin/20
                val index=samples.indexOfFirst { it.timeMs>=time }
                when {
                    index<=0 -> abs(if(index<0) samples.last().degrees else samples.first().degrees)
                    else -> { val a=samples[index-1]; val b=samples[index]
                        abs(a.degrees+(b.degrees-a.degrees)*(time-a.timeMs)/(b.timeMs-a.timeMs)) }
                }
            }
            interval.positive to curve
        }
        return listOf(true,false).mapNotNull { positive ->
            val selected=curves.filter { it.first==positive }.map { it.second }
            if(selected.isEmpty()) null else {
                val average=(0..20).map { i->selected.map { it[i] }.average() }
                val peak=average.indices.maxBy { average[it] }
                RollProfile(positive,selected.size,average,average[peak],peak*5)
            }
        }
    }
}
