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
    private var telemetryOverlay: TextView? = null
    private var replayOrientation: Map<String,List<BootRoll>> = emptyMap()
    private var selectedTab = 0

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        SkiUi.configureWindow(this)
        id = intent.getStringExtra("session_id") ?: run { finish(); return }
        elapsedMs = state?.getDouble("elapsed_ms") ?: 0.0
        pendingVideo = state?.getBoolean("pending_video") ?: false
        selectedTab = state?.getInt("review_tab") ?: 0
        load()
        handler.post(ticker)
    }
    private fun load() {
        setContentView(text("Loading session…",18f).apply { setBackgroundColor(SkiUi.CANVAS); setPadding(dp(24),dp(48),dp(24),dp(24)) })
        io.execute {
            try {
                val loaded = LocalSessionStore(this).use { it.loadData(id) }
                val result = loaded?.let(SessionAnalysis::build)
                val calibrations=loaded?.let { item -> LocalSessionStore(this).use { it.calibrations(item.session) } }.orEmpty()
                val corrector=ProgressStore(this)
                val bootRoll=if(result==null) emptyList() else calibrations.flatMap { BootOrientation.estimate(result.timeline.corrected(corrector::correctionForSide),it) }
                runOnUiThread { if (!isDestroyed && !isFinishing) {
                    if (loaded == null) { finish(); return@runOnUiThread }
                    data = loaded; analysis = result; render(loaded, result!!,calibrations,bootRoll)
                } }
            } catch (error: Exception) { runOnUiThread { message("Could not load session: ${error.message}") } }
        }
    }
    private fun dp(n: Int) = (n*resources.displayMetrics.density).toInt()
    private fun text(s: String, size: Float = 14f) = SkiUi.label(this,s,size,if(size>=18) SkiUi.TEXT else SkiUi.SECONDARY,size>=18).apply {
        setPadding(0,dp(7),0,dp(7))
    }
    private fun button(s: String, action: () -> Unit) = SkiUi.button(this,s,when {
        s.startsWith("Delete") -> SkiUi.ButtonStyle.DANGER
        s.startsWith("‹") -> SkiUi.ButtonStyle.QUIET
        else -> SkiUi.ButtonStyle.SECONDARY
    },action).apply {
        layoutParams=LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(6); bottomMargin=dp(4) }
    }
    private fun note(title: String, body: String) = LinearLayout(this).apply {
        orientation=LinearLayout.VERTICAL
        val explanation=text(body,12f).apply { visibility=android.view.View.GONE }
        addView(button("＋  $title") {
            val open=explanation.visibility!=android.view.View.VISIBLE
            explanation.visibility=if(open) android.view.View.VISIBLE else android.view.View.GONE
            (getChildAt(0) as Button).text="${if(open) "−" else "＋"}  $title"
        })
        addView(explanation)
    }
    private fun message(s: String) { if (!isDestroyed) Toast.makeText(this,s,Toast.LENGTH_LONG).show() }
    private fun number(n: Double) = String.format(Locale.US,"%.1f",n)
    private fun render(loaded: SessionData, result: AnalysisResult, calibrations: List<BootCalibration>, bootRoll: List<BootRoll>) {
        video?.stopPlayback(); videoReady = false
        val session = loaded.session
        replayOrientation=bootRoll.groupBy { it.side }.mapValues { it.value.sortedBy { p->p.timeMs } }
        telemetryOverlay=null
        val compact=resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val root = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setBackgroundColor(SkiUi.CANVAS) }
        val header = LinearLayout(this).apply { orientation=if(compact) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity=android.view.Gravity.CENTER_VERTICAL; setPadding(dp(20),dp(12),dp(20),dp(12)) }
        val back=button("‹  Back") { finish() }
        header.addView(back,LinearLayout.LayoutParams(if(compact) -2 else -1,-2))
        val title=text(session.title.ifBlank { "Ski session" },if(compact) 20f else 24f).apply {
            maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END
            contentDescription=session.title.ifBlank { "Ski session" }
        }
        header.addView(title,LinearLayout.LayoutParams(if(compact) 0 else -1,-2,if(compact) 1f else 0f).apply { if(compact) marginStart=dp(12) })
        if(session.origin=="synthetic") header.addView(SkiUi.chip(this,if(compact) "DEMO" else "DEMO · Simulated sensor data").apply { contentDescription="Demo: simulated sensor data" })
        else if(session.testSession) header.addView(SkiUi.chip(this,if(compact) "TEST" else "TEST · Indoor boot movements",SkiUi.SKY).apply { contentDescription="Test: indoor boot movements" })
        root.addView(header)
        fun reviewPage()=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(20),dp(12),dp(20),dp(30)) }
        val replayPage=reviewPage()
        val analysisPage=reviewPage()
        val detailsPage=reviewPage()
        if(session.testSession) {
            val summary=SkiUi.card(this)
            summary.addView(SkiUi.label(this,"COMPLETED LEAN MOVEMENTS",11f,SkiUi.MUTED,true))
            val counts=listOf("L","R").map { side ->
                val stream=bootRoll.filter { it.side==side }
                if(stream.isEmpty()) "—" else DrySkiAnalysis.detectTrials(stream,loaded.markers).movements.size.toString()
            }
            summary.addView(SkiUi.label(this,counts.joinToString(" / "),34f,SkiUi.ACCENT,true).apply { setPadding(0,dp(8),0,dp(4)) })
            summary.addView(SkiUi.label(this,"Left boot / right boot · Experimental indoor detector",12f,SkiUi.SECONDARY))
            analysisPage.addView(summary,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(14) })
        }
        val pages=listOf(replayPage,analysisPage,detailsPage)
        val tabRow=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; setPadding(dp(16),0,dp(16),dp(8)) }
        val scrolls=pages.map { content -> ScrollView(this).apply { isFillViewport=true; addView(content) } }
        val content=FrameLayout(this)
        scrolls.forEach { content.addView(it,FrameLayout.LayoutParams(-1,-1)) }
        val tabs=mutableListOf<Button>()
        fun selectTab(index: Int) {
            selectedTab=index.coerceIn(0,2)
            scrolls.forEachIndexed { i, view -> view.visibility=if(i==selectedTab) android.view.View.VISIBLE else android.view.View.GONE }
            tabs.forEachIndexed { i, view -> view.isSelected=i==selectedTab; SkiUi.styleButton(view,if(i==selectedTab) SkiUi.ButtonStyle.PRIMARY else SkiUi.ButtonStyle.QUIET) }
            if(selectedTab!=0) video?.pause()
        }
        listOf("Replay","Analysis","Details").forEachIndexed { index, title ->
            val tab=button(title) { selectTab(index) }
            tabs.add(tab); tabRow.addView(tab,LinearLayout.LayoutParams(0,-2,1f))
        }
        root.addView(tabRow)
        root.addView(content,LinearLayout.LayoutParams(-1,0,1f))
        fun card(host: LinearLayout)=SkiUi.card(this).also { host.addView(it,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(14) }) }
        var page=card(detailsPage)
        val duration = ((session.endedAtMs ?: System.currentTimeMillis())-session.startedAtMs).coerceAtLeast(0)
        page.addView(text("${java.text.SimpleDateFormat("EEE, d MMM yyyy · HH:mm",Locale.getDefault()).format(java.util.Date(session.startedAtMs))}\nDuration ${number(duration/1000.0)}s"))
        if (session.notes.isNotBlank()) page.addView(text(session.notes))
        page.addView(text("Equipment: ${session.equipment.ifBlank { "Not set" }}"))
        page.addView(button("Set equipment") { equipment(loaded) })
        page.addView(button("Edit name, notes and alignment") { edit(loaded) })
        page=card(detailsPage)
        page.addView(text("Recording quality",18f))
        result.quality.forEach { q -> page.addView(text("${if(q.side=="L") "Left" else "Right"}: ${q.liveSamples} live · ${q.flashSamples} validated flash samples\nEstimated coverage ${number(q.coverage*100)}% · ${q.gaps} gaps over 0.1s · longest ${number(q.longestGapMs/1000)}s\nSensor-reported dropped samples: ${q.dropped}")) }
        loaded.captures.forEach { c -> page.addView(text("${c.side} backup: ${c.state} · ${c.expected} records" + if(c.error.isBlank()) "" else "\n${c.error}")) }
        page.addView(text(result.alignment))
        if (loaded.events.isNotEmpty()) page.addView(button("Recording events (${loaded.events.size})") {
            AlertDialog.Builder(this).setTitle("Recording events").setMessage(loaded.events.takeLast(100).joinToString("\n") {
                "${number((it.first-session.startedAtMs)/1000.0)}s · ${it.second}"
            }).setPositiveButton("Close",null).show()
        })
        page=card(replayPage)
        page.addView(text("Motion timeline",18f))
        page.addView(text("Left · solid lime     Right · dashed blue",12f))
        page.addView(note("Timeline help","Tap the graph or scrub the timeline to select a moment. Video time = session time + video offset; a negative offset means the video starts later."))
        page.addView(spinner(listOf("Acceleration magnitude (m/s²)","Gyro X (rad/s)","Gyro Y (rad/s)","Gyro Z (rad/s)","Gyro magnitude (rad/s)","Estimated boot roll (degrees)","Estimated boot pitch (degrees)","Relative yaw (degrees, drifts)")) { position -> chart?.channel=position; chart?.invalidate() }.apply { setSelection(if(bootRoll.isEmpty()) 0 else 5) })
        page.addView(spinner(listOf("30-second window","10-second window","Whole session")) { position ->
            windowMs = when(position) { 1->10_000.0; 2->duration.coerceAtLeast(1).toDouble(); else->30_000.0 }; updateChart()
        })
        chart = MotionChart(this).apply {
            channel=if(bootRoll.isEmpty()) 0 else 5
            points=result.timeline; originMs=session.startedAtMs.toDouble()
            roll=bootRoll
            markers=loaded.markers
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
        page=card(replayPage)
        page.addView(text("Boot orientation",18f))
        page.addView(SkiUi.chip(this,"EXPERIMENTAL",SkiUi.SKY))
        page.addView(note("Calibration guide & limits","Select the beginning of a two-second stationary, neutral stance on the timeline, then calibrate each boot. Choose the sensor axis pointing along the boot toward its toe. This estimates roll relative to that neutral stance, not ski edge angle against the snow. Moving acceleration is not used as a spirit level; gyro drift grows during motion. After gaps, the trace resumes only after detected rest. Changing boot time offsets clears calibration."))
        page.addView(text(if(calibrations.isEmpty()) "No boot calibration saved" else calibrations.joinToString("\n") {
            "${it.side}: neutral reference at ${number((it.timeMs-session.startedAtMs)/1000)}s · ${listOf("X","Y","Z")[it.axis]} ${if(it.sign>0) "+" else "−"} toward toe"
        } + "\nLongest time without rest correction: ${number(bootRoll.maxOfOrNull { it.driftSeconds } ?: 0.0)}s"))
        page.addView(button("Calibrate boot from selected time") { calibrateBoot(loaded,result) })
        if(session.testSession) {
            page=card(detailsPage)
            page.addView(text("Sampling check",18f))
            for(side in listOf("L","R")) for(source in listOf("live","flash")) {
                val stream=result.raw.filter { it.side==side && it.source==source }
                if(stream.isNotEmpty()) {
                    val stats=DrySkiAnalysis.sampleTiming(stream)
                    page.addView(text("$side $source: ${stats.samples} samples · median interval ${stats.medianIntervalMs?.let { number(it) } ?: "—"} ms · effective ${stats.effectiveHz?.let { number(it) } ?: "—"} Hz · ${stats.gaps} gaps >100 ms · ${stats.duplicateTimes} duplicate times"))
                }
            }
            page.addView(text("Sampling checks use raw live and flash sources separately; effective rate includes gaps. Overlapping flash captures can produce duplicate times."))
            page=card(replayPage)
            page.addView(text("Labels & alignment",18f))
            page.addView(button("Add reviewed label at selected time") { reviewedMarker(loaded) })
            page.addView(button("Review / delete labels (${loaded.markers.size})") { reviewMarkers(loaded) })
            page.addView(button("Align video using a SYNC marker") { syncVideo(loaded) })
        }
        page=card(replayPage)
        page.addView(text("Video replay",18f))
        if(session.videoUri==null) page.addView(text("Attach a local video to review movement alongside the sensor timeline."))
        page.addView(button(if(session.videoUri==null) "Attach video" else "Replace video") { pickVideo() })
        video=null
        if(session.videoUri!=null) {
            page.addView(text(session.videoName ?: "Attached video"))
            video=VideoView(this).apply {
                setVideoURI(Uri.parse(session.videoUri))
                setOnPreparedListener { videoReady=true; moveTo(elapsedMs,true) }
                setOnErrorListener { _,_,_ -> videoReady=false; message("Video unavailable. Reattach it if it was moved or removed."); true }
            }
            val frame=FrameLayout(this)
            frame.addView(video,FrameLayout.LayoutParams(-1,-1))
            telemetryOverlay=text("",12f).apply { setBackgroundColor(0xB0000000.toInt()); setPadding(dp(8),dp(4),dp(8),dp(4)) }
            frame.addView(telemetryOverlay,FrameLayout.LayoutParams(-1,-2,android.view.Gravity.BOTTOM))
            page.addView(frame,LinearLayout.LayoutParams(-1,dp(280)))
            page.addView(button("Play / pause video") {
                if(!videoReady) return@button
                if(video?.isPlaying==true) video?.pause() else {
                    val position=elapsedMs+session.videoOffsetMs
                    if(position<0 || position >= (video?.duration ?: 0)) { message("No video at this session time; adjust the offset or scrub to its interval"); return@button }
                    video?.start()
                }
            })
        }
        page=card(analysisPage)
        page.addView(text("Analysis setup",18f))
        page.addView(SkiUi.chip(this,"EXPERIMENTAL",SkiUi.SKY))
        page.addView(note("Candidate analysis guide","Gyro lobes can help find turns for video review. These are unvalidated candidates, not skiing scores. Choose the axis perpendicular to the turn plane for your sensor mounting."))
        val candidatesLabel=text("")
        val timingLabel=text("")
        val profileLabel=text("")
        val profileChart=TurnProfileChart(this)
        val dryLabel=text("")
        val comparisonLabel=text("")
        val reviewSettings=org.json.JSONObject(session.analysisJson)
        var reviewSide=reviewSettings.optString("selected_side","L").takeIf { it in listOf("L","R") } ?: "L"
        var axis=reviewSettings.optJSONObject(reviewSide)?.optInt("turn_axis",2) ?: 2
        var positiveLeft=reviewSettings.optJSONObject(reviewSide)?.optBoolean("positive_left",true) ?: true
        var axisControl: Spinner?=null
        var signControl: CheckBox?=null
        var reviewedMovements: List<DryMovement> = emptyList()
        fun candidates() {
            val found=SessionAnalysis.candidates(result.timeline,reviewSide,axis)
            val timing=SessionAnalysis.timing(result.timeline,reviewSide,axis)
            val dry=DrySkiAnalysis.detectTrials(bootRoll.filter { it.side==reviewSide },loaded.markers)
            reviewedMovements=dry.movements
            val profileIntervals=if(session.testSession) dry.movements.map { CandidateInterval(it.startMs,it.endMs,it.positive) } else timing.intervals
            val profiles=BootOrientation.profiles(bootRoll.filter { it.side==reviewSide },profileIntervals)
            val ranges=DrySkiAnalysis.trialRanges(loaded.markers,(session.endedAtMs ?: System.currentTimeMillis()).toDouble())
            val trialLabels=loaded.markers.filter { m -> ranges.any { (start,end)->m.timeMs>=start && m.timeMs<end } }
            val evaluation=DrySkiAnalysis.evaluate(trialLabels,dry,reviewSide,positiveLeft)
            val transitionEvaluation=DrySkiAnalysis.evaluateTransitions(trialLabels,dry,reviewSide)
            val balance=DrySkiAnalysis.balance(dry)
            fun polarity(side: String)=if(side==reviewSide) positiveLeft else reviewSettings.optJSONObject(side)?.optBoolean("positive_left",true) ?: true
            val leftRoll=bootRoll.filter { it.side=="L" }
            val rightRoll=bootRoll.filter { it.side=="R" }
            val comparison=BootComparison.analyse(leftRoll,rightRoll,DrySkiAnalysis.detectTrials(leftRoll,loaded.markers),
                DrySkiAnalysis.detectTrials(rightRoll,loaded.markers),polarity("L"),polarity("R"))
            comparisonLabel.text="${comparison.anglePairs} time-matched angle samples · mean roll difference ${comparison.meanRollDifferenceDeg?.let { "${number(it)}°" } ?: "—"}\n" +
                "Roll correlation ${comparison.correlation?.let { String.format(Locale.US,"%.3f",it) } ?: "—"}\n" +
                "${comparison.movementPairs} paired lean movements · median right-minus-left onset lag ${comparison.medianOnsetLagMs?.let { "${number(it)} ms" } ?: "—"}\n" +
                "Median right-minus-left neutral return lag ${comparison.medianReturnLagMs?.let { "${number(it)} ms" } ?: "—"}"
            dryLabel.text="${dry.movements.size} completed lean movements · ${dry.transitions.size} returns through neutral\n" +
                "Entry ≥8° · neutral ≤3° · minimum 0.3s · thresholds are test settings\n" +
                (balance?.let { b ->
                    val left=if(positiveLeft) b.positiveDurationMs else b.negativeDurationMs
                    val right=if(positiveLeft) b.negativeDurationMs else b.positiveDurationMs
                    "Mean lean duration L/R: ${number(left/1000)} / ${number(right/1000)}s\n" +
                        "Left/right mean duration ratio ${number(b.durationRatioPercent)}% · mean peak roll ratio ${number(b.peakRatioPercent)}% (descriptive ratios, not skill scores)\n"
                } ?: "Left/right comparison needs two complete movements in each direction.\n") +
                "Transitions: ${transitionEvaluation.matched}/${transitionEvaluation.labels} labels matched · mean absolute error ${transitionEvaluation.meanAbsoluteErrorMs?.let { number(it) } ?: "—"} ms\n" +
                (if(evaluation.labels==0) "Add LEFT/RIGHT labels to evaluate onset timing.\n" else
                    "Annotated window: ${evaluation.matched}/${evaluation.labels} labels matched · ${evaluation.detections-evaluation.matched} unmatched detections · ±0.5s tolerance\n" +
                    "Mean absolute onset error: ${evaluation.meanAbsoluteErrorMs?.let { number(it) } ?: "—"} ms · signed error: ${evaluation.meanSignedErrorMs?.let { number(it) } ?: "—"} ms")
            profileChart.profiles=profiles; profileChart.positiveLeft=positiveLeft; profileChart.invalidate()
            profileLabel.text=profiles.joinToString("\n") {
                "Candidate ${if(it.positive==positiveLeft) "left" else "right"}: ${it.count} intervals · peak of mean roll magnitude ${number(it.peakDegrees)}° at ${it.peakPercent}%"
            }.ifBlank { if(session.testSession) "Calibrate this boot, start a trial and complete lean movements to see profiles." else "Calibrate this boot and select the correct turn axis to see complete profiles." }
            fun seconds(value: Double?) = value?.let { "${number(it/1000)}s" } ?: "—"
            val leftMean=if(positiveLeft) timing.positiveMeanMs else timing.negativeMeanMs
            val rightMean=if(positiveLeft) timing.negativeMeanMs else timing.positiveMeanMs
            timingLabel.text="${if(reviewSide=="L") "Left boot" else "Right boot"} · ${timing.intervals.size} usable alternating intervals\n" +
                "Median candidate interval: ${seconds(timing.medianMs)}\n" +
                "Candidate cadence: ${timing.cadencePerMinute?.let { number(it) } ?: "—"}/min\n" +
                "Timing variation: ${timing.variationPercent?.let { "${number(it)}%" } ?: "— (needs 3 intervals)"}\n" +
                "Candidate left / right mean: ${seconds(leftMean)} / ${seconds(rightMean)}\n" +
                "Mean left–right cycle: ${seconds(timing.cycleMeanMs)}"
            candidatesLabel.text="${found.size} candidates · 0.35 rad/s threshold · 0.5–20s lobes\n" + found.take(40).joinToString("\n") { c ->
                val direction=if(c.positive==positiveLeft) "left" else "right"
                "${c.side}: ${number((c.startMs-session.startedAtMs)/1000)}–${number((c.endMs-session.startedAtMs)/1000)}s · candidate $direction · ${number(c.peakRadps.toDouble())} rad/s"
            } + if(found.size>40) "\nShowing the first 40." else ""
        }
        val sideSpinner=spinner(listOf("Review left boot","Review right boot")) {
            val selected=if(it==0) "L" else "R"
            if(selected!=reviewSide) {
                reviewSide=selected
                axis=reviewSettings.optJSONObject(reviewSide)?.optInt("turn_axis",2) ?: 2
                positiveLeft=reviewSettings.optJSONObject(reviewSide)?.optBoolean("positive_left",true) ?: true
                axisControl?.setSelection(axis); signControl?.isChecked=positiveLeft
            }
            candidates()
        }
        sideSpinner.setSelection(if(reviewSide=="L") 0 else 1)
        page.addView(sideSpinner)
        val axisSpinner=spinner(listOf("Gyro X","Gyro Y","Gyro Z")) { axis=it; candidates() }
        axisControl=axisSpinner
        axisSpinner.setSelection(axis)
        page.addView(axisSpinner)
        val signBox=CheckBox(this).apply { text=if(session.testSession) "Positive calibrated roll means left (gyro axis only affects gyro candidates)" else "Positive rotation means left (requires matching mounting on both boots)"; setTextColor(SkiUi.SECONDARY); textSize=13f; minHeight=dp(48)
            isChecked=positiveLeft; setOnCheckedChangeListener { _,checked -> positiveLeft=checked; candidates() } }
        signControl=signBox
        page.addView(signBox)
        page.addView(button("Save analysis settings for this boot") {
            val selectedSide=reviewSide; val selectedAxis=axis; val selectedSign=positiveLeft
            io.execute { LocalSessionStore(this).use { it.saveReviewSettings(id,selectedSide,selectedAxis,selectedSign) }; runOnUiThread { load() } }
        })
        page=card(analysisPage)
        page.addView(text("Turn timing",18f))
        page.addView(note("How to read candidate timing","Intervals run from one detected gyro-lobe onset to the next opposite onset, not measured edge changes. Each boot is reviewed separately. Gaps and isolated motions are excluded; the last candidate has no complete interval. Lower timing variation means a more regular rhythm, not better technique. Timing alone cannot distinguish short-radius turns from carving."))
        page.addView(timingLabel)
        page=card(analysisPage)
        page.addView(text("Boot roll profile",18f))
        page.addView(note("Profile guide","Green = candidate left · blue = candidate right. Mean absolute roll at 5% steps. " +
            if(session.testSession) "Test profiles use completed roll-defined lean movements, from entry to neutral return. Peak timing is descriptive, not a technique target."
            else "Profiles use gyro candidate onset intervals, not validated turn boundaries. Peak timing is descriptive, not a technique target."))
        page.addView(profileChart,LinearLayout.LayoutParams(-1,dp(230)))
        page.addView(profileLabel)
        if(session.testSession) {
            page=card(analysisPage)
            page.addView(text("Indoor movements",18f))
            page.addView(note("Detector settings & evaluation","Calibrated lean excursions ending on return to neutral. START TEST / PAUSE markers limit the trial; without START TEST, all calibrated data is used. Incomplete movements and gaps are excluded. Counts and durations describe simulated movements, not ski turns. Observer tap latency, BLE timing and detector thresholds affect label errors. Evaluation covers the first-to-last directional label window, expanded by 0.5s."))
            page.addView(dryLabel)
            page.addView(button("Browse detected movements · tap to replay") {
                if(reviewedMovements.isEmpty()) { message("No completed movements for this boot"); return@button }
                val items=reviewedMovements.mapIndexed { index,m ->
                    "${index+1}: ${if(m.positive==positiveLeft) "LEFT" else "RIGHT"} · ${number((m.startMs-session.startedAtMs)/1000)}s · ${number((m.endMs-m.startMs)/1000)}s · ${number(m.peakRoll)}°"
                }
                AlertDialog.Builder(this).setTitle("$reviewSide boot · completed movements")
                    .setItems(items.toTypedArray()) { _,index -> selectTab(0); moveTo(reviewedMovements[index].startMs-session.startedAtMs,true) }
                    .setNegativeButton("Close",null).show()
            })
            page=card(analysisPage)
            page.addView(text("Both boots",18f))
            page.addView(note("Comparison guide","Angles are interpolated only across close, continuous samples. Positive lag means the right boot moved later. Clock alignment is approximate; differences describe boot motion, not ski pressure or technique quality. Save each boot's direction setting before export."))
            page.addView(comparisonLabel)
        }
        page=card(analysisPage)
        page.addView(text("Gyro candidates",18f))
        page.addView(candidatesLabel); candidates()
        page=card(detailsPage)
        page.addView(text("Session files",18f))
        page.addView(button("Export session (.zip with CSV and metadata)") { export(loaded) })
        page.addView(button("Delete session") {
            AlertDialog.Builder(this).setTitle("Delete this session?")
                .setMessage("This removes its samples and notes from this phone. The original video remains in place.")
                .setNegativeButton("Cancel",null).setPositiveButton("Delete") { _,_ ->
                    io.execute { val deleted=LocalSessionStore(this).use { it.deleteSession(id) }
                        runOnUiThread { if(deleted) finish() else message("Stop recording and finish sensor recovery before deleting this session") } }
                }.show()
        })
        setContentView(root)
        SkiUi.applyInsets(root)
        selectTab(selectedTab)
        moveTo(elapsedMs,false)
    }
    private fun spinner(items: List<String>, textColor: Int=SkiUi.TEXT, selected: (Int)->Unit) = Spinner(this).apply {
        minimumHeight=dp(48)
        background=SkiUi.rounded(this@SessionDetailActivity,SkiUi.SURFACE_RAISED,10)
        layoutParams=LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(5); bottomMargin=dp(5) }
        adapter=ArrayAdapter(this@SessionDetailActivity,android.R.layout.simple_spinner_dropdown_item,items)
        onItemSelectedListener=object: AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, row: Long) { (view as? TextView)?.setTextColor(textColor); selected(position) }
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
        telemetryOverlay?.text="Session ${number(elapsedMs/1000)}s · experimental boot orientation\n"+listOf("L","R").joinToString("\n") { side ->
            val stream=replayOrientation[side].orEmpty()
            val time=session.startedAtMs+elapsedMs
            val index=stream.binarySearchBy(time) { it.timeMs }.let { if(it<0) -it-1 else it }
            val p=listOfNotNull(stream.getOrNull(index),stream.getOrNull(index-1)).minByOrNull { kotlin.math.abs(it.timeMs-time) }
            if(p==null || kotlin.math.abs(p.timeMs-time)>100) "$side: orientation unavailable"
            else "$side roll ${number(p.degrees)}° · pitch ${number(p.pitchDegrees)}° · yaw ${number(p.yawDegrees)}° · drift age ${number(p.driftSeconds)}s"
        }
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
    private fun reviewedMarker(loaded: SessionData) {
        val timestamp=(loaded.session.startedAtMs+elapsedMs).toLong()
        var label="LEFT"; var side=""
        val form=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        val labels=listOf("LEFT","RIGHT","TRANSITION","SYNC","EVENT","START_TEST","PAUSE")
        form.addView(spinner(labels) { label=labels[it] })
        form.addView(spinner(listOf("Both boots","Left boot","Right boot")) { side=when(it) { 1->"L"; 2->"R"; else->"" } })
        val note=EditText(this).apply { hint="Observation / note" }; form.addView(note)
        AlertDialog.Builder(this).setTitle("Label selected session time (${number(elapsedMs/1000)}s)").setView(form)
            .setNegativeButton("Cancel",null).setPositiveButton("Save") { _,_ ->
                val selectedLabel=label; val selectedSide=side; val selectedNote=note.text.toString()
                io.execute {
                    val saved=LocalSessionStore(this).use { it.addMarker(id,timestamp,selectedLabel,selectedSide,"video_review",selectedNote) }
                    runOnUiThread { if(saved) load() else message("Label is outside this test session") }
                }
            }.show()
    }
    private fun reviewMarkers(loaded: SessionData) {
        val markers=loaded.markers
        if(markers.isEmpty()) { message("No labels yet"); return }
        AlertDialog.Builder(this).setTitle("Labels · select to seek or delete")
            .setItems(markers.map { "${number((it.timeMs-loaded.session.startedAtMs)/1000.0)}s · ${it.label} · ${it.side.ifBlank { "both" }} · ${it.source}" }.toTypedArray()) { _,index ->
                val marker=markers[index]
                moveTo((marker.timeMs-loaded.session.startedAtMs).toDouble(),true)
                AlertDialog.Builder(this).setTitle(marker.label).setMessage(marker.note.ifBlank { "Selected label on timeline" })
                    .setNegativeButton("Keep",null).setPositiveButton("Delete label") { _,_ ->
                        io.execute { LocalSessionStore(this).use { it.deleteMarker(id,marker.id) }; runOnUiThread { load() } }
                    }.show()
            }.setNegativeButton("Close",null).show()
    }
    private fun syncVideo(loaded: SessionData) {
        val markers=loaded.markers.filter { it.label=="SYNC" }
        if(loaded.session.videoUri==null || markers.isEmpty()) { message("Attach a video and add a SYNC marker first"); return }
        AlertDialog.Builder(this).setTitle("Choose the matching sensor SYNC event")
            .setItems(markers.map { "${number((it.timeMs-loaded.session.startedAtMs)/1000.0)}s · ${it.source}" }.toTypedArray()) { _,index ->
                val marker=markers[index]
                val input=EditText(this).apply { hint="Time of the same event in video, in seconds" }
                val dialog=AlertDialog.Builder(this).setTitle("Observed video timestamp").setView(input)
                    .setNegativeButton("Cancel",null).setPositiveButton("Align",null).create()
                dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val seconds=input.text.toString().toDoubleOrNull()
                    if(seconds==null || !seconds.isFinite() || seconds !in 0.0..86400.0) { message("Enter a video timestamp between 0 and 86400 seconds"); return@setOnClickListener }
                    val offset=(seconds*1000-(marker.timeMs-loaded.session.startedAtMs)).toLong()
                    io.execute { LocalSessionStore(this).use { store ->
                        val current=store.getSession(id) ?: return@use
                        store.editSession(id,current.title,current.notes,current.leftOffsetMs,current.rightOffsetMs,offset)
                    }; runOnUiThread { dialog.dismiss(); load() } }
                } }; dialog.show()
            }.setNegativeButton("Cancel",null).show()
    }

    private fun calibrateBoot(loaded: SessionData, result: AnalysisResult) {
        val form=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(20),dp(8),dp(20),dp(8)) }
        var side="L"; var axis=0; var sign=1
        form.addView(spinner(listOf("Left boot","Right boot")) { side=if(it==0) "L" else "R" })
        form.addView(spinner(listOf("Sensor +X points toward toe","Sensor −X points toward toe",
            "Sensor +Y points toward toe","Sensor −Y points toward toe","Sensor +Z points toward toe","Sensor −Z points toward toe")) {
            axis=it/2; sign=if(it%2==0) 1 else -1
        })
        val start=loaded.session.startedAtMs+elapsedMs
        form.addView(TextView(this).apply { text="Use two stationary seconds from ${number(elapsedMs/1000)}s, with the boots in their neutral stance. Movement or an unsuitable axis will be rejected." })
        val dialog=AlertDialog.Builder(this).setTitle("Mounting calibration").setView(form)
            .setNegativeButton("Cancel",null).setPositiveButton("Save",null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val selectedSide=side; val selectedAxis=axis; val selectedSign=sign
            io.execute {
                val calibration=BootOrientation.calibrate(result.timeline.corrected(ProgressStore(this)::correctionForSide),selectedSide,start,selectedAxis,selectedSign)
                if(calibration!=null) LocalSessionStore(this).use { it.saveCalibration(id,calibration) }
                runOnUiThread { if(!isDestroyed && !isFinishing) {
                    if(calibration==null) message("Need two continuous stationary seconds and a forward axis roughly perpendicular to gravity")
                    else { dialog.dismiss(); load() }
                } }
            }
        } }; dialog.show()
    }
    private fun equipment(loaded: SessionData) {
        io.execute {
            val labels = LocalSessionStore(this).use { it.equipmentLabels() }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                val field = AutoCompleteTextView(this).apply {
                    hint = "Ski model, length and setup"
                    setText(loaded.session.equipment)
                    threshold = 1
                    setAdapter(ArrayAdapter(this@SessionDetailActivity, android.R.layout.simple_dropdown_item_1line, labels))
                    setPadding(dp(20), dp(12), dp(20), dp(12))
                }
                AlertDialog.Builder(this).setTitle("Session equipment").setView(field)
                    .setNegativeButton("Cancel", null).setPositiveButton("Save") { _, _ ->
                        val label = field.text.toString().trim()
                        io.execute {
                            LocalSessionStore(this).use { it.setEquipment(id, label) }
                            runOnUiThread { if (!isDestroyed && !isFinishing) load() }
                        }
                    }.show()
            }
        }
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
                contentResolver.openOutputStream(uri,"w")?.use { SessionExport.write(current,it,ProgressStore(this)::correctionForSide) } ?: error("Export destination unavailable")
                runOnUiThread { message("Session exported") }
            } catch(error: Exception) { runOnUiThread { message("Export failed: ${error.message}") } } }
        }
    }
    override fun onSaveInstanceState(state: Bundle) { state.putDouble("elapsed_ms",elapsedMs); state.putBoolean("pending_video",pendingVideo); state.putInt("review_tab",selectedTab); super.onSaveInstanceState(state) }
    override fun onStop() { video?.pause(); super.onStop() }
    override fun onDestroy() { handler.removeCallbacks(ticker); video?.stopPlayback(); io.shutdown(); super.onDestroy() }
    companion object { private const val REQUEST_VIDEO=1; private const val REQUEST_EXPORT=2 }
}
