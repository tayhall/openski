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
    private data class Wait(val opcode: Int, val timeout: Runnable, val action: (RecorderStatus) -> Unit)
    private val waits = mutableMapOf<String, Wait>()
    private data class Transfer(val capture: SensorCapture, val validator: TransferValidator, val generation: Int)
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
    private val runBoots = object : RunBoots {
        override fun connected(side: String) = clients[side]?.isReady == true
        override fun productionMode(side: String): Boolean? = if (side in modeSeen) side in productionSides else null
        override fun zeroed(side: String) = zeroTracker.isZeroed(side)
        override fun flashRetained(side: String) = recorderStates[side]?.let { it.hasSession || it.recording } == true
        override fun flashFull(side: String) = side in runFullSides
        // A run sets the mode itself, so it goes straight to the boot and skips the app-side recording guard.
        override fun setProduction(side: String, production: Boolean): Boolean =
            clients[side]?.command(RecorderCommand.SET_MODE, if (production) 1 else 0) == true
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
                if (demo) runSessionId?.let { store.finishSession(it, System.currentTimeMillis()) } else stopRecording()
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
    }
    private val runController: RunController by lazy { RunController(runBoots, runSink) { CoachSettingsStore(this).settings } }
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
        createNotificationChannel()
        activeSessionId = store.latestOpenSessionId()
        testRecording=activeSessionId?.let { store.getSession(it)?.testSession } ?: false
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
                ACTION_START_RECORDING -> startRecording(intent.getBooleanExtra("test_session",false))
                ACTION_STOP_RECORDING -> if (runController.phase() == RunPhase.RUNNING) stopRun() else stopRecording()
                ACTION_RECOVER -> recoverFlash()
                ACTION_PAUSE_RECOVERY -> if (activeSessionId == null) pauseRecovery()
                ACTION_RESUME_RECORDING -> if (activeSessionId != null) { ensureForeground(); acquireWakeLock() } else settleForeground()
            }
            if (!recoveryPaused) restoreConnections()
        } catch (error: Exception) { storageError(error) }
        return if (monitoring || activeSessionId != null || store.hasPendingRecovery()) START_STICKY else START_NOT_STICKY
    }

    fun sessionId(): String? = activeSessionId
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
        startMonitoring()
        addresses[side] = device.address
        corrections = null
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("sensor_$side", device.address).apply()
        reconnectAttempts[side] = 0
        reconnectTasks.remove(side)?.let(mainHandler::removeCallbacks)
        connectAddress(side, device.address, device)
    }

    fun canAssign(side: String, address: String): Boolean = side in listOf("L","R") &&
        addresses[if(side=="L") "R" else "L"]!=address &&
        (addresses[side] == address || (activeSessionId == null && !store.hasPendingRecovery()))

    fun restoreConnections() {
        listOf("L", "R").forEach { side ->
            val address = getSharedPreferences(PREFS, MODE_PRIVATE).getString("sensor_$side", null) ?: return@forEach
            startMonitoring()
            addresses[side] = address
            if (!clients.containsKey(side) && !reconnectTasks.containsKey(side)) connectAddress(side, address)
        }
    }

    private fun startMonitoring() {
        if (monitoring) return
        monitoring = true
        recoveryPaused = false
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
        if (activeSessionId != null || transfers.isNotEmpty() || store.hasPendingRecovery()) return false
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
        if (activeSessionId != null || transfers.isNotEmpty() || store.hasPendingRecovery()) return false
        val left = addresses["L"]
        val right = addresses["R"]
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
        restoreConnections()
        return true
    }

    private fun invalidate(side: String) {
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
        lastSampleTime.remove(side)
        batteryLevels.remove(side)
        batteryTimes.remove(side)
        rssiLevels.remove(side)
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
            onDisconnected = {
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
        if (runSessionId != null && status.full) runFullSides.add(side)
        updateInfo(side)
        if (status.opcode == 0x85) {
            val transfer = transfers[side] ?: return
            transferTimeouts.remove(side)?.let(mainHandler::removeCallbacks)
            if (!transfer.validator.complete(status)) { retryFlash(side, "Incomplete download; flash retained"); return }
            // All chunk writes have already been queued on this same executor.
            io.execute {
                try {
                    val verified = store.validateDownload(transfer.capture.id, status.samples, status.dropped)
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
            try { store.saveDownload(transfer.capture.id, chunk) }
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
    fun setOnSnowMode(production: Boolean) { runController.setOnSnowMode(production); runListener?.invoke(runState()) }

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
        if (coachState.running) return null
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
        val audio = coachAudio ?: CoachAudio(this, onRoute = {}, onProblem = { text -> mainHandler.post { coachListener?.invoke(CoachState(message = text)) } })
        audio.playTestSounds(settings.normalised().gainPercent)
    }

    private fun onCoachVerdict(verdict: CoachVerdict) {
        val target = coachState.target ?: return
        val countInMs = (COUNT_IN_BEATS * target.beatSeconds * 1000).toLong()
        val counting = coachSettings.metronome && coachSettings.countIn && SystemClock.elapsedRealtime() - coachStartedAt < countInMs
        if (coachSettings.chirps && !counting && !coachState.paused) coachAudio?.chirp(verdict.verdict)
        publishCoach(coachState.copy(last = verdict))
    }

    /** Boots with a live, ready link; only these can take a command. */
    fun connectedSides(): List<String> = clients.filterValues { it.isReady }.keys.sorted()

    /** Ask a boot to recapture its neutral pose from the next still second. Stand upright and still first. */
    fun zeroSensor(side: String): Boolean = clients[side]?.command(RecorderCommand.ZERO) == true

    /** Production turns the boot's Wi-Fi and raw stream off to save battery; diagnostics turns them back on. */
    fun setSensorMode(side: String, production: Boolean): Boolean {
        if (ProductionModeRule.blockedReason(activeSessionId != null, transfers.isNotEmpty() || waits.isNotEmpty(), production) != null) return false
        return clients[side]?.command(RecorderCommand.SET_MODE, if (production) 1 else 0) == true
    }

    /** Why production mode cannot be entered right now, or null if it can. */
    fun productionBlockedReason(): String? =
        ProductionModeRule.blockedReason(activeSessionId != null, transfers.isNotEmpty() || waits.isNotEmpty())

    fun calibrateTest(side: String, axis: Int, sign: Int, completed: (String)->Unit) {
        val id=activeSessionId
        if(id==null || !testRecording) { completed("Start a test session first"); return }
        flush()
        io.execute {
            try {
                val data=store.loadData(id) ?: error("Session unavailable")
                val timeline=SessionAnalysis.build(data).timeline.corrected(::correctionFor)
                val end=timeline.filter { it.side==side }.maxOfOrNull { it.timeMs } ?: error("No samples for this boot")
                if(System.currentTimeMillis()-end>2000) error("Boot stream is stale")
                val calibration=BootOrientation.calibrate(timeline,side,end-2000,axis,sign)
                    ?: error("Hold the boot neutral and still for two seconds; check the forward axis")
                store.saveCalibration(id,calibration)
                mainHandler.post { if(!destroyed && activeSessionId==id) {
                    orientationTrackers[side]=BootOrientation.Tracker(calibration.copy(timeMs=System.currentTimeMillis().toDouble()))
                    movementTrackers[side]=DrySkiAnalysis.Tracker()
                    completed("$side boot calibrated · ready for movements")
                } }
            } catch(error: Exception) { mainHandler.post { if(!destroyed) completed(error.message ?: "Calibration failed") } }
        }
    }

    fun learnTestMounting(side: String, completed: (String)->Unit) {
        val id=activeSessionId
        if(id==null || !testRecording) { completed("Start a test session first"); return }
        flush()
        io.execute {
            try {
                val data=store.loadData(id) ?: error("Session unavailable")
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

    private fun startRecording(testSession: Boolean=false, kind: String="session") {
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
            val id = UUID.randomUUID().toString()
            store.createSession(id, System.currentTimeMillis(),testSession, kind = kind)
            testRecording=testSession
            orientationTrackers.clear(); movementTrackers.clear(); latestOrientation.clear(); guidedTrial=null
            activeSessionId = id
            acquireWakeLock()
            listener?.onRecordingChanged(id, if(testSession) "Test recording · hold neutral for two seconds, then calibrate" else "Recording · continues with screen off")
            clients.keys.toList().forEach { side -> if (recorderSupported[side] == true) requestInfo(side) }
        } catch (error: Exception) { storageError(error) }
    }

    private fun stopRecording() {
        val id = activeSessionId ?: return
        activeSessionId = null
        guidedTrial=null
        flush()
        io.execute {
            try {
                store.finishSession(id, System.currentTimeMillis())
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

    private fun flush() {
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
        val id = activeSessionId
        activeSessionId = null
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
                    stopRecording()
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
        sensorInfo[side] = "$battery · ${rssiLevels[side]?.first?.let { "$it dBm" } ?: "RSSI not reported"}\n$flash"
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
        startForeground(NOTIFICATION_ID, notification(message))
        lastNotificationMessage = message
        foreground = true
    }
    private fun settleForeground() {
        if (activeSessionId == null && transfers.isEmpty() && !coachState.running) releaseWakeLock()
        if (!monitoring && !coachState.running && !runActive() && activeSessionId == null && !store.hasPendingRecovery() && transfers.isEmpty() && !storageFailed) {
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
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val action = if (activeSessionId != null) ACTION_STOP_RECORDING else if (store.hasPendingRecovery()) ACTION_PAUSE_RECOVERY else ACTION_STOP_MONITORING
        val stop = PendingIntent.getService(this, 1, Intent(this, SensorSessionService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL_ID).setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("OpenSki").setContentText(message).setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, if (activeSessionId != null) "Stop recording" else if (store.hasPendingRecovery()) "Pause recovery" else "Disconnect sensors", stop).build()).build()
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
        destroyed = true
        mainHandler.removeCallbacks(runTick)
        stopCoaching()
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
        const val ACTION_MONITOR = "com.openski.android.MONITOR"
        private const val COUNT_IN_BEATS = 4
        const val ACTION_STOP_MONITORING = "com.openski.android.STOP_MONITORING"
        const val ACTION_START_RECORDING = "com.openski.android.START_RECORDING"
        const val ACTION_STOP_RECORDING = "com.openski.android.STOP_RECORDING"
        const val ACTION_RECOVER = "com.openski.android.RECOVER"
        const val ACTION_PAUSE_RECOVERY = "com.openski.android.PAUSE_RECOVERY"
        const val ACTION_RESUME_RECORDING = "com.openski.android.RESUME_RECORDING"
        const val CHANNEL_ID = "openski_recording"
        const val NOTIFICATION_ID = 107
        const val PREFS = "openski_sensor_assignments"
    }
}
