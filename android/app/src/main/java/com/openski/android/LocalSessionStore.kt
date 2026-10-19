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
    val equipment: String = "",
    val calibrationJson: String = "{}",
    val testSession: Boolean = false,
    val origin: String = "sensor",
    val analysisJson: String = "{}",
    val kind: String = "session",
)

data class SensorCapture(val id: String, val sessionId: String, val side: String, val address: String,
    val startedAtMs: Long, val state: String, val expected: Int, val dropped: Int, val error: String,
    /** 2 = legacy 16-byte records, 3 = records whose timestamp low bits are signal-quality flags. */
    val recordFormat: Int = 2)
data class StoredLive(val side: String, val receivedAtMs: Long, val sample: SensorSample)
data class StoredFlash(val capture: SensorCapture, val index: Int, val record: FlashRecord)
data class SessionData(val session: SkiSession, val live: List<StoredLive>, val flash: List<StoredFlash>,
    val captures: List<SensorCapture>, val events: List<Pair<Long, String>>,
    val markers: List<TestMarker> = emptyList(), val health: List<SensorHealth> = emptyList(),
    val run: RunData? = null,
    val clockPairs: List<StoredClockPair> = emptyList(), val mag: List<StoredMag> = emptyList(),
    val phone: List<StoredPhone> = emptyList(), val practiceReviews: List<PracticeExportReview> = emptyList())

/** One time-sync exchange as stored (phone times are elapsedRealtimeNanos; [accepted] means it passed the sanity checks). */
data class StoredClockPair(val side: String, val token: Int, val sendNs: Long, val recvNs: Long, val sensorUs: Long,
    val rttNs: Long, val accepted: Boolean)
/** One raw magnetometer frame; [receiptMs] is phone wall-clock time like samples.received_at_ms. */
data class StoredMag(val side: String, val sensorMs: Long, val receiptMs: Long, val x: Int, val y: Int, val z: Int, val flags: Int)

data class TestMarker(val id: Long, val timeMs: Long, val label: String, val side: String,
    val source: String, val note: String)
data class SensorHealth(val side: String, val timeMs: Long, val battery: Int?, val batteryAtMs: Long?,
    val rssi: Int?, val rssiAtMs: Long?)

class LocalSessionStore(context: Context, databaseName: String = "openski-local.db") : SQLiteOpenHelper(context, databaseName, null, VERSION) {
    companion object {
        /** Current schema version; every step has a migration in [onUpgrade] and a check in StorageInstrumentation. */
        const val VERSION = 15
        private val captureWrites = java.util.concurrent.atomic.AtomicLong()
        /** Rows per query when a session is read in chunks ([loadDataInChunks]). */
        const val LOAD_CHUNK_ROWS = 5_000
    }

    /**
     * Latest sign of life of an open session: its last live sample or run half-turn as received, or its start. Used to
     * tell a recording from a session left open by a crash.
     */
    @Synchronized fun lastActivityMs(sessionId: String): Long? = readableDatabase.rawQuery(
        """SELECT MAX(COALESCE((SELECT MAX(received_at_ms) FROM samples WHERE session_id=s.id), 0),
            COALESCE((SELECT MAX(received_ms) FROM run_events WHERE session_id=s.id), 0), s.started_at_ms) FROM sessions s WHERE s.id=?""",
        arrayOf(sessionId)).use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null }

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
        addEquipmentSchema(db)
        addCalibrationSchema(db)
        addTestSchema(db)
        addOriginSchema(db)
        addAnalysisSchema(db)
        addRunSchema(db)
        addQualitySchema(db)
        addTrackSchema(db)
        addClockSchema(db)
        addResortSchema(db)
        addDaySchema(db)
        addPracticeSchema(db)
        addPracticeReviewSchema(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) addRecordingSchema(db)
        if (oldVersion < 3) addEquipmentSchema(db)
        if (oldVersion < 4) addCalibrationSchema(db)
        if (oldVersion < 5) addTestSchema(db)
        if (oldVersion < 6) addOriginSchema(db)
        if (oldVersion < 7) addAnalysisSchema(db)
        if (oldVersion < 8) addRunSchema(db)
        if (oldVersion < 9) addQualitySchema(db)
        if (oldVersion < 10) addTrackSchema(db)
        if (oldVersion < 11) addClockSchema(db)
        if (oldVersion < 12) addResortSchema(db)
        if (oldVersion < 13) addDaySchema(db)
        if (oldVersion < 14) addPracticeSchema(db)
        if (oldVersion < 15) addPracticeReviewSchema(db)
    }

    private fun addPracticeReviewSchema(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE practice_attempts ADD COLUMN review_json TEXT")
        db.execSQL("ALTER TABLE practice_attempts ADD COLUMN score_eligible INTEGER")
        // No raw-session foreign key: deleting samples never deletes practice or reflection.
        db.execSQL("CREATE TABLE practice_reflections(attempt_id TEXT PRIMARY KEY, intention TEXT NOT NULL, reflection TEXT NOT NULL)")
    }

    /** Version 14: durable practice history. Session links deliberately have no cascading foreign key. */
    private fun addPracticeSchema(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE practice_attempts(id TEXT PRIMARY KEY, drill_id TEXT NOT NULL, time_ms INTEGER NOT NULL,
            score INTEGER NOT NULL, stars INTEGER NOT NULL, points INTEGER NOT NULL, demo INTEGER NOT NULL,
            session_id TEXT, status TEXT NOT NULL, scoring_version INTEGER NOT NULL,
            target_movements INTEGER, target_beat REAL, target_depth REAL)""")
        db.execSQL("CREATE INDEX practice_time_idx ON practice_attempts(time_ms)")
        db.execSQL("CREATE TABLE practice_imports(id TEXT PRIMARY KEY)")
    }

    /**
     * Version 13: whole ski days (see DayStore). Independent of sessions and resorts: deleting a session or a resort
     * never touches these tables; deleting a day removes its fixes and run tags in one transaction. day_fixes keeps
     * each fix as the phone gave it, with the barometric altitude read alongside (null without a barometer); an
     * accuracy the phone did not report is null (read back as RunTrack.UNKNOWN_ACCURACY_M).
     */
    private fun addDaySchema(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE ski_days(id TEXT PRIMARY KEY, title TEXT NOT NULL DEFAULT '', started_at_ms INTEGER NOT NULL,
            ended_at_ms INTEGER, source TEXT NOT NULL, resort_id TEXT, notes TEXT NOT NULL DEFAULT '',
            tags_json TEXT NOT NULL DEFAULT '{}', created_at_ms INTEGER NOT NULL)""")
        db.execSQL("""CREATE TABLE day_fixes(day_id TEXT NOT NULL, time_ms INTEGER NOT NULL, lat REAL NOT NULL, lon REAL NOT NULL,
            alt REAL, speed REAL, accuracy REAL, vertical_accuracy REAL, baro_alt REAL)""")
        db.execSQL("CREATE INDEX day_fixes_day_idx ON day_fixes(day_id, time_ms)")
        db.execSQL("""CREATE TABLE day_run_tags(day_id TEXT NOT NULL, segment_start_ms INTEGER NOT NULL,
            tags_json TEXT NOT NULL DEFAULT '{}', note TEXT NOT NULL DEFAULT '', PRIMARY KEY(day_id, segment_start_ms))""")
    }

    /**
     * Version 12: cached resort maps from OpenStreetMap (see ResortStore). Independent of sessions: deleting a session
     * never touches these tables, and deleting a resort removes its ways and stations in the same transaction.
     * resorts.truncated marks a radius cut to the 25 km maximum; resort_ways.part numbers the pieces of a way split
     * at a gap in its geometry; resort_stations.osm_type is 'node' or 'way' (a station mapped as an outline).
     */
    private fun addResortSchema(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE resorts(id TEXT PRIMARY KEY, name TEXT NOT NULL, centre_lat REAL NOT NULL, centre_lon REAL NOT NULL,
            radius_m INTEGER NOT NULL, fetched_at_ms INTEGER NOT NULL, way_count INTEGER NOT NULL, source_note TEXT NOT NULL DEFAULT '',
            truncated INTEGER NOT NULL DEFAULT 0)""")
        db.execSQL("""CREATE TABLE resort_ways(resort_id TEXT NOT NULL, osm_id INTEGER NOT NULL, part INTEGER NOT NULL DEFAULT 0,
            kind TEXT NOT NULL, name TEXT, ref TEXT, difficulty TEXT, difficulty_raw TEXT, aerialway TEXT, operator TEXT, osm_version INTEGER,
            area INTEGER NOT NULL DEFAULT 0, relation_id INTEGER, geometry TEXT NOT NULL, PRIMARY KEY(resort_id, osm_id, part))""")
        db.execSQL("""CREATE TABLE resort_stations(resort_id TEXT NOT NULL, osm_type TEXT NOT NULL DEFAULT 'node', osm_id INTEGER NOT NULL,
            name TEXT, lat REAL NOT NULL, lon REAL NOT NULL, operator TEXT, osm_version INTEGER, PRIMARY KEY(resort_id, osm_type, osm_id))""")
    }

    /**
     * Version 11: protocol clock sync and magnetometer. clock_sync holds the raw ping pairs, mag_samples the raw
     * magnetometer frames, and phone_samples is created for the phone's own motion sensors (written while recording, see PhoneMotion.kt).
     */
    private fun addClockSchema(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE clock_sync(session_id TEXT NOT NULL, side TEXT NOT NULL, token INTEGER NOT NULL,
            send_ns INTEGER NOT NULL, recv_ns INTEGER NOT NULL, sensor_us INTEGER NOT NULL, rtt_ns INTEGER NOT NULL,
            accepted INTEGER NOT NULL)""")
        db.execSQL("CREATE INDEX clock_sync_session_idx ON clock_sync(session_id, side, recv_ns)")
        db.execSQL("""CREATE TABLE mag_samples(session_id TEXT NOT NULL, side TEXT NOT NULL, sensor_ms INTEGER NOT NULL,
            phone_receipt_ms INTEGER NOT NULL, x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL, flags INTEGER NOT NULL)""")
        db.execSQL("CREATE INDEX mag_samples_session_idx ON mag_samples(session_id, side, sensor_ms)")
        db.execSQL("""CREATE TABLE phone_samples(session_id TEXT NOT NULL, phone_ns INTEGER NOT NULL,
            gx REAL NOT NULL, gy REAL NOT NULL, gz REAL NOT NULL, ax REAL NOT NULL, ay REAL NOT NULL, az REAL NOT NULL,
            qw REAL NOT NULL, qx REAL NOT NULL, qy REAL NOT NULL, qz REAL NOT NULL)""")
        db.execSQL("CREATE INDEX phone_samples_session_idx ON phone_samples(session_id, phone_ns)")
    }

    @Synchronized fun addClockPairs(sessionId: String, pairs: List<StoredClockPair>) {
        if (pairs.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            pairs.forEach { p -> db.insertOrThrow("clock_sync", null, ContentValues().apply {
                put("session_id", sessionId); put("side", p.side); put("token", p.token); put("send_ns", p.sendNs)
                put("recv_ns", p.recvNs); put("sensor_us", p.sensorUs); put("rtt_ns", p.rttNs); put("accepted", if (p.accepted) 1 else 0)
            }) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    @Synchronized fun addMagSamples(sessionId: String, rows: List<StoredMag>) {
        if (rows.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            rows.forEach { m -> db.insertOrThrow("mag_samples", null, ContentValues().apply {
                put("session_id", sessionId); put("side", m.side); put("sensor_ms", m.sensorMs); put("phone_receipt_ms", m.receiptMs)
                put("x", m.x); put("y", m.y); put("z", m.z); put("flags", m.flags)
            }) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    @Synchronized fun addPhoneSamples(sessionId: String, rows: List<StoredPhone>) {
        if (rows.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            rows.forEach { r -> db.insertOrThrow("phone_samples", null, ContentValues().apply {
                put("session_id", sessionId); put("phone_ns", r.phoneNs)
                put("gx", r.gx); put("gy", r.gy); put("gz", r.gz); put("ax", r.ax); put("ay", r.ay); put("az", r.az)
                put("qw", r.qw); put("qx", r.qx); put("qy", r.qy); put("qz", r.qz)
            }) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    @Synchronized fun phoneSamples(sessionId: String): List<StoredPhone> = readableDatabase.rawQuery(
        "SELECT phone_ns,gx,gy,gz,ax,ay,az,qw,qx,qy,qz FROM phone_samples WHERE session_id=? ORDER BY phone_ns,rowid", arrayOf(sessionId),
    ).use { c -> buildList { while (c.moveToNext()) add(StoredPhone(c.getLong(0), c.getFloat(1), c.getFloat(2), c.getFloat(3),
        c.getFloat(4), c.getFloat(5), c.getFloat(6), c.getFloat(7), c.getFloat(8), c.getFloat(9), c.getFloat(10))) } }

    @Synchronized fun clockPairs(sessionId: String): List<StoredClockPair> = readableDatabase.rawQuery(
        "SELECT side,token,send_ns,recv_ns,sensor_us,rtt_ns,accepted FROM clock_sync WHERE session_id=? ORDER BY recv_ns,rowid", arrayOf(sessionId),
    ).use { c -> buildList { while (c.moveToNext()) add(StoredClockPair(c.getString(0), c.getInt(1), c.getLong(2), c.getLong(3),
        c.getLong(4), c.getLong(5), c.getInt(6) != 0)) } }

    @Synchronized fun magSamples(sessionId: String): List<StoredMag> = readableDatabase.rawQuery(
        "SELECT side,sensor_ms,phone_receipt_ms,x,y,z,flags FROM mag_samples WHERE session_id=? ORDER BY phone_receipt_ms,rowid", arrayOf(sessionId),
    ).use { c -> buildList { while (c.moveToNext()) add(StoredMag(c.getString(0), c.getLong(1), c.getLong(2),
        c.getInt(3), c.getInt(4), c.getInt(5), c.getInt(6))) } }

    /** Version 9: signal quality. Live samples carry quality bits; a capture records its flash record format. */
    private fun addQualitySchema(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE captures ADD COLUMN record_format INTEGER NOT NULL DEFAULT 2")
        db.execSQL("ALTER TABLE samples ADD COLUMN quality INTEGER NOT NULL DEFAULT 0")
    }

    /** Version 10: phone GPS for runs. run_info.gps records whether tracking actually started for the run. */
    private fun addTrackSchema(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE run_info ADD COLUMN gps INTEGER NOT NULL DEFAULT 0")
        db.execSQL("""CREATE TABLE run_track(session_id TEXT NOT NULL, time_ms INTEGER NOT NULL, lat REAL NOT NULL,
            lon REAL NOT NULL, alt REAL, speed REAL, accuracy REAL NOT NULL, vertical_accuracy REAL)""")
        db.execSQL("CREATE INDEX run_track_session_idx ON run_track(session_id, time_ms)")
    }

    private fun addOriginSchema(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE sessions ADD COLUMN origin TEXT NOT NULL DEFAULT 'sensor'")
    }

    /** Version 8: runs. A run is a session with kind 'run' plus its half-turns, coaching verdicts and run settings. */
    private fun addRunSchema(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE sessions ADD COLUMN kind TEXT NOT NULL DEFAULT 'session'")
        db.execSQL("""CREATE TABLE run_events(session_id TEXT NOT NULL, side TEXT NOT NULL, sequence INTEGER NOT NULL,
            start_ms INTEGER NOT NULL, duration_ms INTEGER NOT NULL, peak_roll REAL NOT NULL, peak_rate REAL NOT NULL,
            pitch REAL NOT NULL, flags INTEGER NOT NULL, received_ms INTEGER NOT NULL)""")
        db.execSQL("CREATE INDEX run_events_session_idx ON run_events(session_id, received_ms)")
        db.execSQL("""CREATE TABLE run_verdicts(session_id TEXT NOT NULL, time_ms INTEGER NOT NULL, side TEXT NOT NULL,
            verdict TEXT NOT NULL, score INTEGER NOT NULL, tempo INTEGER NOT NULL, steadiness INTEGER NOT NULL,
            depth INTEGER NOT NULL, balance INTEGER, mean_beat REAL NOT NULL, mean_depth REAL NOT NULL, outside INTEGER NOT NULL)""")
        db.execSQL("""CREATE TABLE run_info(session_id TEXT PRIMARY KEY, target_beat REAL NOT NULL, target_depth REAL NOT NULL,
            target_label TEXT NOT NULL, window_size INTEGER NOT NULL, coach_boot TEXT NOT NULL, mode_before TEXT NOT NULL,
            ended_by TEXT NOT NULL DEFAULT '', first_turn_ms INTEGER, last_turn_ms INTEGER, gaps_json TEXT NOT NULL DEFAULT '[]')""")
    }

    private fun addAnalysisSchema(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE sessions ADD COLUMN analysis_json TEXT NOT NULL DEFAULT '{}'")
    }

    @Synchronized fun saveReviewSettings(id: String, side: String, axis: Int, positiveLeft: Boolean) {
        require(side in listOf("L","R") && axis in 0..2)
        val json=org.json.JSONObject(getSession(id)?.analysisJson ?: "{}")
        json.put(side,org.json.JSONObject().put("turn_axis",axis).put("positive_left",positiveLeft))
        json.put("selected_side",side)
        writableDatabase.update("sessions",ContentValues().apply { put("analysis_json",json.toString()) },"id=?",arrayOf(id))
    }

    @Synchronized fun createDemo(): String {
        val id=java.util.UUID.randomUUID().toString()
        val data=DemoSession.generate(id,System.currentTimeMillis()-66_000)
        val db=writableDatabase
        db.beginTransaction()
        try {
            createSession(id,data.session.startedAtMs,true,"synthetic")
            editSession(id,data.session.title,data.session.notes,0,0,0)
            saveSamples(id,data.live.map { RecordedSample(it.side,it.receivedAtMs,it.sample) })
            finishSession(id,data.session.endedAtMs!!)
            data.markers.forEach { addMarker(id,it.timeMs,it.label,it.side,it.source,it.note) }
            val timeline=SessionAnalysis.build(data).timeline
            listOf("L","R").forEach { side ->
                saveCalibration(id,BootOrientation.calibrate(timeline,side,data.session.startedAtMs.toDouble(),0,1)
                    ?: error("Synthetic calibration unavailable"))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return id
    }

    private fun addTestSchema(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE sessions ADD COLUMN test_session INTEGER NOT NULL DEFAULT 0")
        db.execSQL("""CREATE TABLE test_markers(id INTEGER PRIMARY KEY AUTOINCREMENT,session_id TEXT NOT NULL,
            time_ms INTEGER NOT NULL,label TEXT NOT NULL,side TEXT NOT NULL,source TEXT NOT NULL,note TEXT NOT NULL)""")
        db.execSQL("CREATE INDEX test_markers_session_idx ON test_markers(session_id,time_ms)")
        db.execSQL("""CREATE TABLE sensor_health(session_id TEXT NOT NULL,side TEXT NOT NULL,time_ms INTEGER NOT NULL,
            battery INTEGER,battery_at_ms INTEGER,rssi INTEGER,rssi_at_ms INTEGER)""")
        db.execSQL("CREATE INDEX sensor_health_session_idx ON sensor_health(session_id,time_ms)")
    }

    @Synchronized fun addMarker(id: String, timeMs: Long, label: String, side: String="", source: String="observer", note: String=""): Boolean {
        require(label in listOf("LEFT","RIGHT","TRANSITION","EVENT","SYNC","START_TEST","PAUSE"))
        require(side in listOf("","L","R") && source in listOf("observer","video_review","synthetic"))
        val session=getSession(id) ?: return false
        if(!session.testSession || timeMs<session.startedAtMs ||
            timeMs>(session.endedAtMs ?: System.currentTimeMillis())) return false
        writableDatabase.insertOrThrow("test_markers",null,ContentValues().apply {
            put("session_id",id); put("time_ms",timeMs); put("label",label); put("side",side)
            put("source",source); put("note",note)
        })
        return true
    }

    @Synchronized fun deleteMarker(sessionId: String, markerId: Long) {
        writableDatabase.delete("test_markers","session_id=? AND id=?",arrayOf(sessionId,markerId.toString()))
    }

    @Synchronized fun saveHealth(id: String, value: SensorHealth) {
        writableDatabase.insertOrThrow("sensor_health",null,ContentValues().apply {
            put("session_id",id); put("side",value.side); put("time_ms",value.timeMs)
            put("battery",value.battery); put("battery_at_ms",value.batteryAtMs)
            put("rssi",value.rssi); put("rssi_at_ms",value.rssiAtMs)
        })
    }

    private fun addCalibrationSchema(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE sessions ADD COLUMN calibration_json TEXT NOT NULL DEFAULT '{}'")
    }

    @Synchronized fun saveCalibration(id: String, calibration: BootCalibration) {
        val json=org.json.JSONObject(getSession(id)?.calibrationJson ?: "{}")
        fun vector(v: Vector3)=org.json.JSONArray(listOf(v.x,v.y,v.z))
        json.put(calibration.side,org.json.JSONObject().put("time_ms",calibration.timeMs)
            .put("axis",calibration.axis).put("sign",calibration.sign)
            .put("gravity",vector(calibration.gravity)).put("bias",vector(calibration.bias))
            .put("forward",calibration.forward?.let(::vector) ?: org.json.JSONObject.NULL))
        writableDatabase.update("sessions",ContentValues().apply { put("calibration_json",json.toString()) },"id=?",arrayOf(id))
    }

    fun calibrations(session: SkiSession): List<BootCalibration> {
        val json=org.json.JSONObject(session.calibrationJson)
        return listOf("L","R").mapNotNull { side -> json.optJSONObject(side)?.let { c ->
            fun vector(key: String)=c.getJSONArray(key).let { Vector3(it.getDouble(0),it.getDouble(1),it.getDouble(2)) }
            BootCalibration(side,c.getDouble("time_ms"),c.getInt("axis"),c.getInt("sign"),vector("gravity"),vector("bias"),
                if(c.has("forward") && !c.isNull("forward")) vector("forward") else null)
        } }
    }

    private fun addEquipmentSchema(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE sessions ADD COLUMN equipment TEXT NOT NULL DEFAULT ''")
    }

    @Synchronized fun setEquipment(id: String, equipment: String) {
        writableDatabase.update("sessions", ContentValues().apply {
            put("equipment", equipment.trim())
        }, "id=?", arrayOf(id))
    }

    @Synchronized fun equipmentLabels(): List<String> = readableDatabase.rawQuery(
        "SELECT DISTINCT equipment FROM sessions WHERE equipment <> '' ORDER BY equipment COLLATE NOCASE", null,
    ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

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
        val old=getSession(id)
        writableDatabase.update("sessions", ContentValues().apply {
            put("title", title); put("notes", notes); put("left_offset_ms", leftMs)
            put("right_offset_ms", rightMs); put("video_offset_ms", videoMs)
            if(old?.leftOffsetMs!=leftMs || old.rightOffsetMs!=rightMs) put("calibration_json","{}")
        }, "id=?", arrayOf(id))
    }

    /** Bumped by every write to captures from any instance, so recovery readers can skip a re-read when nothing changed. */
    val captureRevision get() = captureWrites.get()

    @Synchronized fun deleteSession(id: String): Boolean {
        val session = getSession(id) ?: return false
        if (session.endedAtMs == null || captures(id).any { it.state !in listOf("erased", "unavailable", "lost") }) return false
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (table in listOf("flash_samples", "download_samples")) db.execSQL(
                "DELETE FROM $table WHERE capture_id IN (SELECT id FROM captures WHERE session_id=?)", arrayOf(id))
            for (table in listOf("samples", "captures", "session_events", "test_markers", "sensor_health", "run_events", "run_verdicts", "run_info", "run_track", "clock_sync", "mag_samples", "phone_samples")) db.delete(table, "session_id=?", arrayOf(id))
            db.delete("sessions", "id=?", arrayOf(id))
            db.setTransactionSuccessful()
            return true
        } finally { db.endTransaction(); captureWrites.incrementAndGet() }
    }

    /** Messages of [sessionId]'s events that start with [prefix], oldest first. */
    @Synchronized fun eventMessages(sessionId: String, prefix: String): List<String> =
        readableDatabase.rawQuery("SELECT message FROM session_events WHERE session_id=? ORDER BY time_ms", arrayOf(sessionId)).use { c ->
            buildList { while (c.moveToNext()) c.getString(0).takeIf { it.startsWith(prefix) }?.let(::add) }
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
        captureWrites.incrementAndGet()
        return SensorCapture(id, sessionId, side, address, startedAtMs, state, 0, 0, "")
    }

    @Synchronized fun updateCapture(id: String, state: String, expected: Int = 0, dropped: Int = 0, error: String = "") {
        writableDatabase.update("captures", ContentValues().apply {
            put("state", state); put("expected", expected); put("dropped", dropped); put("error", error)
        }, "id=?", arrayOf(id))
        captureWrites.incrementAndGet()
    }

    @Synchronized fun captures(sessionId: String? = null): List<SensorCapture> = readableDatabase.rawQuery(
        "SELECT id,session_id,side,address,started_at_ms,state,expected,dropped,error,record_format FROM captures " +
            (if (sessionId != null) "WHERE session_id=? " else "") + "ORDER BY started_at_ms", sessionId?.let { arrayOf(it) },
    ).use { c -> buildList { while (c.moveToNext()) add(SensorCapture(c.getString(0), c.getString(1), c.getString(2),
        c.getString(3), c.getLong(4), c.getString(5), c.getInt(6), c.getInt(7), c.getString(8), c.getInt(9))) } }

    @Synchronized fun pendingCapture(address: String): SensorCapture? = captures().lastOrNull {
        it.address == address && it.state !in listOf("erased", "unavailable", "lost")
    }
    @Synchronized fun hasPendingRecovery(): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM captures WHERE state NOT IN ('erased','unavailable','lost') LIMIT 1", null,
    ).use { it.moveToFirst() }

    @Synchronized fun pendingRecoverySides(): Set<String> = readableDatabase.rawQuery(
        "SELECT DISTINCT side FROM captures WHERE state NOT IN ('erased','unavailable','lost')", null,
    ).use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }

    /** Only validateDownload commits rows here; a later retry never deletes the validated copy until replacement validates. */
    @Synchronized fun hasCommittedCopy(captureId: String): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM flash_samples WHERE capture_id=? LIMIT 1", arrayOf(captureId),
    ).use { it.moveToFirst() }

    /** Pending work plus the latest capture session, or all captures of one exact result. */
    @Synchronized fun recoveryCaptures(sessionId: String? = null): List<SensorCapture> =
        if (sessionId != null) captures(sessionId) else readableDatabase.rawQuery(
            "SELECT id,session_id,side,address,started_at_ms,state,expected,dropped,error,record_format FROM captures " +
                "WHERE state NOT IN ('erased','unavailable','lost') OR session_id=(SELECT session_id FROM captures ORDER BY started_at_ms DESC,rowid DESC LIMIT 1) ORDER BY started_at_ms", null,
        ).use { c -> buildList { while (c.moveToNext()) add(SensorCapture(c.getString(0), c.getString(1), c.getString(2),
            c.getString(3), c.getLong(4), c.getString(5), c.getInt(6), c.getInt(7), c.getString(8), c.getInt(9))) } }

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

    /**
     * Validate the committed bytes, then promote the staging copy in one transaction. [recordFormat]
     * (2 or 3) is stored with the promoted records, so a failed validation leaves the old format alone.
     */
    @Synchronized fun validateDownload(id: String, expected: Int, dropped: Int, recordFormat: Int): Boolean {
        require(recordFormat == 2 || recordFormat == 3)
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
            db.execSQL("UPDATE captures SET record_format=? WHERE id=?", arrayOf<Any>(recordFormat, id))
            updateCapture(id, "verified", expected, dropped)
            db.setTransactionSuccessful()
            return true
        } finally { db.endTransaction() }
    }

    /** [includeSync] adds the clock pairs and magnetometer rows, which only the export needs; the detail and analysis views skip them. */
    @Synchronized fun loadData(id: String, includeSync: Boolean = true): SessionData? {
        val session = getSession(id) ?: return null
        val live = readableDatabase.rawQuery("SELECT side,received_at_ms,sensor_time_ms,sequence,ax,ay,az,gx,gy,gz,quality FROM samples WHERE session_id=? ORDER BY id", arrayOf(id)).use { c ->
            buildList { while (c.moveToNext()) add(StoredLive(c.getString(0), c.getLong(1), SensorSample(c.getInt(3), c.getLong(2),
                c.getFloat(4), c.getFloat(5), c.getFloat(6), c.getFloat(7), c.getFloat(8), c.getFloat(9), c.getInt(10)))) }
        }
        val captures = captures(id)
        val flash = captures.flatMap { capture -> readableDatabase.rawQuery(
            "SELECT record_index,raw FROM flash_samples WHERE capture_id=? ORDER BY record_index", arrayOf(capture.id)).use { c ->
                buildList { while (c.moveToNext()) FlashRecord.decode(c.getBlob(1), capture.recordFormat >= 3)?.let { add(StoredFlash(capture, c.getInt(0), it)) } }
            }
        }
        val events = readableDatabase.rawQuery("SELECT time_ms,message FROM session_events WHERE session_id=? ORDER BY time_ms", arrayOf(id)).use { c ->
            buildList { while (c.moveToNext()) add(c.getLong(0) to c.getString(1)) }
        }
        val markers=readableDatabase.rawQuery("SELECT id,time_ms,label,side,source,note FROM test_markers WHERE session_id=? ORDER BY time_ms,id",arrayOf(id)).use { c ->
            buildList { while(c.moveToNext()) add(TestMarker(c.getLong(0),c.getLong(1),c.getString(2),c.getString(3),c.getString(4),c.getString(5))) }
        }
        val health=readableDatabase.rawQuery("SELECT side,time_ms,battery,battery_at_ms,rssi,rssi_at_ms FROM sensor_health WHERE session_id=? ORDER BY time_ms",arrayOf(id)).use { c ->
            buildList { while(c.moveToNext()) add(SensorHealth(c.getString(0),c.getLong(1),if(c.isNull(2)) null else c.getInt(2),
                if(c.isNull(3)) null else c.getLong(3),if(c.isNull(4)) null else c.getInt(4),if(c.isNull(5)) null else c.getLong(5))) }
        }
        return SessionData(session, live, flash, captures, events,markers,health, if (session.kind == "run") runData(id) else null,
            if (includeSync) clockPairs(id) else emptyList(), if (includeSync) magSamples(id) else emptyList(),
            if (includeSync) phoneSamples(id) else emptyList(), practiceExportReviews(id))
    }

    /**
     * The same data as [loadData], read in chunks of [chunkRows] rows: each chunk is its own short query under the lock,
     * so neither this helper's monitor nor SQLite's read lock is held across a whole session (the ski day reads several
     * sessions this way while the recorder may be writing). Only for ended sessions; the export keeps [loadData].
     */
    fun loadDataInChunks(id: String, chunkRows: Int = LOAD_CHUNK_ROWS, includeSync: Boolean = true): SessionData? {
        require(chunkRows > 0)
        val session = getSession(id) ?: return null
        /** Pages through [sql] (which must select the paging key first and end with "key>? ORDER BY key") by that key. */
        fun <T> paged(sql: String, args: Array<String>, row: (android.database.Cursor) -> T?): List<T> {
            val out = ArrayList<T>()
            var after = Long.MIN_VALUE
            while (true) {
                val n = synchronized(this) {
                    readableDatabase.rawQuery("$sql LIMIT $chunkRows", args + after.toString()).use { c ->
                        var k = 0
                        while (c.moveToNext()) { after = c.getLong(0); row(c)?.let(out::add); k++ }
                        k
                    }
                }
                if (n < chunkRows) return out
            }
        }
        val live = paged("SELECT id,side,received_at_ms,sensor_time_ms,sequence,ax,ay,az,gx,gy,gz,quality FROM samples WHERE session_id=? AND id>? ORDER BY id",
            arrayOf(id)) { c -> StoredLive(c.getString(1), c.getLong(2), SensorSample(c.getInt(4), c.getLong(3),
                c.getFloat(5), c.getFloat(6), c.getFloat(7), c.getFloat(8), c.getFloat(9), c.getFloat(10), c.getInt(11))) }
        val captures = captures(id)
        val flash = captures.flatMap { capture -> paged("SELECT record_index,raw FROM flash_samples WHERE capture_id=? AND record_index>? ORDER BY record_index",
            arrayOf(capture.id)) { c -> FlashRecord.decode(c.getBlob(1), capture.recordFormat >= 3)?.let { StoredFlash(capture, c.getInt(0), it) } } }
        val events = synchronized(this) { readableDatabase.rawQuery("SELECT time_ms,message FROM session_events WHERE session_id=? ORDER BY time_ms", arrayOf(id)).use { c ->
            buildList { while (c.moveToNext()) add(c.getLong(0) to c.getString(1)) } } }
        val markers = synchronized(this) { readableDatabase.rawQuery("SELECT id,time_ms,label,side,source,note FROM test_markers WHERE session_id=? ORDER BY time_ms,id", arrayOf(id)).use { c ->
            buildList { while (c.moveToNext()) add(TestMarker(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5))) } } }
        val health = synchronized(this) { readableDatabase.rawQuery("SELECT side,time_ms,battery,battery_at_ms,rssi,rssi_at_ms FROM sensor_health WHERE session_id=? ORDER BY time_ms", arrayOf(id)).use { c ->
            buildList { while (c.moveToNext()) add(SensorHealth(c.getString(0), c.getLong(1), if (c.isNull(2)) null else c.getInt(2),
                if (c.isNull(3)) null else c.getLong(3), if (c.isNull(4)) null else c.getInt(4), if (c.isNull(5)) null else c.getLong(5))) } } }
        // Phone and magnetometer rows are paged by rowid and come back in rowid order, then sorted as loadData sorts them.
        val phone = if (!includeSync) emptyList() else paged("SELECT rowid,phone_ns,gx,gy,gz,ax,ay,az,qw,qx,qy,qz FROM phone_samples WHERE session_id=? AND rowid>? ORDER BY rowid",
            arrayOf(id)) { c -> c.getLong(0) to StoredPhone(c.getLong(1), c.getFloat(2), c.getFloat(3), c.getFloat(4), c.getFloat(5), c.getFloat(6),
                c.getFloat(7), c.getFloat(8), c.getFloat(9), c.getFloat(10), c.getFloat(11)) }.sortedWith(compareBy({ it.second.phoneNs }, { it.first })).map { it.second }
        val mag = if (!includeSync) emptyList() else paged("SELECT rowid,side,sensor_ms,phone_receipt_ms,x,y,z,flags FROM mag_samples WHERE session_id=? AND rowid>? ORDER BY rowid",
            arrayOf(id)) { c -> c.getLong(0) to StoredMag(c.getString(1), c.getLong(2), c.getLong(3), c.getInt(4), c.getInt(5), c.getInt(6), c.getInt(7)) }
            .sortedWith(compareBy({ it.second.receiptMs }, { it.first })).map { it.second }
        return SessionData(session, live, flash, captures, events, markers, health, if (session.kind == "run") runData(id) else null,
            if (includeSync) clockPairs(id) else emptyList(), mag, phone, practiceExportReviews(id))
    }

    private fun practiceExportReviews(id: String): List<PracticeExportReview> {
        val practices = PracticeStore(this)
        return practices.forSession(id).map { PracticeExportReview(it, practices.reflection(it.id)) }
    }

    @Synchronized fun createSession(id: String, startedAtMs: Long, testSession: Boolean=false, origin: String="sensor", kind: String="session") {
        require(origin in listOf("sensor","synthetic"))
        require(kind in listOf("session","run"))
        writableDatabase.insertOrThrow("sessions", null, ContentValues().apply {
            put("id", id); put("started_at_ms", startedAtMs)
            put("test_session",if(testSession) 1 else 0)
            put("origin",origin)
            put("kind",kind)
            if(kind=="run") put("title","Run")
            if(testSession) put("title","Indoor test")
        })
    }

    // Runs ---------------------------------------------------------------------------------------------------

    @Synchronized fun saveRunStart(sessionId: String, info: RunStartInfo) {
        writableDatabase.insertWithOnConflict("run_info", null, ContentValues().apply {
            put("session_id", sessionId); put("target_beat", info.target.beatSeconds); put("target_depth", info.target.depthDegrees)
            put("target_label", info.targetLabel); put("window_size", info.windowSize); put("coach_boot", info.coachBoot)
            put("mode_before", org.json.JSONObject().apply { info.modeBefore.forEach { (side, mode) -> put(side, mode ?: org.json.JSONObject.NULL) } }.toString())
            put("gps", if (info.gps) 1 else 0)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized fun saveRunEnd(sessionId: String, info: RunEndInfo) {
        writableDatabase.update("run_info", ContentValues().apply {
            put("ended_by", info.endedBy.name.lowercase())
            if (info.firstTurnMs == null) putNull("first_turn_ms") else put("first_turn_ms", info.firstTurnMs)
            if (info.lastTurnMs == null) putNull("last_turn_ms") else put("last_turn_ms", info.lastTurnMs)
            put("gaps_json", org.json.JSONArray().apply { info.gaps.forEach { put(org.json.JSONObject().put("side", it.side).put("start_ms", it.startMs).put("end_ms", it.endMs)) } }.toString())
        }, "session_id=?", arrayOf(sessionId))
    }

    @Synchronized fun addRunEvent(sessionId: String, side: String, event: SkiEvent, receivedMs: Long) {
        writableDatabase.insertOrThrow("run_events", null, ContentValues().apply {
            put("session_id", sessionId); put("side", side); put("sequence", event.sequence); put("start_ms", event.startMs)
            put("duration_ms", event.durationMs); put("peak_roll", event.peakRollDegrees); put("peak_rate", event.peakRateDps)
            put("pitch", event.pitchDegrees); put("flags", skiEventFlags(event)); put("received_ms", receivedMs)
        })
    }

    @Synchronized fun addRunVerdict(sessionId: String, side: String, verdict: CoachVerdict, atMs: Long) {
        writableDatabase.insertOrThrow("run_verdicts", null, ContentValues().apply {
            put("session_id", sessionId); put("time_ms", atMs); put("side", side); put("verdict", verdict.verdict.name)
            put("score", verdict.score); put("tempo", verdict.tempo); put("steadiness", verdict.steadiness); put("depth", verdict.depth)
            if (verdict.balance == null) putNull("balance") else put("balance", verdict.balance)
            put("mean_beat", verdict.meanBeatSeconds); put("mean_depth", verdict.meanDepthDegrees)
            put("outside", if (verdict.outsideEnvelope) 1 else 0)
        })
    }

    @Synchronized fun addRunFix(sessionId: String, fix: GpsFix) {
        writableDatabase.insertOrThrow("run_track", null, ContentValues().apply {
            put("session_id", sessionId); put("time_ms", fix.timeMs); put("lat", fix.lat); put("lon", fix.lon)
            if (fix.altM == null) putNull("alt") else put("alt", fix.altM)
            if (fix.speedMps == null) putNull("speed") else put("speed", fix.speedMps)
            put("accuracy", fix.accuracyM)
            if (fix.verticalAccuracyM == null) putNull("vertical_accuracy") else put("vertical_accuracy", fix.verticalAccuracyM)
        })
    }

    @Synchronized fun runData(sessionId: String): RunData {
        val events = readableDatabase.rawQuery("SELECT side,sequence,start_ms,duration_ms,peak_roll,peak_rate,pitch,flags,received_ms FROM run_events WHERE session_id=? ORDER BY received_ms,rowid", arrayOf(sessionId)).use { c ->
            buildList { while (c.moveToNext()) add(RunEventRow(c.getString(0), c.getInt(1), c.getLong(2), c.getInt(3), c.getFloat(4), c.getFloat(5), c.getFloat(6), c.getInt(7), c.getLong(8))) }
        }
        val verdicts = readableDatabase.rawQuery("SELECT time_ms,side,verdict,score,tempo,steadiness,depth,balance,mean_beat,mean_depth,outside FROM run_verdicts WHERE session_id=? ORDER BY time_ms,rowid", arrayOf(sessionId)).use { c ->
            buildList { while (c.moveToNext()) add(RunVerdictRow(c.getLong(0), c.getString(1), c.getString(2), c.getInt(3), c.getInt(4), c.getInt(5), c.getInt(6),
                if (c.isNull(7)) null else c.getInt(7), c.getDouble(8), c.getDouble(9), c.getInt(10) != 0)) }
        }
        val info = readableDatabase.rawQuery("SELECT target_beat,target_depth,target_label,window_size,coach_boot,mode_before,ended_by,first_turn_ms,last_turn_ms,gaps_json,gps FROM run_info WHERE session_id=?", arrayOf(sessionId)).use { c ->
            if (c.moveToFirst()) RunInfoRow(c.getDouble(0), c.getDouble(1), c.getString(2), c.getInt(3), c.getString(4), c.getString(5), c.getString(6),
                if (c.isNull(7)) null else c.getLong(7), if (c.isNull(8)) null else c.getLong(8), c.getString(9), c.getInt(10) != 0) else null
        }
        val track = readableDatabase.rawQuery("SELECT time_ms,lat,lon,alt,speed,accuracy,vertical_accuracy FROM run_track WHERE session_id=? ORDER BY time_ms,rowid", arrayOf(sessionId)).use { c ->
            buildList { while (c.moveToNext()) add(GpsFix(c.getLong(0), c.getDouble(1), c.getDouble(2),
                if (c.isNull(3)) null else c.getDouble(3), if (c.isNull(4)) null else c.getDouble(4),
                c.getFloat(5), if (c.isNull(6)) null else c.getFloat(6))) }
        }
        return RunData(events, verdicts, info, track)
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
                    put("quality", s.quality)
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
            s.title,s.notes,s.left_offset_ms,s.right_offset_ms,s.video_offset_ms,s.equipment,s.calibration_json,s.test_session,s.origin,s.analysis_json,s.kind
            FROM sessions s ${if (selection != null) "WHERE $selection" else ""} ORDER BY s.started_at_ms DESC"""
        return readableDatabase.rawQuery(sql, args).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(SkiSession(
                    cursor.getString(0), cursor.getLong(1), if (cursor.isNull(2)) null else cursor.getLong(2),
                    cursor.getLong(5), cursor.getLong(6), if (cursor.isNull(3)) null else cursor.getString(3),
                    if (cursor.isNull(4)) null else cursor.getString(4),
                    cursor.getString(7), cursor.getString(8), cursor.getLong(9), cursor.getLong(10), cursor.getLong(11),
                    cursor.getString(12),
                    cursor.getString(13),
                    cursor.getInt(14)!=0,
                    cursor.getString(15),
                    cursor.getString(16),
                    cursor.getString(17),
                ))
            }
        }
    }
}
