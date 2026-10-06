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
                store.editSession("old","Morning run","Test notes",10,-20,30)
                check(store.getSession("old")!!.title=="Morning run")
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
            }
            context.deleteDatabase(name)
            finish(Activity.RESULT_OK,Bundle().apply { putString("stream","PASS: v1 migration, session edits, staged recovery validation, retained copy, ZIP export and protected deletion\n") })
        } catch(error: Throwable) {
            targetContext.deleteDatabase(name)
            finish(Activity.RESULT_CANCELED,Bundle().apply { putString("stream","FAIL: ${android.util.Log.getStackTraceString(error)}\n") })
        }
    }
}
