package com.openski.android

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Resort maps: search OpenStreetMap ski areas by name, or download the area around the phone (its position coarsened
 * to about 1 km first), list the cached maps with their age, refresh or delete them, and choose one for a day. Every
 * network call runs on a background thread, one at a time. Downloads are refused while a sensor recording or run is
 * in progress ([ResortRepository]'s download guard). Snow look; experimental.
 */
class ResortPickerActivity : Activity() {
    private val io = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var frame: FrameLayout
    private var dayId: String? = null
    private var dayResortId: String? = null
    private var cached: List<StoredResort> = emptyList()
    private var results: List<ResortCandidate> = emptyList()
    private var busy: String? = null
    private var message: String? = null
    private var query = ""
    private var destroyed = false
    private var service: SensorSessionService? = null
    private var bound = false
    /** Mirrors the sensor service's recording state; read by the download guard on the io thread. */
    @Volatile private var sensorRecording = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as? SensorSessionService.LocalBinder)?.service
            sensorRecording = service?.recordingActive() == true
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null; sensorRecording = false }
    }
    private val poll = object : Runnable {
        override fun run() { sensorRecording = service?.recordingActive() == true; handler.postDelayed(this, 1000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Snow.configureWindow(this)
        dayId = intent.getStringExtra(EXTRA_DAY)
        query = savedInstanceState?.getString("query") ?: ""
        frame = FrameLayout(this).apply { setBackgroundColor(Snow.SNOW) }
        SkiUi.applyInsets(frame)
        setContentView(frame)
    }

    override fun onSaveInstanceState(state: Bundle) { state.putString("query", query); super.onSaveInstanceState(state) }

    override fun onStart() {
        super.onStart()
        // Flags 0: binds only if the sensor service is already running; when it is not, nothing is recording.
        if (!bound) bound = bindService(Intent(this, SensorSessionService::class.java), connection, 0)
        handler.post(poll)
        reload()
    }

    override fun onStop() {
        handler.removeCallbacks(poll)
        if (bound) { unbindService(connection); bound = false }
        service = null
        super.onStop()
    }

    // shutdown, not shutdownNow: a download that has started finishes and saves even if the screen is closed.
    override fun onDestroy() { destroyed = true; io.shutdown(); super.onDestroy() }

    /** Runs [block] with a repository on the io thread. The guard also checks the database for an open recording. */
    private fun <T> withRepository(store: LocalSessionStore, block: (ResortRepository) -> T): T =
        block(ResortRepository(HttpResortFetcher(), ResortStore(store), downloadBlocker = {
            if (sensorRecording || store.latestOpenSessionId() != null)
                "a boot recording or run is in progress; download maps before or after it" else null
        }))

    private fun reload() {
        io.execute {
            val (list, resortId) = try {
                LocalSessionStore(this).use { s -> ResortStore(s).list() to dayId?.let { DayStore(s).day(it)?.resortId } }
            } catch (e: Exception) { runOnUiThread { message = "Could not read cached maps: ${e.message}" }; emptyList<StoredResort>() to null }
            runOnUiThread { if (!destroyed) { cached = list; dayResortId = resortId; render() } }
        }
    }

    /** Runs one network job at a time, showing [label] with a spinner meanwhile. */
    private fun job(label: String, work: (ResortRepository) -> String) {
        if (busy != null) return
        busy = label; message = null; render()
        io.execute {
            val text = try { LocalSessionStore(this).use { withRepository(it, work) } }
            catch (e: Exception) { "Failed: ${e.message ?: e.javaClass.simpleName}" }
            runOnUiThread {
                if (destroyed) return@runOnUiThread
                busy = null; message = text; render(); reload()
            }
        }
    }

    private fun search() {
        val name = query.trim()
        if (name.isEmpty()) { message = "Type part of the resort's name first."; render(); return }
        job("Searching for \"$name\"…") { repo ->
            val r = repo.search(name)
            val found = (r as? SearchResult.Found)?.candidates.orEmpty()
            runOnUiThread { results = found }
            SkiDayText.search(r)
        }
    }

    private fun download(request: ResortRequest) = job("Downloading ${request.name}… this can take a minute.") { repo ->
        val result = repo.download(request)
        if (result is DownloadResult.Saved) dayId?.let { id -> attach(id, result.resort.id) }
        SkiDayText.download(result) + if (result is DownloadResult.Saved && dayId != null) " Chosen for this day." else ""
    }

    /** Downloads the area around the phone's last known position, coarsened before anything is built from it. */
    @SuppressLint("MissingPermission")
    private fun aroundMe() {
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQUEST_LOCATION)
            return
        }
        val manager = getSystemService(LocationManager::class.java)
        val last = try {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
                .mapNotNull { p -> runCatching { manager.getLastKnownLocation(p) }.getOrNull() }.maxByOrNull { it.time }
        } catch (_: Exception) { null }
        if (last == null) {
            message = "No recent position on this phone. Start the ski day (or open a map app) for a moment, then try again."
            render(); return
        }
        val centre = ResortRepository.coarsen(LatLon(last.latitude, last.longitude))
        val label = String.format(Locale.US, "%.2f, %.2f", centre.lat, centre.lon)
        download(ResortRequest.nearPosition("near-$label".replace(" ", ""), "Area near $label", centre))
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_LOCATION) return
        if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) aroundMe()
        else { message = "Without location, search by name instead."; render() }
    }

    private fun attach(id: String, resortId: String?) = LocalSessionStore(this).use { DayStore(it).setResort(id, resortId) }

    private fun choose(r: StoredResort?) {
        val id = dayId ?: return
        io.execute {
            val text = try { attach(id, r?.id); if (r == null) "No resort map for this day." else "${r.name} chosen for this day." }
            catch (e: Exception) { "Could not save: ${e.message}" }
            runOnUiThread { message = text; reload() }
        }
    }

    private fun confirmDelete(r: StoredResort) {
        AlertDialog.Builder(this).setTitle("Delete ${r.name}?")
            .setMessage("The cached map is removed from this phone. Days that used it keep their fixes and lose their names until you download it again.")
            .setPositiveButton("Delete") { _, _ ->
                io.execute {
                    val text = try { LocalSessionStore(this).use { ResortStore(it).delete(r.id) }; "${r.name} deleted." }
                    catch (e: Exception) { "Could not delete: ${e.message}" }
                    runOnUiThread { message = text; reload() }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun t(text: String, type: Snow.Type = Snow.Type.BODY, tint: Int = Snow.INK) = Snow.text(this, text, type, tint)
    private fun LinearLayout.add(view: View, top: Int = 0, bottom: Int = 0) =
        addView(view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Snow.dp(this@ResortPickerActivity, top); bottomMargin = Snow.dp(this@ResortPickerActivity, bottom) })

    private fun render() {
        if (destroyed) return
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val inset = Snow.dp(this@ResortPickerActivity, 20)
            setPadding(inset, inset, inset, Snow.dp(this@ResortPickerActivity, 28))
        }
        column.add(t("Resort map", Snow.Type.HERO))
        column.add(t("Pistes and lifts are downloaded once from OpenStreetMap and kept on this phone, so naming runs never needs a signal. Download over Wi-Fi before you ski.",
            Snow.Type.BODY, Snow.INK_SOFT), top = 4, bottom = 16)

        val searchCard = Snow.card(this, 18)
        val field = EditText(this).apply {
            setText(query); hint = "Resort name"; inputType = InputType.TYPE_CLASS_TEXT; imeOptions = EditorInfo.IME_ACTION_SEARCH
            minHeight = Snow.dp(this@ResortPickerActivity, 48)
            setOnEditorActionListener { v, action, _ -> if (action == EditorInfo.IME_ACTION_SEARCH) { query = v.text.toString(); search(); true } else false }
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) { query = s?.toString() ?: "" }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })
        }
        searchCard.add(field)
        searchCard.add(Snow.button(this, "Search") { search() }.apply { isEnabled = busy == null; alpha = if (busy == null) 1f else .4f }, top = 10)
        searchCard.add(Snow.button(this, "Download the area around me", Snow.ButtonKind.SECONDARY) { aroundMe() }
            .apply { isEnabled = busy == null; alpha = if (busy == null) 1f else .4f }, top = 8)
        searchCard.add(t("A search sends the typed name; the area download sends a box around your position rounded to about 1 km. Nothing else leaves the phone.",
            Snow.Type.CAPTION, Snow.INK_SOFT), top = 8)
        column.add(searchCard, bottom = 12)

        busy?.let { label ->
            val row = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
            row.addView(ProgressBar(this).apply { isIndeterminate = true }, LinearLayout.LayoutParams(Snow.dp(this, 32), Snow.dp(this, 32)))
            row.addView(t(label, Snow.Type.STRONG), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = Snow.dp(this@ResortPickerActivity, 10) })
            column.add(row, bottom = 12)
        }
        message?.let { column.add(t(it, Snow.Type.STRONG), bottom = 12) }

        if (results.isNotEmpty()) {
            column.add(t("Found", Snow.Type.DISPLAY), bottom = 8)
            val list = Snow.card(this, 16)
            results.forEachIndexed { i, c ->
                list.add(t(c.name, Snow.Type.TITLE), top = if (i == 0) 0 else 12)
                list.add(t(String.format(Locale.US, "near %.3f, %.3f", c.centre.lat, c.centre.lon), Snow.Type.BODY, Snow.INK_SOFT))
                list.add(Snow.button(this, "Download", Snow.ButtonKind.QUIET) { download(ResortRequest.forCandidate(c)) }
                    .apply { isEnabled = busy == null })
            }
            column.add(list, bottom = 16)
        }

        column.add(t("On this phone", Snow.Type.DISPLAY), bottom = 8)
        if (cached.isEmpty()) column.add(t("No maps downloaded yet.", Snow.Type.BODY, Snow.INK_SOFT), bottom = 12)
        val now = System.currentTimeMillis()
        cached.forEach { r ->
            val card = Snow.card(this, 16)
            val chosen = dayId != null && r.id == dayResortId
            card.add(t(r.name + if (chosen) " · chosen for this day" else "", Snow.Type.TITLE))
            card.add(t("${r.wayCount} pistes and lifts · fetched ${SkiDayText.age(r.ageMs(now))}" +
                (if (r.isStale(now)) " · stale, refresh before relying on it" else "") +
                (if (r.truncated) " · area cut to ${OverpassQuery.MAX_RADIUS_M / 1000} km" else ""), Snow.Type.BODY, Snow.INK_SOFT), top = 2)
            if (dayId != null && !chosen) card.add(Snow.button(this, "Use for this day", Snow.ButtonKind.SECONDARY) { choose(r) }, top = 10)
            card.add(Snow.button(this, "Refresh", Snow.ButtonKind.QUIET) {
                download(ResortRequest(r.id, r.name, r.centre, r.radiusM))
            }.apply { isEnabled = busy == null })
            card.add(Snow.button(this, "Delete", Snow.ButtonKind.QUIET) { confirmDelete(r) })
            column.add(card, bottom = 10)
        }
        if (dayId != null && dayResortId != null) column.add(Snow.button(this, "Use no map for this day", Snow.ButtonKind.QUIET) { choose(null) }, bottom = 8)
        column.add(t("Map data ${ResortRepository.ATTRIBUTION}. Lines and names may be missing or wrong; runs that do not match a mapped piste stay unnamed.",
            Snow.Type.CAPTION, Snow.INK_SOFT), top = 8)

        frame.removeAllViews()
        frame.addView(ScrollView(this).apply { isFillViewport = true; addView(column) })
    }

    companion object {
        const val EXTRA_DAY = "day"
        private const val REQUEST_LOCATION = 71
    }
}
