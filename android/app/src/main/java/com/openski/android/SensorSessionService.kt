package com.openski.android

import android.app.*
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.os.*
import java.util.UUID
import java.util.concurrent.Executors

/** Owns live capture and validated flash recovery independently of the activity. */
class SensorSessionService : Service() {
    interface Listener {
        fun onSensorStatus(side: String, message: String)
        fun onSensorSample(side: String, sample: SensorSample)
        fun onRecordingChanged(sessionId: String?, message: String)
        fun onSensorInfo(side: String, message: String) {}
        fun onHistoryChanged() {}
        fun onBootOrientation(side: String, value: BootRoll?) {}
        fun onTestFeedback(message: String) {}
        fun onMovementEvent(side: String, event: MovementEvent) {}
        fun onSkiEvent(side: String, event: SkiEvent) {}
        fun onSkiState(side: String, state: SkiState) {}
        fun onHeadingTurn(side: String, turn: HeadingTurnFrame) {}
    }
    inner class LocalBinder : Binder() { val service: SensorSessionService get() = this@SensorSessionService }
    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val store by lazy { LocalSessionStore(this) }
    private val clients = mutableMapOf<String, BleSensorClient>()
    private val addresses = mutableMapOf<String, String>()
    private val generations = mutableMapOf<String, Int>()
    private val reconnectAttempts = mutableMapOf<String, Int>()
    private val reconnectTasks = mutableMapOf<String, Runnable>()
    private val sensorStatuses = mutableMapOf<String, String>()
    private val sensorInfo = mutableMapOf<String, String>()
    private val recorderStates = mutableMapOf<String, RecorderStatus>()
    private val recorderSupported = mutableMapOf<String, Boolean>()
    private val batteryLevels = mutableMapOf<String, Int?>()
    private val batteryTimes = mutableMapOf<String, Long>()
    private val rssiLevels = mutableMapOf<String, Pair<Int,Long>>()
    private val lastSampleTime = mutableMapOf<String, Long>()
    private val flashRetries = mutableMapOf<String, Int>()
    /** Boots last seen in production mode (from state frames and recorder responses); sticky until a frame says otherwise. */
    private val productionSides = mutableSetOf<String>()
    private val captures = mutableMapOf<String, SensorCapture>()
    private data class Pending(val sessionId: String, val record: RecordedSample)
    private val pendingSamples = mutableListOf<Pending>()
    private class PendingMag(val sessionId: String, val row: StoredMag)
    private val pendingMag = mutableListOf<PendingMag>()
    private val phoneBuffer = PhoneMotionBuffer()
    private var phoneRecorder: PhoneMotionRecorder? = null
    private var phoneSessionId: String? = null
    /** Protocol clock sync: ping bursts, the per-boot fit and the drift monitor. Pairs are stored while recording. */
    private val clockSync by lazy { ClockSyncCoordinator(mainHandler, { clients[it] }, { recorderSupported[it] == true },
        onPair = { row -> activeSessionId?.let { id -> io.execute { try { store.addClockPairs(id, listOf(row)) }
            catch (error: Exception) { mainHandler.post { if (!destroyed) storageError(error) } } } } },
        onGestureOnly = { side -> activeSessionId?.let { logEvent(it, "$side boot has no time sync; gesture sync only") }; updateInfo(side) }) }
    private data class Wait(val opcode: Int, val timeout: Runnable, val action: (RecorderStatus) -> Unit)
    private val waits = mutableMapOf<String, Wait>()
    private data class Transfer(val capture: SensorCapture, val validator: TransferValidator, val generation: Int, var validating: Boolean = false)
    private val modeRequests = ModeRequestTracker()
    private var pendingRecoverySides = emptySet<String>() // Refreshed on the I/O executor for passive readiness updates.
    private val transfers = mutableMapOf<String, Transfer>()
    private val transferTimeouts = mutableMapOf<String, Runnable>()
    private var recordingWakeLock: PowerManager.WakeLock? = null
    private var activeSessionId: String? = null
    private var testRecording=false
    private var lastHealthPoll=0L
    private var listener: Listener? = null
    private var foreground = false
    private var monitoring = false
    private var coachState = CoachState()
    private var coachSession: CoachSession? = null
    private var coachAudio: CoachAudio? = null
    private var previewAudio: CoachAudio? = null
    private var practiceAudioOwned = false
    private var practiceAudioGuided = false
    private var practiceFocus = PracticeFocus.RHYTHM
    private var practiceTargetSource = "confirmed_preset"
    private var practiceAudioPhase: DrillPhase? = null
    private var practiceAudioCount = 0
    private var practiceMetronome = false
    private var practiceCountdownSecond: Int? = null
    private var coachSettings = CoachSettings()
    private var coachStartedAt = 0L
    private var coachStartedMonitoring = false
    private var coachDemo: DemoCoachFeed? = null
    private var coachListener: ((CoachState) -> Unit)? = null
    private val coachDemoStep = object : Runnable {
        override fun run() {
            val feed = coachDemo ?: return
            val step = feed.next()
            runController.onHalfTurn("L", step.event, System.currentTimeMillis())   // before the coach, so a verdict is never stamped earlier than its last turn
            coachSession?.onEvent("L", step.event)
            mainHandler.postDelayed(this, step.afterMs)
        }
    }
    private var lastNotificationMessage: String? = null

    // Practice capture belongs to the service; observers never own or stop it.
    private var drillGhost: RollTrace? = null
    private var requestedRepeat: Attempt? = null
    private var drillGhostAttempt: Attempt? = null
    private var drillNext: Drill? = null
    fun nextPracticeDrill(): Drill? = drillNext
    private val drillObservers = linkedMapOf<Any, (DrillState) -> Unit>()
    private val drillEffects: DrillEffects = object : DrillEffects {
        override fun prepare(attempt: Attempt, completed: (Result<Int>) -> Unit) {
            val repeatSource = requestedRepeat
            io.execute {
                val result = runCatching {
                    repeatSource?.let { source ->
                        val saved = requireNotNull(progressStore.attempt(source.id)) { "Saved target is unavailable." }
                        require(saved == source && saved.demo == attempt.demo && saved.target == attempt.target && saved.scoringVersion == attempt.scoringVersion) { "Saved target changed. Reopen the result." }
                        PracticeRepeat.drill(saved)
                    }
                    val previous = progressStore.attempts() // Imports legacy history before the first new write.
                    if (!attempt.demo && !Progress(previous, progressStore.experience.startsOn).unlocked(Programme.drill(attempt.drillId)!!))
                        error("Complete the preceding measured sets, or explore this drill in Demo.")
                    PracticeStore(store).record(attempt.copy(review = PracticeReview.capture(attempt, null, null, null)
                        .copy(focus = practiceFocus, targetSource = practiceTargetSource)))
                    Triple(previous.count { it.drillId == attempt.drillId && it.demo == attempt.demo }, progressStore.lastTrace(attempt.drillId, attempt.demo),
                        previous.lastOrNull { it.drillId == attempt.drillId && it.demo == attempt.demo && it.id == progressStore.lastTraceAttemptId(attempt.drillId, attempt.demo) })
                }
                mainHandler.post { if (!destroyed) {
                    if (drillController.id == attempt.id) result.onSuccess { drillGhost = it.second; drillGhostAttempt = it.third }
                    completed(result.map { it.first }); publishDrill()
                } }
            }
        }
        override fun openSession(attempt: Attempt, completed: (Result<Unit>) -> Unit) {
            if (activeSessionId != null || runActive()) { completed(Result.failure(IllegalStateException("Another recording is active"))); return }
            startRecording(true, drill = true, drillOwner = attempt.id, requestedId = attempt.sessionId)
            completed(if (activeSessionId == attempt.sessionId) Result.success(Unit)
                else Result.failure(IllegalStateException("Could not start recording. Check live samples, mode and storage.")))
        }
        override fun calibrate(attempt: Attempt, side: String, mounting: Mounting, completed: (Result<Unit>) -> Unit) {
            if (activeSessionId != attempt.sessionId) { completed(Result.failure(IllegalStateException("Practice session changed"))); return }
            calibrateTestResult(side, mounting.axis, mounting.sign) { result -> completed(result); publishDrill() }
        }
        override fun mark(attempt: Attempt, label: String) {
            if (!attempt.demo && activeSessionId == attempt.sessionId) markTest(label, note = "Drill: ${attempt.drillId}")
        }
        override fun stopSession(attempt: Attempt) {
            if (!attempt.demo && activeSessionId == attempt.sessionId) stopRecording(endBurst = false)
        }
        override fun save(attempt: Attempt, run: DrillRun?, completed: (Result<Attempt>) -> Unit) {
            val reviewed = attempt.copy(review = attempt.review?.copy(focus = practiceFocus, targetSource = practiceTargetSource))
            io.execute {
                val result = runCatching {
                    val saved = PracticeStore(store).record(reviewed, calculateReward = true)
                    if (run != null) progressStore.saveTrace(attempt.drillId, attempt.demo, run.trace, attempt.id)
                    attempt.sessionId?.let { id ->
                        store.getSession(id)?.let { session ->
                            store.editSession(id, session.title.ifBlank { "Drill · ${Programme.drill(attempt.drillId)?.title ?: attempt.drillId}" },
                                session.notes.ifBlank { "Dry-ski practice · ${saved.status.name.lowercase()}" },
                                session.leftOffsetMs, session.rightOffsetMs, session.videoOffsetMs)
                        }
                    }
                    saved to progressStore.progress(saved.demo).next(progressStore.goal ?: Goal.CARVING)
                }
                mainHandler.post { if (!destroyed) { result.onSuccess { if (drillController.id == attempt.id) drillNext = it.second?.takeIf { d -> d.id != attempt.drillId } }
                    completed(result.map { it.first }); publishDrill(); settleForeground() } }
            }
        }
    }
    private val drillController: DrillController by lazy { DrillController(drillEffects, SystemClock::elapsedRealtime, System::currentTimeMillis) }
    private val drillTick = object : Runnable {
        override fun run() {
            if (destroyed) return
            val live = clients.keys.filter { clients[it]?.isReady == true &&
                SystemClock.elapsedRealtime() - (lastSampleTime[it] ?: 0L) < 2000 }.toSet()
            drillController.tick(live, listOf("L", "R").associateWith(progressStore::mounting), activeSessionId)
            publishDrill()
            if (drillController.active) mainHandler.postDelayed(this, 100) else settleForeground()
        }
    }
    fun drillState(): DrillState {
        val state = drillController.state()
        val current = state.attempt
        val previous = drillGhostAttempt
        val compatible = current != null && previous != null && PracticeComparisonPolicy.evaluate(current,
            current.review?.evidence ?: PracticeEvidence(), previous, previous.review?.evidence ?: PracticeEvidence()).comparable
        return state.copy(ghost = if (compatible) drillGhost else null)
    }
    fun observeDrill(owner: Any, observer: (DrillState) -> Unit) { drillObservers[owner] = observer; observer(drillState()) }
    fun removeDrillObserver(owner: Any) { drillObservers.remove(owner) }
    private fun publishDrill() {
        updatePracticeAudio()
        if (drillObservers.isNotEmpty()) { val snapshot = drillState(); drillObservers.values.toList().forEach { it(snapshot) } }
    }
    fun startDrill(id: String, demo: Boolean, repeat: Attempt? = null, focus: PracticeFocus = PracticeFocus.RHYTHM,
                   audioGuided: Boolean = false): String? {
        val drill = if (repeat != null) runCatching { PracticeRepeat.drill(repeat) }.getOrElse { return it.message ?: "Cannot repeat target" }
            else Programme.drill(id) ?: return "Unknown drill"
        if (repeat != null && (repeat.drillId != id || repeat.demo != demo)) return "Saved target source does not match."
        if (!demo && listOf("L", "R").none { progressStore.sensorAddress(it) != null }) return "Pair a boot sensor before starting measured practice."
        if (activeSessionId != null || runActive() || coachState.running) return "Another recording or coaching session is active. Finish it first."
        if (transfers.isNotEmpty() || store.hasPendingRecovery()) return "Recover the retained recording before starting practice."
        if (drillController.active) return "A practice set is already active. Return to it or finish it first."
        stopPracticeAudio()
        previewAudio?.stop(); previewAudio = null
        practiceFocus = focus
        practiceTargetSource = if (repeat != null) "saved_target" else "confirmed_preset"
        practiceAudioGuided = audioGuided
        practiceAudioPhase = null; practiceAudioCount = 0
        requestedRepeat = repeat
        val problem = drillController.start(drill, demo, UUID.randomUUID().toString(), if (demo) null else UUID.randomUUID().toString())
        if (problem != null) return problem
        try {
            val keep = Intent(this, SensorSessionService::class.java).setAction(ACTION_RESUME_DRILL)
            // The bounded 6.5 s sensor-free preview needs no connected-device or pretend audio foreground type.
            if (demo) startService(keep) else {
                startForegroundService(keep)
                ensureForeground(); acquireWakeLock()
                monitoring = true; restoreConnections()
            }
        } catch (error: Exception) {
            drillController.interrupt("Could not keep practice running in the background.")
        }
        mainHandler.removeCallbacks(drillTick); mainHandler.post(drillTick); publishDrill()
        return null
    }
    fun finishDrill(id: String? = drillController.id) { if (id == drillController.id) drillController.finishEarly(); publishDrill() }
    fun retryDrillSave() { drillController.retrySave(); publishDrill() }

    fun pauseDrill() {
        if (drillController.pause("Practice paused. Resume when standing neutral.")) coachAudio?.takeIf { practiceAudioOwned }?.pausePractice()
        publishDrill()
    }
    fun resumeDrill(): String? {
        if (drillController.phase != DrillPhase.PAUSED) return "Practice is not paused."
        if (practiceAudioGuided) {
            val audio = coachAudio?.takeIf { practiceAudioOwned }
            val error = if (audio == null || audio.practiceAvailability().phase == PracticeAudioPhase.FAILED)
                startPracticeAudio() else audio.resumePractice()
            if (error != null) return error
        }
        if (!drillController.resume()) return "Practice could not resume."
        publishDrill()
        return null
    }
    private fun startPracticeAudio(metronome: Boolean = true): String? {
        stopPracticeAudio()
        val target = drillController.state().drill ?: return "Practice target is unavailable."
        val owner = drillController.id
        val audio = CoachAudio(this, onRoute = {}, onProblem = { problem -> mainHandler.post {
            if (practiceAudioOwned && drillController.id == owner && drillController.pause(problem)) publishDrill()
        } })
        coachAudio = audio; practiceAudioOwned = true
        practiceMetronome = metronome
        val settings = CoachSettingsStore(this).settings.copy(metronome = metronome, countIn = false)
        val error = audio.startPractice(settings, CoachTarget.of(target)) { availability ->
            mainHandler.post {
                if (coachAudio === audio && practiceAudioOwned && !availability.usable && availability.phase != PracticeAudioPhase.STOPPED) {
                    if (drillController.pause(availability.message)) publishDrill()
                }
            }
        }
        if (error != null) { audio.stop(); if (coachAudio === audio) { coachAudio = null; practiceAudioOwned = false } }
        return error
    }
    private fun stopPracticeAudio() {
        if (practiceAudioOwned) { val audio = coachAudio; coachAudio = null; practiceAudioOwned = false; audio?.stop() }
    }
    private fun updatePracticeAudio() {
        val state = drillController.state()
        val previous = practiceAudioPhase
        practiceAudioPhase = state.phase
        if (!practiceAudioGuided) return
        if (state.phase == DrillPhase.COUNTDOWN) {
            if (!practiceAudioOwned || practiceMetronome) {
                startPracticeAudio(metronome = false)?.let { problem -> drillController.pause(problem); return }
            }
            if (practiceCountdownSecond != state.countdownSeconds && (state.countdownSeconds ?: 0) > 0) {
                practiceCountdownSecond = state.countdownSeconds
                coachAudio?.playChime(ToneSynth.tick(false))
            }
        } else practiceCountdownSecond = null
        if (state.phase == DrillPhase.RUNNING && previous != DrillPhase.RUNNING) {
            if (!practiceAudioOwned || !practiceMetronome) startPracticeAudio()?.let { problem -> drillController.pause(problem); return }
            coachAudio?.playChime(ToneSynth.startChime())
        }
        if (state.phase == DrillPhase.RUNNING && state.count > practiceAudioCount) coachAudio?.playChime(ToneSynth.tone(1000.0, 90))
        practiceAudioCount = state.count
        val terminal = setOf(DrillPhase.SAVING, DrillPhase.SAVE_FAILED, DrillPhase.DONE)
        if (state.phase in terminal && previous !in terminal && practiceAudioOwned) {
            coachAudio?.playChime(ToneSynth.endChime())
            val audio = coachAudio
            mainHandler.postDelayed({ if (practiceAudioOwned && coachAudio === audio) stopPracticeAudio() }, 1200)
        }
        if (state.phase == DrillPhase.PAUSED) coachAudio?.takeIf { practiceAudioOwned }?.pausePractice()
    }

    // Runs: the lifecycle lives in RunController; these adapters connect it to the boots, the database and the phone.
    private val zeroTracker = ZeroTracker()
    private val modeSeen = mutableSetOf<String>()
    private val runFullSides = mutableSetOf<String>()
    private var runSessionId: String? = null
    private var runIsDemo = false
    private var runStartedMonitoring = false
    private var runTicks = 0
    private var lastRunId: String? = null
    @Volatile private var runCaptures: List<SensorCapture> = emptyList()
    private var runSidesAtStart = setOf<String>()
    private var runListener: ((RunState) -> Unit)? = null
    /** True only while a run's GPS is tracking; the foreground type includes location only then. */
    private var gpsTracking = false
    private val gps by lazy { GpsListener(this) { fix -> runController.onFix(fix) } }
    private val runBoots = object : RunBoots {
        override fun connected(side: String) = clients[side]?.isReady == true
        override fun productionMode(side: String): Boolean? = if (clients[side]?.isReady == true && side in modeSeen && !modeRequests.pending(side)) side in productionSides else null
        override fun modeChangePending(side: String): Boolean = modeRequests.pending(side)
        override fun zeroed(side: String) = zeroTracker.isZeroed(side)
        override fun flashRetained(side: String) = recorderStates[side]?.let { it.hasSession || it.recording } == true
        override fun flashFull(side: String) = side in runFullSides
        // A run sets the mode itself, so it goes straight to the boot and skips the app-side recording guard.
        override fun setProduction(side: String, production: Boolean): Boolean = requestMode(side, production, ownedRun = true)
        override fun zero(side: String) {
            zeroTracker.requested(side, System.currentTimeMillis())
            clients[side]?.command(RecorderCommand.ZERO)
        }
        override fun beginSession(demo: Boolean): String? {
            if (!demo && activeSessionId != null) return "A recording is already running. Stop it first."
            stopCoaching()  // a run supersedes any coaching already playing, including a demo, so synthetic turns cannot leak in
            runFullSides.clear()
            runIsDemo = demo
            runCaptures = emptyList()
            if (demo) {
                val id = UUID.randomUUID().toString()
                try { store.createSession(id, System.currentTimeMillis(), false, "synthetic", "run") } catch (error: Exception) {
                    storageError(error); return "Could not save the run on this phone."
                }
                runSessionId = id
                runSidesAtStart = emptySet()
            } else {
                startRecording(false, "run")
                runSessionId = activeSessionId
                if (runSessionId == null) return "Could not start recording. Check the phone's storage and that both boots are connected."
                runSidesAtStart = RunController.SIDES.filter { clients[it]?.isReady == true && recorderSupported[it] == true }.toSet()
            }
            val problem = startCoaching(CoachSettingsStore(this@SensorSessionService).settings, demo)
            if (problem != null) {
                // Coaching could not start (for example the earbuds went away): do not run without it.
                if (demo) runSessionId?.let { store.finishSession(it, System.currentTimeMillis()) } else stopRecording(endBurst = false)
                runSessionId = null
                return problem
            }
            coachAudio?.playChime(ToneSynth.startChime())
            lastRunId = runSessionId
            refreshRunCaptures()
            return null
        }
        override fun endSession() {
            coachAudio?.playChime(ToneSynth.endChime())
            stopCoaching()
            if (runIsDemo) runSessionId?.let { store.finishSession(it, System.currentTimeMillis()) } else stopRecording()
            refreshRunCaptures()
        }
        override fun saveProgress(side: String): Int? {
            val capture = runCaptures.firstOrNull { it.side == side }
            if (capture != null && (capture.state == "erased" || capture.state == "verified" || capture.state == "unavailable")) return 100
            return transfers[side]?.let { t -> (t.validator.next * 100L / maxOf(1, t.validator.expected)).toInt().coerceIn(0, 100) }
        }
        // Each boot that was recording at the start must have its own capture saved. Reading the captures happens off
        // the main thread (see refreshRunCaptures), so this never waits on the database.
        override fun flashSaved(): Boolean {
            if (runSessionId == null || activeSessionId != null || transfers.isNotEmpty()) return false
            return runSidesAtStart.all { side -> runCaptures.firstOrNull { it.side == side }?.state.let { it == "erased" || it == "unavailable" } }
        }
        override fun flashSaveFailed(): Boolean {
            if (runSessionId == null) return false
            return storageFailed || recoveryPaused || runCaptures.any { it.state == "lost" } ||
                runCaptures.any { it.state != "erased" && it.state != "unavailable" && (flashRetries[it.side] ?: 0) >= 3 }
        }
        override fun earbudsReady() = CoachAudio(this@SensorSessionService, {}, {}).headphonesConnected() ||
            CoachSettingsStore(this@SensorSessionService).settings.allowPhoneSpeaker
        override fun storageOk() = StatFs(filesDir.absolutePath).availableBytes >= 20L * 1024 * 1024
        override fun preparationIssues(): List<ReadinessIssue> = buildList {
            if (drillController.active || (activeSessionId != null && activeSessionId != runSessionId) || (coachState.running && !runActive()))
                add(ReadinessIssue(IssueKind.CAPTURE_CONFLICT, "Another practice, recording or coaching session is active. Return to it before starting a run.", PreparationAction.RETURN_TO_CAPTURE))
            if (activeSessionId == null) pendingRecoverySides.forEach { side ->
                add(ReadinessIssue(IssueKind.RETAINED_DATA, "${if (side == "L") "Left" else "Right"} boot recovery is pending. Finish recovery before starting.", PreparationAction.RECOVER, side))
            }
            RunController.SIDES.filter { progressStore.sensorAddress(it) != null && progressStore.mounting(it) == null }.forEach {
                add(ReadinessIssue(IssueKind.MOUNTING, "Choose the ${if (it == "L") "left" else "right"} sensor's mounting before dry-ski drills.", PreparationAction.MOUNTING, it, IssueSeverity.GUIDANCE))
            }
        }
    }
    private val runLocation = object : RunLocation {
        override val enabled: Boolean get() = GpsSettings(this@SensorSessionService).enabled
        override fun start(): Boolean {
            if (!gps.start()) return false
            gpsTracking = true
            // Re-announce the foreground service with the location type now that tracking is on. If the platform
            // refuses, the run goes ahead without GPS rather than failing to start.
            try { ensureForeground() } catch (_: Exception) {
                gps.stop(); gpsTracking = false
                return false
            }
            return true
        }
        override val problem: String?
            get() = when {
                !gps.permitted -> "location permission not granted"
                !gps.providerOn -> "location is off on this phone"
                else -> null
            }
        override fun stop() {
            gps.stop()
            if (gpsTracking) {
                gpsTracking = false
                if (foreground) try { ensureForeground() } catch (_: Exception) { /* keeps the previous notification */ }
            }
        }
    }
    private val runSink = object : RunSink {
        private fun write(block: (String) -> Unit) {
            val id = runSessionId ?: return
            io.execute { try { block(id) } catch (error: Exception) { mainHandler.post { if (!destroyed) storageError(error) } } }
        }
        override fun started(info: RunStartInfo) = write { store.saveRunStart(it, info) }
        override fun halfTurn(side: String, event: SkiEvent, receivedMs: Long) = write { store.addRunEvent(it, side, event, receivedMs) }
        override fun verdict(side: String, verdict: CoachVerdict, atMs: Long) = write { store.addRunVerdict(it, side, verdict, atMs) }
        override fun ended(info: RunEndInfo) = write { store.saveRunEnd(it, info) }
        override fun fix(fix: GpsFix) = write { store.addRunFix(it, fix) }
    }
    private val runController: RunController by lazy { RunController(runBoots, runSink, runLocation) { CoachSettingsStore(this).settings } }
    private val runTick = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            runController.tick(now)
            val state = runController.state(now)
            runListener?.invoke(state)
            when (state.phase) {
                RunPhase.RUNNING -> { if (++runTicks % 10 == 0) { ensureForeground(); refreshRunCaptures() } }
                RunPhase.SAVING -> { refreshRunCaptures(); if (++runTicks % 10 == 0) ensureForeground() }
                RunPhase.DONE, RunPhase.READY -> { runFinished(); return }
                else -> Unit
            }
            mainHandler.postDelayed(this, 500)
        }
    }
    private var uiVisible = false
    private var destroyed = false
    private var storageFailed = false
    private var recoveryPaused = false
        set(value) {
            if (field == value) return
            field = value
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("recovery_paused", value).apply()
        }
    private var lastInfoPoll = 0L
    private var lastStorageCheck = 0L
    private val orientationTrackers=mutableMapOf<String,BootOrientation.Tracker>()
    private val liveClocks=mutableMapOf<String,LiveSensorClock>()
    private val movementTrackers=mutableMapOf<String,DrySkiAnalysis.Tracker>()
    private val latestOrientation=mutableMapOf<String,BootRoll>()
    private var guidedTrial: GuidedTrial?=null
    private val progressStore by lazy { ProgressStore(this) }
    @Volatile private var corrections: Map<String, AccelCorrection?>? = null

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        recoveryPaused = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("recovery_paused", false)
        createNotificationChannel()
        activeSessionId = store.latestOpenSessionId()
        val interrupted = PracticeStore(store).interruptActive(System.currentTimeMillis())
        PracticeStore(store).list().lastOrNull()?.let(drillController::restoreSaved)
        if (interrupted.any { !it.demo && it.sessionId == activeSessionId }) {
            activeSessionId = null
        }
        testRecording=activeSessionId?.let { store.getSession(it)?.testSession } ?: false
        activeSessionId?.let { id ->
            // Reuse the decision made when the session started, not the switch as it is now.
            val phone = PhoneMotionRule.resumeDecision(store.eventMessages(id, "phone_motion "), testRecording, PhoneMotionSettings(this).enabled)
            if (phone) { logEvent(id, "phone_motion resumed after service restart"); startPhoneMotion(id, true) }
        }
        activeSessionId?.let { id -> store.getSession(id)?.let { session -> store.calibrations(session).forEach { c ->
            orientationTrackers[c.side]=BootOrientation.Tracker(c)
            movementTrackers[c.side]=DrySkiAnalysis.Tracker()
        } } }
        mainHandler.post(tick)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            if (activeSessionId != null) { ensureForeground(); acquireWakeLock() }
            when (intent?.action) {
                ACTION_MONITOR -> { monitoring = true; ensureForeground() }
                ACTION_STOP_MONITORING -> {
                    if (drillController.active) { finishDrill(); return START_STICKY }
                    monitoring = false
                    recoveryPaused = true
                    reconnectTasks.values.forEach(mainHandler::removeCallbacks)
                    reconnectTasks.clear()
                    clients.keys.toList().forEach { side ->
                        invalidate(side)
                        clients.remove(side)?.disconnect()
                        reportStatus(side, "Monitoring stopped")
                    }
                    settleForeground()
                }
                ACTION_START_RECORDING -> startRecording(intent.getBooleanExtra("test_session",false), drill = intent.getBooleanExtra(EXTRA_DRILL, false))
                ACTION_STOP_RECORDING -> if (drillController.active) finishDrill() else if (runController.phase() == RunPhase.RUNNING) stopRun() else stopRecording()
                ACTION_RESUME_DRILL -> if (drillController.active && !drillController.demo) { ensureForeground(); acquireWakeLock() } else if (!drillController.active) settleForeground()
                ACTION_RECOVER -> recoverFlash()
                ACTION_PAUSE_RECOVERY -> if (activeSessionId == null) pauseRecovery()
                ACTION_RESUME_RECORDING -> if (activeSessionId != null) { ensureForeground(); acquireWakeLock() } else settleForeground()
            }
            if (!recoveryPaused && !(drillController.active && drillController.demo)) restoreConnections()
        } catch (error: Exception) { storageError(error) }
        return if (drillController.active || monitoring || activeSessionId != null || store.hasPendingRecovery()) START_STICKY else START_NOT_STICKY
    }

    fun sessionId(): String? = activeSessionId
    /** True while a sensor recording or a run is in progress (resort downloads are refused then). Main thread. */
    fun recordingActive(): Boolean = activeSessionId != null || runActive() || drillController.active
    fun setUiVisible(visible: Boolean) {
        uiVisible = visible
        if (visible && !recoveryPaused) clients.keys.toList().forEach {
            if (recorderSupported[it] == true) requestInfo(it)
        }
    }
    fun setListener(value: Listener?) {
        listener = value
        if (value != null) {
            sensorStatuses.forEach { (side, message) -> value.onSensorStatus(side, message) }
            sensorInfo.forEach { (side, message) -> value.onSensorInfo(side, message) }
            latestOrientation.forEach { (side,point)->value.onBootOrientation(side,point) }
            guidedTrial?.let { value.onTestFeedback(it.description()) }
            value.onRecordingChanged(activeSessionId, if (activeSessionId != null) "Recording continues in background"
                else if (store.hasPendingRecovery()) "Live recording saved · sensor recovery pending" else "Ready to record")
        }
    }

    fun connect(side: String, device: BluetoothDevice) {
        if (!canAssign(side, device.address)) return
        recoveryPaused = false // Explicit assignment/connection resumes recovery; passive observers do not.
        startMonitoring()
        addresses[side] = device.address
        corrections = null
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("sensor_$side", device.address).apply()
        reconnectAttempts[side] = 0
        reconnectTasks.remove(side)?.let(mainHandler::removeCallbacks)
        connectAddress(side, device.address, device)
    }

    private fun assignedAddress(side: String): String? = addresses[side]
        ?: getSharedPreferences(PREFS, MODE_PRIVATE).getString("sensor_$side", null)

    fun canAssign(side: String, address: String): Boolean = AssignmentPolicy.canAssign(side, address,
        RunController.SIDES.associateWith(::assignedAddress), recordingActive() || store.hasPendingRecovery())

    fun restoreConnections() {
        listOf("L", "R").forEach { side ->
            val address = getSharedPreferences(PREFS, MODE_PRIVATE).getString("sensor_$side", null) ?: return@forEach
            startMonitoring()
            addresses[side] = address
            if (!clients.containsKey(side) && !reconnectTasks.containsKey(side)) connectAddress(side, address)
        }
    }

    fun resumeSavedConnections() { recoveryPaused = false; restoreConnections() }

    private fun startMonitoring() {
        if (monitoring) return
        monitoring = true
        try {
            startForegroundService(Intent(this, SensorSessionService::class.java).setAction(ACTION_MONITOR))
            ensureForeground()
        } catch (error: Exception) {
            monitoring = false
            throw error
        }
    }

    fun reconnectSavedSensor(side: String, device: BluetoothDevice) {
        if (getSharedPreferences(PREFS, MODE_PRIVATE).getString("sensor_$side", null) != device.address) return
        if (clients[side]?.isReady == true || sensorStatuses[side]?.startsWith("Connecting") == true ||
            sensorStatuses[side] == "Connected; discovering service") return
        connect(side, device)
    }

    fun forgetSensor(side: String): Boolean {
        if (recordingActive() || transfers.isNotEmpty() || store.hasPendingRecovery()) return false
        invalidate(side)
        clients.remove(side)?.disconnect()
        addresses.remove(side)
        reconnectTasks.remove(side)?.let(mainHandler::removeCallbacks)
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove("sensor_$side").apply()
        corrections = null
        reportStatus(side, "Not assigned")
        sensorInfo.remove(side)
        listener?.onSensorInfo(side, "Battery not reported")
        if (addresses.isEmpty()) { monitoring = false; settleForeground() }
        return true
    }

    fun swapSensors(): Boolean {
        if (recordingActive() || transfers.isNotEmpty() || store.hasPendingRecovery()) return false
        val left = assignedAddress("L")
        val right = assignedAddress("R")
        listOf("L", "R").forEach { invalidate(it); clients.remove(it)?.disconnect()
            reconnectTasks.remove(it)?.let(mainHandler::removeCallbacks) }
        val edit = getSharedPreferences(PREFS, MODE_PRIVATE).edit()
        if (right == null) edit.remove("sensor_L") else edit.putString("sensor_L", right)
        if (left == null) edit.remove("sensor_R") else edit.putString("sensor_R", left)
        edit.apply()
        corrections = null
        addresses.clear()
        sensorStatuses.clear()
        sensorInfo.clear()
        runCatching { restoreConnections() }.onFailure { reportStatus("L", "Assignments saved; connect boots to continue") }
        return true
    }

    private fun invalidate(side: String) {
        zeroTracker.disconnected(side)
        generations[side] = (generations[side] ?: 0) + 1
        waits.remove(side)?.let { mainHandler.removeCallbacks(it.timeout) }
        transfers.remove(side)
        transferTimeouts.remove(side)?.let(mainHandler::removeCallbacks)
    }

    private fun connectAddress(side: String, address: String, supplied: BluetoothDevice? = null) {
        invalidate(side)
        clients.remove(side)?.disconnect()
        recorderSupported.remove(side)
        recorderStates.remove(side)
        modeSeen.remove(side); modeRequests.clear(side)
        lastSampleTime.remove(side)
        batteryLevels.remove(side)
        batteryTimes.remove(side)
        rssiLevels.remove(side)
        clockSync.connecting(side, address)
        val remote = supplied ?: try {
            (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter?.getRemoteDevice(address)
        } catch (_: Exception) { null }
        if (remote == null) { reportStatus(side, "Bluetooth unavailable"); scheduleReconnect(side); return }
        val client = BleSensorClient(this,
            onStatus = { message -> reportStatus(side, message) },
            onSample = { sample ->
                val wasStale = (lastSampleTime[side]?.let { SystemClock.elapsedRealtime() - it > 3_000 } ?: false)
                lastSampleTime[side] = SystemClock.elapsedRealtime()
                if (wasStale) reportStatus(side, "Live stream ready")
                listener?.onSensorSample(side, sample)
                clockSync.onLiveSample(side, sample.sequence)
                val receivedAt=System.currentTimeMillis()
                activeSessionId?.let { id -> pendingSamples.add(Pending(id, RecordedSample(side, receivedAt, sample))) }
                acceptOrientation(side,receivedAt,sample)
            },
            onReady = { supported ->
                reconnectAttempts[side] = 0
                runController.retryRestore()
                lastSampleTime[side] = SystemClock.elapsedRealtime()
                recorderSupported[side] = supported
                updateInfo(side)
                if (supported && !recoveryPaused) requestInfo(side)
                clockSync.becameReady(side, supported)
            },
            onRecorder = { status -> handleRecorder(side, status) },
            onChunk = { chunk -> handleChunk(side, chunk) },
            onBattery = { level -> batteryLevels[side] = level
                if(level==null) batteryTimes.remove(side) else batteryTimes[side]=System.currentTimeMillis()
                updateInfo(side) },
            onRssi = { value -> rssiLevels[side]=value to System.currentTimeMillis(); updateInfo(side) },
            onMovement = { event -> listener?.onMovementEvent(side, event) },
            onSkiEvent = { event ->
                val skier = SkierFrame.of(side, event)
                listener?.onSkiEvent(side, skier)
                mainHandler.post {
                    runController.onHalfTurn(side, event, System.currentTimeMillis())   // stored as the boot sent it; stamped before the coach can stamp a verdict
                    coachSession?.onEvent(side, skier)
                }
            },
            onSkiState = { state ->
                if (state.production) productionSides.add(side) else productionSides.remove(side)
                modeSeen.add(side)
                zeroTracker.frame(side, state.zeroed, System.currentTimeMillis())
                listener?.onSkiState(side, SkierFrame.of(side, state))
            },
            onHeadingTurn = { turn -> listener?.onHeadingTurn(side, turn) },
            onMagnetometer = { frame -> activeSessionId?.let { id ->
                pendingMag.add(PendingMag(id, StoredMag(side, frame.timeMs, System.currentTimeMillis(), frame.x, frame.y, frame.z, frame.flags))) } },
            onPong = { pong, recvNs -> clockSync.onPong(side, pong, recvNs) },
            onDisconnected = {
                clockSync.disconnected(side)
                latestOrientation.remove(side)
                listener?.onBootOrientation(side,null)
                invalidate(side)
                clients.remove(side)
                if (activeSessionId != null) logEvent(activeSessionId!!, "$side boot disconnected; live samples unavailable until reconnect")
                if (!recoveryPaused) scheduleReconnect(side,sensorStatuses[side])
            })
        clients[side] = client
        try { client.connect(remote) } catch (_: SecurityException) {
            clients.remove(side)?.disconnect()
            reportStatus(side, "Bluetooth permission required")
        } catch (_: Exception) {
            clients.remove(side)?.disconnect()
            reportStatus(side, "Could not connect")
            scheduleReconnect(side)
        }
    }

    private fun scheduleReconnect(side: String, reason: String?=null) {
        val address = addresses[side] ?: return
        reconnectTasks.remove(side)?.let(mainHandler::removeCallbacks)
        val attempt = (reconnectAttempts[side] ?: 0) + 1
        reconnectAttempts[side] = attempt
        val delay = (2_000L shl (attempt - 1).coerceAtMost(4)).coerceAtMost(30_000L)
        reportStatus(side, "${reason ?: "Disconnected"} · reconnecting in ${delay / 1000}s")
        val task = Runnable { reconnectTasks.remove(side); connectAddress(side, address) }
        reconnectTasks[side] = task
        mainHandler.postDelayed(task, delay)
    }

    private fun send(side: String, opcode: Int, action: (RecorderStatus) -> Unit) {
        if (waits.containsKey(side) || clients[side]?.isReady != true) return
        val timeout = Runnable { retryFlash(side, "Sensor command timed out; flash retained") }
        waits[side] = Wait(opcode, timeout, action)
        if (clients[side]?.command(opcode) == true) mainHandler.postDelayed(timeout, 10_000)
        else { waits.remove(side); retryFlash(side, "Recorder unavailable; flash retained") }
    }

    private fun requestInfo(side: String) {
        if (transfers.containsKey(side) || waits.containsKey(side) || recoveryPaused) return
        send(side, RecorderCommand.INFO) { state -> reconcile(side, state) }
    }

    private fun handleRecorder(side: String, status: RecorderStatus) {
        recorderStates[side] = status
        if (status.production) productionSides.add(side) else productionSides.remove(side)
        modeSeen.add(side)
        if (status.opcode == (RecorderCommand.SET_MODE or 0x80))
            modeRequests.acknowledged(side, status.result, status.production)
        if (runSessionId != null && status.full) runFullSides.add(side)
        updateInfo(side)
        if (status.opcode == 0x85) {
            val transfer = transfers[side] ?: return
            transferTimeouts.remove(side)?.let(mainHandler::removeCallbacks)
            if (!transfer.validator.complete(status)) { retryFlash(side, "Incomplete download; flash retained"); return }
            transfer.validating = true
            // All chunk writes have already been queued on this same executor.
            io.execute {
                try {
                    val verified = store.validateDownload(transfer.capture.id, status.samples, status.dropped, transfer.validator.recordFormat)
                    mainHandler.post {
                        if (destroyed || generations[side] != transfer.generation || transfers[side] !== transfer) return@post
                        transfers.remove(side)
                        if (!verified || storageFailed) { retryFlash(side, "Saved copy failed validation; flash retained"); return@post }
                        listener?.onHistoryChanged()
                        // Recheck the frozen snapshot before erasing, on this same connection.
                        send(side, RecorderCommand.INFO) { current ->
                            if (current.result != 0 || current.recording || current.samples != status.samples || current.storageError) {
                                retryFlash(side, "Sensor state changed; flash retained")
                            } else send(side, RecorderCommand.ERASE) { erased ->
                                if (erased.result == 0 && !erased.hasSession && !erased.recording) {
                                    store.updateCapture(transfer.capture.id, "erased", status.samples, status.dropped)
                                    captures.remove(side)
                                    flashRetries[side] = 0
                                    reportStatus(side, "Live stream ready")
                                    listener?.onHistoryChanged()
                                    reconcile(side, erased)
                                    settleForeground()
                                } else retryFlash(side, "Erase not acknowledged; saved copy retained")
                            }
                        }
                    }
                } catch (error: Exception) { mainHandler.post { if (!destroyed) storageError(error) } }
            }
            return
        }
        val waiting = waits[side] ?: return
        if (waiting.opcode != status.opcode) return
        waits.remove(side)
        mainHandler.removeCallbacks(waiting.timeout)
        try { waiting.action(status) } catch (error: Exception) { storageError(error) }
    }

    private fun reconcile(side: String, state: RecorderStatus) {
        if (state.result != 0 || !state.storageReady) {
            reportStatus(side, "Live only · sensor flash unavailable")
            return
        }
        val address = addresses[side] ?: return
        if (!foreground && !uiVisible && (state.hasSession || state.recording || activeSessionId != null)) {
            reportStatus(side, "Sensor flash pending · open the app to recover")
            return
        }
        var capture = store.pendingCapture(address)
        if (state.hasSession || state.recording) {
            if (capture == null) {
                // An unknown retained recording gets its own history entry, never mixed into a new run.
                val id = UUID.randomUUID().toString()
                val now = System.currentTimeMillis()
                store.createSession(id, now - state.samples * 10L)
                store.finishSession(id, now)
                store.editSession(id, "Recovered $side sensor", "Original recording time unknown; timeline is approximate.", 0, 0, 0)
                capture = store.createCapture(id, side, address, now - state.samples * 10L, "pending")
                logEvent(id, "Imported retained sensor recording; original session identity unavailable")
            }
            captures[side] = capture
            if (state.recording && capture.sessionId == activeSessionId) {
                store.updateCapture(capture.id, "recording", state.samples, state.dropped)
                if (state.storageError) reportStatus(side, "Sensor flash error · live recording continues")
                return
            }
            if (capture.sessionId == activeSessionId && state.full) {
                reportStatus(side, "Sensor flash full · live recording continues")
                return
            }
            ensureForeground()
            if (state.recording) {
                send(side, RecorderCommand.STOP) { stopped ->
                    if (stopped.result == 0 && !stopped.recording) beginDownload(side, capture, stopped)
                    else retryFlash(side, "Sensor stop failed; flash retained")
                }
            } else beginDownload(side, capture, state)
            return
        }
        if (capture != null) {
            if (capture.state == "requested" && capture.sessionId == activeSessionId) {
                startSensor(side, capture)
                return
            }
            // An erase may have succeeded just before a disconnect. A verified copy stays on the phone.
            store.updateCapture(capture.id, if (capture.state == "verified") "erased" else "lost",
                capture.expected, capture.dropped, if (capture.state == "verified") "" else "Sensor no longer has this recording")
            if (capture.state != "verified") logEvent(capture.sessionId, "$side sensor flash missing after reconnect; live samples retained")
            captures.remove(side)
            listener?.onHistoryChanged()
        }
        val id = activeSessionId
        if (id != null && !storageFailed) {
            // Each reboot/flash-capacity segment receives a separate capture row.
            val next = store.createCapture(id, side, address, System.currentTimeMillis())
            captures[side] = next
            startSensor(side, next)
        } else settleForeground()
    }

    private fun startSensor(side: String, capture: SensorCapture) {
        send(side, RecorderCommand.START) { state ->
            if (state.result == 0 && state.recording) {
                store.updateCapture(capture.id, "recording", state.samples, state.dropped)
                logEvent(capture.sessionId, "$side sensor flash recording started")
                // The phone may have stopped recording while START was in flight.
                if (activeSessionId != capture.sessionId) requestInfo(side)
            } else {
                store.updateCapture(capture.id, "unavailable", state.samples, state.dropped, "Start rejected (${state.result})")
                captures.remove(side)
                reportStatus(side, "Live only · sensor recorder could not start")
                if (state.hasSession) requestInfo(side)
            }
        }
    }

    private fun beginDownload(side: String, capture: SensorCapture, state: RecorderStatus) {
        if (recoveryPaused || storageFailed || (flashRetries[side] ?: 0) >= 3) return
        if (state.storageError) { reportStatus(side, "Sensor storage error · flash retained"); return }
        if (state.samples == 0 && !state.hasSession && !state.recording) {
            store.updateCapture(capture.id, "erased")
            captures.remove(side)
            listener?.onHistoryChanged()
            requestInfo(side)
            settleForeground()
            return
        }
        val generation = generations[side] ?: return
        val transfer = Transfer(capture, TransferValidator(state.samples), generation)
        transfers[side] = transfer
        store.updateCapture(capture.id, "pending", state.samples, state.dropped)
        ensureForeground()
        acquireWakeLock()
        reportStatus(side, "Recovering sensor flash · 0 / ${state.samples}")
        io.execute {
            try {
                store.beginDownload(capture.id)
                mainHandler.post {
                    if (destroyed || generations[side] != generation || transfers[side] !== transfer) return@post
                    send(side, RecorderCommand.DOWNLOAD) { response ->
                        if (response.result != 0 || response.samples != state.samples || response.recording) {
                            retryFlash(side, "Download rejected; flash retained")
                        } else armTransferTimeout(side)
                    }
                }
            } catch (error: Exception) { mainHandler.post { if (!destroyed) storageError(error) } }
        }
    }

    private fun handleChunk(side: String, chunk: FlashChunk?) {
        val transfer = transfers[side] ?: return
        if (chunk == null || !transfer.validator.accept(chunk)) {
            retryFlash(side, "Download gap or invalid record; restarting safely")
            return
        }
        armTransferTimeout(side)
        io.execute {
            try {
                // The record format travels with the transfer and is committed by validateDownload.
                store.saveDownload(transfer.capture.id, chunk)
            }
            catch (error: Exception) { mainHandler.post { if (!destroyed) storageError(error) } }
        }
        if (transfer.validator.next % 500 < chunk.records.size || transfer.validator.next == transfer.validator.expected) {
            reportStatus(side, "Recovering sensor flash · ${transfer.validator.next} / ${transfer.validator.expected}")
        }
    }

    private fun armTransferTimeout(side: String) {
        transferTimeouts.remove(side)?.let(mainHandler::removeCallbacks)
        val task = Runnable { retryFlash(side, "Download stalled; flash retained") }
        transferTimeouts[side] = task
        mainHandler.postDelayed(task, 15_000)
    }

    private fun retryFlash(side: String, message: String) {
        val attempts = (flashRetries[side] ?: 0) + 1
        flashRetries[side] = attempts
        val capture = captures[side]
        if (capture != null) store.updateCapture(capture.id, "pending", recorderStates[side]?.samples ?: capture.expected,
            recorderStates[side]?.dropped ?: capture.dropped, message)
        invalidate(side)
        clients.remove(side)?.disconnect()
        reportStatus(side, if (attempts >= 3) "$message · tap Recover sensor flash to retry" else message)
        // Restore the live link even if automatic recovery has reached its retry limit.
        scheduleReconnect(side)
        settleForeground()
    }

    fun recoverFlash() {
        recoveryPaused = false
        storageFailed = false
        flush()
        flashRetries.clear()
        restoreConnections()
        clients.keys.toList().forEach { side -> if (recorderSupported[side] == true) requestInfo(side) }
        if (store.hasPendingRecovery()) ensureForeground()
    }

    private fun pauseRecovery() {
        recoveryPaused = true
        monitoring = false
        listOf("L", "R").forEach { side ->
            invalidate(side)
            clients.remove(side)?.disconnect()
            reconnectTasks.remove(side)?.let(mainHandler::removeCallbacks)
            reportStatus(side, "Recovery paused · flash retained")
        }
        releaseWakeLock()
        if (foreground) stopForeground(STOP_FOREGROUND_REMOVE)
        foreground = false
        stopSelf()
    }


    /** Accelerometer correction for the sensor currently assigned to [side]; loaded once and refreshed on reassignment. */
    private fun correctionFor(side: String): AccelCorrection? =
        (corrections ?: listOf("L", "R").associateWith { progressStore.correctionForSide(it) }.also { corrections = it })[side]

    /** Called by Bench tools after it saves or clears a correction. */
    fun reloadCorrections() { corrections = null }

    fun isTestRecording() = activeSessionId!=null && testRecording

    fun startGuidedTrial(pace: String): String {
        if (drillController.active) return "A practice set is active. Finish it first."
        if(!isTestRecording()) return "Start a test recording first"
        val side=listOf("L","R").firstOrNull { latestOrientation[it]?.let { p ->
            System.currentTimeMillis()-p.timeMs<1000 && kotlin.math.abs(p.degrees)<3
        }==true } ?: return "Calibrate a boot, return to neutral and wait for live orientation"
        val now=System.currentTimeMillis()
        guidedTrial=GuidedTrial(side,pace,now.toDouble())
        movementTrackers.keys.toList().forEach { movementTrackers[it]=DrySkiAnalysis.Tracker() }
        markTest("START_TEST",now,"Guided $pace set: 20 movements, counted from $side boot")
        return guidedTrial!!.description()
    }

    private fun acceptOrientation(side: String, receivedAt: Long, sample: SensorSample) {
        val time=liveClocks.getOrPut(side) { LiveSensorClock() }.timestamp(sample.timestampMs,receivedAt)
        val tracker=orientationTrackers[side] ?: return
        val roll=tracker.accept(TimelinePoint(side,time,correctionFor(side)?.apply(sample) ?: sample,"live"))
        if(roll==null) { latestOrientation.remove(side); listener?.onBootOrientation(side,null); return }
        latestOrientation[side]=roll
        listener?.onBootOrientation(side,roll)
        drillController.accept(roll)
        val movement=movementTrackers.getOrPut(side) { DrySkiAnalysis.Tracker() }.accept(roll) ?: return
        val trial=guidedTrial
        if(trial!=null && trial.side==side && trial.accept(movement)) {
            listener?.onTestFeedback(trial.description())
            if(trial.complete) markTest("PAUSE",receivedAt,"Guided ${trial.pace} set complete")
        } else if(trial==null) listener?.onTestFeedback("$side completed movement · ${String.format(java.util.Locale.US,"%.1f",movement.peakRoll)}° peak boot roll")
    }

    fun markTest(label: String, timeMs: Long=System.currentTimeMillis(), note: String=""): Boolean {
        val id=activeSessionId ?: return false
        if(!testRecording) return false
        if(label=="PAUSE") guidedTrial=null
        io.execute { try { store.addMarker(id,timeMs,label,note=note) }
            catch(error: Exception) { mainHandler.post { if(!destroyed) storageError(error) } } }
        return true
    }

    // Runs ----------------------------------------------------------------------------------------------

    private fun runPhase(): RunPhase = runController.phase()
    private fun runActive() = runPhase().let { it == RunPhase.ZEROING || it == RunPhase.RUNNING || it == RunPhase.SAVING }

    fun runState(): RunState = runController.state(System.currentTimeMillis())
    fun setRunListener(value: ((RunState) -> Unit)?) { runListener = value }

    /** The mode of each boot as last reported: true on snow, false training, null not known yet. */
    fun bootModes(): Map<String, Boolean?> = RunController.SIDES.associateWith { runBoots.productionMode(it) }

    /** Starts a run. Returns null if it started, otherwise the reason it could not. */
    fun startRun(demo: Boolean): String? {
        if (drillController.active) return "A practice set is active. Return to it or finish it first."
        if (!demo && (transfers.isNotEmpty() || store.hasPendingRecovery())) return "Finish sensor recovery before starting a run. Open preparation and recovery."
        val reason = runController.start(System.currentTimeMillis(), demo)
        if (reason == null) {
            runStartedMonitoring = !monitoring
            try { startMonitoring() } catch (error: Exception) {
                runController.cancel()
                return "Could not keep the run going in the background."
            }
            runTicks = 0
            mainHandler.removeCallbacks(runTick)
            mainHandler.post(runTick)
        }
        runListener?.invoke(runState())
        return reason
    }

    /** Reads the run's captures on the database thread, so the main thread never waits on the store. */
    private fun refreshRunCaptures() {
        val id = runSessionId ?: return
        io.execute { try { runCaptures = store.captures(id) } catch (_: Exception) { /* keep the last snapshot */ } }
    }

    /** The run most recently started, for the Done screen's comparison. */
    fun lastRunId(): String? = lastRunId

    /** Builds a run's comparison off the main thread; the callback gets null if the run is not found. */
    fun loadRunComparison(id: String, callback: (RunComparison?) -> Unit) {
        io.execute {
            val result = try {
                val session = store.getSession(id)
                if (session == null || session.kind != "run") null else RunComparisons.build(store.runData(id), session.origin == "synthetic")
            } catch (error: Exception) { null }
            mainHandler.post { if (!destroyed) callback(result) }
        }
    }

    fun cancelRun() { runController.cancel(); runFinished(); runListener?.invoke(runState()) }
    fun retryZero() { runController.retryZero(System.currentTimeMillis()); runListener?.invoke(runState()) }
    fun stopRun() { runController.stop(System.currentTimeMillis()); runListener?.invoke(runState()) }
    /** Stop waiting for the boots' data; recovery carries on in the background and the boot modes are left as they are. */
    fun abandonRunSave() { runController.abandonSave(System.currentTimeMillis()); runListener?.invoke(runState()) }
    fun resetRun() { runController.reset(); runListener?.invoke(runState()) }
    fun setOnSnowMode(production: Boolean) {
        if (recordingActive() || transfers.isNotEmpty() || store.hasPendingRecovery()) return
        runController.setOnSnowMode(production); runListener?.invoke(runState())
    }

    private fun runFinished() {
        if (runStartedMonitoring) { monitoring = false; runStartedMonitoring = false }
        if (runController.phase() != RunPhase.RUNNING) { runSessionId = null }
        settleForeground()
    }

    // Coaching ------------------------------------------------------------------------------------------

    fun coachState(): CoachState = coachState
    fun setCoachListener(value: ((CoachState) -> Unit)?) { coachListener = value }

    private fun publishCoach(state: CoachState) {
        coachState = state
        coachListener?.invoke(state)
        if (foreground) ensureForeground()  // refresh the notification text
    }

    /**
     * Starts the metronome and section chirps. With [demo] on, synthetic half-turns drive the coach so it can be
     * heard without boots. Returns a reason if it could not start, or null if it started.
     */
    fun startCoaching(settings: CoachSettings, demo: Boolean): String? {
        if (drillController.active) return "A practice set is active. Finish it first."
        if (coachState.running) return null
        stopPracticeAudio()
        previewAudio?.stop(); previewAudio = null
        val chosen = settings.normalised()
        val target = chosen.target()
        val audio = CoachAudio(this,
            onRoute = { headphones -> mainHandler.post { publishCoach(coachState.copy(paused = !headphones,
                message = if (headphones) "Coaching resumed." else "Coaching paused: earbuds disconnected.")) } },
            onProblem = { text -> mainHandler.post { stopCoaching(); publishCoach(CoachState(message = text)) } })
        audio.start(chosen, target)?.let { return it }
        val wasMonitoring = monitoring
        try {
            startMonitoring()  // keeps this service in the foreground with the screen off
        } catch (error: Exception) {
            audio.stop()
            return "Could not keep coaching running in the background."
        }
        acquireWakeLock()
        coachStartedMonitoring = !wasMonitoring
        coachSettings = chosen
        coachAudio = audio
        coachStartedAt = SystemClock.elapsedRealtime()
        coachSession = CoachSession(chosen) { side, verdict ->
            onCoachVerdict(verdict)
            runController.onVerdict(side, verdict, System.currentTimeMillis())
        }
        if (demo) {
            coachDemo = DemoCoachFeed(target)
            mainHandler.postDelayed(coachDemoStep, (target.beatSeconds * 1000).toLong())
        }
        publishCoach(CoachState(running = true, demo = demo, target = target,
            message = when {
                demo -> "Coaching on demo boots."
                clients.values.none { it.isReady } -> "No boot connected yet. You will hear the metronome, and chirps start once a boot sends turns."
                else -> "Coaching. Start skiing when you hear the first ticks."
            }))
        return null
    }

    fun stopCoaching() {
        mainHandler.removeCallbacks(coachDemoStep)
        coachDemo = null
        coachSession = null
        coachAudio?.stop()
        coachAudio = null
        if (!coachState.running) return
        publishCoach(CoachState(message = "Coaching stopped."))
        // Coaching turned monitoring on only if it was off; put it back so the service can stop when nothing else needs it.
        if (coachStartedMonitoring) { monitoring = false; coachStartedMonitoring = false }
        settleForeground()
    }

    fun playCoachTestSounds(settings: CoachSettings) {
        if (drillController.active || coachState.running) return
        previewAudio?.stop()
        val audio = CoachAudio(this, onRoute = {}, onProblem = { text -> mainHandler.post { coachListener?.invoke(CoachState(message = text)) } })
        previewAudio = audio
        audio.playTestSounds(settings.normalised())
        mainHandler.postDelayed({ if (previewAudio === audio) { audio.stop(); previewAudio = null } }, 6000)
    }

    fun playPracticeTestSounds(settings: CoachSettings): String? {
        if (drillController.active || coachState.running || runActive()) return "Finish active practice or coaching before testing sounds."
        stopPracticeAudio()
        previewAudio?.stop(); previewAudio = null
        val audio = CoachAudio(this, onRoute = {}, onProblem = {})
        previewAudio = audio
        val error = audio.startPractice(settings.normalised().copy(metronome = false), settings.normalised().target()) {}
        if (error != null) { audio.stop(); previewAudio = null; return error }
        audio.playPracticeExamples()
        mainHandler.postDelayed({ if (previewAudio === audio) { audio.stop(); previewAudio = null } }, 8000)
        return null
    }
    fun stopPracticeSoundPreview() { previewAudio?.stop(); previewAudio = null }

    private fun onCoachVerdict(verdict: CoachVerdict) {
        val target = coachState.target ?: return
        val countInMs = (COUNT_IN_BEATS * target.beatSeconds * 1000).toLong()
        val counting = coachSettings.metronome && coachSettings.countIn && SystemClock.elapsedRealtime() - coachStartedAt < countInMs
        if (coachSettings.chirps && !counting && !coachState.paused) coachAudio?.chirp(verdict.verdict)
        publishCoach(coachState.copy(last = verdict))
    }

    /** Boots with a live, ready link; only these can take a command. */
    fun connectedSides(): List<String> = clients.filterValues { it.isReady }.keys.sorted()
    fun canConfigureMounting(): Boolean = !recordingActive() && !coachState.running

    /** Ask a boot to recapture its neutral pose from the next still second. Stand upright and still first. */
    fun zeroSensor(side: String): Boolean {
        if (recordingActive() || transfers.isNotEmpty() || store.hasPendingRecovery()) return false
        zeroTracker.requested(side, System.currentTimeMillis())
        return clients[side]?.command(RecorderCommand.ZERO) == true
    }

    /** Production turns the boot's Wi-Fi and raw stream off to save battery; diagnostics turns them back on. */
    fun setSensorMode(side: String, production: Boolean): Boolean {
        return requestMode(side, production, ownedRun = false)
    }

    private fun requestMode(side: String, production: Boolean, ownedRun: Boolean): Boolean {
        if ((!ownedRun && recordingActive()) || transfers.isNotEmpty() || store.hasPendingRecovery()) return false
        if (clients[side]?.isReady != true) return false
        val now = SystemClock.elapsedRealtime()
        if (!modeRequests.shouldSend(side, production, now)) return true
        val queued = clients[side]?.command(RecorderCommand.SET_MODE, if (production) 1 else 0) == true
        if (queued) modeRequests.queued(side, production, now)
        return queued
    }

    /** Main-thread snapshot of runtime facts; persisted captures are read on the existing I/O executor. */
    fun loadPreparation(sessionId: String? = null, callback: (Result<PreparationSnapshot>) -> Unit) {
        val boots = RunController.SIDES.map { side -> BootPreparation(side, progressStore.sensorAddress(side),
            clients[side]?.isReady == true, progressStore.mounting(side)?.label(progressStore.landmarks()),
            runBoots.productionMode(side), zeroTracker.isZeroed(side), batteryLevels[side]) }
        val progress = transfers.values.associate { t -> t.capture.id to (t.validator.next * 100L / maxOf(1, t.validator.expected)).toInt() }
        val validating = transfers.values.filter { it.validating }.map { it.capture.id }.toSet()
        val failed = flashRetries.filterValues { it >= 3 }.keys.toSet()
        val paused = recoveryPaused
        val storage = storageFailed
        val active = activeSessionId
        val busy = recordingActive() || coachState.running
        io.execute {
            val result = runCatching {
                val items = store.recoveryCaptures(sessionId).map { c -> RecoverySummary.item(c, progress[c.id], c.id in validating,
                    paused, storage || c.side in failed, active, store.hasCommittedCopy(c.id)) }
                items to store.pendingRecoverySides()
            }
            mainHandler.post { if (!destroyed) {
                result.onSuccess { pendingRecoverySides = it.second }
                callback(result.mapCatching { (items, _) -> PreparationSnapshot(boots, runController.readiness(), RecoveryReport(items), busy) })
            } }
        }
    }

    /** Why production mode cannot be entered right now, or null if it can. */
    fun productionBlockedReason(): String? =
        if (store.hasPendingRecovery()) "Finish sensor recovery before changing boot mode."
        else ProductionModeRule.blockedReason(recordingActive(), transfers.isNotEmpty() || waits.isNotEmpty())

    fun calibrateTest(side: String, axis: Int, sign: Int, completed: (String)->Unit) {
        if (drillController.active) { completed("A practice set owns calibration. Finish it first."); return }
        calibrateTestResult(side, axis, sign) { result ->
            completed(result.fold({ "$side boot calibrated · ready for movements" }, { it.message ?: "Calibration failed" }))
        }
    }

    private fun calibrateTestResult(side: String, axis: Int, sign: Int, completed: (Result<Unit>)->Unit) {
        val id=activeSessionId
        if(id==null || !testRecording) { completed(Result.failure(IllegalStateException("Start a test session first"))); return }
        flush()
        io.execute {
            try {
                val data=store.loadData(id, includeSync = false) ?: error("Session unavailable")
                val timeline=SessionAnalysis.build(data).timeline.corrected(::correctionFor)
                val end=timeline.filter { it.side==side }.maxOfOrNull { it.timeMs } ?: error("No samples for this boot")
                if(System.currentTimeMillis()-end>2000) error("Boot stream is stale")
                val calibration=BootOrientation.calibrate(timeline,side,end-2000,axis,sign)
                    ?: error("Hold the boot neutral and still for two seconds; check the forward axis")
                store.saveCalibration(id,calibration)
                mainHandler.post { if(!destroyed && activeSessionId==id) {
                    orientationTrackers[side]=BootOrientation.Tracker(calibration.copy(timeMs=System.currentTimeMillis().toDouble()))
                    movementTrackers[side]=DrySkiAnalysis.Tracker()
                    completed(Result.success(Unit))
                } }
            } catch(error: Exception) { mainHandler.post { if(!destroyed) completed(Result.failure(error)) } }
        }
    }

    fun learnTestMounting(side: String, completed: (String)->Unit) {
        if (drillController.active) { completed("A practice set owns calibration. Finish it first."); return }
        val id=activeSessionId
        if(id==null || !testRecording) { completed("Start a test session first"); return }
        flush()
        io.execute {
            try {
                val data=store.loadData(id, includeSync = false) ?: error("Session unavailable")
                val calibration=store.calibrations(data.session).firstOrNull { it.side==side } ?: error("Calibrate neutral first")
                val timeline=SessionAnalysis.build(data).timeline.corrected(::correctionFor)
                val end=timeline.filter { it.side==side }.maxOfOrNull { it.timeMs } ?: error("No boot samples")
                if(System.currentTimeMillis()-end>2000) error("Boot stream is stale")
                val refined=BootOrientation.learnForward(timeline,calibration,end-5000)
                    ?: error("Need five seconds of pure side-to-side roll with little pitch/yaw; check the toe direction hint")
                store.saveCalibration(id,refined)
                mainHandler.post { if(!destroyed && activeSessionId==id) {
                    // The gesture ends in neutral before live tracking is restarted.
                    orientationTrackers[side]=BootOrientation.Tracker(refined.copy(timeMs=System.currentTimeMillis().toDouble()),awaitRest=true)
                    movementTrackers[side]=DrySkiAnalysis.Tracker()
                    completed("$side forward axis learned · return neutral and hold for two seconds")
                } }
            } catch(error: Exception) { mainHandler.post { if(!destroyed) completed(error.message ?: "Mounting calibration failed") } }
        }
    }

    /** [drill] marks a drill's test recording, which follows the phone motion switch (garden trials T3 and T4). */
    private fun startRecording(testSession: Boolean=false, kind: String="session", drill: Boolean = false,
                               drillOwner: String? = null, requestedId: String? = null) {
        if (drillController.active && drillOwner != drillController.id) {
            listener?.onRecordingChanged(activeSessionId, "A practice set is active. Finish it first."); return
        }
        if (activeSessionId != null) { ensureForeground(); return }
        recoveryPaused = false
        ensureForeground() // Meet the foreground-service deadline before opening the database.
        val available = StatFs(filesDir.absolutePath).availableBytes
        if (available < 20L * 1024 * 1024 || clients.values.none { it.isReady }) {
            listener?.onRecordingChanged(null, if (available < 20L * 1024 * 1024) "Phone storage too low to record"
                else "Wait for a sensor's live stream before recording")
            settleForeground()
            return
        }
        val inProduction = clients.filterValues { it.isReady }.keys.filter { it in productionSides }
        ProductionModeRule.recordingBlockedReason(inProduction, run = kind == "run")?.let { reason ->
            listener?.onRecordingChanged(null, reason)
            settleForeground()
            return
        }
        try {
            storageFailed = false
            val id = requestedId ?: UUID.randomUUID().toString()
            store.createSession(id, System.currentTimeMillis(),testSession, kind = kind)
            testRecording=testSession
            orientationTrackers.clear(); movementTrackers.clear(); latestOrientation.clear(); guidedTrial=null
            activeSessionId = id
            acquireWakeLock()
            startClockSync(id)
            // The switch is read once, here; the decision is logged so a service restart can reuse it.
            val phone = PhoneMotionRule.recordsPhone(PhoneMotionSettings(this).enabled, testSession, drill)
            logEvent(id, PhoneMotionRule.decisionEvent(phone))
            startPhoneMotion(id, phone)
            listener?.onRecordingChanged(id, if(testSession) "Test recording · hold neutral for two seconds, then calibrate" else "Recording · continues with screen off")
            clients.keys.toList().forEach { side -> if (recorderSupported[side] == true) requestInfo(side) }
        } catch (error: Exception) { storageError(error) }
    }

    /** Stores the clock reference and the recent pings with the new session, then pings both boots (interleaved). */
    private fun startClockSync(id: String) {
        val recent = clockSync.recentPairs()
        logEvent(id, "clock_ref wall_ms=${System.currentTimeMillis()} elapsed_ns=${SystemClock.elapsedRealtimeNanos()}")
        io.execute { try { store.addClockPairs(id, recent) } catch (error: Exception) { mainHandler.post { if (!destroyed) storageError(error) } } }
        clockSync.recordingStarted(SystemClock.elapsedRealtime())
        clockSync.burst(clients.keys.toList())
    }

    /**
     * Starts the phone's own motion sensors for this session when [record] (decided once, when the session started:
     * PhoneMotionRule) is true. A failure only skips the phone source.
     */
    private fun startPhoneMotion(id: String, record: Boolean) {
        phoneBuffer.drain() // rows left over from a session that ended in a storage error belong to no live session
        phoneSessionId = null
        if (!record) return
        try {
            val recorder = PhoneMotionRecorder(this, phoneBuffer) { line ->
                // Called on the sensor thread; the service may already be shutting its executor down.
                try { logEvent(id, line) } catch (_: java.util.concurrent.RejectedExecutionException) {}
            }
            if (recorder.start()) { phoneRecorder = recorder; phoneSessionId = id } else logEvent(id, "phone_motion unavailable")
        } catch (error: Exception) { logEvent(id, "phone_motion failed: ${error.message}") }
    }

    private fun stopPhoneMotion() {
        phoneRecorder?.stop()
        phoneRecorder = null
    }

    private fun flushPhone() {
        val id = phoneSessionId ?: return
        val batch = phoneBuffer.drain()
        if (phoneRecorder == null) phoneSessionId = null
        if (batch.isEmpty()) return
        io.execute {
            try { store.addPhoneSamples(id, batch) }
            catch (error: Exception) { mainHandler.post { if (!destroyed) { phoneBuffer.restore(batch); storageError(error) } } }
        }
    }

    /** Stop, end burst and save sequence (pure, StopSequencer); [pendingSave] is its armed timer. */
    private val stopSequencer = StopSequencer()
    private var pendingSave: Runnable? = null

    private fun cancelPendingSave() { pendingSave?.let(mainHandler::removeCallbacks); pendingSave = null }

    /**
     * Stops the recording. With [endBurst] a full recording or run first gets a short ping burst while the boots are
     * still connected (EndBurstPlanner, about 3 s), and the save and flash recovery follow at its fixed deadline. The
     * session end is the time of this call either way.
     */
    private fun stopRecording(endBurst: Boolean = true) {
        val id = activeSessionId ?: return
        val now = System.currentTimeMillis()
        val action = if (endBurst) stopSequencer.stop(id, now) { clockSync.endBurst(id, testRecording, clients.keys.toList()) }
            else stopSequencer.stopNow(now)
        when (action) {
            is StopSequencer.Action.Wait -> {
                cancelPendingSave()
                lateinit var save: Runnable
                save = Runnable {
                    if (pendingSave === save) pendingSave = null
                    stopSequencer.deadline(action.token, activeSessionId)?.let { finishRecording(it) }
                }
                pendingSave = save
                mainHandler.postDelayed(save, action.delayMs)
                listener?.onRecordingChanged(id, "Stopping · syncing boot clocks for a few seconds, then saving")
            }
            is StopSequencer.Action.SaveNow -> finishRecording(action.stopAtMs)
            StopSequencer.Action.Ignore -> Unit
        }
    }

    private fun finishRecording(stopAtMs: Long) {
        cancelPendingSave()
        val id = activeSessionId ?: return
        activeSessionId = null
        guidedTrial=null
        stopPhoneMotion()
        flush()
        io.execute {
            try {
                store.finishSession(id, stopAtMs)
                mainHandler.post {
                    if (destroyed) return@post
                    listener?.onRecordingChanged(null, "Live session saved · recovering sensor flash")
                    listener?.onHistoryChanged()
                    clients.keys.toList().forEach { side -> if (recorderSupported[side] == true) requestInfo(side) }
                    settleForeground()
                }
            } catch (error: Exception) { mainHandler.post { if (!destroyed) storageError(error) } }
        }
    }

    private fun flushMag() {
        if (pendingMag.isEmpty()) return
        val batch = pendingMag.toList()
        pendingMag.clear()
        io.execute {
            try { batch.groupBy { it.sessionId }.forEach { (id, rows) -> store.addMagSamples(id, rows.map { it.row }) } }
            catch (error: Exception) { mainHandler.post { if (!destroyed) { pendingMag.addAll(0, batch); storageError(error) } } }
        }
    }

    private fun flush() {
        if (!storageFailed) { flushMag(); flushPhone() }
        if (pendingSamples.isEmpty() || storageFailed) return
        val batch = pendingSamples.toList()
        pendingSamples.clear()
        io.execute {
            try { batch.groupBy { it.sessionId }.forEach { (id, records) -> store.saveSamples(id, records.map { it.record }) } }
            catch (error: Exception) { mainHandler.post {
                if (!destroyed) { pendingSamples.addAll(0, batch); storageError(error) }
            } }
        }
    }

    private fun storageError(error: Exception) {
        if (storageFailed) return
        storageFailed = true
        drillController.interrupt("Storage failed. Practice interrupted; retained data can be recovered.")
        runLocation.stop()   // fixes could no longer be written, so do not keep the location indicator on
        val id = activeSessionId
        activeSessionId = null
        cancelPendingSave()        // a stop waiting for its end burst is dropped; the session is closed below
        stopSequencer.cancel()
        stopPhoneMotion()
        // Stop accepting new samples, retain sensor backups and any pending RAM batch.
        transfers.keys.toList().forEach { side -> invalidate(side); clients.remove(side)?.disconnect() }
        listener?.onRecordingChanged(null, "Recording paused · sensor flash retained: ${error.message ?: "storage error"}")
        id?.let { io.execute { try { store.finishSession(it, System.currentTimeMillis()) } catch (_: Exception) {} } }
        if (foreground || uiVisible) {
            try {
                ensureForeground()
                updateNotification("Recording paused · sensor flash retained · open app to retry recovery")
            } catch (_: Exception) { /* A locked/background launch cannot start a foreground service. */ }
        }
        releaseWakeLock()
    }

    private fun logEvent(id: String, message: String) {
        io.execute { try { store.event(id, message) } catch (error: Exception) { mainHandler.post { if (!destroyed) storageError(error) } } }
    }

    private val tick = object : Runnable {
        override fun run() {
            flush()
            val now = SystemClock.elapsedRealtime()
            clockSync.tick(now, activeSessionId != null && !storageFailed)
            if(now-lastHealthPoll>=5000) {
                lastHealthPoll=now
                clients.values.forEach { it.pollRssi() }
                if(testRecording) activeSessionId?.let { id ->
                    val snapshots=clients.filter { it.value.isReady }.keys.map { side ->
                        SensorHealth(side,System.currentTimeMillis(),batteryLevels[side],batteryTimes[side],
                            rssiLevels[side]?.first,rssiLevels[side]?.second)
                    }
                    io.execute { try { snapshots.forEach { store.saveHealth(id,it) } }
                        catch(error: Exception) { mainHandler.post { if(!destroyed) storageError(error) } } }
                }
            }
            clients.keys.toList().forEach { side ->
                if (clients[side]?.isReady == true && now - (lastSampleTime[side] ?: now) > 3_000 &&
                    transfers[side] == null) {
                    if (sensorStatuses[side] != "No live samples for 3 seconds") {
                        reportStatus(side, "No live samples for 3 seconds")
                        activeSessionId?.let { logEvent(it, "$side live stream stalled") }
                    }
                }
            }
            if (now - lastInfoPoll > 15_000) {
                lastInfoPoll = now
                clients.keys.toList().forEach { if (recorderSupported[it] == true && (flashRetries[it] ?: 0) < 3) requestInfo(it) }
            }
            if (now - lastStorageCheck > 30_000) {
                lastStorageCheck = now
                if (activeSessionId != null && StatFs(filesDir.absolutePath).availableBytes < 10L * 1024 * 1024) {
                    stopRecording(endBurst = false)
                    listener?.onRecordingChanged(null, "Recording stopped · phone storage is low")
                }
            }
            if (foreground && !storageFailed) updateNotification(notificationText())
            if (foreground && (activeSessionId != null || transfers.isNotEmpty())) acquireWakeLock()
            mainHandler.postDelayed(this, 500)
        }
    }

    private fun updateInfo(side: String) {
        val state = recorderStates[side]
        val battery = batteryLevels[side]?.let { "Battery $it%" } ?: "Battery not reported"
        val flash = if (recorderSupported[side] != true) "Flash not reported" else if (state == null) "Checking flash…" else
            if (!state.storageReady) "Flash unavailable" else
                "Flash ${state.samples} / ${state.capacity} samples · ${((state.capacity - state.samples).coerceAtLeast(0) / 100)}s free" +
                    (if (state.dropped > 0) " · ${state.dropped} dropped" else "") +
                    (if (state.recording) " · recording" else "") +
                    (if (state.full) " · full" else "") +
                    (if (state.storageError) " · storage error" else "")
        sensorInfo[side] = "$battery · ${rssiLevels[side]?.first?.let { "$it dBm" } ?: "RSSI not reported"}" +
            (if (clockSync.isGestureOnly(side)) " · gesture sync only" else "") + "\n$flash"
        listener?.onSensorInfo(side, sensorInfo[side]!!)
    }

    private fun reportStatus(side: String, message: String) {
        sensorStatuses[side] = message
        listener?.onSensorStatus(side, message)
        if (foreground && !storageFailed) updateNotification(notificationText())
    }

    private fun notificationText(): String {
        val warnings = addresses.keys.filter { side -> clients[side]?.isReady != true ||
            SystemClock.elapsedRealtime() - (lastSampleTime[side] ?: 0) > 3_000 }
        return when {
            drillController.active -> "Practice · ${drillController.phase.name.lowercase()} · tap to return"
            runPhase() == RunPhase.RUNNING -> runController.state(System.currentTimeMillis()).let { s ->
                "Run in progress · %d:%02d · %d turns".format(s.elapsedMs / 60000, s.elapsedMs / 1000 % 60, s.turns.values.sum()) }
            runPhase() == RunPhase.SAVING -> runController.state(System.currentTimeMillis()).saveProgress.entries
                .joinToString(" · ", "Saving run data · ") { (side, percent) -> "${if (side == "L") "Left" else "Right"} ${percent ?: 0}%" }
            coachState.running && coachState.paused -> "Coaching paused: earbuds disconnected"
            activeSessionId != null -> if (warnings.isEmpty()) "Recording both available boot streams" else "Recording · ${warnings.joinToString("/")} boot samples unavailable"
            transfers.isNotEmpty() -> "Recovering sensor flash · keep sensors powered and nearby"
            store.hasPendingRecovery() -> "Live recording saved · sensor flash recovery pending"
            monitoring -> if (warnings.isEmpty()) "Boot sensors connected · monitoring continues with screen off"
                else "Monitoring · ${warnings.joinToString("/")} boot reconnecting"
            else -> "Session saved on this phone"
        }
    }

    private fun ensureForeground() {
        val message = notificationText()
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            // Same types the manifest gave before, so nothing else changes; location is added only while a run tracks GPS.
            var type = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            if (gpsTracking) type = type or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            startForeground(NOTIFICATION_ID, notification(message), type)
        } else startForeground(NOTIFICATION_ID, notification(message))
        lastNotificationMessage = message
        foreground = true
    }
    private fun settleForeground() {
        if (drillController.active && drillController.demo && !monitoring && !coachState.running &&
            activeSessionId == null && transfers.isEmpty()) return
        if (activeSessionId == null && transfers.isEmpty() && !coachState.running && !drillController.active) releaseWakeLock()
        if (!monitoring && !coachState.running && !runActive() && !drillController.active && activeSessionId == null && !store.hasPendingRecovery() && transfers.isEmpty() && !storageFailed) {
            if (foreground) stopForeground(STOP_FOREGROUND_REMOVE)
            foreground = false
            stopSelf()
        } else if (!recoveryPaused) ensureForeground()
    }
    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "OpenSki sensors and recording", NotificationManager.IMPORTANCE_LOW))
    }
    private fun notification(message: String): Notification {
        val destination = if (drillController.active) Intent(this, DrillActivity::class.java).putExtra("drill", drillState().drill?.id)
            else Intent(this, MainActivity::class.java)
        val open = PendingIntent.getActivity(this, 0, destination,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val action = if (drillController.active || activeSessionId != null) ACTION_STOP_RECORDING else if (store.hasPendingRecovery()) ACTION_PAUSE_RECOVERY else ACTION_STOP_MONITORING
        val stop = PendingIntent.getService(this, 1, Intent(this, SensorSessionService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL_ID).setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("OpenSki").setContentText(message).setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, if (drillController.active) "Finish practice" else if (activeSessionId != null) "Stop recording" else if (store.hasPendingRecovery()) "Pause recovery" else "Disconnect sensors", stop).build()).build()
    }
    private fun updateNotification(message: String) {
        if (lastNotificationMessage == message) return
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(message))
        lastNotificationMessage = message
    }
    private fun acquireWakeLock() {
        if (recordingWakeLock?.isHeld == true) return
        recordingWakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:OpenSkiRecording").apply {
                setReferenceCounted(false); acquire(12 * 60 * 60 * 1000L)
            }
    }
    private fun releaseWakeLock() { recordingWakeLock?.let { if (it.isHeld) it.release() }; recordingWakeLock = null }

    override fun onDestroy() {
        // A stop that was waiting for the end ping burst saves now rather than leaving the session open.
        cancelPendingSave()
        stopSequencer.flush(activeSessionId)?.let { finishRecording(it) }
        drillController.interrupt("Practice interrupted by service shutdown.")
        destroyed = true
        drillObservers.clear()
        mainHandler.removeCallbacks(drillTick)
        stopPhoneMotion()
        mainHandler.removeCallbacks(runTick)
        clockSync.stop()
        stopPracticeAudio()
        previewAudio?.stop(); previewAudio = null
        stopCoaching()
        gps.stop()
        mainHandler.removeCallbacks(tick)
        listOf("L", "R").forEach { invalidate(it) }
        reconnectTasks.values.forEach(mainHandler::removeCallbacks)
        // Flush accepted samples. Leave the open session recoverable on process/service restart.
        flush()
        clients.values.forEach(BleSensorClient::disconnect)
        clients.clear()
        releaseWakeLock()
        io.execute { store.close() }
        io.shutdown()
        super.onDestroy()
    }

    companion object {
        const val ACTION_RESUME_DRILL = "com.openski.android.RESUME_DRILL"
        const val ACTION_MONITOR = "com.openski.android.MONITOR"
        private const val COUNT_IN_BEATS = 4
        const val ACTION_STOP_MONITORING = "com.openski.android.STOP_MONITORING"
        const val ACTION_START_RECORDING = "com.openski.android.START_RECORDING"
        const val ACTION_STOP_RECORDING = "com.openski.android.STOP_RECORDING"
        const val ACTION_RECOVER = "com.openski.android.RECOVER"
        const val ACTION_PAUSE_RECOVERY = "com.openski.android.PAUSE_RECOVERY"
        const val ACTION_RESUME_RECORDING = "com.openski.android.RESUME_RECORDING"
        /** With ACTION_START_RECORDING and test_session: the test recording is a drill's. */
        const val EXTRA_DRILL = "drill"
        const val CHANNEL_ID = "openski_recording"
        const val NOTIFICATION_ID = 107
        const val PREFS = "openski_sensor_assignments"
    }
}
