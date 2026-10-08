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
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val name="openski-test-${UUID.randomUUID()}.db"
        try {
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
                check(!store.validateDownload(capture.id,3,0)) { "Validated incomplete copy" }
                store.saveDownload(capture.id,FlashChunk(2,records.takeLast(1)))
                check(store.validateDownload(capture.id,3,1))
                check(store.loadData("old")!!.flash.size==3)
                store.beginDownload(capture.id)
                store.saveDownload(capture.id,FlashChunk(0,records.take(1)))
                check(!store.validateDownload(capture.id,3,0))
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
            finish(Activity.RESULT_OK,Bundle().apply { putString("stream","PASS: v1–v6 migration, demo creation, saved review settings, calibration, test labels, health, session edits, staged recovery validation, retained copy, ZIP export and protected deletion\n") })
        } catch(error: Throwable) {
            targetContext.deleteDatabase(name)
            finish(Activity.RESULT_CANCELED,Bundle().apply { putString("stream","FAIL: ${android.util.Log.getStackTraceString(error)}\n") })
        }
    }
}
