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
    val title: String = "",
    val notes: String = "",
    val leftOffsetMs: Long = 0,
    val rightOffsetMs: Long = 0,
    val videoOffsetMs: Long = 0,
)

data class SensorCapture(val id: String, val sessionId: String, val side: String, val address: String,
    val startedAtMs: Long, val state: String, val expected: Int, val dropped: Int, val error: String)
data class StoredLive(val side: String, val receivedAtMs: Long, val sample: SensorSample)
data class StoredFlash(val capture: SensorCapture, val index: Int, val record: FlashRecord)
data class SessionData(val session: SkiSession, val live: List<StoredLive>, val flash: List<StoredFlash>,
    val captures: List<SensorCapture>, val events: List<Pair<Long, String>>)

class LocalSessionStore(context: Context, databaseName: String = "openski-local.db") : SQLiteOpenHelper(context, databaseName, null, 2) {
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
        addRecordingSchema(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) addRecordingSchema(db)
    }

    private fun addRecordingSchema(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE sessions ADD COLUMN title TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE sessions ADD COLUMN notes TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE sessions ADD COLUMN left_offset_ms INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE sessions ADD COLUMN right_offset_ms INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE sessions ADD COLUMN video_offset_ms INTEGER NOT NULL DEFAULT 0")
        db.execSQL("""CREATE TABLE captures (id TEXT PRIMARY KEY, session_id TEXT NOT NULL,
            side TEXT NOT NULL, address TEXT NOT NULL, started_at_ms INTEGER NOT NULL,
            state TEXT NOT NULL, expected INTEGER NOT NULL DEFAULT 0, dropped INTEGER NOT NULL DEFAULT 0,
            error TEXT NOT NULL DEFAULT '')""")
        db.execSQL("CREATE INDEX captures_address_idx ON captures(address, state)")
        db.execSQL("CREATE INDEX captures_session_idx ON captures(session_id)")
        db.execSQL("CREATE TABLE flash_samples (capture_id TEXT NOT NULL, record_index INTEGER NOT NULL, raw BLOB NOT NULL, PRIMARY KEY(capture_id, record_index))")
        db.execSQL("CREATE TABLE download_samples (capture_id TEXT NOT NULL, record_index INTEGER NOT NULL, raw BLOB NOT NULL, PRIMARY KEY(capture_id, record_index))")
        db.execSQL("CREATE TABLE session_events (session_id TEXT NOT NULL, time_ms INTEGER NOT NULL, message TEXT NOT NULL)")
        db.execSQL("CREATE INDEX session_events_idx ON session_events(session_id)")
    }

    @Synchronized fun editSession(id: String, title: String, notes: String, leftMs: Long, rightMs: Long, videoMs: Long) {
        writableDatabase.update("sessions", ContentValues().apply {
            put("title", title); put("notes", notes); put("left_offset_ms", leftMs)
            put("right_offset_ms", rightMs); put("video_offset_ms", videoMs)
        }, "id=?", arrayOf(id))
    }

    @Synchronized fun deleteSession(id: String): Boolean {
        val session = getSession(id) ?: return false
        if (session.endedAtMs == null || captures(id).any { it.state !in listOf("erased", "unavailable", "lost") }) return false
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (table in listOf("flash_samples", "download_samples")) db.execSQL(
                "DELETE FROM $table WHERE capture_id IN (SELECT id FROM captures WHERE session_id=?)", arrayOf(id))
            for (table in listOf("samples", "captures", "session_events")) db.delete(table, "session_id=?", arrayOf(id))
            db.delete("sessions", "id=?", arrayOf(id))
            db.setTransactionSuccessful()
            return true
        } finally { db.endTransaction() }
    }

    @Synchronized fun event(sessionId: String, message: String) {
        writableDatabase.insertOrThrow("session_events", null, ContentValues().apply {
            put("session_id", sessionId); put("time_ms", System.currentTimeMillis()); put("message", message)
        })
    }

    @Synchronized fun createCapture(sessionId: String, side: String, address: String, startedAtMs: Long,
        state: String = "requested"): SensorCapture {
        val id = java.util.UUID.randomUUID().toString()
        writableDatabase.insertOrThrow("captures", null, ContentValues().apply {
            put("id", id); put("session_id", sessionId); put("side", side); put("address", address)
            put("started_at_ms", startedAtMs); put("state", state)
        })
        return SensorCapture(id, sessionId, side, address, startedAtMs, state, 0, 0, "")
    }

    @Synchronized fun updateCapture(id: String, state: String, expected: Int = 0, dropped: Int = 0, error: String = "") {
        writableDatabase.update("captures", ContentValues().apply {
            put("state", state); put("expected", expected); put("dropped", dropped); put("error", error)
        }, "id=?", arrayOf(id))
    }

    @Synchronized fun captures(sessionId: String? = null): List<SensorCapture> = readableDatabase.rawQuery(
        "SELECT id,session_id,side,address,started_at_ms,state,expected,dropped,error FROM captures " +
            (if (sessionId != null) "WHERE session_id=? " else "") + "ORDER BY started_at_ms", sessionId?.let { arrayOf(it) },
    ).use { c -> buildList { while (c.moveToNext()) add(SensorCapture(c.getString(0), c.getString(1), c.getString(2),
        c.getString(3), c.getLong(4), c.getString(5), c.getInt(6), c.getInt(7), c.getString(8))) } }

    @Synchronized fun pendingCapture(address: String): SensorCapture? = captures().lastOrNull {
        it.address == address && it.state !in listOf("erased", "unavailable", "lost")
    }
    @Synchronized fun hasPendingRecovery(): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM captures WHERE state NOT IN ('erased','unavailable','lost') LIMIT 1", null,
    ).use { it.moveToFirst() }

    @Synchronized fun beginDownload(id: String) { writableDatabase.delete("download_samples", "capture_id=?", arrayOf(id)) }

    @Synchronized fun saveDownload(id: String, chunk: FlashChunk) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            chunk.records.forEachIndexed { index, record -> db.insertOrThrow("download_samples", null, ContentValues().apply {
                put("capture_id", id); put("record_index", chunk.firstIndex + index); put("raw", record.raw)
            }) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /** Validate the committed bytes, then promote the staging copy in one transaction. */
    @Synchronized fun validateDownload(id: String, expected: Int, dropped: Int): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        try {
            var next = 0
            db.rawQuery("SELECT record_index,raw FROM download_samples WHERE capture_id=? ORDER BY record_index", arrayOf(id)).use { c ->
                while (c.moveToNext()) {
                    if (c.getInt(0) != next || FlashRecord.decode(c.getBlob(1)) == null) return false
                    next++
                }
            }
            if (next != expected) return false
            db.delete("flash_samples", "capture_id=?", arrayOf(id))
            db.execSQL("INSERT INTO flash_samples SELECT * FROM download_samples WHERE capture_id=?", arrayOf(id))
            db.delete("download_samples", "capture_id=?", arrayOf(id))
            updateCapture(id, "verified", expected, dropped)
            db.setTransactionSuccessful()
            return true
        } finally { db.endTransaction() }
    }

    @Synchronized fun loadData(id: String): SessionData? {
        val session = getSession(id) ?: return null
        val live = readableDatabase.rawQuery("SELECT side,received_at_ms,sensor_time_ms,sequence,ax,ay,az,gx,gy,gz FROM samples WHERE session_id=? ORDER BY id", arrayOf(id)).use { c ->
            buildList { while (c.moveToNext()) add(StoredLive(c.getString(0), c.getLong(1), SensorSample(c.getInt(3), c.getLong(2),
                c.getFloat(4), c.getFloat(5), c.getFloat(6), c.getFloat(7), c.getFloat(8), c.getFloat(9)))) }
        }
        val captures = captures(id)
        val flash = captures.flatMap { capture -> readableDatabase.rawQuery(
            "SELECT record_index,raw FROM flash_samples WHERE capture_id=? ORDER BY record_index", arrayOf(capture.id)).use { c ->
                buildList { while (c.moveToNext()) FlashRecord.decode(c.getBlob(1))?.let { add(StoredFlash(capture, c.getInt(0), it)) } }
            }
        }
        val events = readableDatabase.rawQuery("SELECT time_ms,message FROM session_events WHERE session_id=? ORDER BY time_ms", arrayOf(id)).use { c ->
            buildList { while (c.moveToNext()) add(c.getLong(0) to c.getString(1)) }
        }
        return SessionData(session, live, flash, captures, events)
    }

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
            (SELECT COUNT(*) FROM samples WHERE session_id=s.id AND side='R'),
            s.title,s.notes,s.left_offset_ms,s.right_offset_ms,s.video_offset_ms
            FROM sessions s ${if (selection != null) "WHERE $selection" else ""} ORDER BY s.started_at_ms DESC"""
        return readableDatabase.rawQuery(sql, args).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(SkiSession(
                    cursor.getString(0), cursor.getLong(1), if (cursor.isNull(2)) null else cursor.getLong(2),
                    cursor.getLong(5), cursor.getLong(6), if (cursor.isNull(3)) null else cursor.getString(3),
                    if (cursor.isNull(4)) null else cursor.getString(4),
                    cursor.getString(7), cursor.getString(8), cursor.getLong(9), cursor.getLong(10), cursor.getLong(11),
                ))
            }
        }
    }
}
