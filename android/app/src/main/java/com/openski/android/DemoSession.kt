package com.openski.android

import kotlin.math.*

/** Deterministic, physically consistent roll-only input. Never presented as a hardware recording. */
object DemoSession {
    fun generate(id: String, startedAtMs: Long): SessionData {
        val live=mutableListOf<StoredLive>()
        val markers=mutableListOf<TestMarker>()
        var markerId=0L
        markers.add(TestMarker(++markerId,startedAtMs+4000,"START_TEST","","synthetic","Synthetic reference, not observed video"))
        for(side in listOf("L","R")) {
            val delay=if(side=="L") 0.0 else 0.08
            val amplitude=if(side=="L") 35.0 else 30.0
            for(index in 0..3300) {
                val elapsed=index*0.02
                val t=elapsed-4-delay
                val phase=if(t>0 && t<60) PI*t/1.5 else 0.0
                val angle=if(t>0 && t<60) Math.toRadians(amplitude)*sin(phase) else 0.0
                val rate=if(t>0 && t<60) Math.toRadians(amplitude)*PI/1.5*cos(phase) else 0.0
                live.add(StoredLive(side,startedAtMs+index*20,SensorSample(index and 65535,index*20L,
                    0f,(9.81*sin(angle)).toFloat(),(9.81*cos(angle)).toFloat(),rate.toFloat(),0f,0f)))
            }
            for(turn in 0 until 40) markers.add(TestMarker(++markerId,
                startedAtMs+(4000+delay*1000+turn*1500).toLong(),if(turn%2==0) "LEFT" else "RIGHT",side,"synthetic","Synthetic movement onset"))
        }
        markers.add(TestMarker(++markerId,startedAtMs+65000,"PAUSE","","synthetic","Synthetic test ends"))
        return SessionData(SkiSession(id,startedAtMs,startedAtMs+66000,3301,3301,null,null,
            title="Simulated boot movements",notes="Synthetic data for app development. No physical sensors, video, battery or RSSI were measured.",
            testSession=true,origin="synthetic"),live.sortedBy { it.receivedAtMs },emptyList(),emptyList(),emptyList(),markers.sortedBy { it.timeMs })
    }
}
