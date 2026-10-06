package com.openski.android

import kotlin.math.*

data class BootComparisonResult(val anglePairs: Int, val meanRollDifferenceDeg: Double?,
    val correlation: Double?, val movementPairs: Int, val medianOnsetLagMs: Double?, val medianReturnLagMs: Double?)

object BootComparison {
    private fun median(values: List<Double>): Double? {
        if(values.isEmpty()) return null
        val sorted=values.sorted()
        return (sorted[(sorted.size-1)/2]+sorted[sorted.size/2])/2
    }
    fun analyse(left: List<BootRoll>, right: List<BootRoll>, leftMovements: DryDetection,
        rightMovements: DryDetection, leftPositiveLeft: Boolean=true, rightPositiveLeft: Boolean=true): BootComparisonResult {
        val ordered=right.sortedBy { it.timeMs }
        val pairs=mutableListOf<Pair<Double,Double>>()
        var index=0
        for(l in left.sortedBy { it.timeMs }) {
            while(index<ordered.size && ordered[index].timeMs<l.timeMs) index++
            val b=ordered.getOrNull(index) ?: continue
            val a=ordered.getOrNull(index-1)
            val r=if(b.timeMs==l.timeMs) b.degrees else {
                if(a==null || b.timeMs-a.timeMs>100 || a.segment!=b.segment ||
                    l.timeMs-a.timeMs>50 || b.timeMs-l.timeMs>50) continue
                a.degrees+(b.degrees-a.degrees)*(l.timeMs-a.timeMs)/(b.timeMs-a.timeMs)
            }
            pairs.add((l.degrees*if(leftPositiveLeft) 1 else -1) to (r*if(rightPositiveLeft) 1 else -1))
        }
        val meanL=pairs.map { it.first }.takeIf { it.isNotEmpty() }?.average() ?: 0.0
        val meanR=pairs.map { it.second }.takeIf { it.isNotEmpty() }?.average() ?: 0.0
        val covariance=pairs.sumOf { (it.first-meanL)*(it.second-meanR) }
        val denominator=sqrt(pairs.sumOf { (it.first-meanL).pow(2) }*pairs.sumOf { (it.second-meanR).pow(2) })
        val options=leftMovements.movements.flatMapIndexed { li,l -> rightMovements.movements.mapIndexedNotNull { ri,r ->
            if((l.positive==leftPositiveLeft)==(r.positive==rightPositiveLeft) && abs(r.startMs-l.startMs)<=500)
                Triple(li,ri,r.startMs-l.startMs) else null
        } }.sortedBy { abs(it.third) }
        val usedL=mutableSetOf<Int>(); val usedR=mutableSetOf<Int>()
        val onset=mutableListOf<Double>(); val returns=mutableListOf<Double>()
        for((li,ri,lag) in options) if(li !in usedL && ri !in usedR) {
            usedL.add(li); usedR.add(ri); onset.add(lag)
            returns.add(rightMovements.movements[ri].endMs-leftMovements.movements[li].endMs)
        }
        return BootComparisonResult(pairs.size,pairs.takeIf { it.isNotEmpty() }?.map { abs(it.first-it.second) }?.average(),
            if(pairs.size>=3 && denominator>1e-6) (covariance/denominator).coerceIn(-1.0,1.0) else null,
            onset.size,median(onset),median(returns))
    }
}
