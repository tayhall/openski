package com.openski.android

import android.app.Instrumentation
import android.app.Activity
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.ZipInputStream
import org.json.JSONObject

/** Runs against an isolated database on a real Android SQLite implementation. */
class StorageInstrumentation : Instrumentation() {
    private var practiceUiOnly = false
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        practiceUiOnly = arguments?.getString("practice_ui_only") == "true"
        start()
    }
    /** Tables each later schema version adds; dropping them takes a latest-schema database back below that version. */
    private val tablesByVersion = mapOf(
        11 to listOf("clock_sync","mag_samples","phone_samples"),
        12 to listOf("resorts","resort_ways","resort_stations"),
        13 to listOf("ski_days","day_fixes","day_run_tags"),
        14 to listOf("practice_attempts","practice_imports"))
    /** Drops every table added after [version], so reopening with an older version number is a genuine upgrade. */
    private fun dropTablesAbove(db: SQLiteDatabase, version: Int) =
        tablesByVersion.filterKeys { it > version }.values.flatten().forEach { db.execSQL("DROP TABLE IF EXISTS $it") }
    /** Takes a latest-schema database back to version 9 by rebuilding run_info, since DROP COLUMN needs SQLite 3.35 (Android 14). */
    private fun removeVersion10(db: SQLiteDatabase) {
        dropTablesAbove(db,10)
        db.execSQL("DROP TABLE run_track")
        db.execSQL("ALTER TABLE run_info RENAME TO run_info_v10")
        db.execSQL("""CREATE TABLE run_info(session_id TEXT PRIMARY KEY, target_beat REAL NOT NULL, target_depth REAL NOT NULL,
            target_label TEXT NOT NULL, window_size INTEGER NOT NULL, coach_boot TEXT NOT NULL, mode_before TEXT NOT NULL,
            ended_by TEXT NOT NULL DEFAULT '', first_turn_ms INTEGER, last_turn_ms INTEGER, gaps_json TEXT NOT NULL DEFAULT '[]')""")
        db.execSQL("INSERT INTO run_info SELECT session_id,target_beat,target_depth,target_label,window_size,coach_boot,mode_before,ended_by,first_turn_ms,last_turn_ms,gaps_json FROM run_info_v10")
        db.execSQL("DROP TABLE run_info_v10")
    }
    /** A small resort map: a lift, a piste split into two parts at a gap, a station node and a station outline. */
    private fun resortSample(line: List<LatLon>) = ResortData(listOf(
        ResortWayInfo(ResortWay(11L,WayKind.LIFT,"Sunny Ridge","C4",null,"chair_lift",line),null,"Example Lifts",7,false),
        ResortWayInfo(ResortWay(12L,WayKind.PISTE,null,null,"novice",null,line.reversed()),PisteDifficulty.EASY,null,null,true,300L,part=0),
        ResortWayInfo(ResortWay(12L,WayKind.PISTE,null,null,"novice",null,line.take(2)),PisteDifficulty.EASY,null,null,false,300L,part=1)),
        listOf(ResortStation(21L,"Top",line.last(),null,1), ResortStation(21L,"Top hall",line.first(),"Example Lifts",3,osmType="way")))
    override fun onStart() {
        val name="openski-test-${UUID.randomUUID()}.db"
        try {
            if (practiceUiOnly) {
                PracticeUiRegression(this).run()
                finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "PASS: native controls and selected navigation, chart facts, stable coaching focus/scroll, exact run/practice links and missing-record fallbacks\n") })
                return
            }
            val context=targetContext
            for(version in listOf(2,3,4,5,6)) {
                val migrationName="openski-migration-$version-${UUID.randomUUID()}.db"
                try {
                    val migrationPath=context.getDatabasePath(migrationName)
                    migrationPath.parentFile!!.mkdirs()
                    SQLiteDatabase.openOrCreateDatabase(migrationPath,null).use { db ->
                        db.execSQL("""CREATE TABLE sessions(id TEXT PRIMARY KEY,started_at_ms INTEGER NOT NULL,
                            ended_at_ms INTEGER,video_uri TEXT,video_name TEXT,title TEXT NOT NULL DEFAULT '',
                            notes TEXT NOT NULL DEFAULT '',left_offset_ms INTEGER NOT NULL DEFAULT 0,
                            right_offset_ms INTEGER NOT NULL DEFAULT 0,video_offset_ms INTEGER NOT NULL DEFAULT 0
                            ${if(version>=3) ",equipment TEXT NOT NULL DEFAULT ''" else ""}
                            ${if(version>=4) ",calibration_json TEXT NOT NULL DEFAULT '{}'" else ""}
                            ${if(version>=5) ",test_session INTEGER NOT NULL DEFAULT 0" else ""}
                            ${if(version>=6) ",origin TEXT NOT NULL DEFAULT 'sensor'" else ""})""")
                        db.execSQL("CREATE TABLE samples(session_id TEXT,side TEXT)")
                        // A genuine version 2+ database already has the recording tables; the version-9 upgrade alters captures.
                        db.execSQL("CREATE TABLE captures(id TEXT PRIMARY KEY,session_id TEXT NOT NULL,side TEXT NOT NULL,address TEXT NOT NULL,started_at_ms INTEGER NOT NULL,state TEXT NOT NULL,expected INTEGER NOT NULL DEFAULT 0,dropped INTEGER NOT NULL DEFAULT 0,error TEXT NOT NULL DEFAULT '')")
                        db.execSQL("INSERT INTO sessions(id,started_at_ms,title) VALUES('preserved',100000,'Existing run')")
                        if(version>=3) db.execSQL("UPDATE sessions SET equipment='Existing skis'")
                        db.version=version
                    }
                    LocalSessionStore(context,migrationName).use { store ->
                        val session=store.getSession("preserved")!!
                        check(session.title=="Existing run" && session.calibrationJson=="{}")
                        check(session.equipment==if(version>=3) "Existing skis" else "")
                        check(!session.testSession)
                        check(session.origin=="sensor" && session.analysisJson=="{}")
                        val calibration=BootCalibration("R",100000.0,1,-1,Vector3(0.0,0.0,1.0),Vector3(0.0,0.0,0.0))
                        store.saveCalibration(session.id,calibration)
                        check(store.calibrations(store.getSession(session.id)!!)==listOf(calibration))
                    }
                } finally { context.deleteDatabase(migrationName) }
            }
            // Version 8: runs. Old sessions become kind 'session'; a run stores, reads back and deletes its records.
            val runMigration="openski-migration-7-${UUID.randomUUID()}.db"
            try {
                val runPath=context.getDatabasePath(runMigration)
                runPath.parentFile!!.mkdirs()
                // A version-1 database, as in the existing check below: the store then migrates it through every step to 8,
                // so all the tables a real database has by version 7 exist when the run tables are added.
                SQLiteDatabase.openOrCreateDatabase(runPath,null).use { db ->
                    db.execSQL("CREATE TABLE sessions(id TEXT PRIMARY KEY,started_at_ms INTEGER NOT NULL,ended_at_ms INTEGER,video_uri TEXT,video_name TEXT)")
                    db.execSQL("CREATE TABLE samples(id INTEGER PRIMARY KEY AUTOINCREMENT,session_id TEXT NOT NULL,side TEXT NOT NULL,received_at_ms INTEGER NOT NULL,sensor_time_ms INTEGER NOT NULL,sequence INTEGER NOT NULL,ax REAL NOT NULL,ay REAL NOT NULL,az REAL NOT NULL,gx REAL NOT NULL,gy REAL NOT NULL,gz REAL NOT NULL)")
                    db.execSQL("INSERT INTO sessions VALUES('before-runs',100000,160000,NULL,NULL)")
                    db.version=1
                }
                LocalSessionStore(context,runMigration).use { store ->
                    check(store.getSession("before-runs")!!.kind=="session")
                    val id="a-run"
                    store.createSession(id,200000,false,"sensor","run")
                    check(store.getSession(id)!!.kind=="run")
                    store.saveRunStart(id,RunStartInfo(200000,false,CoachTarget(2.0,20.0),"Steady rhythm",4,"AUTO",mapOf("L" to false,"R" to null)))
                    store.addRunEvent(id,"R",SkiEvent(3,5000,1800,-21.5f,120f,15f,false,true,false,true),200500)
                    store.addRunVerdict(id,"R",CoachVerdict(Verdict.POSITIVE,91,90,92,93,null,2.0,20.0,false),203000)
                    store.saveRunEnd(id,RunEndInfo(260000,RunEnd.QUIET,200500,250000,listOf(RunGap("R",220000,225000))))
                    store.finishSession(id,260000)
                    val run=store.loadData(id)!!.run!!
                    check(run.events.single().let { it.side=="R" && it.peakRoll==-21.5f && it.flags==(2 or 8) && it.receivedMs==200500L })
                    check(run.verdicts.single().let { it.verdict=="POSITIVE" && it.balance==null })
                    check(run.info!!.endedBy=="quiet" && run.info!!.targetLabel=="Steady rhythm" && run.info!!.lastTurnMs==250000L)
                    check(store.deleteSession(id))
                    check(store.runData(id).let { it.events.isEmpty() && it.verdicts.isEmpty() && it.info==null })
                    check(store.getSession("before-runs")!!.let { it.startedAtMs==100000L && it.kind=="session" })
                }
            } finally { context.deleteDatabase(runMigration) }
            // Version 9: signal quality. Legacy flash bytes are never masked; a capture marked format 3 loads with flags.
            val qualityMigration="openski-migration-8-${UUID.randomUUID()}.db"
            try {
                val qualityPath=context.getDatabasePath(qualityMigration)
                qualityPath.parentFile!!.mkdirs()
                SQLiteDatabase.openOrCreateDatabase(qualityPath,null).use { db ->
                    db.execSQL("CREATE TABLE sessions(id TEXT PRIMARY KEY,started_at_ms INTEGER NOT NULL,ended_at_ms INTEGER,video_uri TEXT,video_name TEXT)")
                    db.execSQL("CREATE TABLE samples(id INTEGER PRIMARY KEY AUTOINCREMENT,session_id TEXT NOT NULL,side TEXT NOT NULL,received_at_ms INTEGER NOT NULL,sensor_time_ms INTEGER NOT NULL,sequence INTEGER NOT NULL,ax REAL NOT NULL,ay REAL NOT NULL,az REAL NOT NULL,gx REAL NOT NULL,gy REAL NOT NULL,gz REAL NOT NULL)")
                    db.version=1
                }
                LocalSessionStore(context,qualityMigration).use { it.readableDatabase }
                // Rebuild captures and samples without the version-9 columns so the next open is a genuine 8 -> 9 upgrade.
                SQLiteDatabase.openOrCreateDatabase(qualityPath,null).use { db ->
                    db.execSQL("CREATE TABLE captures_v8 AS SELECT id,session_id,side,address,started_at_ms,state,expected,dropped,error FROM captures")
                    db.execSQL("DROP TABLE captures")
                    db.execSQL("ALTER TABLE captures_v8 RENAME TO captures")
                    db.execSQL("CREATE TABLE samples_v8 AS SELECT id,session_id,side,received_at_ms,sensor_time_ms,sequence,ax,ay,az,gx,gy,gz FROM samples")
                    db.execSQL("DROP TABLE samples")
                    db.execSQL("ALTER TABLE samples_v8 RENAME TO samples")
                    db.execSQL("INSERT INTO sessions(id,started_at_ms,ended_at_ms) VALUES('q8',100000,160000)")
                    db.execSQL("INSERT INTO samples(session_id,side,received_at_ms,sensor_time_ms,sequence,ax,ay,az,gx,gy,gz) VALUES('q8','L',100000,10,1,0,0,9.81,0,0,0)")
                    db.execSQL("INSERT INTO captures VALUES('legacy','q8','L','AA:01',100000,'verified',1,0,'')")
                    val legacy=java.nio.ByteBuffer.allocate(16).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(0x0000_0105).array()
                    db.execSQL("INSERT INTO flash_samples VALUES('legacy',0,?)",arrayOf<Any>(legacy))
                    // The first open built the latest schema; remove the version-10 parts so the reopen upgrades 8 -> 9 -> 10 cleanly.
                    removeVersion10(db)
                    db.version=8
                }
                LocalSessionStore(context,qualityMigration).use { store ->
                    store.readableDatabase.rawQuery("PRAGMA table_info(captures)",null).use { c ->
                        var found=false
                        while(c.moveToNext()) if(c.getString(1)=="record_format") { found=true; check(c.getString(4)=="2" && c.getInt(3)==1) }
                        check(found) { "captures.record_format missing" }
                    }
                    store.readableDatabase.rawQuery("PRAGMA table_info(samples)",null).use { c ->
                        var found=false
                        while(c.moveToNext()) if(c.getString(1)=="quality") { found=true; check(c.getString(4)=="0" && c.getInt(3)==1) }
                        check(found) { "samples.quality missing" }
                    }
                    val old=store.loadData("q8")!!
                    check(old.live.single().sample.quality==0)
                    check(old.captures.single().recordFormat==2)
                    old.flash.single().record.let { check(it.timestampUs==0x105L && it.sample.quality==0) { "Legacy record was masked" } }
                    store.saveSamples("q8",listOf(RecordedSample("R",100500,SensorSample(2,20,0f,0f,9.81f,0f,0f,0f,9))))
                    check(store.loadData("q8")!!.live.last().sample.quality==9) { "Live quality not stored" }
                    // A failed validation must keep the old format; a successful one commits the staged format with the data.
                    store.beginDownload("legacy")
                    store.saveDownload("legacy",FlashChunk(0,listOf(FlashRecord.decode(java.nio.ByteBuffer.allocate(16).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(0x0000_0105).array())!!),withFlags=true))
                    check(!store.validateDownload("legacy",2,0,3)) { "Validated incomplete flagged copy" }
                    check(store.loadData("q8")!!.captures.single().recordFormat==2) { "Failed validation changed record format" }
                    check(store.validateDownload("legacy",1,0,3))
                    val flagged=store.loadData("q8")!!
                    check(flagged.captures.single().recordFormat==3)
                    flagged.flash.single().record.let { check(it.timestampUs==0x100L && it.sample.quality==17) { "Flagged record not decoded" } }
                }
            } finally { context.deleteDatabase(qualityMigration) }
            // Version 9 -> 10: phone GPS for runs. A genuine upgrade: build the latest schema, remove the version-10 parts, set 9.
            val gpsMigration="openski-migration-9-${UUID.randomUUID()}.db"
            try {
                LocalSessionStore(context,gpsMigration).use { it.writableDatabase.version }
                val gpsPath=context.getDatabasePath(gpsMigration)
                SQLiteDatabase.openDatabase(gpsPath.path,null,SQLiteDatabase.OPEN_READWRITE).use { db ->
                    removeVersion10(db)
                    db.version=9
                }
                LocalSessionStore(context,gpsMigration).use { store ->
                    val id=UUID.randomUUID().toString()
                    store.createSession(id,1_000L,kind="run")
                    store.saveRunStart(id,RunStartInfo(1_000L,false,CoachTarget(2.0,20.0),"Custom",4,"AUTO",mapOf("L" to false,"R" to false),gps=true))
                    store.addRunFix(id,GpsFix(2_000L,46.0,7.0,2100.0,3.5,6f,4f))
                    store.addRunFix(id,GpsFix(3_000L,46.0001,7.0,null,null,8f,null))
                    val run=store.runData(id)
                    check(run.info?.gps==true) { "gps flag not stored" }
                    check(run.track.size==2 && run.track[0].altM==2100.0 && run.track[1].altM==null) { "track round trip" }
                    store.finishSession(id,4_000L)
                    check(store.deleteSession(id)) { "run delete" }
                    check(store.readableDatabase.rawQuery("SELECT COUNT(*) FROM run_track WHERE session_id=?",arrayOf(id)).use { it.moveToFirst() && it.getInt(0)==0 }) { "track not deleted with the session" }
                }
            } finally { context.deleteDatabase(gpsMigration) }
            // Version 10 -> 11: clock sync and magnetometer tables. A genuine upgrade: drop the three new tables, set 10, reopen.
            val clockMigration="openski-migration-10-${UUID.randomUUID()}.db"
            try {
                LocalSessionStore(context,clockMigration).use { it.writableDatabase.version }
                SQLiteDatabase.openDatabase(context.getDatabasePath(clockMigration).path,null,SQLiteDatabase.OPEN_READWRITE).use { db ->
                    dropTablesAbove(db,10)
                    db.version=10
                }
                LocalSessionStore(context,clockMigration).use { store ->
                    check(store.readableDatabase.version==LocalSessionStore.VERSION) { "Not upgraded from 10" }
                    listOf("clock_sync","mag_samples","phone_samples").forEach { table ->
                        check(store.readableDatabase.rawQuery("SELECT COUNT(*) FROM $table",null).use { it.moveToFirst() && it.getInt(0)==0 }) { "$table missing or not empty" }
                    }
                    val id=UUID.randomUUID().toString()
                    store.createSession(id,1_000_000L)
                    store.event(id,"clock_ref wall_ms=1000000 elapsed_ns=5000000000000")
                    store.addClockPairs(id,(0 until 8).map { StoredClockPair("L",it,5_000_000_000_000L+it*100_000_000L,5_000_000_000_000L+it*100_000_000L+30_000_000L,
                        (4_900_000_000_000L+it*100_000_000L+15_000_000L)/1000,30_000_000L,true) } +
                        StoredClockPair("R",99,5_000_000_000_000L,5_000_000_000_000L-1L,5L,-1L,false))
                    store.addMagSamples(id,listOf(StoredMag("L",500,1_000_250L,6842,-3421,32767,3)))
                    check(store.clockPairs(id).size==9 && store.clockPairs(id).count { !it.accepted }==1)
                    check(store.magSamples(id).single().let { it.x==6842 && it.y==-3421 && it.z==32767 && it.flags==3 })
                    store.addPhoneSamples(id,listOf(StoredPhone(5_000_000_000_000L,0.5f,-0.25f,0f,0f,0f,9.81f,1f,0f,0f,0f),
                        StoredPhone(5_000_010_000_000L,0.6f,-0.2f,0.1f,0.1f,0f,9.8f,0.99f,0.01f,0f,0f)))
                    check(store.phoneSamples(id).let { it.size==2 && it[0].gx==0.5f && it[1].qw==0.99f && it[1].phoneNs==5_000_010_000_000L }) { "phone rows round trip" }
                    store.finishSession(id,1_100_000L)
                    val loaded=store.loadData(id)!!
                    // The chunked reader used by the ski day returns exactly what loadData returns, even one row per query.
                    check(store.loadDataInChunks(id,1)==loaded && store.loadDataInChunks(id)==loaded) { "chunked load differs from loadData" }
                    check(store.lastActivityMs(id)==1_000_000L) { "last activity of a session without samples is its start" }
                    check(loaded.clockPairs.size==9 && loaded.mag.size==1 && loaded.phone.size==2) { "loadData omitted clock, magnetometer or phone rows" }
                    check(store.loadData(id,includeSync=false)!!.phone.isEmpty()) { "phone rows loaded without includeSync" }
                    val zipped=ByteArrayOutputStream()
                    SessionExport.write(loaded,zipped)
                    val files=mutableMapOf<String,String>()
                    ZipInputStream(zipped.toByteArray().inputStream()).use { zip ->
                        while(true) { val entry=zip.nextEntry ?: break; files[entry.name]=zip.readBytes().toString(Charsets.UTF_8) }
                    }
                    check(files.getValue("clock-sync.csv").trim().lines().size==10) { "clock-sync.csv rows" }
                    check(JSONObject(files.getValue("clock-sync-fit.json")).getJSONObject("L").getBoolean("available")) { "clock-sync-fit.json" }
                    check(files.getValue("magnetometer.csv").trim().lines().let { it.size==2 && it[1].endsWith("uncalibrated") }) { "magnetometer.csv" }
                    check(files.getValue("phone-motion.csv").trim().lines().let { l -> l.count { !it.startsWith("#") }==3 }) { "phone-motion.csv rows" }
                    check(files.getValue("samples.csv").trim().lines().size==1) { "raw samples changed" }
                    check(store.deleteSession(id)) { "clock session delete" }
                    listOf("clock_sync","mag_samples","phone_samples").forEach { table ->
                        check(store.readableDatabase.rawQuery("SELECT COUNT(*) FROM $table WHERE session_id=?",arrayOf(id)).use { it.moveToFirst() && it.getInt(0)==0 }) { "$table not deleted with the session" }
                    }
                }
            } finally { context.deleteDatabase(clockMigration) }
            // Version 11 -> 12: resort maps. A genuine upgrade: drop the three resort tables, keep a session, set 11, reopen.
            val resortMigration="openski-migration-11-${UUID.randomUUID()}.db"
            try {
                LocalSessionStore(context,resortMigration).use { it.writableDatabase.version }
                SQLiteDatabase.openDatabase(context.getDatabasePath(resortMigration).path,null,SQLiteDatabase.OPEN_READWRITE).use { db ->
                    dropTablesAbove(db,11)
                    db.execSQL("INSERT INTO sessions(id,started_at_ms,ended_at_ms) VALUES('before-resorts',100000,160000)")
                    db.version=11
                }
                LocalSessionStore(context,resortMigration).use { store ->
                    check(store.readableDatabase.version==LocalSessionStore.VERSION) { "Not upgraded from 11" }
                    check(store.getSession("before-resorts")!!.endedAtMs==160000L) { "Upgrade lost a session" }
                    listOf("resorts","resort_ways","resort_stations").forEach { table ->
                        check(store.readableDatabase.rawQuery("SELECT COUNT(*) FROM $table",null).use { it.moveToFirst() && it.getInt(0)==0 }) { "$table missing or not empty" }
                    }
                    val resorts=ResortStore(store)
                    val line=listOf(LatLon(46.010123,7.010456),LatLon(46.020001,7.019999),LatLon(46.025,7.03))
                    val data=resortSample(line)
                    val alpha=StoredResort("alpha","Alpha",LatLon(46.0,7.0),25_000,1_000L,3,"test; ${ResortRepository.ATTRIBUTION}",truncated=true)
                    resorts.save(alpha,data)
                    resorts.save(alpha.copy(id="beta",name="Beta"),data)
                    check(resorts.list().map { it.id }==listOf("alpha","beta")) { "resort list" }
                    check(resorts.resort("alpha")==alpha) { "resort row round trip (truncated flag)" }
                    check(resorts.resort("beta")!!.truncated) { "truncated flag" }
                    check(resorts.ways("alpha")==data.ways) { "ways round trip: ${resorts.ways("alpha")}" }
                    check(resorts.stations("alpha")==data.stations) { "stations round trip" }
                    check(resorts.age("alpha",4_000L)==3_000L) { "age" }
                    // A save that fails part-way (a duplicate way id breaks the primary key) must leave the old copy whole.
                    val broken=data.copy(ways=data.ways+data.ways.first())
                    check(runCatching { resorts.save(alpha.copy(name="Broken",fetchedAtMs=2_000L),broken) }.isFailure) { "duplicate way accepted" }
                    check(resorts.resort("alpha")==alpha && resorts.ways("alpha").size==3 && resorts.stations("alpha").size==2) { "failed save was not atomic" }
                    // Deleting a session never touches resorts.
                    store.createSession("plain",200000)
                    store.finishSession("plain",201000)
                    check(store.deleteSession("plain"))
                    check(store.deleteSession("before-resorts"))
                    check(resorts.list().size==2 && resorts.ways("beta").size==3) { "session delete touched resorts" }
                    // Deleting a resort deletes its ways and stations and nothing else.
                    check(resorts.delete("alpha") && !resorts.delete("alpha"))
                    listOf("resort_ways","resort_stations").forEach { table ->
                        check(store.readableDatabase.rawQuery("SELECT COUNT(*) FROM $table WHERE resort_id='alpha'",null).use { it.moveToFirst() && it.getInt(0)==0 }) { "$table rows left after resort delete" }
                    }
                    check(resorts.ways("beta").size==3 && resorts.stations("beta").size==2) { "resort delete touched another resort" }
                    // The repository over the real store: an empty answer keeps the cached map.
                    val repo=ResortRepository({ FetchResult.Body("{\"elements\":[]}") },resorts,clock={ 5_000L })
                    check(repo.download(ResortRequest("beta","Beta",LatLon(46.0,7.0),3000)) is DownloadResult.NoData) { "empty answer not refused" }
                    check(resorts.ways("beta").size==3 && resorts.resort("beta")!!.fetchedAtMs==1_000L) { "empty answer replaced the cache" }
                }
            } finally { context.deleteDatabase(resortMigration) }
            // Fresh install: onCreate builds the version 12 resort tables directly; a resort map round-trips through them.
            val freshResorts="openski-fresh-12-${UUID.randomUUID()}.db"
            try {
                LocalSessionStore(context,freshResorts).use { store ->
                    check(store.readableDatabase.version==LocalSessionStore.VERSION) { "Fresh database not at the latest version" }
                    check(DayStore(store).list().isEmpty()) { "fresh install has no day tables" }
                    val resorts=ResortStore(store)
                    val data=resortSample(listOf(LatLon(45.5,6.5),LatLon(45.501,6.502),LatLon(45.502,6.503)))
                    val fresh=StoredResort("fresh","Fresh",LatLon(45.5,6.5),5000,7_000L,3,"fresh; ${ResortRepository.ATTRIBUTION}")
                    resorts.save(fresh,data)
                    check(resorts.list()==listOf(fresh)) { "fresh resort list" }
                    check(resorts.ways("fresh")==data.ways && resorts.stations("fresh")==data.stations) { "fresh round trip" }
                    check(resorts.delete("fresh") && resorts.list().isEmpty()) { "fresh delete" }
                }
            } finally { context.deleteDatabase(freshResorts) }
            // Version 13 -> 14 and fresh install: import rollback, retry, rewards and session independence.
            for (upgrade in listOf(false, true)) {
                val practiceName = "openski-practice-${UUID.randomUUID()}.db"
                try {
                    LocalSessionStore(context, practiceName).use { it.writableDatabase.version }
                    if (upgrade) SQLiteDatabase.openDatabase(context.getDatabasePath(practiceName).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                        dropTablesAbove(db, 13)
                        db.version = 13
                    }
                    LocalSessionStore(context, practiceName).use { store ->
                        check(store.readableDatabase.version == LocalSessionStore.VERSION)
                        val practice = PracticeStore(store)
                        val row = """{"drill":"slow-rolls","time":1000,"score":95,"stars":3,"vert":190,"demo":true}"""
                        check(runCatching { practice.importLegacy("[$row,null]") }.isFailure)
                        check(practice.list().isEmpty()) { "failed import leaked rows" }
                        practice.importLegacy("[$row,$row]")
                        practice.importLegacy("malformed after completed import must be ignored")
                        check(practice.list().size == 2 && practice.list().map { it.id }.toSet().size == 2)
                        check(practice.list().all { it.demo && it.target == null && it.vert == 190 })
                        store.createSession("practice-session", 2000)
                        val completed = Attempt("slow-rolls", 2000, 70, 1, 0, false, id = "stable-attempt",
                            sessionId = "practice-session", target = PracticeTarget.of(Programme.drills.first()))
                        val saved = practice.record(completed, calculateReward = true)
                        check(saved.vert == 140) { "demo affected measured reward" }
                        check(practice.record(completed.copy(score = 100), calculateReward = true) == saved) { "completion was not idempotent" }
                        check(practice.get(saved.id) == saved) { "target or link round trip" }
                        check(!store.deleteSession("practice-session")) { "open-session deletion guard was bypassed" }
                        store.finishSession("practice-session", 3000)
                        check(store.deleteSession("practice-session"))
                        check(practice.get(saved.id) == saved) { "session deletion removed achievement" }
                        val early = practice.record(completed.copy(id = "early", status = PracticeStatus.INCOMPLETE), calculateReward = true)
                        check(early.vert == 0 && early.stars == 0)
                        check(Progress(practice.list()).best("slow-rolls") == saved)
                        val active = completed.copy(id = "active", sessionId = null, status = PracticeStatus.ACTIVE, score = 0, stars = 0)
                        practice.record(active)
                        val finished = practice.record(active.copy(status = PracticeStatus.COMPLETED, score = 80, stars = 2), calculateReward = true)
                        check(finished.vert == 20 && finished.stars == 2) { "active-to-completed reward" }
                        check(practice.record(active.copy(status = PracticeStatus.COMPLETED, score = 100, stars = 3), calculateReward = true) == finished)
                        store.createSession("practice-orphan", 4000)
                        store.createSession("manual-unrelated", 4000)
                        practice.record(active.copy(id = "abandoned", sessionId = "practice-orphan"))
                        store.writableDatabase.execSQL("CREATE TRIGGER fail_orphan_close BEFORE UPDATE ON sessions WHEN OLD.id='practice-orphan' BEGIN SELECT RAISE(ABORT, 'test failure'); END")
                        check(runCatching { practice.interruptActive(4500) }.isFailure)
                        check(practice.get("abandoned")!!.status == PracticeStatus.ACTIVE && store.getSession("practice-orphan")!!.endedAtMs == null)
                        store.writableDatabase.execSQL("DROP TRIGGER fail_orphan_close")
                        check(practice.interruptActive(4500).map { it.id } == listOf("abandoned"))
                        check(store.getSession("practice-orphan")!!.endedAtMs == 4500L)
                        check(store.getSession("manual-unrelated")!!.endedAtMs == null) { "recovery closed an unrelated recording" }
                        check(practice.get("abandoned")!!.let { it.status == PracticeStatus.INTERRUPTED && it.vert == 0 && it.stars == 0 })
                        check(practice.interruptActive().isEmpty()) { "restart reconciliation repeated" }

                    }
                    LocalSessionStore(context, practiceName).use { store ->
                        val practice = PracticeStore(store)
                        practice.importLegacy("[]")
                        check(practice.list().size == 6 && practice.get("stable-attempt")!!.vert == 140)
                    }
                } finally { context.deleteDatabase(practiceName) }
            }
            // Version 12 -> 13: ski days. A genuine upgrade: drop the three day tables, keep a session and a resort, set 12, reopen.
            val dayMigration="openski-migration-12-${UUID.randomUUID()}.db"
            try {
                LocalSessionStore(context,dayMigration).use { store ->
                    store.writableDatabase.version
                    ResortStore(store).save(StoredResort("kept","Kept",LatLon(46.0,7.0),5000,1_000L,3,"test"),
                        resortSample(listOf(LatLon(46.0,7.0),LatLon(46.001,7.001),LatLon(46.002,7.002))))
                }
                SQLiteDatabase.openDatabase(context.getDatabasePath(dayMigration).path,null,SQLiteDatabase.OPEN_READWRITE).use { db ->
                    dropTablesAbove(db,12)
                    db.execSQL("INSERT INTO sessions(id,started_at_ms,ended_at_ms) VALUES('before-days',100000,160000)")
                    db.version=12
                }
                LocalSessionStore(context,dayMigration).use { store ->
                    check(store.readableDatabase.version==LocalSessionStore.VERSION) { "Not upgraded from 12" }
                    check(store.getSession("before-days")!!.endedAtMs==160000L) { "Upgrade lost a session" }
                    check(ResortStore(store).ways("kept").size==3) { "Upgrade lost a resort" }
                    listOf("ski_days","day_fixes","day_run_tags").forEach { table ->
                        check(store.readableDatabase.rawQuery("SELECT COUNT(*) FROM $table",null).use { it.moveToFirst() && it.getInt(0)==0 }) { "$table missing or not empty" }
                    }
                    val days=DayStore(store)
                    val tags=DayTags(snow="packed",visibility="flat light",crowd=null,drill="Edge rolls")
                    val day=SkiDay("d1","Day one",1_000_000L,null,DaySource.PHONE,"kept","",DayTags(),900_000L)
                    days.create(day)
                    check(days.day("d1")==day && days.openPhoneDay()==day) { "day round trip / open day" }
                    // More than one batch of fixes, with and without optional values, one unstorable row, and the barometer.
                    val rows=(0 until 450).map { i -> DayFix(GpsFix(1_000_000L+i*1000L,46.0+i*1e-5,7.0,if (i%3==0) null else 2000.0-i,
                        if (i%2==0) 5.5 else null,4f+(i%5),if (i%4==0) null else 3f),if (i%7==0) null else 1500.0-i*0.5) } +
                        DayFix(GpsFix(2_000_000L,Double.NaN,7.0,null,null,5f,null))
                    check(days.addFixes("d1",rows)==1) { "unstorable row not skipped" }
                    val back=days.fixes("d1")
                    check(back==rows.take(450)) { "fixes round trip" }
                    check(days.fixCount("d1")==450)
                    days.update("d1","  Day one renamed ","Windy\nafternoon",tags)
                    days.setResort("d1",null)
                    check(days.day("d1")!!.let { it.title=="Day one renamed" && it.notes=="Windy\nafternoon" && it.tags==tags && it.resortId==null }) { "day update" }
                    days.setRunTag("d1",RunTag(1_100_000L,tags,"first run"))
                    days.setRunTag("d1",RunTag(1_100_000L,DayTags(snow="icy"),"replaced"))
                    days.setRunTag("d1",RunTag(1_200_000L,DayTags(crowd="busy"),""))
                    check(days.runTags("d1")==listOf(RunTag(1_100_000L,DayTags(snow="icy"),"replaced"),RunTag(1_200_000L,DayTags(crowd="busy"),""))) { "run tags" }
                    days.setRunTag("d1",RunTag(1_200_000L,DayTags(),""))
                    check(days.runTags("d1").size==1) { "empty run tag not removed" }
                    days.finish("d1",1_450_000L)
                    check(days.openPhoneDay()==null && days.day("d1")!!.endedAtMs==1_450_000L) { "finish" }
                    // An imported day, then deleting a session or a resort never touches days.
                    val gpx=SkiDay("g1","Imported",500_000L,600_000L,DaySource.GPX,null,"",DayTags(),700_000L)
                    val unknown=DayFix(GpsFix(550_000L,46.0,7.0,null,null,RunTrack.UNKNOWN_ACCURACY_M,null))
                    check(days.importDay(gpx,rows.take(3)+unknown)==0 && days.fixes("g1").size==4) { "import" }
                    check(store.readableDatabase.rawQuery("SELECT COUNT(*) FROM day_fixes WHERE day_id='g1' AND accuracy IS NULL",null).use { it.moveToFirst() && it.getInt(0)==1 }) { "unknown accuracy not stored as null" }
                    check(days.fixes("g1").single { it.fix.timeMs==unknown.fix.timeMs }==unknown) { "unknown accuracy round trip" }
                    check(days.lastFixTime("d1")==1_449_000L && days.lastFixTime("nothing")==null) { "last fix time" }
                    // A day left open for over 12 h is closed at its last fix instead of being resumed.
                    days.create(SkiDay("old","Old",1_000L,null,DaySource.PHONE,null,"",DayTags(),1_000L))
                    days.addFixes("old",listOf(DayFix(GpsFix(5_000L,46.0,7.0,null,null,5f,null))))
                    check(days.settleOpenDays(DayTrackingConfig.STALE_OPEN_DAY_MS+10_000L)==null) { "stale day resumed" }
                    check(days.day("old")!!.endedAtMs==5_000L) { "stale day not closed at its last fix" }
                    check(days.delete("old"))
                    check(store.deleteSession("before-days"))
                    check(ResortStore(store).delete("kept"))
                    check(days.list().map { it.id }==listOf("d1","g1") && days.fixCount("d1")==450 && days.runTags("d1").size==1) { "session or resort delete touched days" }
                    // Deleting a day removes its fixes and tags and nothing else.
                    check(days.delete("d1") && !days.delete("d1"))
                    listOf("day_fixes","day_run_tags").forEach { table ->
                        check(store.readableDatabase.rawQuery("SELECT COUNT(*) FROM $table WHERE day_id='d1'",null).use { it.moveToFirst() && it.getInt(0)==0 }) { "$table rows left after day delete" }
                    }
                    check(days.fixCount("g1")==4 && days.list().single().id=="g1") { "day delete touched another day" }
                }
            } finally { context.deleteDatabase(dayMigration) }
            val path=context.getDatabasePath(name)
            path.parentFile!!.mkdirs()
            SQLiteDatabase.openOrCreateDatabase(path,null).use { db ->
                db.execSQL("CREATE TABLE sessions(id TEXT PRIMARY KEY,started_at_ms INTEGER NOT NULL,ended_at_ms INTEGER,video_uri TEXT,video_name TEXT)")
                db.execSQL("CREATE TABLE samples(id INTEGER PRIMARY KEY AUTOINCREMENT,session_id TEXT NOT NULL,side TEXT NOT NULL,received_at_ms INTEGER NOT NULL,sensor_time_ms INTEGER NOT NULL,sequence INTEGER NOT NULL,ax REAL NOT NULL,ay REAL NOT NULL,az REAL NOT NULL,gx REAL NOT NULL,gy REAL NOT NULL,gz REAL NOT NULL)")
                db.execSQL("INSERT INTO sessions VALUES('old',100000,102000,'content://test/video','existing.mp4')")
                db.execSQL("INSERT INTO samples VALUES(1,'old','L',100000,100,1,0,0,9.81,0,0,0)")
                db.version=1
            }
            LocalSessionStore(context,name).use { store ->
                val old=store.getSession("old")!!
                check(old.leftSamples==1L && old.videoName=="existing.mp4" && old.videoUri=="content://test/video") { "Migration lost existing samples/video" }
                check(old.equipment.isEmpty()) { "Migration should leave equipment unset" }
                check(old.calibrationJson=="{}")
                check(!old.testSession)
                check(!store.addMarker("old",100000,"LEFT")) { "Normal recording accepted test label" }
                store.setEquipment("old", "  Demo skis 170 cm  ")
                store.editSession("old","Morning run","Test notes",10,-20,30)
                check(store.getSession("old")!!.title=="Morning run")
                check(store.getSession("old")!!.equipment=="Demo skis 170 cm") { "Session edit lost equipment" }
                check(store.equipmentLabels()==listOf("Demo skis 170 cm"))
                val calibration=BootCalibration("L",100000.0,0,1,Vector3(0.0,0.0,1.0),Vector3(0.0,0.0,0.0))
                store.saveCalibration("old",calibration)
                check(store.calibrations(store.getSession("old")!!)==listOf(calibration))
                val capture=store.createCapture("old","L","AA:BB",100000,"pending")
                check(!store.deleteSession("old")) { "Deleted pending recovery" }
                val records=(0..2).map { FlashRecord.decode(java.nio.ByteBuffer.allocate(16).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(it*10000).array())!! }
                store.beginDownload(capture.id)
                store.saveDownload(capture.id,FlashChunk(0,records.take(2)))
                check(!store.validateDownload(capture.id,3,0,3)) { "Validated incomplete copy" }
                store.saveDownload(capture.id,FlashChunk(2,records.takeLast(1)))
                check(store.validateDownload(capture.id,3,1,2))
                check(store.loadData("old")!!.flash.size==3)
                store.beginDownload(capture.id)
                store.saveDownload(capture.id,FlashChunk(0,records.take(1)))
                check(!store.validateDownload(capture.id,3,0,3))
                check(store.captures("old").single().recordFormat==2) { "Failed replacement changed record format" }
                check(store.loadData("old")!!.flash.size==3) { "Failed replacement destroyed verified copy" }
                store.event("old","Test, \"quoted\" event")
                val output=ByteArrayOutputStream()
                SessionExport.write(store.loadData("old")!!,output)
                val entries=mutableMapOf<String,String>()
                ZipInputStream(output.toByteArray().inputStream()).use { zip ->
                    while(true) { val entry=zip.nextEntry ?: break; entries[entry.name]=zip.readBytes().toString(Charsets.UTF_8) }
                }
                check(JSONObject(entries.getValue("session.json")).getString("title")=="Morning run")
                check(JSONObject(entries.getValue("session.json")).getString("equipment")=="Demo skis 170 cm")
                check(JSONObject(entries.getValue("session.json")).getJSONObject("boot_calibration").has("L"))
                check(entries.getValue("boot-roll.csv").contains("estimated_boot_roll_deg"))
                store.editSession("old","Morning run","Test notes",11,-20,30)
                check(store.calibrations(store.getSession("old")!!).isEmpty()) { "Offset edit retained stale calibration" }
                check(entries.getValue("samples.csv").trim().lines().size==5) { "Export omitted a raw source" }
                check(entries.getValue("events.csv").contains("\"\"quoted\"\""))
                store.updateCapture(capture.id,"erased",3,1)
                check(store.deleteSession("old"))
                check(store.loadData("old")==null)
                check(store.captures().isEmpty())
                store.createSession("active",100000)
                check(!store.deleteSession("active"))
                store.finishSession("active",102000)
                check(store.deleteSession("active"))
                store.createSession("test",100000,true)
                check(store.getSession("test")!!.testSession)
                check(store.addMarker("test",100100,"LEFT"))
                check(store.addMarker("test",100200,"SYNC","L","video_review","Three, \"sharp\" movements"))
                check(!store.addMarker("test",99999,"RIGHT"))
                store.saveHealth("test",SensorHealth("L",100150,null,null,-60,100140))
                store.finishSession("test",102000)
                check(!store.addMarker("test",102001,"RIGHT"))
                val testData=store.loadData("test")!!
                check(testData.markers.size==2 && testData.health.single().battery==null)
                val exported=ByteArrayOutputStream()
                SessionExport.write(testData,exported)
                val testEntries=mutableMapOf<String,String>()
                ZipInputStream(exported.toByteArray().inputStream()).use { zip ->
                    while(true) { val entry=zip.nextEntry ?: break; testEntries[entry.name]=zip.readBytes().toString(Charsets.UTF_8) }
                }
                check(JSONObject(testEntries.getValue("session.json")).getBoolean("test_session"))
                check(testEntries.getValue("labels.csv").contains("\"\"sharp\"\""))
                check(testEntries.getValue("sensor-health.csv").contains("L,100150,,,-60,100140"))
                store.deleteMarker("test",testData.markers.first().id)
                check(store.loadData("test")!!.markers.size==1)
                check(store.deleteSession("test"))
                check(store.readableDatabase.rawQuery("SELECT COUNT(*) FROM test_markers",null).use { it.moveToFirst(); it.getInt(0) }==0)
                check(store.readableDatabase.rawQuery("SELECT COUNT(*) FROM sensor_health",null).use { it.moveToFirst(); it.getInt(0) }==0)
                val demoId=store.createDemo()
                val demo=store.loadData(demoId)!!
                check(demo.session.origin=="synthetic" && demo.live.size==6602)
                check(demo.health.isEmpty() && demo.markers.size==82)
                check(store.calibrations(demo.session).size==2)
                store.saveReviewSettings(demoId,"R",1,false)
                store.editSession(demoId,"Renamed demo","Demo notes",0,0,0)
                val updated=store.loadData(demoId)!!
                check(updated.session.origin=="synthetic") { "Renaming concealed synthetic origin" }
                check(JSONObject(updated.session.analysisJson).getJSONObject("R").getBoolean("positive_left")==false)
                val demoOutput=ByteArrayOutputStream()
                SessionExport.write(updated,demoOutput)
                val demoEntries=mutableMapOf<String,String>()
                ZipInputStream(demoOutput.toByteArray().inputStream()).use { zip ->
                    while(true) { val entry=zip.nextEntry ?: break; demoEntries[entry.name]=zip.readBytes().toString(Charsets.UTF_8) }
                }
                check(JSONObject(demoEntries.getValue("session.json")).getString("data_origin")=="synthetic")
                check(demoEntries.getValue("detected-movements.csv").trim().lines().size==81)
                check(JSONObject(demoEntries.getValue("analysis-summary.json")).getString("data_origin")=="synthetic")
                check(store.deleteSession(demoId))
            }
            context.deleteDatabase(name)
            DrillMountingRegression.run(this)
            PracticeUiRegression(this).run()
            finish(Activity.RESULT_OK,Bundle().apply { putString("stream","PASS: v1–v6 migration, demo creation, saved review settings, calibration, test labels, health, session edits, staged recovery validation, retained copy, ZIP export and protected deletion, v9->v10 run GPS track, v11->v12 resort maps, fresh-install resort round trip, v12->v13 ski days, v13->v14 practice attempts, atomic legacy import and idempotent completion\n") })
        } catch(error: Throwable) {
            targetContext.deleteDatabase(name)
            finish(Activity.RESULT_CANCELED,Bundle().apply { putString("stream","FAIL: ${android.util.Log.getStackTraceString(error)}\n") })
        }
    }
}
