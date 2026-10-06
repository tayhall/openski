package com.openski.android

class GuidedTrial(val side: String, val pace: String, val startedAtMs: Double, val target: Int=20) {
    var count=0
        private set
    val complete get()=count>=target
    private var lastEnd=startedAtMs
    fun accept(movement: DryMovement): Boolean {
        if(complete || movement.startMs<lastEnd || movement.endMs<=movement.startMs) return false
        lastEnd=movement.endMs
        count++
        return true
    }
    fun description()="$pace set · $side boot · $count/$target completed movements" + if(complete) " · set complete" else ""
}
