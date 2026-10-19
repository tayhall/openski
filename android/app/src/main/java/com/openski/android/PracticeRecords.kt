package com.openski.android

import java.util.UUID
import org.json.JSONArray

enum class PracticeStatus { ACTIVE, COMPLETED, INCOMPLETE, INTERRUPTED }

/** Targets are snapshots, never reconstructed from a historical drill title. */
data class PracticeTarget(val movements: Int, val beatSeconds: Double, val depthDegrees: Double) {
    init {
        require(movements > 0 && beatSeconds.isFinite() && beatSeconds > 0 && depthDegrees.isFinite() && depthDegrees > 0)
    }
    companion object {
        fun of(drill: Drill) = PracticeTarget(drill.movements, drill.paceSeconds, drill.depthDegrees)
    }
}

data class Attempt(
    val drillId: String, val timeMs: Long, val score: Int, val stars: Int, val vert: Int, val demo: Boolean,
    val id: String = UUID.randomUUID().toString(), val sessionId: String? = null,
    val status: PracticeStatus = PracticeStatus.COMPLETED, val target: PracticeTarget? = null,
    val scoringVersion: Int = 1,
    val review: PracticeReview? = null,
) {
    val eligible get() = status == PracticeStatus.COMPLETED && review?.scoreEligible != false
    val resultLabel get() = if (status == PracticeStatus.COMPLETED && !eligible) "Completed · cannot judge" else status.name.lowercase()
}

/** Strict import: malformed history must fail without marking the import complete or dropping rows. */
object LegacyPractice {
    fun decode(json: String): List<Attempt> {
        val rows = JSONArray(json)
        return (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            Attempt(row.getString("drill"), row.getLong("time"), row.getInt("score"), row.getInt("stars"),
                row.getInt("vert"), row.getBoolean("demo"), id = "legacy-preferences-$index")
        }
    }
}
