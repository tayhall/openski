package com.openski.android

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Profile/mounting remain in preferences; attempts are imported once into SQLite. A caller that already holds
 * the database (the service) passes it as [shared] so practice reads use its connection instead of opening another.
 */
class ProgressStore(context: Context, private val shared: LocalSessionStore? = null) {
    private val appContext = context.applicationContext
    private val sensorPrefs = context.getSharedPreferences(SensorSessionService.PREFS, Context.MODE_PRIVATE)
    private val prefs = context.getSharedPreferences("openski-training", Context.MODE_PRIVATE)

    var goal: Goal?
        get() = prefs.getString("goal", null)?.let { name -> Goal.entries.firstOrNull { it.name == name } }
        set(value) = prefs.edit().putString("goal", value?.name).apply()

    var experience: Experience
        get() = prefs.getString("experience", null)?.let { name -> Experience.entries.firstOrNull { it.name == name } } ?: Experience.NEW
        set(value) = prefs.edit().putString("experience", value.name).apply()

    val onboarded get() = goal != null

    private fun <T> withPractice(block: (PracticeStore) -> T): T =
        shared?.let { block(practice(it)) } ?: LocalSessionStore(appContext).use { block(practice(it)) }

    private fun practice(database: LocalSessionStore): PracticeStore {
        (appContext as? OpenSkiApplication)?.ensurePracticeRecovered()
        val practice = PracticeStore(database)
        // The import is once per database; checking it once per process avoids a write transaction on every read.
        if (!legacyImported) synchronized(ProgressStore::class.java) {
            if (!legacyImported) { practice.importLegacy(prefs.getString("attempts", "[]") ?: "[]"); legacyImported = true }
        }
        return practice
    }

    fun ensureImported() { withPractice { Unit } }

    fun attempts(): List<Attempt> = withPractice { it.list() }
    fun attempt(id: String): Attempt? = withPractice { it.get(id) }

    fun progress(demo: Boolean = false) = Progress(attempts(), experience.startsOn, demo = demo)

    /** Which signed sensor axis points toward the toe, remembered per boot ("L" or "R"). */
    fun mounting(side: String): Mounting? {
        val value = prefs.getInt("mount_$side", -1)
        return Mounting.options.getOrNull(value)
    }
    fun setMounting(side: String, mounting: Mounting) =
        prefs.edit().putInt("mount_$side", Mounting.options.indexOf(mounting)).apply()

    /** Which sensor axes point at the board's holes edge and chip side, learned in Bench tools. */
    fun landmarks() = Landmarks(Face.all.getOrNull(prefs.getInt("landmark_holes", -1)), Face.all.getOrNull(prefs.getInt("landmark_chip", -1)))
    fun setHoles(face: Face) = prefs.edit().putInt("landmark_holes", Face.all.indexOf(face)).apply()
    fun setChip(face: Face) = prefs.edit().putInt("landmark_chip", Face.all.indexOf(face)).apply()

    /** The saved sensor address for a boot ("L" or "R"), if one is assigned. */
    fun sensorAddress(side: String): String? = sensorPrefs.getString("sensor_$side", null)

    /** Accelerometer correction learned in Bench tools, kept per sensor so it follows the board, not the boot slot. */
    fun correction(address: String): AccelCorrection? = prefs.getString("accel_$address", null)?.let(AccelCorrection::fromStorage)
    fun setCorrection(address: String, correction: AccelCorrection?) {
        prefs.edit().apply { if (correction == null) remove("accel_$address") else putString("accel_$address", correction.toStorage()) }.apply()
    }
    fun correctionForSide(side: String): AccelCorrection? = sensorAddress(side)?.let(::correction)

    /** Last roll trace per drill, thinned for storage, drawn as the ghost line next time. */
    fun saveTrace(drillId: String, demo: Boolean, trace: RollTrace, attemptId: String? = null) {
        val step = (trace.seconds.size / 300).coerceAtLeast(1)
        val seconds = JSONArray(); val degrees = JSONArray()
        for (i in trace.seconds.indices step step) { seconds.put(trace.seconds[i].toDouble()); degrees.put(trace.degrees[i].toDouble()) }
        prefs.edit().putString("trace_${drillId}_$demo", JSONObject().put("s", seconds).put("d", degrees).toString())
            .putString("trace_id_${drillId}_$demo", attemptId).apply()
    }

    fun lastTraceAttemptId(drillId: String, demo: Boolean): String? = prefs.getString("trace_id_${drillId}_$demo", null)

    fun lastTrace(drillId: String, demo: Boolean): RollTrace? = runCatching {
        val json = JSONObject(prefs.getString("trace_${drillId}_$demo", null) ?: return null)
        val s = json.getJSONArray("s"); val d = json.getJSONArray("d")
        RollTrace(FloatArray(s.length()) { s.getDouble(it).toFloat() }, FloatArray(d.length()) { d.getDouble(it).toFloat() })
    }.getOrNull()

    fun record(attempt: Attempt): Attempt = withPractice { it.record(attempt, calculateReward = true) }

    private companion object { @Volatile var legacyImported = false }
}
