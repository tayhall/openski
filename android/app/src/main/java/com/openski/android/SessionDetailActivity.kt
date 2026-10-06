package com.openski.android

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.*
import java.util.Locale
import java.util.concurrent.Executors

class SessionDetailActivity : Activity() {
    private val io = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var id: String
    private var data: SessionData? = null
    private var analysis: AnalysisResult? = null
    private var video: VideoView? = null
    private var chart: MotionChart? = null
    private var seek: SeekBar? = null
    private var timeLabel: TextView? = null
    private var elapsedMs = 0.0
    private var windowMs = 30_000.0
    private var videoReady = false
    private var pendingExport: SessionData? = null
    private var pendingVideo = false

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        id = intent.getStringExtra("session_id") ?: run { finish(); return }
        elapsedMs = state?.getDouble("elapsed_ms") ?: 0.0
        pendingVideo = state?.getBoolean("pending_video") ?: false
        load()
        handler.post(ticker)
    }
    private fun load() {
        setContentView(TextView(this).apply { text = "Loading session…"; setPadding(24,48,24,24) })
        io.execute {
            try {
                val loaded = LocalSessionStore(this).use { it.loadData(id) }
                val result = loaded?.let(SessionAnalysis::build)
                runOnUiThread { if (!isDestroyed && !isFinishing) {
                    if (loaded == null) { finish(); return@runOnUiThread }
                    data = loaded; analysis = result; render(loaded, result!!)
                } }
            } catch (error: Exception) { runOnUiThread { message("Could not load session: ${error.message}") } }
        }
    }
    private fun dp(n: Int) = (n*resources.displayMetrics.density).toInt()
    private fun text(s: String, size: Float = 14f) = TextView(this).apply {
        text = s; textSize = size; setTextColor(Color.WHITE); setPadding(0,dp(7),0,dp(7))
    }
    private fun button(s: String, action: () -> Unit) = Button(this).apply {
        text = s; isAllCaps = false; setOnClickListener { action() }
    }
    private fun message(s: String) { if (!isDestroyed) Toast.makeText(this,s,Toast.LENGTH_LONG).show() }
    private fun number(n: Double) = String.format(Locale.US,"%.1f",n)
    private fun render(loaded: SessionData, result: AnalysisResult) {
        video?.stopPlayback(); videoReady = false
        val session = loaded.session
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18),dp(22),dp(18),dp(30))
            setBackgroundColor(Color.rgb(11,20,31)) }
        page.addView(button("Back to dashboard") { finish() })
        page.addView(text(session.title.ifBlank { "Ski session" },24f))
        val duration = ((session.endedAtMs ?: System.currentTimeMillis())-session.startedAtMs).coerceAtLeast(0)
        page.addView(text("${java.text.SimpleDateFormat("EEE, d MMM yyyy · HH:mm",Locale.getDefault()).format(java.util.Date(session.startedAtMs))}\nDuration ${number(duration/1000.0)}s"))
        if (session.notes.isNotBlank()) page.addView(text(session.notes))
        page.addView(button("Edit name, notes and alignment") { edit(loaded) })
        page.addView(text("RECORDING QUALITY",18f))
        result.quality.forEach { q -> page.addView(text("${if(q.side=="L") "Left" else "Right"}: ${q.liveSamples} live · ${q.flashSamples} validated flash samples\nEstimated coverage ${number(q.coverage*100)}% · ${q.gaps} gaps over 0.1s · longest ${number(q.longestGapMs/1000)}s\nSensor-reported dropped samples: ${q.dropped}")) }
        loaded.captures.forEach { c -> page.addView(text("${c.side} backup: ${c.state} · ${c.expected} records" + if(c.error.isBlank()) "" else "\n${c.error}")) }
        page.addView(text(result.alignment))
        if (loaded.events.isNotEmpty()) page.addView(button("Recording events (${loaded.events.size})") {
            AlertDialog.Builder(this).setTitle("Recording events").setMessage(loaded.events.takeLast(100).joinToString("\n") {
                "${number((it.first-session.startedAtMs)/1000.0)}s · ${it.second}"
            }).setPositiveButton("Close",null).show()
        })
        page.addView(text("MOTION & VIDEO",18f))
        page.addView(text("Left = green · right = blue. Tap the graph or scrub the timeline.\nVideo time = session time + video offset; a negative value means the video starts later."))
        page.addView(spinner(listOf("Acceleration magnitude (m/s²)","Gyro X (rad/s)","Gyro Y (rad/s)","Gyro Z (rad/s)","Gyro magnitude (rad/s)")) { position -> chart?.channel=position; chart?.invalidate() })
        page.addView(spinner(listOf("30-second window","10-second window","Whole session")) { position ->
            windowMs = when(position) { 1->10_000.0; 2->duration.coerceAtLeast(1).toDouble(); else->30_000.0 }; updateChart()
        })
        chart = MotionChart(this).apply {
            points=result.timeline; originMs=session.startedAtMs.toDouble()
            onSeek = { ms -> moveTo(ms-session.startedAtMs,true) }
        }
        page.addView(chart,LinearLayout.LayoutParams(-1,dp(230)))
        timeLabel=text("0.0s")
        page.addView(timeLabel)
        seek=SeekBar(this).apply {
            max=10_000
            setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) { if(fromUser) moveTo(duration*progress/10_000.0,true) }
                override fun onStartTrackingTouch(bar: SeekBar?) { video?.pause() }
                override fun onStopTrackingTouch(bar: SeekBar?) {}
            })
        }
        page.addView(seek)
        page.addView(button(if(session.videoUri==null) "Attach video" else "Replace video") { pickVideo() })
        video=null
        if(session.videoUri!=null) {
            page.addView(text(session.videoName ?: "Attached video"))
            video=VideoView(this).apply {
                setVideoURI(Uri.parse(session.videoUri))
                setOnPreparedListener { videoReady=true; moveTo(elapsedMs,true) }
                setOnErrorListener { _,_,_ -> videoReady=false; message("Video unavailable. Reattach it if it was moved or removed."); true }
            }
            page.addView(video,LinearLayout.LayoutParams(-1,dp(240)))
            page.addView(button("Play / pause video") {
                if(!videoReady) return@button
                if(video?.isPlaying==true) video?.pause() else {
                    val position=elapsedMs+session.videoOffsetMs
                    if(position<0 || position >= (video?.duration ?: 0)) { message("No video at this session time; adjust the offset or scrub to its interval"); return@button }
                    video?.start()
                }
            })
        }
        page.addView(text("EXPERIMENTAL TURN CANDIDATES",18f))
        page.addView(text("Gyro lobes can help find turns for video review. These are unvalidated candidates, not skiing scores. Choose the axis perpendicular to the turn plane for your sensor mounting."))
        val candidatesLabel=text("")
        var axis=2
        var positiveLeft=true
        fun candidates() {
            val found=listOf("L","R").flatMap { SessionAnalysis.candidates(result.timeline,it,axis) }.sortedBy { it.startMs }
            candidatesLabel.text="${found.size} candidates · 0.35 rad/s threshold · 0.5–20s lobes\n" + found.take(40).joinToString("\n") { c ->
                val direction=if(c.positive==positiveLeft) "left" else "right"
                "${c.side}: ${number((c.startMs-session.startedAtMs)/1000)}–${number((c.endMs-session.startedAtMs)/1000)}s · candidate $direction · ${number(c.peakRadps.toDouble())} rad/s"
            } + if(found.size>40) "\nShowing the first 40." else ""
        }
        val axisSpinner=spinner(listOf("Gyro X","Gyro Y","Gyro Z")) { axis=it; candidates() }
        axisSpinner.setSelection(2)
        page.addView(axisSpinner)
        page.addView(CheckBox(this).apply { text="Positive rotation means left (requires matching mounting on both boots)"; setTextColor(Color.WHITE)
            isChecked=true; setOnCheckedChangeListener { _,checked -> positiveLeft=checked; candidates() } })
        page.addView(candidatesLabel); candidates()
        page.addView(button("Export session (.zip with CSV and metadata)") { export(loaded) })
        page.addView(button("Delete session") {
            AlertDialog.Builder(this).setTitle("Delete this session?")
                .setMessage("This removes its samples and notes from this phone. The original video remains in place.")
                .setNegativeButton("Cancel",null).setPositiveButton("Delete") { _,_ ->
                    io.execute { val deleted=LocalSessionStore(this).use { it.deleteSession(id) }
                        runOnUiThread { if(deleted) finish() else message("Stop recording and finish sensor recovery before deleting this session") } }
                }.show()
        })
        setContentView(ScrollView(this).apply { addView(page) })
        moveTo(elapsedMs,false)
    }
    private fun spinner(items: List<String>, selected: (Int)->Unit) = Spinner(this).apply {
        adapter=ArrayAdapter(this@SessionDetailActivity,android.R.layout.simple_spinner_dropdown_item,items)
        onItemSelectedListener=object: AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, row: Long) { (view as? TextView)?.setTextColor(Color.WHITE); selected(position) }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }
    private fun updateChart() {
        val session=data?.session ?: return
        val duration=((session.endedAtMs ?: System.currentTimeMillis())-session.startedAtMs).coerceAtLeast(1).toDouble()
        val width=windowMs.coerceAtMost(duration)
        val start=(elapsedMs-width/2).coerceIn(0.0,(duration-width).coerceAtLeast(0.0))
        chart?.startMs=session.startedAtMs+start
        chart?.endMs=session.startedAtMs+start+width
        chart?.selectedMs=session.startedAtMs+elapsedMs
        chart?.invalidate()
    }
    private fun moveTo(ms: Double, seekVideo: Boolean) {
        val session=data?.session ?: return
        val duration=((session.endedAtMs ?: System.currentTimeMillis())-session.startedAtMs).coerceAtLeast(1).toDouble()
        elapsedMs=ms.coerceIn(0.0,duration)
        seek?.progress=(elapsedMs/duration*10_000).toInt()
        timeLabel?.text="Session ${number(elapsedMs/1000)}s · video ${number((elapsedMs+session.videoOffsetMs)/1000)}s"
        if(seekVideo && videoReady) { video?.pause(); video?.seekTo((elapsedMs+session.videoOffsetMs).toInt().coerceIn(0,(video?.duration ?: 0).coerceAtLeast(0))) }
        updateChart()
    }
    private val ticker=object: Runnable {
        override fun run() { if(videoReady && video?.isPlaying==true) moveTo((video?.currentPosition ?: 0)-(data?.session?.videoOffsetMs ?: 0).toDouble(),false)
            handler.postDelayed(this,200) }
    }
    private fun edit(loaded: SessionData) {
        val session=loaded.session
        val form=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(20),dp(8),dp(20),dp(8)) }
        fun field(hintText: String, value: String)=EditText(this).apply { hint=hintText; setText(value); form.addView(this) }
        val title=field("Session name",session.title)
        val notes=field("Notes",session.notes)
        val left=field("Left time offset (seconds)",(session.leftOffsetMs/1000.0).toString())
        val right=field("Right time offset (seconds)",(session.rightOffsetMs/1000.0).toString())
        val videoOffset=field("Video offset (seconds)",(session.videoOffsetMs/1000.0).toString())
        form.addView(TextView(this).apply { text="Positive boot offsets move samples later. Video time = session time + video offset." })
        val dialog=AlertDialog.Builder(this).setTitle("Edit session").setView(form).setNegativeButton("Cancel",null).setPositiveButton("Save",null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val offsets=listOf(left,right,videoOffset).map { it.text.toString().toDoubleOrNull() }
            if(offsets.any { it==null || !it.isFinite() || kotlin.math.abs(it)>86_400 }) { message("Enter offsets in seconds, within ±24 hours"); return@setOnClickListener }
            io.execute { LocalSessionStore(this).use { it.editSession(id,title.text.toString().trim(),notes.text.toString(),
                (offsets[0]!!*1000).toLong(),(offsets[1]!!*1000).toLong(),(offsets[2]!!*1000).toLong()) }
                runOnUiThread { dialog.dismiss(); load() } }
        } }; dialog.show()
    }
    private fun pickVideo() {
        pendingVideo=true
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type="video/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) },REQUEST_VIDEO)
    }
    private fun export(loaded: SessionData) {
        pendingExport=loaded
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type="application/zip"
            putExtra(Intent.EXTRA_TITLE,"openski-${loaded.session.id.take(8)}.zip") },REQUEST_EXPORT)
    }
    @Deprecated("Picker compatibility")
    override fun onActivityResult(requestCode: Int,resultCode: Int,intent: Intent?) {
        super.onActivityResult(requestCode,resultCode,intent)
        if(resultCode!=RESULT_OK) { pendingExport=null; pendingVideo=false; return }
        val uri=intent?.data ?: return
        if(requestCode==REQUEST_VIDEO && pendingVideo) {
            pendingVideo=false
            try { contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch(_: SecurityException) {}
            val name=contentResolver.query(uri,arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),null,null,null)?.use {
                if(it.moveToFirst()) it.getString(0) else null } ?: "Video"
            io.execute { LocalSessionStore(this).use { it.attachVideo(id,uri.toString(),name) }; runOnUiThread { load() } }
        } else if(requestCode==REQUEST_EXPORT) {
            val snapshot=pendingExport; pendingExport=null
            io.execute { try {
                val current=snapshot ?: LocalSessionStore(this).use { it.loadData(id) } ?: error("Session unavailable")
                contentResolver.openOutputStream(uri,"w")?.use { SessionExport.write(current,it) } ?: error("Export destination unavailable")
                runOnUiThread { message("Session exported") }
            } catch(error: Exception) { runOnUiThread { message("Export failed: ${error.message}") } } }
        }
    }
    override fun onSaveInstanceState(state: Bundle) { state.putDouble("elapsed_ms",elapsedMs); state.putBoolean("pending_video",pendingVideo); super.onSaveInstanceState(state) }
    override fun onStop() { video?.pause(); super.onStop() }
    override fun onDestroy() { handler.removeCallbacks(ticker); video?.stopPlayback(); io.shutdown(); super.onDestroy() }
    companion object { private const val REQUEST_VIDEO=1; private const val REQUEST_EXPORT=2 }
}
