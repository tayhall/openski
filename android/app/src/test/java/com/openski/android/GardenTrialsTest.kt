package com.openski.android

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class GardenTrialsTest {
    /** The protocol doc, found by walking up from the test's working directory (android/app under Gradle). */
    private fun protocol(): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val f = File(dir, GardenTrials.SOURCE)
            if (f.isFile) return f.readText()
            dir = dir.parentFile
        }
        fail("${GardenTrials.SOURCE} not found above ${System.getProperty("user.dir")}")
        error("unreachable")
    }

    /** Each trial's bold heading and the paragraph that follows it, up to the next trial or section. */
    private fun sections(): Map<String, Pair<String, String>> {
        val text = protocol().replace("\r\n", "\n")
        val heading = Regex("""^\*\*(T\d)\. (.+?)\*\*$""", RegexOption.MULTILINE)
        val found = heading.findAll(text).toList()
        return found.mapIndexed { i, m ->
            val end = found.getOrNull(i + 1)?.range?.first ?: text.indexOf("\n## ", m.range.last).let { if (it < 0) text.length else it }
            m.groupValues[1] to (m.groupValues[2] to text.substring(m.range.last + 1, end))
        }.toMap()
    }

    @Test fun theListCoversT0ToT6InOrder() {
        assertEquals((0..6).map { "T$it" }, GardenTrials.all.map { it.id })
    }

    @Test fun titlesMatchTheProtocolHeadings() {
        val doc = sections()
        assertEquals(GardenTrials.all.map { it.id }, doc.keys.toList())
        GardenTrials.all.forEach { assertEquals(it.id, doc.getValue(it.id).first, it.title) }
    }

    @Test fun exportHintsAreNamedInThatTrialsParagraph() {
        val doc = sections()
        GardenTrials.all.forEach { trial ->
            trial.exports.forEach { file -> assertTrue("${trial.id}: $file", doc.getValue(trial.id).second.contains("`$file`")) }
        }
    }

    @Test fun everyTrialHasStepsAndTheJudgeCommandTheDocGives() {
        val text = protocol()
        assertTrue(text.contains("python tools/analysis/trial_report.py export.zip --trial T1"))
        assertTrue(text.contains("--trial T3 --compare smooth.zip rough.zip"))
        GardenTrials.all.forEach { trial ->
            assertTrue(trial.id, trial.steps.isNotEmpty())
            assertTrue(trial.id, trial.judge.startsWith("python tools/analysis/trial_report.py export.zip --trial ${trial.id}"))
        }
    }

    @Test fun progressCountsOnlyKnownTrials() {
        assertEquals("0 of 7 done", GardenTrials.progress(emptySet()))
        assertEquals("2 of 7 done", GardenTrials.progress(setOf("T0", "T6", "T9")))
    }
}
