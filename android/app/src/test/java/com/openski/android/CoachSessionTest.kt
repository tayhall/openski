package com.openski.android

import org.junit.Assert.*
import org.junit.Test

class CoachSessionTest {
    private fun event(sequence: Int, startMs: Long, positive: Boolean) =
        SkiEvent(sequence, startMs, 1800, (if (positive) 20f else -20f), 100f, 15f, positive, false, false, false)

    private fun run(session: CoachSession, side: String, count: Int, from: Int = 0) =
        repeat(count) { session.onEvent(side, event(from + it + 1, 10_000L + (from + it) * 2000L, (from + it) % 2 == 0)) }

    private val settings = CoachSettings(presetDrillId = null, customBeat = 2.0, customDepth = 20.0)

    @Test fun automaticFollowsTheFirstBootToSendAnEvent() {
        val verdicts = mutableListOf<Pair<String, CoachVerdict>>()
        val session = CoachSession(settings) { side, verdict -> verdicts.add(side to verdict) }
        assertNull(session.bootInUse())
        session.onEvent("R", event(1, 10_000, true))
        assertEquals("R", session.bootInUse())
        session.onEvent("L", event(1, 10_050, true))   // the other boot is ignored
        run(session, "R", 3, from = 1)
        assertEquals(1, verdicts.size)
        assertEquals("R", verdicts[0].first)
        assertEquals(Verdict.POSITIVE, verdicts[0].second.verdict)
    }

    @Test fun aPinnedBootIgnoresTheOther() {
        val verdicts = mutableListOf<String>()
        val session = CoachSession(settings.copy(coachBoot = CoachBoot.LEFT)) { side, _ -> verdicts.add(side) }
        assertEquals("L", session.bootInUse())
        run(session, "R", 8)
        assertTrue(verdicts.isEmpty())
        run(session, "L", 4)
        assertEquals(listOf("L"), verdicts)
    }

    @Test fun theWindowSizeSettingIsUsed() {
        var count = 0
        val session = CoachSession(settings.copy(windowSize = 3)) { _, _ -> count++ }
        run(session, "L", 6)
        assertEquals(2, count)
    }

    @Test fun demoFeedProducesBothChirpsInOrder() {
        val target = CoachTarget(2.0, 20.0)
        val feed = DemoCoachFeed(target)
        val coach = Coach(target, 4)
        val verdicts = (1..16).mapNotNull { coach.onEvent(feed.next().event)?.verdict }
        assertEquals(listOf(Verdict.POSITIVE, Verdict.POSITIVE, Verdict.NEGATIVE, Verdict.NEGATIVE), verdicts)
    }

    @Test fun demoFeedDeliversAtTheTargetBeat() {
        val feed = DemoCoachFeed(CoachTarget(2.0, 20.0))
        val gaps = (1..8).map { feed.next().afterMs }
        assertTrue(gaps.all { it in 1900L..2150L })
    }

    @Test fun settingsDefaultsAndClamping() {
        val defaults = CoachSettings()
        assertEquals(4, defaults.windowSize)
        assertEquals(CoachTarget(2.0, 20.0), defaults.target())   // the steady-rhythm preset
        val clamped = CoachSettings(windowSize = 9, customBeat = 0.1, customDepth = 99.0, gainPercent = 250).normalised()
        assertEquals(5, clamped.windowSize)
        assertEquals(0.8, clamped.customBeat, 0.0)
        assertEquals(45.0, clamped.customDepth, 0.0)
        assertEquals(100, clamped.gainPercent)
        assertEquals(3, CoachSettings(windowSize = 0).normalised().windowSize)
    }

    @Test fun anUnknownPresetFallsBackToTheCustomNumbers() {
        val settings = CoachSettings(presetDrillId = "no-such-drill", customBeat = 1.5, customDepth = 30.0)
        assertEquals(CoachTarget(1.5, 30.0), settings.target())
        assertTrue(settings.targetLabel().startsWith("Custom"))
        assertTrue(CoachSettings().targetLabel().startsWith("Steady rhythm"))
    }

    @Test fun aCustomTargetSurvivesStorage() {
        // SharedPreferences removes a key stored as null, so "custom" must be stored as something else.
        assertEquals("", presetToStored(null))
        assertNull(presetFromStored(presetToStored(null), "steady-rhythm"))
        assertEquals("quick-edge-change", presetFromStored(presetToStored("quick-edge-change"), "steady-rhythm"))
        assertEquals("steady-rhythm", presetFromStored(null, "steady-rhythm"))   // never saved: the default preset
    }
}
