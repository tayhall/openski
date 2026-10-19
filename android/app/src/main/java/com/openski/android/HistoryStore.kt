package com.openski.android

/** Lightweight bounded reads: no raw samples, day fixes or reconstructed analysis. */
data class HistoryCursor(val time: Long, val id: String)
data class HistoryRow(val id: String, val time: Long, val title: String, val detail: String)
enum class HistoryKind { RUNS, DRILLS, DAYS }
class HistoryStore(private val store: LocalSessionStore) {
    fun page(kind: HistoryKind, demo: Boolean, before: HistoryCursor? = null, limit: Int = 50): List<HistoryRow> = synchronized(store) {
        require(limit in 1..100)
        val table: String; val time: String; val filter: String; val title: String; val detail: String
        when (kind) {
            HistoryKind.RUNS -> { table = "sessions"; time = "started_at_ms"; filter = "kind='run' AND origin='${if (demo) "synthetic" else "sensor"}'"; title = "title"; detail = "CASE WHEN ended_at_ms IS NULL THEN 'Recording active' ELSE 'Saved run · check recovery in result' END" }
            HistoryKind.DRILLS -> { table = "practice_attempts"; time = "time_ms"; filter = "demo=${if (demo) 1 else 0}"; title = "drill_id"; detail = "status" }
            HistoryKind.DAYS -> { table = "ski_days"; time = "started_at_ms"; filter = "1=1"; title = "title"; detail = "source" }
        }
        val bounds = if (before == null) "" else " AND ($time < ? OR ($time = ? AND id < ?))"
        val args = before?.let { arrayOf(it.time.toString(), it.time.toString(), it.id) }
        store.readableDatabase.rawQuery("SELECT id,$time,$title,$detail FROM $table WHERE $filter$bounds ORDER BY $time DESC,id DESC LIMIT $limit", args).use { c ->
            buildList { while (c.moveToNext()) add(HistoryRow(c.getString(0), c.getLong(1), c.getString(2), c.getString(3))) }
        }
    }
}
