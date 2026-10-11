package com.openski.android

import org.junit.Assert.*
import org.junit.Test

class SampleCsvTest {
    private val point=TimelinePoint("L",1500.0,SensorSample(7,42,0f,0f,9.81f,0f,0f,0f,quality=18),"live",
        receivedAtMs=1490)
    @Test fun qualityIsTheLastColumnInHeaderAndRows() {
        val header=SampleCsv.HEADER.trim().split(",")
        assertEquals("quality",header.last())
        assertEquals("gz_radps",header[header.size-2])
        val row=SampleCsv.row(point,1000L).trim().split(",")
        assertEquals(header.size,row.size)
        assertEquals("18",row.last())
        assertEquals("500.0",row[4])
    }
}
