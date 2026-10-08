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
    val startedAtMs: Long, val state: String, val expected: Int, val dropped: Int, val error: String)
data class StoredLive(val side: String, val receivedAtMs: Long, val sample: SensorSample)
data class StoredFlash(val capture: SensorCapture, val index: Int, val record: FlashRecord)
data class SessionData(val session: SkiSession, val live: List<StoredLive>, val flash: List<StoredFlash>,
    val captures: List<SensorCapture>, val events: List<Pair<Long, String>>,
    val markers: List<TestMarker> = emptyList(), val health: List<SensorHealth> = emptyList(),
    val run: RunData? = null)

data class TestMarker(val id: Long, val timeMs: Long, val label: String, val side: String,
    val source: String, val note: String)
data class SensorHealth(val side: String, val timeMs: Long, val battery: Int?, val batteryAtMs: Long?,
    val rssi: Int?, val rssiAtMs: Long?)

class LocalSessionStore(context: Context, databaseName: String = "openski-local.db") : SQLiteOpenHelper(context, databaseName, null, 8) {
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
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) addRecordingSchema(db)
        if (oldVersion < 3) addEquipmentSchema(db)
        if (oldVersion < 4) addCalibrationSchema(db)
        if (oldVersion < 5) addTestSchema(db)
        if (oldVersion < 6) addOriginSchema(db)
        if (oldVersion < 7) addAnalysisSchema(db)
        if (oldVersion < 8) addRunSchema(db)
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

    @Synchronized fun deleteSession(id: String): Boolean {
        val session = getSession(id) ?: return false
        if (session.endedAtMs == null || captures(id).any { it.state !in listOf("erased", "unavailable", "lost") }) return false
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (table in listOf("flash_samples", "download_samples")) db.execSQL(
                "DELETE FROM $table WHERE capture_id IN (SELECT id FROM captures WHERE session_id=?)", arrayOf(id))
            for (table in listOf("samples", "captures", "session_events", "test_markers", "sensor_health", "run_events", "run_verdicts", "run_info")) db.delete(table, "session_id=?", arrayOf(id))
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
        val markers=readableDatabase.rawQuery("SELECT id,time_ms,label,side,source,note FROM test_markers WHERE session_id=? ORDER BY time_ms,id",arrayOf(id)).use { c ->
            buildList { while(c.moveToNext()) add(TestMarker(c.getLong(0),c.getLong(1),c.getString(2),c.getString(3),c.getString(4),c.getString(5))) }
        }
        val health=readableDatabase.rawQuery("SELECT side,time_ms,battery,battery_at_ms,rssi,rssi_at_ms FROM sensor_health WHERE session_id=? ORDER BY time_ms",arrayOf(id)).use { c ->
            buildList { while(c.moveToNext()) add(SensorHealth(c.getString(0),c.getLong(1),if(c.isNull(2)) null else c.getInt(2),
                if(c.isNull(3)) null else c.getLong(3),if(c.isNull(4)) null else c.getInt(4),if(c.isNull(5)) null else c.getLong(5))) }
        }
        return SessionData(session, live, flash, captures, events,markers,health, if (session.kind == "run") runData(id) else null)
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

    @Synchronized fun runData(sessionId: String): RunData {
        val events = readableDatabase.rawQuery("SELECT side,sequence,start_ms,duration_ms,peak_roll,peak_rate,pitch,flags,received_ms FROM run_events WHERE session_id=? ORDER BY received_ms,rowid", arrayOf(sessionId)).use { c ->
            buildList { while (c.moveToNext()) add(RunEventRow(c.getString(0), c.getInt(1), c.getLong(2), c.getInt(3), c.getFloat(4), c.getFloat(5), c.getFloat(6), c.getInt(7), c.getLong(8))) }
        }
        val verdicts = readableDatabase.rawQuery("SELECT time_ms,side,verdict,score,tempo,steadiness,depth,balance,mean_beat,mean_depth,outside FROM run_verdicts WHERE session_id=? ORDER BY time_ms,rowid", arrayOf(sessionId)).use { c ->
            buildList { while (c.moveToNext()) add(RunVerdictRow(c.getLong(0), c.getString(1), c.getString(2), c.getInt(3), c.getInt(4), c.getInt(5), c.getInt(6),
                if (c.isNull(7)) null else c.getInt(7), c.getDouble(8), c.getDouble(9), c.getInt(10) != 0)) }
        }
        val info = readableDatabase.rawQuery("SELECT target_beat,target_depth,target_label,window_size,coach_boot,mode_before,ended_by,first_turn_ms,last_turn_ms,gaps_json FROM run_info WHERE session_id=?", arrayOf(sessionId)).use { c ->
            if (c.moveToFirst()) RunInfoRow(c.getDouble(0), c.getDouble(1), c.getString(2), c.getInt(3), c.getString(4), c.getString(5), c.getString(6),
                if (c.isNull(7)) null else c.getLong(7), if (c.isNull(8)) null else c.getLong(8), c.getString(9)) else null
        }
        return RunData(events, verdicts, info)
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
