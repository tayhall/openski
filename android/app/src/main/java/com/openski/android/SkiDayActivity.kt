package com.openski.android

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import java.text.DateFormat
import java.util.Date
import java.util.UUID
import java.util.concurrent.Executors

/**
 * The ski day screen: start and stop whole-day tracking, the day's runs, lifts and rests with names from the resort
 * map, day totals, tags and notes for the day and each run, GPX import and export. It only shows what [DayStore] holds
 * and what [DayTrackingService] reports; every number comes from [SkiDayReport]. Snow look. EXPERIMENTAL: GPS
 * numbers, not technique; boot numbers are dry-ski boot roll descriptors.
 */
class SkiDayActivity : Activity() {
    private val io = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var frame: FrameLayout
    private var dayId: String? = null
    private var loaded: Loaded? = null
    private var loading = false
    private var message: String? = null
    private var statusView: TextView? = null
    private var statusSignature = ""
    private var destroyed = false

    /** Everything the screen shows for one day, read on the io thread. */
    private class Loaded(val day: SkiDay?, val days: List<SkiDay>, val report: SkiDayReport?, val fixCount: Int,
                         val runTags: List<RunTag>, val resort: StoredResort?, val openDay: SkiDay?)

    private val refresh = object : Runnable {
        override fun run() {
            val s = DayTrackingService.status
            // Follow a day the service has just started.
            if (s.tracking && s.dayId != null && s.dayId != dayId) { dayId = s.dayId; load() }
            statusView?.text = trackingLine(s)
            val signature = listOf(s.tracking, s.dayId, s.error, s.gpsOn).joinToString("|")
            if (signature != statusSignature) { statusSignature = signature; render() }
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Snow.configureWindow(this)
        dayId = savedInstanceState?.getString("day") ?: intent.getStringExtra(EXTRA_DAY)
        frame = FrameLayout(this).apply { setBackgroundColor(Snow.SNOW) }
        SkiUi.applyInsets(frame)
        setContentView(frame)
    }

    override fun onSaveInstanceState(state: Bundle) { dayId?.let { state.putString("day", it) }; super.onSaveInstanceState(state) }
    override fun onResume() { super.onResume(); load(); handler.post(refresh) }
    override fun onPause() { handler.removeCallbacks(refresh); super.onPause() }
    override fun onDestroy() { destroyed = true; io.shutdown(); super.onDestroy() }

    /** Reads the day and builds its report off the main thread. */
    private fun load() {
        loading = true
        render()
        val wanted = dayId
        io.execute {
            val result = try {
                LocalSessionStore(this).use { store ->
                    val days = DayStore(store)
                    val all = days.list()
                    val open = DayRules.resumable(all)
                    val day = wanted?.let { id -> all.firstOrNull { it.id == id } } ?: open ?: all.firstOrNull()
                    if (day == null) Loaded(null, all, null, 0, emptyList(), null, open) else {
                        val fixes = days.fixes(day.id)
                        val resorts = ResortStore(store)
                        val resort = day.resortId?.let(resorts::resort)
                        val map = resort?.let { r -> resorts.ways(r.id).map { it.way }.takeIf { it.isNotEmpty() }?.let(::ResortMap) }
                        val end = day.endedAtMs ?: System.currentTimeMillis()
                        val boots = DayBootSource.forDay(store, day.startedAtMs, end, ProgressStore(this)::correctionForSide)
                        Loaded(day, all, SkiDayReport.build(fixes, map, boots), fixes.size, days.runTags(day.id), resort, open)
                    }
                }
            } catch (e: Exception) { runOnUiThread { message = "Could not load the day: ${e.message}" }; null }
            runOnUiThread {
                if (destroyed) return@runOnUiThread
                loading = false
                if (result != null) { loaded = result; dayId = result.day?.id }
                render()
            }
        }
    }

    private fun t(text: String, type: Snow.Type = Snow.Type.BODY, tint: Int = Snow.INK) = Snow.text(this, text, type, tint)
    private fun LinearLayout.add(view: View, top: Int = 0, bottom: Int = 0) =
        addView(view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Snow.dp(this@SkiDayActivity, top); bottomMargin = Snow.dp(this@SkiDayActivity, bottom) })

    private fun trackingLine(s: DayTrackingStatus): String = when {
        s.error != null -> s.error
        !s.tracking -> "Not tracking"
        !s.gpsOn -> "Waiting for location…"
        else -> {
            val ago = s.lastFixElapsedMs?.let { (SystemClock.elapsedRealtime() - it) / 1000 }
            val fix = if (ago == null) "no fix yet" else "last fix ${ago}s ago" + (s.lastAccuracyM?.let { " (±${it.toInt()} m)" } ?: "")
            val pace = if (s.intervalMs > DayTrackingConfig.MOVING_INTERVAL_MS) "standing still, a fix every ${s.intervalMs / 1000} s" else "a fix every second"
            val baro = when (s.barometer) { true -> "barometer on"; false -> "no barometer"; null -> "" }
            listOf("Tracking · ${s.fixes} fixes", fix, pace, baro).filter { it.isNotEmpty() }.joinToString(" · ")
        }
    }

    private fun render() {
        if (destroyed) return
        statusView = null
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val inset = Snow.dp(this@SkiDayActivity, 20)
            setPadding(inset, inset, inset, Snow.dp(this@SkiDayActivity, 28))
        }
        val data = loaded
        val status = DayTrackingService.status
        column.add(t("Ski day", Snow.Type.HERO))
        column.add(t("Runs, lifts and rests from the phone's GPS (and barometer where the phone has one). Experimental: thresholds untested on a real day.",
            Snow.Type.BODY, Snow.INK_SOFT), top = 4, bottom = 16)

        // Tracking ------------------------------------------------------------------------------------------
        val track = Snow.card(this, 18, radius = 28)
        val day = data?.day
        val trackingThis = status.tracking && day != null && status.dayId == day.id
        val open = data?.openDay
        track.add(t(when {
            trackingThis -> "Tracking this day"
            status.tracking -> "Tracking another day"
            open != null -> "A day is still open"
            else -> "Track a whole day"
        }, Snow.Type.TITLE))
        statusView = t(trackingLine(status), Snow.Type.BODY, Snow.INK_SOFT).also { track.add(it, top = 4) }
        if (status.error != null && !hasLocation()) {
            track.add(Snow.button(this, "Allow location in Settings", Snow.ButtonKind.QUIET) {
                startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }, top = 6)
        }
        when {
            status.tracking -> track.add(Snow.button(this, "Stop day") { stopDay() }, top = 14)
            open != null -> {
                track.add(t("\"${open.title}\" was not closed (the app or phone stopped while tracking).", Snow.Type.BODY), top = 8)
                track.add(Snow.button(this, "Resume tracking") { withLocation { DayTrackingService.resume(this); later() } }, top = 12)
                track.add(Snow.button(this, "End that day", Snow.ButtonKind.SECONDARY) { closeOpenDay(open) }, top = 8)
            }
            else -> track.add(Snow.button(this, "Start day") { withLocation { DayTrackingService.start(this); later() } }, top = 14)
        }
        track.add(t("Tracking keeps running with the screen off and does not need the boots. Battery use over a whole day has not been measured.",
            Snow.Type.CAPTION, Snow.INK_SOFT), top = 10)
        column.add(track, bottom = 16)

        message?.let { column.add(t(it, Snow.Type.STRONG), bottom = 12) }
        if (loading && data == null) column.add(t("Loading…", Snow.Type.BODY, Snow.INK_SOFT), bottom = 12)

        if (day != null) dayBody(column, data, day, trackingThis)
        else if (!loading) column.add(t("No days yet. Start one, or import a GPX file of your own.", Snow.Type.BODY, Snow.INK_SOFT), bottom = 12)

        column.add(Snow.button(this, "Import a GPX file", Snow.ButtonKind.SECONDARY) { pickGpx() }, top = 8, bottom = 8)
        data?.days?.filter { it.id != day?.id }?.takeIf { it.isNotEmpty() }?.let { others ->
            column.add(t("Other days", Snow.Type.DISPLAY), top = 16, bottom = 8)
            val list = Snow.card(this, 16)
            others.take(30).forEachIndexed { i, d ->
                val row = t("${d.title}\n${dateText(d.startedAtMs)} · ${if (d.source == DaySource.GPX) "imported GPX" else "phone"}" +
                    if (d.open) " · open" else "", Snow.Type.BODY).apply {
                    isClickable = true; isFocusable = true; minHeight = Snow.dp(this@SkiDayActivity, 48)
                    setOnClickListener { dayId = d.id; loaded = null; load() }
                }
                list.add(row, top = if (i == 0) 0 else 10)
            }
            column.add(list)
        }
        frame.removeAllViews()
        frame.addView(ScrollView(this).apply { isFillViewport = true; addView(column) })
    }

    private fun dayBody(column: LinearLayout, data: Loaded, day: SkiDay, trackingThis: Boolean) {
        val report = data.report
        column.add(t(day.title, Snow.Type.DISPLAY))
        column.add(t("${dateText(day.startedAtMs)} · ${if (day.source == DaySource.GPX) "imported GPX" else "phone tracking"} · ${data.fixCount} fixes" +
            if (day.open) " · open" else "", Snow.Type.BODY, Snow.INK_SOFT), top = 2, bottom = 12)

        // Resort ---------------------------------------------------------------------------------------------
        val resortCard = Snow.card(this, 18)
        val r = data.resort
        resortCard.add(t(when {
            r != null -> "Resort: ${r.name}"
            day.resortId != null -> "Resort map deleted"
            else -> "No resort map"
        }, Snow.Type.TITLE))
        resortCard.add(t(when {
            r != null -> "${r.wayCount} pistes and lifts · fetched ${SkiDayText.age(r.ageMs(System.currentTimeMillis()))}" +
                if (r.isStale(System.currentTimeMillis())) " · old, consider refreshing" else ""
            else -> "Without a map, runs and lifts are told apart by altitude only and have no names."
        }, Snow.Type.BODY, Snow.INK_SOFT), top = 4)
        resortCard.add(Snow.button(this, if (r == null) "Choose a resort map" else "Change resort map", Snow.ButtonKind.SECONDARY) {
            startActivity(Intent(this, ResortPickerActivity::class.java).putExtra(ResortPickerActivity.EXTRA_DAY, day.id))
        }, top = 12)
        column.add(resortCard, bottom = 16)

        if (report == null) return
        // Totals ---------------------------------------------------------------------------------------------
        val totals = Snow.card(this, 18)
        val tt = report.totals
        totals.add(t("${tt.runs} runs · ${SkiDayText.vertical(tt.descendedM)} down", Snow.Type.DISPLAY))
        val longest = tt.longestRunIndex?.let { report.segments[it] }
        listOf(
            "Lifts" to "${tt.lifts}" + (tt.climbedM?.let { " · ${SkiDayText.vertical(it)} up" } ?: ""),
            "Longest run" to (longest?.let { "${it.title} · ${SkiDayText.distance(it.segment.distanceM)}" } ?: "–"),
            "Skiing" to SkiDayText.duration(tt.skiingS),
            "On lifts" to SkiDayText.duration(tt.liftS),
            "Resting" to SkiDayText.duration(tt.restS),
            "Distance skied" to SkiDayText.distance(tt.distanceSkiedM),
            "Top speed" to SkiDayText.speed(tt.topSpeedMps),
        ).forEach { (label, value) -> totals.add(row(label, value), top = 6) }
        if (tt.descendedM == null) totals.add(t("No usable altitude, so no vertical.", Snow.Type.BODY), top = 8)
        totals.add(t(report.split.notes.joinToString("; ").ifEmpty { "Phone GPS numbers, not a measure of technique." },
            Snow.Type.CAPTION, Snow.INK_SOFT), top = 10)
        column.add(totals, bottom = 16)

        // Tags -----------------------------------------------------------------------------------------------
        val tagCard = Snow.card(this, 18)
        tagCard.add(t("Day tags and notes", Snow.Type.TITLE))
        tagCard.add(t(day.tags.entries().joinToString(" · ") { "${it.first}: ${it.second}" }.ifEmpty { "No tags yet." } +
            if (day.notes.isNotBlank()) "\n${day.notes}" else "", Snow.Type.BODY, Snow.INK_SOFT), top = 4)
        tagCard.add(Snow.button(this, "Edit day tags", Snow.ButtonKind.SECONDARY) { editDay(day) }, top = 12)
        column.add(tagCard, bottom = 16)

        // Segments -------------------------------------------------------------------------------------------
        column.add(t("The day", Snow.Type.DISPLAY), bottom = 8)
        if (report.segments.isEmpty()) column.add(t(if (data.fixCount < 2) "No fixes yet." else
            "Not enough usable fixes to split the day yet.", Snow.Type.BODY, Snow.INK_SOFT), bottom = 12)
        val list = Snow.card(this, 16)
        report.segments.forEachIndexed { i, seg ->
            val tag = if (seg.kind == SegmentKind.DESCENT) DayRules.tagFor(seg.segment.startMs, seg.segment.endMs, data.runTags) else null
            list.add(segmentRow(seg, tag, day), top = if (i == 0) 0 else 14)
        }
        if (report.segments.isNotEmpty()) column.add(list, bottom = 8)
        if (report.mapUsed) column.add(t("Piste and lift names and lines: ${report.attribution}", Snow.Type.CAPTION, Snow.INK_SOFT), bottom = 8)
        column.add(t("Tap a run to tag it. Boot numbers appear on runs that overlap a boot recording; they describe dry-ski boot roll, not ski turns.",
            Snow.Type.CAPTION, Snow.INK_SOFT), bottom = 16)

        // Export and housekeeping ----------------------------------------------------------------------------
        column.add(Snow.button(this, "Export ski-day.gpx (raw fixes)", Snow.ButtonKind.SECONDARY) { export(day, GPX) }, bottom = 8)
        column.add(Snow.button(this, "Export ski-day.json (runs, tags, numbers)", Snow.ButtonKind.SECONDARY) { export(day, JSON) }, bottom = 8)
        column.add(Snow.button(this, "Refresh", Snow.ButtonKind.QUIET) { load() }, bottom = 4)
        if (!trackingThis) column.add(Snow.button(this, "Delete this day", Snow.ButtonKind.QUIET) { confirmDelete(day) }, bottom = 4)
    }

    private fun row(label: String, value: String) = LinearLayout(this).apply {
        addView(t(label, Snow.Type.BODY, Snow.INK_SOFT), LinearLayout.LayoutParams(0, -2, 1f))
        addView(t(value, Snow.Type.STRONG))
    }

    private fun segmentRow(seg: ReportSegment, tag: RunTag?, day: SkiDay): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val head = LinearLayout(this@SkiDayActivity).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(t(seg.title, Snow.Type.TITLE, if (seg.kind == SegmentKind.REST) Snow.INK_SOFT else Snow.INK), LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(t(timeText(seg.segment.startMs), Snow.Type.CAPTION, Snow.INK_SOFT))
        addView(head)
        seg.subtitle?.let { addView(t(it, Snow.Type.STRONG)) }
        if (seg.kind != SegmentKind.REST && seg.match != null && seg.subtitle == null) addView(t("Not matched to a mapped ${if (seg.kind == SegmentKind.LIFT) "lift" else "piste"}", Snow.Type.BODY, Snow.INK_SOFT))
        addView(t(SkiDayText.segmentLine(seg), Snow.Type.BODY))
        SkiDayText.bootLine(seg.boot)?.let { addView(t(it, Snow.Type.BODY, Snow.INK_SOFT)) }
        tag?.let { tg -> (tg.tags.entries().map { "${it.first}: ${it.second}" } + listOfNotNull(tg.note.takeIf { it.isNotBlank() }))
            .joinToString(" · ").takeIf { it.isNotEmpty() }?.let { addView(t(it, Snow.Type.CAPTION, Snow.INK_SOFT)) } }
        if (seg.kind == SegmentKind.DESCENT) {
            isClickable = true; isFocusable = true
            minimumHeight = Snow.dp(this@SkiDayActivity, 48)
            contentDescription = "${seg.title}. ${seg.subtitle ?: ""}. ${SkiDayText.segmentLine(seg)}. Double tap to tag this run."
            setOnClickListener { editRun(day, seg, tag) }
        }
    }

    // Tags ---------------------------------------------------------------------------------------------------

    private fun spinner(options: List<String>, current: String?): Spinner {
        val values = listOf("—") + options + listOfNotNull(current?.takeIf { it !in options })
        return Spinner(this).apply {
            adapter = ArrayAdapter(this@SkiDayActivity, android.R.layout.simple_spinner_dropdown_item, values)
            setSelection(current?.let { values.indexOf(it) }?.takeIf { it >= 0 } ?: 0)
            minimumHeight = Snow.dp(this@SkiDayActivity, 48)
        }
    }

    private fun Spinner.value(): String? = (selectedItem as? String)?.takeIf { it != "—" }

    /** A form with the four condition tags and a note; [title] adds a title field (day only). */
    private fun tagForm(tags: DayTags, note: String, title: String?, onSave: (DayTags, String, String?) -> Unit, heading: String) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = Snow.dp(this@SkiDayActivity, 20)
            setPadding(pad, pad, pad, 0)
        }
        val titleField = title?.let { EditText(this).apply { setText(it); hint = "Title"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES } }
        titleField?.let { box.addView(it) }
        fun labelled(label: String, view: View) { box.addView(t(label, Snow.Type.CAPTION, Snow.INK_SOFT)); box.addView(view) }
        val snow = spinner(DayTags.SNOW, tags.snow).also { labelled("Snow", it) }
        val visibility = spinner(DayTags.VISIBILITY, tags.visibility).also { labelled("Visibility", it) }
        val crowd = spinner(DayTags.CROWD, tags.crowd).also { labelled("Crowd", it) }
        val drill = spinner(Programme.drills.map { it.title }, tags.drill).also { labelled("Drill practised", it) }
        val noteField = EditText(this).apply {
            setText(note); hint = "Note"; minLines = 2
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        box.addView(noteField)
        AlertDialog.Builder(this).setTitle(heading).setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Save") { _, _ ->
                onSave(DayTags(DayTags.clean(snow.value()), DayTags.clean(visibility.value()), DayTags.clean(crowd.value()), DayTags.clean(drill.value())),
                    noteField.text.toString(), titleField?.text?.toString())
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun editDay(day: SkiDay) = tagForm(day.tags, day.notes, day.title, { tags, note, title ->
        io.execute {
            try { LocalSessionStore(this).use { DayStore(it).update(day.id, title?.takeIf { s -> s.isNotBlank() } ?: day.title, note, tags) } }
            catch (e: Exception) { runOnUiThread { message = "Could not save: ${e.message}" } }
            runOnUiThread { load() }
        }
    }, "Day tags")

    private fun editRun(day: SkiDay, seg: ReportSegment, existing: RunTag?) = tagForm(existing?.tags ?: DayTags(), existing?.note ?: "", null, { tags, note, _ ->
        // Keep the original key so a tag stays put even if the split moves the start a little.
        val key = existing?.segmentStartMs ?: seg.segment.startMs
        io.execute {
            try { LocalSessionStore(this).use { DayStore(it).setRunTag(day.id, RunTag(key, tags, note)) } }
            catch (e: Exception) { runOnUiThread { message = "Could not save: ${e.message}" } }
            runOnUiThread { load() }
        }
    }, "Tag ${seg.title}")

    // Tracking ------------------------------------------------------------------------------------------------

    private fun hasLocation() = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    private var afterPermission: (() -> Unit)? = null

    /** Runs [action] once precise location is allowed; asks first (with notifications on Android 13+), and says so when refused. */
    private fun withLocation(action: () -> Unit) {
        val wanted = buildList {
            if (!hasLocation()) { add(Manifest.permission.ACCESS_FINE_LOCATION); add(Manifest.permission.ACCESS_COARSE_LOCATION) }
            if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (wanted.isEmpty()) { action(); return }
        afterPermission = action
        requestPermissions(wanted.toTypedArray(), REQUEST_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_PERMISSIONS) return
        val action = afterPermission
        afterPermission = null
        if (hasLocation()) {
            message = if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                "Notifications are off, so the tracking notification and its Stop button will not show. Tracking still runs." else null
            action?.invoke()
        } else message = "Precise location is needed to track a day. Nothing was started. Allow location for OpenSki in Settings."
        render()
    }

    private fun stopDay() {
        DayTrackingService.stop(this)
        later()
    }

    /** Closes a day left open after the app or phone stopped, at its last fix. */
    private fun closeOpenDay(open: SkiDay) {
        io.execute {
            try { LocalSessionStore(this).use { s -> DayStore(s).let { d -> d.finish(open.id, d.fixes(open.id).lastOrNull()?.fix?.timeMs ?: open.startedAtMs) } } }
            catch (e: Exception) { runOnUiThread { message = "Could not close the day: ${e.message}" } }
            runOnUiThread { load() }
        }
    }

    /** Reloads once the service has had a moment to start or close the day. */
    private fun later() = handler.postDelayed({ if (!destroyed) load() }, 1500)

    private fun confirmDelete(day: SkiDay) {
        AlertDialog.Builder(this).setTitle("Delete \"${day.title}\"?")
            .setMessage("Its fixes and tags are removed from this phone. Boot sessions and resort maps are not touched.")
            .setPositiveButton("Delete") { _, _ ->
                io.execute {
                    try { LocalSessionStore(this).use { DayStore(it).delete(day.id) } } catch (e: Exception) { runOnUiThread { message = "Could not delete: ${e.message}" } }
                    runOnUiThread { dayId = null; loaded = null; load() }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    // Import and export ----------------------------------------------------------------------------------------

    private var pendingExport: Pair<String, String>? = null   // day id, kind

    private fun export(day: SkiDay, kind: String) {
        pendingExport = day.id to kind
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = if (kind == GPX) "application/gpx+xml" else "application/json"
            putExtra(Intent.EXTRA_TITLE, if (kind == GPX) "ski-day.gpx" else "ski-day.json")
        }, REQUEST_EXPORT)
    }

    private fun pickGpx() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/gpx+xml", "application/gpx", "application/xml", "text/xml", "application/octet-stream"))
        }, REQUEST_IMPORT)
    }

    @Deprecated("Picker compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, intent: Intent?) {
        super.onActivityResult(requestCode, resultCode, intent)
        val uri = intent?.data
        if (resultCode != RESULT_OK || uri == null) { pendingExport = null; return }
        when (requestCode) {
            REQUEST_IMPORT -> importGpx(uri)
            REQUEST_EXPORT -> pendingExport?.let { (id, kind) -> writeExport(id, kind, uri) }
        }
        pendingExport = null
    }

    private fun importGpx(uri: Uri) {
        message = "Importing…"; render()
        io.execute {
            val text = try {
                val read = contentResolver.openInputStream(uri)?.use { GpxCodec.read(it) } ?: error("The file could not be opened")
                if (read.fixes.isEmpty()) error("The file has no track points with a time")
                val day = DayRules.gpxDay(UUID.randomUUID().toString(), read, System.currentTimeMillis())
                val skipped = LocalSessionStore(this).use { DayStore(it).importDay(day, read.fixes.map { f -> DayFix(f) }) }
                runOnUiThread { dayId = day.id }
                "Imported ${read.fixes.size - skipped} points" + (if (read.skippedPoints + skipped > 0) ", ${read.skippedPoints + skipped} unusable points skipped" else "") +
                    (if (read.assumedUtcPoints > 0) ", ${read.assumedUtcPoints} times without a zone taken as UTC" else "") + "."
            } catch (e: GpxFormatException) { "Could not import: ${e.message}" }
            catch (e: Exception) { "Could not import: ${e.message ?: e.javaClass.simpleName}" }
            runOnUiThread { message = text; loaded = null; load() }
        }
    }

    private fun writeExport(id: String, kind: String, uri: Uri) {
        message = "Exporting…"; render()
        io.execute {
            val text = try {
                LocalSessionStore(this).use { store ->
                    val days = DayStore(store)
                    val day = days.day(id) ?: error("Day no longer stored")
                    val fixes = days.fixes(id)
                    val body = if (kind == GPX) GpxCodec.write(fixes.map { it.fix }, day.title) else {
                        val resorts = ResortStore(store)
                        val resort = day.resortId?.let(resorts::resort)
                        val map = resort?.let { r -> resorts.ways(r.id).map { it.way }.takeIf { it.isNotEmpty() }?.let(::ResortMap) }
                        val boots = DayBootSource.forDay(store, day.startedAtMs, day.endedAtMs ?: System.currentTimeMillis(), ProgressStore(this)::correctionForSide)
                        val report = SkiDayReport.build(fixes, map, boots)
                        SkiDayExport.dayJson(day, report, days.runTags(id), resort?.name, SkiDayReport.baroSeries(fixes)).toString(2)
                    }
                    contentResolver.openOutputStream(uri, "w")?.use { it.write(body.toByteArray(Charsets.UTF_8)) } ?: error("Export destination unavailable")
                }
                if (kind == GPX) "Exported ski-day.gpx" else "Exported ski-day.json"
            } catch (e: Exception) { "Export failed: ${e.message ?: e.javaClass.simpleName}" }
            runOnUiThread { message = text; render() }
        }
    }

    private fun dateText(ms: Long) = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ms))
    private fun timeText(ms: Long) = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms))

    companion object {
        const val EXTRA_DAY = "day"
        private const val GPX = "gpx"
        private const val JSON = "json"
        private const val REQUEST_PERMISSIONS = 61
        private const val REQUEST_IMPORT = 62
        private const val REQUEST_EXPORT = 63
    }
}
