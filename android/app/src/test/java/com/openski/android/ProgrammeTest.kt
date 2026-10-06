package com.openski.android

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class ProgrammeTest {
    private val drill = Programme.drill("steady-rhythm")!!
    /** [beat] seconds between roll starts, alternating by [jitter]; each roll lasts 80% of its beat. */
    private fun moves(count: Int, beat: Double, peak: Double, jitter: Double = 0.0): DryDetection {
        var start = 0.0
        return DryDetection((0 until count).map {
            val interval = beat * 1000 * (1 + if (it % 2 == 0) jitter else -jitter)
            DryMovement(start, start + interval * 0.8, it % 2 == 0, peak).also { start += interval }
        }, emptyList())
    }

    @Test fun perfectSetOnTargetScoresFullMarks() {
        val result = DrillScoring.score(drill, moves(drill.movements, drill.paceSeconds, drill.depthDegrees))
        assertEquals(100, result.score)
        assertEquals(3, result.stars)
    }

    @Test fun shortSetIsScaledByCoverageAndSaysSo() {
        val result = DrillScoring.score(drill, moves(drill.movements / 2, drill.paceSeconds, drill.depthDegrees))
        assertEquals(50, result.score)
        assertEquals(0, result.stars)
        assertTrue(result.cue.contains("${drill.movements / 2} of ${drill.movements}"))
    }

    @Test fun emptyDetectionGivesNoScoreAndAnActionableCue() {
        val result = DrillScoring.score(drill, DryDetection(emptyList(), emptyList()))
        assertEquals(0, result.score)
        assertTrue(result.cue.contains("calibrate"))
    }

    @Test fun cueNamesTheWeakestPart() {
        val slow = DrillScoring.score(drill, moves(drill.movements, drill.paceSeconds * 1.4, drill.depthDegrees))
        assertTrue(slow.cue.contains("slower"))
        val shallow = DrillScoring.score(drill, moves(drill.movements, drill.paceSeconds, drill.depthDegrees * 0.5))
        assertTrue(shallow.cue.contains("Aim for"))
        val uneven = DrillScoring.score(drill, moves(drill.movements, drill.paceSeconds, drill.depthDegrees, jitter = 0.4))
        assertTrue(uneven.score < 100)
    }

    @Test fun demoBootsGetSteadierWithPractice() {
        val scores = (0..5).map { DrillScoring.score(drill, DemoDrill.simulate(drill, it).detection).score }
        assertTrue("$scores", scores.last() > scores.first())
        assertTrue("$scores", scores.last() >= DrillScoring.PASS)
        assertTrue(DemoDrill.simulate(drill, 2).demo)
    }

    @Test fun demoRunsAreDeterministic() {
        val a = DemoDrill.simulate(drill, 1); val b = DemoDrill.simulate(drill, 1)
        assertEquals(a.detection, b.detection)
        assertArrayEquals(a.trace.degrees, b.trace.degrees, 0f)
    }

    @Test fun everyDrillIsReachableAndPlayable() {
        assertEquals(9, Programme.drills.size)
        assertEquals(Programme.drills.size, Programme.drills.map { it.id }.toSet().size)
        for (d in Programme.drills) assertTrue(d.id, DrillScoring.score(d, DemoDrill.simulate(d, 6).detection).passed)
    }

    private fun attempt(id: String, stars: Int, day: LocalDate, score: Int = 70, demo: Boolean = false) =
        Attempt(id, day.atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli(), score, stars, score, demo)

    @Test fun drillsUnlockInOrderAndPistesUnlockAfterTheLastDrill() {
        val day = LocalDate.of(2026, 10, 6)
        val none = Progress(emptyList(), Piste.BLUE, ZoneOffset.UTC)
        assertTrue(none.unlocked(Programme.drills[0]))
        assertFalse(none.unlocked(Programme.drills[1]))
        assertFalse(none.unlocked(Programme.drill("deeper-edges")!!))
        val blueDone = Progress(Programme.drills(Piste.BLUE).map { attempt(it.id, 1, day) }, Piste.BLUE, ZoneOffset.UTC)
        assertTrue(blueDone.unlocked(Programme.drill("deeper-edges")!!))
        assertFalse(blueDone.unlocked(Programme.drill("short-radius-rhythm")!!))
    }

    @Test fun experiencedSkiersSkipEarlierPistes() {
        val skipped = Progress(emptyList(), Piste.RED, ZoneOffset.UTC)
        assertTrue(skipped.unlocked(Programme.drill("deeper-edges")!!))
        assertEquals("deeper-edges", skipped.next(Goal.CARVING)?.id)
        assertEquals("deeper-edges", skipped.next(Goal.BALANCE)?.id)
    }

    @Test fun nextDrillPrefersTheGoalThenFallsBackToTheWeakestReplay() {
        val day = LocalDate.of(2026, 10, 6)
        assertEquals("slow-rolls", Progress(emptyList(), Piste.BLUE, ZoneOffset.UTC).next(Goal.SHORT_TURNS)?.id)
        val all = Progress(Programme.drills.mapIndexed { i, d -> attempt(d.id, 1, day, 60 + i) }, Piste.BLUE, ZoneOffset.UTC)
        assertEquals("slow-rolls", all.next(Goal.CARVING)?.id)
    }

    @Test fun streakCountsConsecutiveDaysAndSurvivesToday() {
        val today = LocalDate.of(2026, 10, 6)
        val now = today.atTime(18, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        val progress = Progress(listOf(attempt("a", 1, today.minusDays(1)), attempt("a", 1, today.minusDays(2)),
            attempt("a", 1, today.minusDays(4))), Piste.BLUE, ZoneOffset.UTC)
        assertEquals(2, progress.streak(now))
        val withToday = Progress(progress.attempts + attempt("b", 1, today), Piste.BLUE, ZoneOffset.UTC)
        assertEquals(3, withToday.streak(now))
        assertEquals(0, Progress(emptyList(), Piste.BLUE, ZoneOffset.UTC).streak(now))
        val week = withToday.week(now)
        assertEquals(7, week.size)
        assertEquals(java.time.DayOfWeek.MONDAY, week.first().first.dayOfWeek)
    }

    @Test fun replayingOnlyPaysForImprovement() {
        val result = DrillResult(80, 2, 0, 0, 0, 0, 0, "")
        assertEquals(160, result.vert(null))
        assertEquals(8, result.vert(80))
        assertEquals(20, result.vert(70))
    }
}

class LiveDrillTest {
    private val drill = Programme.drill("slow-rolls")!!

    private fun feed(recorder: LiveDrillRecorder, run: DrillRun, startMs: Double) {
        val rolls = run.trace.seconds.indices.map {
            BootRoll(recorder.side, startMs + run.trace.seconds[it] * 1000.0, run.trace.degrees[it].toDouble(), 0.0)
        }
        rolls.forEach(recorder::accept)
    }

    @Test fun liveRollsAreCountedAndScoredLikeADemoSet() {
        val demo = DemoDrill.simulate(drill, 4)
        val recorder = LiveDrillRecorder(drill, "L")
        recorder.begin(10_000.0)
        feed(recorder, demo, 10_000.0)
        assertEquals(drill.movements, recorder.count())
        assertTrue(recorder.complete())
        val live = DrillScoring.score(drill, recorder.run().detection)
        assertEquals(DrillScoring.score(drill, demo.detection).score.toDouble(), live.score.toDouble(), 6.0)
        assertFalse(recorder.run().demo)
    }

    @Test fun rollsBeforeStartOrFromTheOtherBootAreIgnored() {
        val recorder = LiveDrillRecorder(drill, "L")
        assertFalse(recorder.accept(BootRoll("L", 1000.0, 20.0, 0.0)))
        recorder.begin(5000.0)
        assertFalse(recorder.accept(BootRoll("R", 6000.0, 20.0, 0.0)))
        assertFalse(recorder.accept(BootRoll("L", 4000.0, 20.0, 0.0)))
        assertEquals(0, recorder.trace().seconds.size)
    }

    @Test fun neutralStanceMustBeFresh() {
        val recorder = LiveDrillRecorder(drill, "L")
        assertFalse(recorder.neutral(0.0))
        recorder.accept(BootRoll("L", 1000.0, 1.0, 0.0))
        assertTrue(recorder.neutral(1500.0))
        assertFalse(recorder.neutral(3000.0))
        recorder.accept(BootRoll("L", 3100.0, 12.0, 0.0))
        assertFalse(recorder.neutral(3200.0))
    }

    @Test fun countStopsAtTheDrillLength() {
        val demo = DemoDrill.simulate(Programme.drill("steady-rhythm")!!, 5)
        val short = Programme.drill("slow-rolls")!!
        val recorder = LiveDrillRecorder(short, "L")
        recorder.begin(0.0)
        feed(recorder, demo, 0.0)
        assertEquals(short.movements, recorder.count())
    }

    @Test fun mountingOptionsCoverAllSixSignedAxes() {
        assertEquals(6, Mounting.options.size)
        assertEquals("+X toward the toe", Mounting(0, 1).label)
        assertEquals("−Z toward the toe", Mounting(2, -1).label)
    }
}
