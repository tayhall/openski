package com.openski.android

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class RecordedSample(
    val side: String,
    val receivedAtMs: Long,
    val sample: SensorSample,
)

data class SkiSession(
    val id: String,
    val startedAtMs: Long,
    val endedAtMs: Long?,
    val leftSamples: Long,
    val rightSamples: Long,
    val videoUri: String?,
    val videoName: String?,
)

class LocalSessionStore(context: Context) : SQLiteOpenHelper(context, "openski-local.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE sessions (
            id TEXT PRIMARY KEY, started_at_ms INTEGER NOT NULL, ended_at_ms INTEGER,
            video_uri TEXT, video_name TEXT)""")
        db.execSQL("""CREATE TABLE samples (
            id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT NOT NULL, side TEXT NOT NULL,
            received_at_ms INTEGER NOT NULL, sensor_time_ms INTEGER NOT NULL, sequence INTEGER NOT NULL,
            ax REAL NOT NULL, ay REAL NOT NULL, az REAL NOT NULL,
            gx REAL NOT NULL, gy REAL NOT NULL, gz REAL NOT NULL,
            FOREIGN KEY(session_id) REFERENCES sessions(id))""")
        db.execSQL("CREATE INDEX samples_session_idx ON samples(session_id, side)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized fun createSession(id: String, startedAtMs: Long) {
        writableDatabase.insertOrThrow("sessions", null, ContentValues().apply {
            put("id", id); put("started_at_ms", startedAtMs)
        })
    }

    @Synchronized fun finishSession(id: String, endedAtMs: Long) {
        writableDatabase.update("sessions", ContentValues().apply { put("ended_at_ms", endedAtMs) }, "id=?", arrayOf(id))
    }

    @Synchronized fun saveSamples(sessionId: String, samples: List<RecordedSample>) {
        if (samples.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            samples.forEach { item ->
                val s = item.sample
                db.insertOrThrow("samples", null, ContentValues().apply {
                    put("session_id", sessionId); put("side", item.side); put("received_at_ms", item.receivedAtMs)
                    put("sensor_time_ms", s.timestampMs); put("sequence", s.sequence)
                    put("ax", s.accelX); put("ay", s.accelY); put("az", s.accelZ)
                    put("gx", s.gyroX); put("gy", s.gyroY); put("gz", s.gyroZ)
                })
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    @Synchronized fun attachVideo(id: String, uri: String, name: String) {
        writableDatabase.update("sessions", ContentValues().apply {
            put("video_uri", uri); put("video_name", name)
        }, "id=?", arrayOf(id))
    }

    @Synchronized fun getSession(id: String): SkiSession? = querySessions("s.id=?", arrayOf(id)).firstOrNull()

    @Synchronized fun latestOpenSessionId(): String? = readableDatabase.rawQuery(
        "SELECT id FROM sessions WHERE ended_at_ms IS NULL ORDER BY started_at_ms DESC LIMIT 1", null,
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    @Synchronized fun listSessions(): List<SkiSession> = querySessions(null, null)

    private fun querySessions(selection: String?, args: Array<String>?): List<SkiSession> {
        val sql = """SELECT s.id, s.started_at_ms, s.ended_at_ms, s.video_uri, s.video_name,
            (SELECT COUNT(*) FROM samples WHERE session_id=s.id AND side='L'),
            (SELECT COUNT(*) FROM samples WHERE session_id=s.id AND side='R')
            FROM sessions s ${if (selection != null) "WHERE $selection" else ""} ORDER BY s.started_at_ms DESC"""
        return readableDatabase.rawQuery(sql, args).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(SkiSession(
                    cursor.getString(0), cursor.getLong(1), if (cursor.isNull(2)) null else cursor.getLong(2),
                    cursor.getLong(5), cursor.getLong(6), if (cursor.isNull(3)) null else cursor.getString(3),
                    if (cursor.isNull(4)) null else cursor.getString(4),
                ))
            }
        }
    }
}
