package com.openski.android

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SessionAnalysisTest {
    private val session=SkiSession("s",100000,102000,0,0,null,null)
    private fun sample(t: Long, z: Float=0f) = SensorSample(0,t,0f,0f,9.81f,0f,0f,z)
    private fun data(live: List<StoredLive>,flash: List<StoredFlash> = emptyList(),captures: List<SensorCapture> = emptyList(),
        session: SkiSession=this.session)=SessionData(session,live,flash,captures,emptyList())
    @Test fun stationaryRecordingHasNoTurnCandidates() {
        val points=(0..500).map { TimelinePoint("L",it*20.0,sample(it*20L),"live") }
        assertTrue(SessionAnalysis.candidates(points,"L").isEmpty())
    }
    @Test fun detectsAlternatingLobesWithoutBridgingDisconnection() {
        val points=(0..300).map { TimelinePoint("L",it*20.0,sample(it*20L,if(it<150) 0.8f else -0.8f),"live") }
        val turns=SessionAnalysis.candidates(points,"L")
        assertEquals(2,turns.size)
        assertTrue(turns[0].positive)
        assertFalse(turns[1].positive)
        val separated=points.take(100)+points.take(100).map { it.copy(timeMs=it.timeMs+10000) }
        assertEquals(2,SessionAnalysis.candidates(separated,"L").size)
    }
    @Test fun reportsBoundaryGapsAndEmptyBoot() {
        val result=SessionAnalysis.build(data((0..49).map { StoredLive("L",100500+it*20L,sample(it*20L)) }))
        val left=result.quality.first()
        assertEquals(0.5,left.coverage,0.02)
        assertEquals(2,left.gaps)
        assertEquals(0.0,result.quality.last().coverage,0.0)
        assertEquals(2000.0,result.quality.last().longestGapMs,0.0)
    }
    @Test fun preservesBothRawSourcesButUsesFlashForGraphs() {
        val capture=SensorCapture("c","s","L","addr",100000,"erased",100,0,"")
        val flash=(0..99).map { i -> val raw=ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(i*10000).putShort(0).putShort(0).putShort(981).putShort(0).putShort(0).putShort(0).array()
            StoredFlash(capture,i,FlashRecord.decode(raw)!!) }
        val live=(0..49).map { StoredLive("L",100000+it*20L,sample(it*20L)) }
        val result=SessionAnalysis.build(data(live,flash,listOf(capture)))
        assertEquals(150,result.raw.size)
        assertEquals(100,result.timeline.size)
        assertEquals(0.5,result.quality.first().coverage,0.02)
        val shifted=SessionAnalysis.build(data(live,flash,listOf(capture),session.copy(leftOffsetMs=1000)))
        assertEquals(result.raw.first().timeMs+1000,shifted.raw.first().timeMs,0.0)
    }
    @Test fun handlesFirmwareMicrosecondClockRollover() {
        val live=listOf(StoredLive("L",100000,sample(4294960)),StoredLive("L",100020,sample(12)))
        val result=SessionAnalysis.build(data(live))
        assertTrue(result.timeline[1].timeMs>result.timeline[0].timeMs)
        assertEquals(19.296,result.timeline[1].timeMs-result.timeline[0].timeMs,0.01)
    }
    @Test fun duplicateSamplesDoNotInflateCoverage() {
        val live=(0..24).flatMap { listOf(StoredLive("L",100000+it*20L,sample(it*20L)),StoredLive("L",100000+it*20L,sample(it*20L))) }
        assertEquals(0.25,SessionAnalysis.build(data(live)).quality.first().coverage,0.02)
    }
}
