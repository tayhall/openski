package com.openski.android

import kotlin.math.abs
import kotlin.math.roundToInt

/** Skill goal chosen in onboarding. It decides which piste Today points toward. */
enum class Goal(val title: String, val blurb: String, val piste: Piste) {
    BALANCE("Balance and rhythm", "Smooth, even movement from boot to boot.", Piste.BLUE),
    CARVING("Carving", "Roll onto the edges and let the ski do the turning.", Piste.RED),
    SHORT_TURNS("Short turns", "Quick, tight turns that stay in rhythm.", Piste.BLACK),
}

enum class Piste(val title: String, val tagline: String) {
    BLUE("Foundations", "Roll, balance and rhythm"),
    RED("Carving", "Deeper edges, cleaner changes"),
    BLACK("Short turns", "Quick edges at pace"),
}

enum class Experience(val title: String, val blurb: String, val startsOn: Piste) {
    NEW("New to technique work", "Start with the basics.", Piste.BLUE),
    COMFORTABLE("Comfortable on blue and red runs", "Skip ahead to carving.", Piste.RED),
    ADVANCED("Confident on black runs", "Jump to short turns.", Piste.BLACK),
}

/**
 * A dry-ski drill measured from boot roll. [paceSeconds] is the target time for one lean-and-return,
 * [depthDegrees] the target peak boot roll. These are training targets, not validated on-snow technique.
 */
data class Drill(
    val id: String,
    val piste: Piste,
    val title: String,
    val summary: String,
    val cue: String,
    val movements: Int,
    val paceSeconds: Double,
    val depthDegrees: Double,
) {
    val minutes: Int get() = maxOf(1, (movements * paceSeconds / 60).roundToInt())
}

object Programme {
    val drills = listOf(
        Drill("slow-rolls", Piste.BLUE, "Slow rolls", "Roll onto one edge, then the other, slowly and evenly.",
            "Roll your ankles first and let the knees follow. Keep your chest quiet.", 10, 3.0, 15.0),
        Drill("even-both-ways", Piste.BLUE, "Even both ways", "Match your left and right rolls for depth and time.",
            "Count the same beat each side. Look for the weaker side and give it equal time.", 12, 2.4, 20.0),
        Drill("steady-rhythm", Piste.BLUE, "Steady rhythm", "Hold one tempo for a whole set.",
            "Breathe out as you roll in. Let the rhythm come from the legs, not the arms.", 16, 2.0, 20.0),
        Drill("deeper-edges", Piste.RED, "Deeper edges", "Roll further without losing control of the return.",
            "Reach with the knee toward the centre of the turn and keep the hips low.", 12, 2.2, 30.0),
        Drill("quick-edge-change", Piste.RED, "Quick edge change", "Switch edges faster at the same depth.",
            "Release the old edge before you commit to the new one.", 14, 1.6, 30.0),
        Drill("link-the-edges", Piste.RED, "Link the edges", "A longer set with depth, tempo and balance together.",
            "Let one roll flow into the next with no pause at neutral.", 20, 1.8, 32.0),
        Drill("short-radius-rhythm", Piste.BLACK, "Short-radius rhythm", "Quick, small rolls at a steady beat.",
            "Keep the upper body facing downhill. Move only from the hips down.", 16, 1.2, 25.0),
        Drill("tighter-quicker", Piste.BLACK, "Tighter and quicker", "Shorten each roll and keep both sides even.",
            "Stay tall and light. Let the skis come back under you.", 18, 1.0, 25.0),
        Drill("short-turn-set", Piste.BLACK, "Short-turn set", "A full set at short-turn pace.",
            "Commit to the rhythm and keep the shape of every roll the same.", 20, 0.9, 25.0),
    )

    fun drill(id: String): Drill? = drills.firstOrNull { it.id == id }
    fun drills(piste: Piste) = drills.filter { it.piste == piste }
}

data class DrillResult(
    val score: Int,
    val stars: Int,
    val tempo: Int,
    val steadiness: Int,
    val balance: Int,
    val depth: Int,
    val completed: Int,
    val cue: String,
) {
    val passed get() = stars >= 1
    /** Vertical metres earned. Replaying a drill still pays, but only a little. */
    fun vert(previousBest: Int?): Int = if (previousBest == null) score * 2 else maxOf(score / 10, (score - previousBest) * 2)
}

object DrillScoring {
    const val PASS = 60
    const val GOOD = 75
    const val GREAT = 90

    /** Maps [value] from a perfect-at-[ideal], zero-at-[ideal]±[tolerance] triangle onto 0..100. */
    private fun closeness(value: Double, ideal: Double, tolerance: Double) =
        (100 * (1 - abs(value - ideal) / tolerance)).coerceIn(0.0, 100.0)

    fun score(drill: Drill, detection: DryDetection): DrillResult {
        val moves = detection.movements.take(drill.movements)
        if (moves.isEmpty()) return DrillResult(0, 0, 0, 0, 0, 0, 0,
            "No movements were detected. Hold still, calibrate, then roll from neutral.")
        // The beat is the time between the starts of consecutive rolls, which is what "one roll every 3 s" means
        // to a skier. A detected movement only spans the time outside neutral, so its length would understate it.
        val beats = if (moves.size < 2) moves.map { (it.endMs - it.startMs) / 1000 }
            else moves.zipWithNext { a, b -> (b.startMs - a.startMs) / 1000 }
        val mean = beats.average()
        // 50% either side of the target beat scores zero.
        val tempo = closeness(mean, drill.paceSeconds, drill.paceSeconds * 0.5)
        val spread = if (beats.size < 2) 1.0 else {
            val variance = beats.sumOf { (it - mean) * (it - mean) } / (beats.size - 1)
            kotlin.math.sqrt(variance) / mean
        }
        val steadiness = (100 * (1 - spread / 0.35)).coerceIn(0.0, 100.0)
        val balance = DrySkiAnalysis.balance(DryDetection(moves, emptyList()))?.let {
            (it.durationRatioPercent + it.peakRatioPercent) / 2
        } ?: 50.0
        val depth = closeness(moves.map { it.peakRoll }.average(), drill.depthDegrees, drill.depthDegrees * 0.6)
        val coverage = moves.size.toDouble() / drill.movements
        val raw = (tempo + steadiness + balance + depth) / 4 * coverage
        val score = raw.roundToInt().coerceIn(0, 100)
        val stars = when { score >= GREAT -> 3; score >= GOOD -> 2; score >= PASS -> 1; else -> 0 }
        val scored = listOf("tempo" to tempo, "steadiness" to steadiness, "balance" to balance, "depth" to depth)
        return DrillResult(score, stars, tempo.roundToInt(), steadiness.roundToInt(), balance.roundToInt(),
            depth.roundToInt(), moves.size, cue(drill, scored.minBy { it.second }, mean, moves, coverage))
    }

    private fun cue(drill: Drill, weakest: Pair<String, Double>, meanSeconds: Double,
                    moves: List<DryMovement>, coverage: Double): String {
        if (coverage < 0.8) return "You completed ${moves.size} of ${drill.movements} movements. Keep going to the end of the set."
        if (weakest.second >= 85) return "Clean set. Every part was close to target."
        return when (weakest.first) {
            "tempo" -> if (meanSeconds > drill.paceSeconds) "You were slower than the ${"%.1f".format(drill.paceSeconds)} second beat. Roll a little sooner."
                       else "You were faster than the ${"%.1f".format(drill.paceSeconds)} second beat. Give each roll more time."
            "steadiness" -> "Your timing drifted from roll to roll. Count the beat out loud."
            "balance" -> {
                val left = moves.filter { it.positive }; val right = moves.filter { !it.positive }
                val longer = if (left.isNotEmpty() && right.isNotEmpty() &&
                    left.map { it.endMs - it.startMs }.average() > right.map { it.endMs - it.startMs }.average()) "first" else "second"
                "One side was slower or shallower than the other (the $longer direction was longer). Give the weaker side equal time."
            }
            else -> {
                val mean = moves.map { it.peakRoll }.average()
                if (mean < drill.depthDegrees) "You rolled about ${mean.roundToInt()}°. Aim for ${drill.depthDegrees.roundToInt()}° and reach with the knee."
                else "You rolled about ${mean.roundToInt()}°, past the ${drill.depthDegrees.roundToInt()}° target. Keep the roll smooth rather than deep."
            }
        }
    }
}

data class Attempt(val drillId: String, val timeMs: Long, val score: Int, val stars: Int, val vert: Int, val demo: Boolean)

/** Pure progress rules, so streaks and unlocks are testable without a device. */
class Progress(val attempts: List<Attempt>, private val startsOn: Piste = Piste.BLUE,
               private val zone: java.time.ZoneId = java.time.ZoneId.systemDefault()) {
    val vert: Int get() = attempts.sumOf { it.vert }
    fun best(drillId: String): Attempt? = attempts.filter { it.drillId == drillId }.maxByOrNull { it.score }
    fun stars(drillId: String) = best(drillId)?.stars ?: 0

    /** A drill opens once the one before it in the same piste is passed; the first drill of an unlocked piste is open. */
    fun unlocked(drill: Drill): Boolean {
        if (drill.piste.ordinal < startsOn.ordinal) return true
        val list = Programme.drills(drill.piste)
        val index = list.indexOf(drill)
        if (index > 0) return stars(list[index - 1].id) >= 1
        if (drill.piste.ordinal == startsOn.ordinal) return true
        val earlier = Programme.drills(Piste.entries[drill.piste.ordinal - 1])
        return earlier.all { stars(it.id) >= 1 }
    }

    fun next(goal: Goal): Drill? {
        val reachable = Programme.drills.filter { it.piste.ordinal >= startsOn.ordinal && unlocked(it) }
        val fresh = reachable.filter { stars(it.id) == 0 }
        // Prefer the goal's piste, then the next fresh drill in path order, then the weakest drill to replay.
        return fresh.firstOrNull { it.piste == goal.piste } ?: fresh.firstOrNull()
            ?: reachable.minByOrNull { best(it.id)?.score ?: 0 }
    }

    private fun day(timeMs: Long) = java.time.Instant.ofEpochMilli(timeMs).atZone(zone).toLocalDate()
    val days: Set<java.time.LocalDate> get() = attempts.map { day(it.timeMs) }.toSet()

    /** Consecutive training days ending today, or yesterday if today has no drill yet. */
    fun streak(nowMs: Long): Int {
        var cursor = day(nowMs)
        if (cursor !in days) cursor = cursor.minusDays(1)
        var count = 0
        while (cursor in days) { count++; cursor = cursor.minusDays(1) }
        return count
    }

    fun week(nowMs: Long): List<Pair<java.time.LocalDate, Boolean>> {
        val today = day(nowMs)
        val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
        return (0..6).map { monday.plusDays(it.toLong()).let { d -> d to (d in days) } }
    }
}
