package com.openski.android

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class ToneSynthTest {
    private fun crossings(samples: ShortArray, from: Int = 0, to: Int = samples.size): Int {
        var count = 0
        for (i in from + 1 until to) if ((samples[i - 1] < 0) != (samples[i] < 0)) count++
        return count
    }

    @Test fun toneHasTheRightLengthAndFrequency() {
        val tone = ToneSynth.tone(1000.0, 100)
        assertEquals(4410, tone.size)
        assertEquals(200.0, crossings(tone).toDouble(), 4.0)  // two zero crossings per cycle, 100 cycles
    }

    @Test fun toneFadesInAndOutSoItNeverClicks() {
        val tone = ToneSynth.tone(1000.0, 100)
        assertTrue(abs(tone.first().toInt()) < 400)
        assertTrue(abs(tone.last().toInt()) < 400)
    }

    @Test fun peakStaysUnderTheCap() {
        val peak = ToneSynth.chirpPositive().maxOf { abs(it.toInt()) }
        assertTrue(peak <= (ToneSynth.PEAK * Short.MAX_VALUE).toInt() + 1)
        assertTrue(peak > 0.4 * Short.MAX_VALUE)
    }

    @Test fun positiveChirpRisesAndNegativeChirpFalls() {
        val up = ToneSynth.chirpPositive()
        val down = ToneSynth.chirpNegative()
        assertEquals(up.size, 2 * 4851)
        val half = up.size / 2
        assertTrue(crossings(up, half, up.size) > crossings(up, 0, half))
        assertTrue(crossings(down, half, down.size) < crossings(down, 0, half))
    }

    @Test fun theTwoChirpsDifferInRegisterAsWellAsDirection() {
        val up = ToneSynth.chirpPositive()
        val down = ToneSynth.chirpNegative()
        assertTrue(crossings(up) > crossings(down))
    }

    @Test fun accentTickIsHigherThanThePlainTick() {
        assertTrue(crossings(ToneSynth.tick(true)) > crossings(ToneSynth.tick(false)))
    }

    @Test fun metronomeBeatStartsWithTheTickAndThenIsSilent() {
        val beat = MetronomeClock(1.0).nextBeat()
        assertEquals(44100, beat.size)
        assertTrue((0 until 1323).any { beat[it].toInt() != 0 })
        assertTrue((1323 until beat.size).all { beat[it].toInt() == 0 })
    }

    @Test fun metronomeAccentsEverySecondBeat() {
        val clock = MetronomeClock(1.0)
        val first = clock.nextBeat()
        val second = clock.nextBeat()
        val third = clock.nextBeat()
        assertTrue(crossings(first, 0, 1323) > crossings(second, 0, 1323))
        assertEquals(crossings(first, 0, 1323), crossings(third, 0, 1323))
    }

    @Test fun metronomeDoesNotDriftOverHundredBeats() {
        val clock = MetronomeClock(1.7)
        val total = (1..100).sumOf { clock.nextBeat().size.toLong() }
        assertEquals(170.0 * ToneSynth.SAMPLE_RATE, total.toDouble(), 1.0)
    }

    @Test fun theShortestBeatStillFitsItsTick() {
        val beat = MetronomeClock(CoachTarget.MIN_BEAT).nextBeat()
        assertEquals(0.8 * ToneSynth.SAMPLE_RATE, beat.size.toDouble(), 1.0)
        assertTrue(beat.any { it.toInt() != 0 })
    }

    @Test fun theRunChimesAreLongerThanChirpsAndRiseOrFall() {
        val start = ToneSynth.startChime()
        val end = ToneSynth.endChime()
        assertTrue(start.size > ToneSynth.chirpPositive().size)
        assertEquals(start.size, end.size)
        val third = start.size / 3
        assertTrue(crossings(start, 2 * third, start.size) > crossings(start, 0, third))
        assertTrue(crossings(end, 2 * third, end.size) < crossings(end, 0, third))
    }
}
