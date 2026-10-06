package com.openski.android

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Training profile and drill history. Kept in preferences; raw sessions stay in [LocalSessionStore]. */
class ProgressStore(context: Context) {
    private val sensorPrefs = context.getSharedPreferences(SensorSessionService.PREFS, Context.MODE_PRIVATE)
    private val prefs = context.getSharedPreferences("openski-training", Context.MODE_PRIVATE)

    var goal: Goal?
        get() = prefs.getString("goal", null)?.let { name -> Goal.entries.firstOrNull { it.name == name } }
        set(value) = prefs.edit().putString("goal", value?.name).apply()

    var experience: Experience
        get() = prefs.getString("experience", null)?.let { name -> Experience.entries.firstOrNull { it.name == name } } ?: Experience.NEW
        set(value) = prefs.edit().putString("experience", value.name).apply()

    val onboarded get() = goal != null

    fun attempts(): List<Attempt> {
        val array = runCatching { JSONArray(prefs.getString("attempts", "[]")) }.getOrDefault(JSONArray())
        return (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let {
                Attempt(it.optString("drill"), it.optLong("time"), it.optInt("score"), it.optInt("stars"),
                    it.optInt("vert"), it.optBoolean("demo"))
            }
        }
    }

    fun progress() = Progress(attempts(), experience.startsOn)

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
    fun saveTrace(drillId: String, demo: Boolean, trace: RollTrace) {
        val step = (trace.seconds.size / 300).coerceAtLeast(1)
        val seconds = JSONArray(); val degrees = JSONArray()
        for (i in trace.seconds.indices step step) { seconds.put(trace.seconds[i].toDouble()); degrees.put(trace.degrees[i].toDouble()) }
        prefs.edit().putString("trace_${drillId}_$demo", JSONObject().put("s", seconds).put("d", degrees).toString()).apply()
    }

    fun lastTrace(drillId: String, demo: Boolean): RollTrace? = runCatching {
        val json = JSONObject(prefs.getString("trace_${drillId}_$demo", null) ?: return null)
        val s = json.getJSONArray("s"); val d = json.getJSONArray("d")
        RollTrace(FloatArray(s.length()) { s.getDouble(it).toFloat() }, FloatArray(d.length()) { d.getDouble(it).toFloat() })
    }.getOrNull()

    fun record(attempt: Attempt) {
        val array = JSONArray()
        (attempts() + attempt).forEach {
            array.put(JSONObject().put("drill", it.drillId).put("time", it.timeMs).put("score", it.score)
                .put("stars", it.stars).put("vert", it.vert).put("demo", it.demo))
        }
        prefs.edit().putString("attempts", array.toString()).apply()
    }
}
