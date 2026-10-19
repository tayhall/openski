package com.openski.android

import android.content.ContentValues
import android.database.Cursor

/**
 * Ski days in the session database (tables ski_days, day_fixes and day_run_tags, database version 13). Fixes are
 * stored exactly as the phone (or the GPX file) gave them; the split, names and totals are always derived again.
 *
 * Locking follows [ResortStore]: every call locks on [store], like the store's own @Synchronized methods, so day and
 * session writes never interleave on this helper. Fixes are written in transactions of at most
 * [DayRules.FIX_BATCH_ROWS] rows, each under its own short lock, so a long import or a day's backlog never holds the
 * session store (or SQLite's write lock) for long. Deleting a day removes its fixes and tags; sessions and resorts are
 * independent: deleting either never touches days (a day whose resort was deleted simply has no map).
 */
class DayStore(private val store: LocalSessionStore) {
    fun create(day: SkiDay) = synchronized(store) {
        store.writableDatabase.insertOrThrow("ski_days", null, ContentValues().apply {
            put("id", day.id); put("title", day.title); put("started_at_ms", day.startedAtMs)
            if (day.endedAtMs == null) putNull("ended_at_ms") else put("ended_at_ms", day.endedAtMs)
            put("source", day.source); if (day.resortId == null) putNull("resort_id") else put("resort_id", day.resortId)
            put("notes", day.notes); put("tags_json", day.tags.toJson()); put("created_at_ms", day.createdAtMs)
        })
        Unit
    }

    fun finish(id: String, endedAtMs: Long) = synchronized(store) {
        store.writableDatabase.update("ski_days", ContentValues().apply { put("ended_at_ms", endedAtMs) }, "id=?", arrayOf(id))
        Unit
    }

    fun day(id: String): SkiDay? = synchronized(store) {
        store.readableDatabase.rawQuery("SELECT $DAY_COLUMNS FROM ski_days WHERE id=?", arrayOf(id)).use { c -> if (c.moveToFirst()) day(c) else null }
    }

    /** Newest first. */
    fun list(): List<SkiDay> = synchronized(store) {
        store.readableDatabase.rawQuery("SELECT $DAY_COLUMNS FROM ski_days ORDER BY started_at_ms DESC, id", null)
            .use { c -> buildList { while (c.moveToNext()) add(day(c)) } }
    }

    /** The open phone day to resume after a restart, if any. */
    fun openPhoneDay(): SkiDay? = DayRules.resumable(list())

    fun update(id: String, title: String, notes: String, tags: DayTags) = synchronized(store) {
        store.writableDatabase.update("ski_days", ContentValues().apply {
            put("title", DayRules.cleanTitle(title)); put("notes", DayRules.cleanNote(notes)); put("tags_json", tags.toJson())
        }, "id=?", arrayOf(id))
        Unit
    }

    fun setResort(id: String, resortId: String?) = synchronized(store) {
        store.writableDatabase.update("ski_days", ContentValues().apply {
            if (resortId == null) putNull("resort_id") else put("resort_id", resortId)
        }, "id=?", arrayOf(id))
        Unit
    }

    /**
     * Appends fixes in transactions of [DayRules.FIX_BATCH_ROWS]. Rows without a finite position are skipped (and
     * counted in the result); everything else is stored as given. Throws on a database error: rows of earlier
     * transactions stay written, so the caller may retry only what it still holds.
     */
    fun addFixes(dayId: String, rows: List<DayFix>): Int {
        var skipped = 0
        for (chunk in DayRules.chunks(rows)) {
            val values = chunk.mapNotNull { r -> if (DayRules.storable(r.fix)) values(dayId, r) else { skipped++; null } }
            if (values.isEmpty()) continue
            synchronized(store) {
                val db = store.writableDatabase
                db.beginTransaction()
                try {
                    values.forEach { db.insertOrThrow("day_fixes", null, it) }
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
            }
        }
        return skipped
    }

    /** All fixes of [dayId] in time order, untouched. */
    fun fixes(dayId: String): List<DayFix> = synchronized(store) {
        store.readableDatabase.rawQuery("""SELECT time_ms,lat,lon,alt,speed,accuracy,vertical_accuracy,baro_alt FROM day_fixes
            WHERE day_id=? ORDER BY time_ms,rowid""", arrayOf(dayId)).use { c ->
            buildList { while (c.moveToNext()) add(DayFix(GpsFix(c.getLong(0), c.getDouble(1), c.getDouble(2), c.doubleOrNull(3),
                c.doubleOrNull(4), if (c.isNull(5)) RunTrack.UNKNOWN_ACCURACY_M else c.getFloat(5),
                if (c.isNull(6)) null else c.getFloat(6)), c.doubleOrNull(7))) }
        }
    }

    /** Time of the day's latest fix (MAX(time_ms)), without reading the fixes; null when it has none. */
    fun lastFixTime(dayId: String): Long? = synchronized(store) {
        store.readableDatabase.rawQuery("SELECT MAX(time_ms) FROM day_fixes WHERE day_id=?", arrayOf(dayId))
            .use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null }
    }

    /**
     * Applies [DayRules.resumePlan]: open phone days that went stale (or are not the latest) are closed at their last
     * fix, and the open day to resume, if any, is returned.
     */
    fun settleOpenDays(nowMs: Long): SkiDay? {
        val plan = DayRules.resumePlan(list(), { lastFixTime(it.id) }, nowMs)
        plan.close.forEach { (id, end) -> finish(id, end) }
        return plan.resume
    }

    fun fixCount(dayId: String): Int = synchronized(store) {
        store.readableDatabase.rawQuery("SELECT COUNT(*) FROM day_fixes WHERE day_id=?", arrayOf(dayId)).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    fun runTags(dayId: String): List<RunTag> = synchronized(store) {
        store.readableDatabase.rawQuery("SELECT segment_start_ms,tags_json,note FROM day_run_tags WHERE day_id=? ORDER BY segment_start_ms",
            arrayOf(dayId)).use { c -> buildList { while (c.moveToNext()) add(RunTag(c.getLong(0), DayTags.fromJson(c.getString(1)), c.getString(2))) } }
    }

    /** Replaces the tag keyed by [tag].segmentStartMs; an empty tag removes it. */
    fun setRunTag(dayId: String, tag: RunTag) = synchronized(store) {
        val db = store.writableDatabase
        if (tag.isEmpty) db.delete("day_run_tags", "day_id=? AND segment_start_ms=?", arrayOf(dayId, tag.segmentStartMs.toString()))
        else db.insertWithOnConflict("day_run_tags", null, ContentValues().apply {
            put("day_id", dayId); put("segment_start_ms", tag.segmentStartMs); put("tags_json", tag.tags.toJson())
            put("note", DayRules.cleanNote(tag.note))
        }, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
        Unit
    }

    /** Deletes the day with its fixes and run tags in one transaction. */
    fun delete(id: String): Boolean = synchronized(store) {
        val db = store.writableDatabase
        db.beginTransaction()
        try {
            db.delete("day_fixes", "day_id=?", arrayOf(id))
            db.delete("day_run_tags", "day_id=?", arrayOf(id))
            val removed = db.delete("ski_days", "id=?", arrayOf(id)) > 0
            db.setTransactionSuccessful()
            removed
        } finally { db.endTransaction() }
    }

    /** Creates [day] and writes [rows] in small transactions; if any write fails the half-imported day is removed. */
    fun importDay(day: SkiDay, rows: List<DayFix>): Int {
        create(day)
        try { return addFixes(day.id, rows) }
        catch (e: Exception) { runCatching { delete(day.id) }; throw e }
    }

    private fun values(dayId: String, r: DayFix) = ContentValues().apply {
        val f = r.fix
        put("day_id", dayId); put("time_ms", f.timeMs); put("lat", f.lat); put("lon", f.lon)
        putFinite("alt", f.altM); putFinite("speed", f.speedMps)
        // Unknown accuracy (not reported, so RunTrack's sentinel, or not finite) is stored as null, never as a number.
        if (!f.accuracyM.isFinite() || f.accuracyM == RunTrack.UNKNOWN_ACCURACY_M) putNull("accuracy") else put("accuracy", f.accuracyM)
        putFinite("vertical_accuracy", f.verticalAccuracyM?.toDouble())
        putFinite("baro_alt", r.baroAltM)
    }

    private fun ContentValues.putFinite(key: String, v: Double?) { if (v == null || !v.isFinite()) putNull(key) else put(key, v) }
    private fun Cursor.doubleOrNull(i: Int): Double? = if (isNull(i)) null else getDouble(i)

    private fun day(c: Cursor) = SkiDay(c.getString(0), c.getString(1), c.getLong(2), if (c.isNull(3)) null else c.getLong(3),
        c.getString(4).takeIf { it in DaySource.ALL } ?: DaySource.PHONE, if (c.isNull(5)) null else c.getString(5), c.getString(6),
        DayTags.fromJson(c.getString(7)), c.getLong(8))

    private companion object {
        const val DAY_COLUMNS = "id,title,started_at_ms,ended_at_ms,source,resort_id,notes,tags_json,created_at_ms"
    }
}
