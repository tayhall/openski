package com.openski.android

import android.content.ContentValues
import android.database.Cursor

/**
 * Resort maps in the session database (tables resorts, resort_ways and resort_stations, database version 12).
 * Geometry is stored as a [Polyline] at 1e-6 degrees.
 *
 * Locking: every call locks on [store], like the store's own @Synchronized methods, so resort and session writes never
 * interleave. [save] prepares all rows (including the polyline encoding) before taking the lock and then holds the
 * lock and SQLite's write lock only for its one transaction. Without WAL, SQLite would make other writers wait for that
 * transaction anyway, so dropping the monitor would not shorten the wait; instead the repository's download guard keeps
 * downloads out of recordings (see ResortRepository).
 */
class ResortStore(private val store: LocalSessionStore) : ResortCache {
    /** Replaces [resort] with its ways and stations in one transaction; on any failure the old copy stays. */
    override fun save(resort: StoredResort, data: ResortData) {
        val resortRow = ContentValues().apply {
            put("id", resort.id); put("name", resort.name); put("centre_lat", resort.centre.lat); put("centre_lon", resort.centre.lon)
            put("radius_m", resort.radiusM); put("fetched_at_ms", resort.fetchedAtMs); put("way_count", data.ways.size)
            put("source_note", resort.sourceNote); put("truncated", if (resort.truncated) 1 else 0)
        }
        val wayRows = data.ways.map { info ->
            val w = info.way
            ContentValues().apply {
                put("resort_id", resort.id); put("osm_id", w.osmId); put("part", info.part); put("kind", w.kind.name); put("name", w.name)
                put("ref", w.ref); put("difficulty", info.difficultyClass); put("difficulty_raw", w.difficulty); put("aerialway", w.aerialway)
                put("operator", info.operator); put("osm_version", info.version); put("area", if (info.area) 1 else 0)
                put("relation_id", info.relationId); put("geometry", Polyline.encode(w.points))
            }
        }
        val stationRows = data.stations.map { s ->
            ContentValues().apply {
                put("resort_id", resort.id); put("osm_type", s.osmType); put("osm_id", s.osmId); put("name", s.name)
                put("lat", s.point.lat); put("lon", s.point.lon); put("operator", s.operator); put("osm_version", s.version)
            }
        }
        synchronized(store) {
            val db = store.writableDatabase
            db.beginTransaction()
            try {
                removeRows(resort.id)
                db.insertOrThrow("resorts", null, resortRow)
                wayRows.forEach { db.insertOrThrow("resort_ways", null, it) }
                stationRows.forEach { db.insertOrThrow("resort_stations", null, it) }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
    }

    override fun list(): List<StoredResort> = synchronized(store) {
        store.readableDatabase.rawQuery("SELECT $RESORT_COLUMNS FROM resorts ORDER BY name COLLATE NOCASE, id", null)
            .use { c -> buildList { while (c.moveToNext()) add(resort(c)) } }
    }

    override fun resort(id: String): StoredResort? = synchronized(store) {
        store.readableDatabase.rawQuery("SELECT $RESORT_COLUMNS FROM resorts WHERE id=?", arrayOf(id))
            .use { c -> if (c.moveToFirst()) resort(c) else null }
    }

    /** The ways of [id] in OSM id and part order. A row whose geometry no longer decodes is left out. */
    override fun ways(id: String): List<ResortWayInfo> = synchronized(store) {
        store.readableDatabase.rawQuery("""SELECT osm_id,kind,name,ref,difficulty,difficulty_raw,aerialway,operator,osm_version,area,
            relation_id,geometry,part FROM resort_ways WHERE resort_id=? ORDER BY osm_id,part""", arrayOf(id)).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val kind = WayKind.entries.firstOrNull { it.name == c.getString(1) } ?: continue
                    val points = Polyline.decode(c.getString(11))?.takeIf { it.size >= 2 } ?: continue
                    // Column difficulty holds the normalised class, difficulty_raw the OSM value that ResortWay carries.
                    add(ResortWayInfo(ResortWay(c.getLong(0), kind, c.text(2), c.text(3), c.text(5), c.text(6), points),
                        c.text(4), c.text(7), c.intOrNull(8), c.getInt(9) != 0, c.longOrNull(10), c.getInt(12)))
                }
            }
        }
    }

    override fun stations(id: String): List<ResortStation> = synchronized(store) {
        store.readableDatabase.rawQuery("""SELECT osm_id,name,lat,lon,operator,osm_version,osm_type FROM resort_stations WHERE resort_id=?
            ORDER BY osm_type,osm_id""", arrayOf(id)).use { c -> buildList {
                while (c.moveToNext()) add(ResortStation(c.getLong(0), c.text(1), LatLon(c.getDouble(2), c.getDouble(3)), c.text(4),
                    c.intOrNull(5), c.getString(6)))
            } }
    }

    /** Deletes [id] and its ways and stations together. Sessions are never touched. */
    override fun delete(id: String): Boolean = synchronized(store) {
        val db = store.writableDatabase
        db.beginTransaction()
        try {
            val removed = removeRows(id)
            db.setTransactionSuccessful()
            removed
        } finally { db.endTransaction() }
    }

    private fun removeRows(id: String): Boolean {
        val db = store.writableDatabase
        db.delete("resort_ways", "resort_id=?", arrayOf(id))
        db.delete("resort_stations", "resort_id=?", arrayOf(id))
        return db.delete("resorts", "id=?", arrayOf(id)) > 0
    }

    private fun resort(c: Cursor) = StoredResort(c.getString(0), c.getString(1), LatLon(c.getDouble(2), c.getDouble(3)),
        c.getInt(4), c.getLong(5), c.getInt(6), c.getString(7), c.getInt(8) != 0)

    private fun Cursor.text(i: Int): String? = if (isNull(i)) null else getString(i)
    private fun Cursor.intOrNull(i: Int): Int? = if (isNull(i)) null else getInt(i)
    private fun Cursor.longOrNull(i: Int): Long? = if (isNull(i)) null else getLong(i)

    private companion object {
        const val RESORT_COLUMNS = "id,name,centre_lat,centre_lon,radius_m,fetched_at_ms,way_count,source_note,truncated"
    }
}
