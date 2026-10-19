package com.openski.android

import android.content.ContentValues
import android.database.Cursor

/** Durable attempt history, independent of raw-session deletion. Completion IDs are immutable. */
class PracticeStore(private val store: LocalSessionStore) {
    fun importLegacy(json: String) = synchronized(store) {
        val db = store.writableDatabase
        db.beginTransaction()
        try {
            val imported = db.rawQuery("SELECT 1 FROM practice_imports WHERE id='preferences-v1'", null)
                .use { it.moveToFirst() }
            if (!imported) {
                LegacyPractice.decode(json).forEach { attempt -> db.insertOrThrow("practice_attempts", null, values(attempt)) }
                db.execSQL("INSERT INTO practice_imports(id) VALUES('preferences-v1')")
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun list(): List<Attempt> = synchronized(store) {
        store.readableDatabase.rawQuery("SELECT $COLUMNS FROM practice_attempts ORDER BY time_ms, rowid", null)
            .use { c -> buildList { while (c.moveToNext()) add(read(c)) } }
    }

    fun get(id: String): Attempt? = synchronized(store) { getLocked(id) }

    /** The most recent attempt, the same one [list] ends with. */
    fun latest(): Attempt? = synchronized(store) {
        store.readableDatabase.rawQuery("SELECT $COLUMNS FROM practice_attempts ORDER BY time_ms DESC, rowid DESC LIMIT 1", null)
            .use { if (it.moveToFirst()) read(it) else null }
    }

    /** Insert once. Retrying completion returns the committed result, including its original reward. */
    fun record(attempt: Attempt, calculateReward: Boolean = false): Attempt = synchronized(store) {
        val db = store.writableDatabase
        db.beginTransaction()
        try {
            val existing = getLocked(attempt.id)
            require(existing == null || (existing.drillId == attempt.drillId && existing.demo == attempt.demo &&
                existing.sessionId == attempt.sessionId && existing.target == attempt.target && existing.scoringVersion == attempt.scoringVersion)) {
                "Attempt identity belongs to a different practice"
            }
            val saved = if (existing != null && (existing.status != PracticeStatus.ACTIVE || attempt.status == PracticeStatus.ACTIVE)) existing else run {
                val reward = if (!attempt.eligible) 0 else if (calculateReward) {
                    val best = db.rawQuery("SELECT MAX(score) FROM practice_attempts WHERE drill_id=? AND demo=? AND status='COMPLETED' AND (score_eligible IS NULL OR score_eligible=1)",
                        arrayOf(attempt.drillId, if (attempt.demo) "1" else "0")).use { c ->
                        if (c.moveToFirst() && !c.isNull(0)) c.getInt(0) else null
                    }
                    if (best == null) attempt.score * 2 else maxOf(attempt.score / 10, (attempt.score - best) * 2)
                } else attempt.vert
                attempt.copy(vert = reward, stars = if (attempt.eligible) attempt.stars else 0).also {
                    if (existing == null) db.insertOrThrow("practice_attempts", null, values(it))
                    else db.update("practice_attempts", values(it), "id=? AND status='ACTIVE'", arrayOf(it.id))
                }
            }
            db.setTransactionSuccessful()
            saved
        } finally { db.endTransaction() }
    }

    /** Called at process/service creation, never when a screen reattaches. Raw closure is atomic with interruption. */
    fun interruptActive(closeOpenSessionsAt: Long? = null): List<Attempt> = synchronized(store) {
        val db = store.writableDatabase
        db.beginTransaction()
        try {
            val unfinished = db.rawQuery("SELECT $COLUMNS FROM practice_attempts WHERE status='ACTIVE' ORDER BY time_ms, rowid", null)
                .use { c -> buildList { while (c.moveToNext()) add(read(c)) } }
            db.execSQL("UPDATE practice_attempts SET status='INTERRUPTED', stars=0, points=0 WHERE status='ACTIVE'")
            if (closeOpenSessionsAt != null) unfinished.mapNotNull { it.sessionId }.distinct().forEach { id ->
                db.execSQL("UPDATE sessions SET ended_at_ms=? WHERE id=? AND ended_at_ms IS NULL", arrayOf(closeOpenSessionsAt, id))
            }
            db.setTransactionSuccessful()
            unfinished
        } finally { db.endTransaction() }
    }

    private fun getLocked(id: String): Attempt? = store.readableDatabase.rawQuery(
        "SELECT $COLUMNS FROM practice_attempts WHERE id=?", arrayOf(id)).use { if (it.moveToFirst()) read(it) else null }

    private fun values(a: Attempt) = ContentValues().apply {
        put("id", a.id); put("drill_id", a.drillId); put("time_ms", a.timeMs); put("score", a.score)
        put("stars", a.stars); put("points", a.vert); put("demo", if (a.demo) 1 else 0)
        put("session_id", a.sessionId); put("status", a.status.name); put("scoring_version", a.scoringVersion)
        put("target_movements", a.target?.movements); put("target_beat", a.target?.beatSeconds)
        put("target_depth", a.target?.depthDegrees)
        put("review_json", a.review?.json()?.toString())
        put("score_eligible", a.review?.scoreEligible?.let { if (it) 1 else 0 })
    }

    private fun read(c: Cursor) = Attempt(c.getString(1), c.getLong(2), c.getInt(3), c.getInt(4), c.getInt(5), c.getInt(6) != 0,
        id = c.getString(0), sessionId = if (c.isNull(7)) null else c.getString(7),
        status = PracticeStatus.valueOf(c.getString(8)),
        target = if (c.isNull(10)) null else PracticeTarget(c.getInt(10), c.getDouble(11), c.getDouble(12)),
        scoringVersion = c.getInt(9), review = if (c.isNull(13)) null else PracticeReview.decode(c.getString(13)))

    fun reflection(id: String): PracticeReflection = synchronized(store) {
        store.readableDatabase.rawQuery("SELECT intention,reflection FROM practice_reflections WHERE attempt_id=?", arrayOf(id)).use {
            if (it.moveToFirst()) PracticeReflection(it.getString(0), it.getString(1)) else PracticeReflection()
        }
    }

    fun saveReflection(id: String, value: PracticeReflection) = synchronized(store) {
        require(getLocked(id) != null) { "Practice no longer exists" }
        require(value.intention.length <= 2000 && value.reflection.length <= 2000) { "Keep each note within 2000 characters" }
        store.writableDatabase.insertWithOnConflict("practice_reflections", null, ContentValues().apply {
            put("attempt_id", id); put("intention", value.intention); put("reflection", value.reflection)
        }, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun forSession(id: String): List<Attempt> = synchronized(store) {
        store.readableDatabase.rawQuery("SELECT $COLUMNS FROM practice_attempts WHERE session_id=? ORDER BY time_ms", arrayOf(id))
            .use { c -> buildList { while (c.moveToNext()) add(read(c)) } }
    }

    companion object {
        private const val COLUMNS = "id,drill_id,time_ms,score,stars,points,demo,session_id,status,scoring_version,target_movements,target_beat,target_depth,review_json"
    }
}
