package com.openski.android
import org.junit.Assert.*
import org.junit.Test
class PracticeRepeatTest {
    private fun saved() = Attempt("slow-rolls", 1, 80, 2, 160, true, id="original", target=PracticeTarget(7,4.25,11.5))
    @Test fun storedTargetOverridesChangedPresetAndSurvivesRestore() {
        val original = saved(); val drill = PracticeRepeat.drill(original)
        assertEquals(7,drill.movements); assertEquals(4.25,drill.paceSeconds,0.0); assertEquals(11.5,drill.depthDegrees,0.0)
        val effects = object : DrillEffects {
            override fun prepare(attempt: Attempt, completed: (Result<Int>)->Unit) { assertNotEquals(original.id,attempt.id); assertTrue(attempt.demo); assertEquals(original.target,attempt.target); completed(Result.success(0)) }
            override fun openSession(attempt: Attempt, completed: (Result<Unit>)->Unit) = error("demo cannot open raw")
            override fun calibrate(attempt: Attempt,side:String,mounting:Mounting,completed:(Result<Unit>)->Unit) = error("demo cannot calibrate")
            override fun mark(attempt:Attempt,label:String) {}
            override fun stopSession(attempt:Attempt) {}
            override fun save(attempt:Attempt,run:DrillRun?,completed:(Result<Attempt>)->Unit) { completed(Result.success(attempt)) }
        }
        var clock=0L
        val controller=DrillController(effects,{clock},{2L})
        controller.restoreSaved(original)
        assertEquals(original.target,PracticeTarget.of(controller.state().drill!!))
        assertNull(controller.start(drill,original.demo,"new",null))
        assertEquals(original.target,controller.state().attempt!!.target)
        clock=6500
        controller.tick(emptySet(),emptyMap(),null)
        val completed=controller.state().attempt!!
        val expected = DrillScoring.score(drill,DemoDrill.simulate(drill,0).detection)
        assertEquals(if(expected.completed == drill.movements) PracticeStatus.COMPLETED else PracticeStatus.INCOMPLETE,completed.status)
        assertEquals(original.target,completed.target)
        assertEquals(expected.score,completed.score)
        assertEquals("original",original.id)
    }
    @Test fun unavailableMetadataIsNeverInferred() {
        for(attempt in listOf(saved().copy(target=null),saved().copy(scoringVersion=2),saved().copy(drillId="removed"),saved().copy(status=PracticeStatus.ACTIVE))) {
            assertTrue(runCatching { PracticeRepeat.drill(attempt) }.isFailure)
        }
    }
}
