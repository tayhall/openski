package com.openski.android

/** samples.csv layout, kept pure so it can be unit tested without an Android context. */
object SampleCsv {
    const val HEADER = "side,source,capture_id,record_index,session_elapsed_ms,aligned_time_ms,received_at_ms,sensor_time_ms,sensor_time_us,sequence,ax_mps2,ay_mps2,az_mps2,gx_radps,gy_radps,gz_radps,quality\n"

    fun row(p: TimelinePoint, sessionStartedAtMs: Long): String {
        val s = p.sample
        return listOf(p.side, p.source, p.captureId ?: "", p.recordIndex ?: "", p.timeMs - sessionStartedAtMs, p.timeMs,
            p.receivedAtMs ?: "", s.timestampMs, p.sensorTimeUs ?: "", if (s.sequence < 0) "" else s.sequence,
            s.accelX, s.accelY, s.accelZ, s.gyroX, s.gyroY, s.gyroZ, s.quality).joinToString(",") + "\n"
    }
}
