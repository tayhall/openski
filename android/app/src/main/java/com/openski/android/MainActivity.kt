package com.openski.android

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.ServiceConnection
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.os.ParcelUuid
import android.net.Uri
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.text.SimpleDateFormat
import java.util.Date

class MainActivity : Activity() {
    private var scanner: android.bluetooth.le.BluetoothLeScanner? = null
    private lateinit var scanButton: Button
    private lateinit var scanHint: TextView
    private lateinit var devicesLayout: LinearLayout
    private lateinit var emptyDevices: TextView
    private lateinit var leftPanel: SensorPanel
    private lateinit var rightPanel: SensorPanel
    private lateinit var recordButton: Button
    private lateinit var recordingStatus: TextView
    private lateinit var recordingHeadline: TextView
    private lateinit var historyLayout: LinearLayout
    private lateinit var sessionStore: LocalSessionStore
    private val discovered = ConcurrentHashMap<String, ScanResult>()
    private var sensorService: SensorSessionService? = null
    private var serviceBound = false
    private var leftAddress: String? = null
    private var rightAddress: String? = null
    @Volatile private var latestLeft: SensorSample? = null
    @Volatile private var latestRight: SensorSample? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    @Volatile private var activeSessionId: String? = null
    private var pendingVideoSessionId: String? = null
    private var pendingTestSession=false
    private val testButtons=mutableListOf<Button>()
    private lateinit var liveOrientationLabel: TextView
    private lateinit var testFeedback: TextView
    private lateinit var skiStatus: TextView
    private lateinit var skiCommand: TextView
    private val skiEventText = mutableMapOf<String, String>()
    private val skiStateText = mutableMapOf<String, String>()
    private val liveOrientation=mutableMapOf<String,BootRoll>()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as? SensorSessionService.LocalBinder)?.service ?: return
            sensorService = service
            serviceBound = true
            service.setListener(sensorListener)
            service.setUiVisible(hasWindowFocus())
            if (hasWindowFocus() && service.sessionId() != null) requestRecordingService(SensorSessionService.ACTION_RESUME_RECORDING)
            service.restoreConnections()
            val prefs = getSharedPreferences(SensorSessionService.PREFS, MODE_PRIVATE)
            leftAddress = prefs.getString("sensor_L", null)
            rightAddress = prefs.getString("sensor_R", null)
            leftAddress?.let { leftPanel.setDevice(it) }
            rightAddress?.let { rightPanel.setDevice(it) }
            refreshDiscoveredDevices()
            refreshHistory()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            sensorService?.setListener(null)
            sensorService = null
            serviceBound = false
            activeSessionId = null
            renderRecordingState(null, "Sensor service disconnected")
        }
    }

    private val sensorListener = object : SensorSessionService.Listener {
        override fun onSensorStatus(side: String, message: String) = runOnUiThread {
            (if (side == "L") leftPanel else rightPanel).setStatus(message)
            if (message.contains("Disconnected", true) || message.contains("No live samples", true)) {
                if (side == "L") latestLeft = null else latestRight = null
                (if (side == "L") leftPanel else rightPanel).clearReadings()
            }
            if (activeSessionId != null && (message.contains("Disconnected") || message.contains("reconnect", true))) {
                recordingStatus.text = "$side boot $message"
            }
        }
        override fun onSensorInfo(side: String, message: String) = runOnUiThread {
            (if (side == "L") leftPanel else rightPanel).setInfo(message)
        }
        override fun onHistoryChanged() = refreshHistory()
        override fun onSensorSample(side: String, sample: SensorSample) {
            if (side == "L") latestLeft = sample else latestRight = sample
        }
        override fun onBootOrientation(side: String, value: BootRoll?) {
            if(value==null) liveOrientation.remove(side) else liveOrientation[side]=value
        }
        override fun onTestFeedback(message: String) = runOnUiThread { testFeedback.text=message }
        override fun onSkiEvent(side: String, event: SkiEvent) = runOnUiThread {
            skiEventText[side] = "half-turn ${"%+.0f".format(java.util.Locale.US, event.peakRollDegrees)}° over " +
                "${"%.1f".format(java.util.Locale.US, event.durationMs / 1000f)} s, peak " +
                "${"%.0f".format(java.util.Locale.US, event.peakRateDps)}°/s" +
                if (event.outsideEnvelope) " (outside expected range)" else ""
            renderSki()
        }
        override fun onSkiState(side: String, state: SkiState) = runOnUiThread {
            skiStateText[side] = "${if (state.production) "production" else "diagnostics"} · " +
                "${if (state.zeroed) "zeroed" else "not zeroed"} · roll " +
                "${"%.0f".format(java.util.Locale.US, state.rollDegrees)}° pitch " +
                "${"%.0f".format(java.util.Locale.US, state.pitchDegrees)}°"
            renderSki()
        }
        override fun onRecordingChanged(sessionId: String?, message: String) = runOnUiThread {
            activeSessionId = sessionId
            renderRecordingState(sessionId, message)
            if (sessionId == null) refreshHistory()
        }
    }

    private val refreshReadings = object : Runnable {
        override fun run() {
            latestLeft?.let(leftPanel::render)
            latestRight?.let(rightPanel::render)
            liveOrientationLabel.text=listOf("L","R").joinToString("\n") { side ->
                val point=liveOrientation[side]
                if(point==null || System.currentTimeMillis()-point.timeMs>1000) "$side: calibrate to show live boot orientation"
                else "$side roll ${String.format(Locale.US,"%.1f",point.degrees)}° · pitch ${String.format(Locale.US,"%.1f",point.pitchDegrees)}° · relative yaw ${String.format(Locale.US,"%.1f",point.yawDegrees)}°"
            }
            mainHandler.postDelayed(this, 200L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingTestSession=savedInstanceState?.getBoolean("pending_test_session") ?: false
        selectedTab=savedInstanceState?.getInt("selected_tab") ?: 0
        SkiUi.configureWindow(this)
        sessionStore = LocalSessionStore(this)
        buildScreen()
        mainHandler.post(refreshReadings)
        refreshHistory()
        if (!hasBluetoothPermissions()) requestBluetoothPermissions()
    }

    override fun onSaveInstanceState(state: Bundle) {
        state.putBoolean("pending_test_session",pendingTestSession)
        state.putInt("selected_tab", selectedTab)
        super.onSaveInstanceState(state)
    }

    private var selectedTab = 0
    private val tabButtons = mutableListOf<Button>()
    private val tabPages = mutableListOf<ScrollView>()

    private fun buildScreen() {
        val compact=resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(CANVAS))
        }
        val identity = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(14))
        }
        identity.addView(label(if(compact) "OPENSKI · GEEK MODE" else "GEEK MODE", if(compact) 16f else 12f, ACCENT, true))
        if(!compact) {
            identity.addView(label("Sensors and raw data.", 28f, WHITE, true).apply { setPadding(0, dp(4), 0, dp(4)) })
            identity.addView(label("Live telemetry, test lab, flash recovery and every saved session.", 14f, SUBTLE))
        }
        root.addView(identity)
        val navigation = LinearLayout(this).apply { setPadding(dp(16), 0, dp(16), dp(12)) }
        listOf("Record", "Sessions", "Test lab").forEachIndexed { index, title ->
            val button = SkiUi.button(this, title, SkiUi.ButtonStyle.QUIET) { selectTab(index) }
            tabButtons.add(button)
            navigation.addView(button, LinearLayout.LayoutParams(0, -2, 1f).apply {
                marginStart = dp(4); marginEnd = dp(4)
            })
        }
        root.addView(navigation)
        fun page(): LinearLayout {
            val content = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(6), dp(20), dp(24))
            }
            val scroll = ScrollView(this).apply { isFillViewport = true; addView(content) }
            tabPages.add(scroll)
            root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
            return content
        }
        val record = page()
        val sessions = page()
        val lab = page()

        record.addView(sectionHeading("YOUR NEXT SESSION", "Boot sensors reconnect automatically"))
        val sessionCard = SkiUi.card(this)
        recordingHeadline=label("Ready when you are", 23f, WHITE, true)
        sessionCard.addView(recordingHeadline)
        recordingStatus = label("Connect a boot sensor to start recording.", 14f, SUBTLE).apply {
            setPadding(0, dp(8), 0, dp(16))
        }
        sessionCard.addView(recordingStatus)
        recordButton = SkiUi.button(this, "Start recording", SkiUi.ButtonStyle.PRIMARY) {
            if (activeSessionId == null) startSession() else stopSession()
        }
        sessionCard.addView(recordButton, LinearLayout.LayoutParams(-1, -2))
        sessionCard.addView(label("Records both boots, including when the screen is off.", 12f, MUTED).apply {
            setPadding(0, dp(10), 0, 0)
        })
        record.addView(sessionCard, bottomMargin(dp(20)))
        record.addView(SkiUi.button(this, "No sensors yet? Try a demo", SkiUi.ButtonStyle.QUIET) { selectTab(2) }, bottomMargin(dp(16)))
        record.addView(sectionHeading("YOUR BOOTS", "Connection and live motion"))
        leftPanel = SensorPanel("Left boot", "L", ACCENT)
        rightPanel = SensorPanel("Right boot", "R", SKY)
        record.addView(leftPanel.view, bottomMargin(dp(12)))
        record.addView(rightPanel.view, bottomMargin(dp(16)))
        liveOrientationLabel = label("", 13f, SUBTLE)
        val orientationCard = SkiUi.card(this)
        orientationCard.addView(label("CALIBRATED ORIENTATION", 11f, ACCENT, true))
        orientationCard.addView(liveOrientationLabel.apply { setPadding(0, dp(8), 0, 0) })
        record.addView(orientationCard, bottomMargin(dp(16)))

        val setup = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scanButton = SkiUi.button(this, "Search for sensors", SkiUi.ButtonStyle.PRIMARY) { ensurePermissionAndScan() }
        setup.addView(scanButton, LinearLayout.LayoutParams(-1, -2))
        scanHint = label("Power on your boot sensors, then search nearby.", 13f, SUBTLE).apply {
            setPadding(0, dp(8), 0, dp(12))
        }
        setup.addView(scanHint)
        setup.addView(SkiUi.button(this, "Manage saved sensors") { manageSensors() })
        emptyDevices = label("Nearby sensors will appear here. Assign each to its boot.", 13f, MUTED)
        setup.addView(emptyDevices, bottomMargin(dp(12)))
        devicesLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        setup.addView(devicesLayout)
        setup.addView(SkiUi.button(this, "Recover sensor flash") {
            sensorService?.recoverFlash() ?: run { recordingStatus.text = "Wait for the sensor service to connect" }
        })
        setup.addView(label("Keep sensors powered on until recovery finishes.", 12f, MUTED).apply { setPadding(0, dp(8), 0, 0) })
        record.addView(disclosure("Sensor setup & recovery", setup, true), bottomMargin(dp(16)))
        record.addView(SkiUi.button(this, "No hardware yet? Explore Test lab", SkiUi.ButtonStyle.QUIET) { selectTab(2) })

        sessions.addView(sectionHeading("YOUR SESSIONS", "Saved on this phone · tap a session to review"))
        historyLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sessions.addView(historyLayout)

        lab.addView(sectionHeading("TEST LAB", "Build confidence before the first snow test"))
        val demoCard = SkiUi.card(this)
        demoCard.addView(label("Try OpenSki today", 23f, WHITE, true))
        demoCard.addView(label("Explore two simulated boots, movement profiles and session review. No sensors required.", 14f, SUBTLE).apply {
            setPadding(0, dp(8), 0, dp(14))
        })
        lateinit var demoButton: Button
        demoButton = SkiUi.button(this, "Explore a demo · no sensors needed", SkiUi.ButtonStyle.PRIMARY) {
            val button = demoButton
            button.isEnabled = false
            io.execute {
                try {
                    val demoId = sessionStore.createDemo()
                    runOnUiThread { button.isEnabled = true; refreshHistory(); showSession(demoId) }
                } catch (error: Exception) {
                    runOnUiThread { button.isEnabled = true; testFeedback.text = "Demo failed: ${error.message}" }
                }
            }
        }
        demoCard.addView(demoButton, LinearLayout.LayoutParams(-1, -2))
        lab.addView(demoCard, bottomMargin(dp(20)))

        val testCard = SkiUi.card(this)
        testCard.addView(label("Dry ski test", 22f, WHITE, true))
        testCard.addView(label("1  Start a test\n2  Hold still and calibrate each boot\n3  Record 20 controlled movements", 14f, SUBTLE).apply {
            setPadding(0, dp(10), 0, dp(16))
        })
        testFeedback = label("Calibrate, then choose a guided set.", 14f, ACCENT)
        testCard.addView(testFeedback, bottomMargin(dp(12)))
        testCard.addView(SkiUi.button(this, "New test", SkiUi.ButtonStyle.PRIMARY) {
            if (activeSessionId == null) startSession(true)
            else testFeedback.text = "Stop the current recording before starting a test."
        })
        testCard.addView(SkiUi.button(this, "Finish current test") {
            if (activeSessionId != null) stopSession() else testFeedback.text = "Start a test to record movements."
        })
        fun testButton(text: String, action: () -> Unit): Button = SkiUi.button(this, text) { action() }.apply {
            isEnabled = false; testButtons.add(this)
        }
        testCard.addView(testButton("Calibrate boot") { calibrateTestBoot() })
        testCard.addView(testButton("Learn mounting from a roll gesture") { calibrateTestBoot(true) })
        testCard.addView(label("GUIDED SET · 20 MOVEMENTS", 11f, MUTED, true).apply { setPadding(0, dp(16), 0, dp(6)) })
        listOf("Slow", "Medium", "Brisk").forEach { pace ->
            testCard.addView(testButton("20 ${pace.lowercase()} movements") {
                testFeedback.text = sensorService?.startGuidedTrial(pace) ?: "Wait for the sensor service."
            })
        }
        lab.addView(testCard, bottomMargin(dp(16)))
        val bootCard = SkiUi.card(this)
        bootCard.addView(label("Boot sensors · experimental", 22f, WHITE, true))
        bootCard.addView(label("Stand upright in your ski stance, then zero. Production mode switches the boots' Wi-Fi and raw stream off to save battery; a boot always starts in diagnostics after a power cycle. Half-turn lean is cuff lean, not ski edge angle.", 14f, SUBTLE).apply {
            setPadding(0, dp(10), 0, dp(16))
        })
        skiCommand = label("Connect a boot to send commands.", 14f, ACCENT)
        bootCard.addView(skiCommand, bottomMargin(dp(12)))
        bootCard.addView(SkiUi.button(this, "Zero boots (stand still)", SkiUi.ButtonStyle.PRIMARY) {
            commandBoots("Zero") { service, side -> service.zeroSensor(side) }
        })
        bootCard.addView(SkiUi.button(this, "Production mode (battery)") {
            val blocked = sensorService?.productionBlockedReason()
            if (blocked != null) skiCommand.text = blocked
            else commandBoots("Production") { service, side -> service.setSensorMode(side, true) }
        })
        bootCard.addView(SkiUi.button(this, "Diagnostics mode (Wi-Fi + raw)") {
            commandBoots("Diagnostics") { service, side -> service.setSensorMode(side, false) }
        })
        skiStatus = label("No boot turn data yet.", 13f, SUBTLE)
        bootCard.addView(skiStatus, bottomMargin(dp(4)))
        lab.addView(bootCard, bottomMargin(dp(16)))
        val markers = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        markers.addView(label("Observer labels use your tap time. Film the test and mark SYNC for video alignment.", 13f, SUBTLE), bottomMargin(dp(12)))
        listOf("LEFT", "RIGHT", "TRANSITION", "SYNC", "START_TEST", "PAUSE", "EVENT").forEach { marker ->
            markers.addView(testButton(marker.replace('_', ' ')) {
                if (sensorService?.markTest(marker, System.currentTimeMillis()) == true)
                    testFeedback.text = "Marked ${marker.replace('_', ' ')}"
            })
        }
        lab.addView(disclosure("Observer labels & sync markers", markers), bottomMargin(dp(16)))
        lab.addView(label("Dry ski movements help validate the software. Real edge angles and carving still need snow validation.", 12f, MUTED))
        SkiUi.applyInsets(root)
        setContentView(root)
        selectTab(selectedTab)
    }

    private fun renderSki() {
        val sides = (skiEventText.keys + skiStateText.keys).sorted()
        skiStatus.text = if (sides.isEmpty()) "No boot turn data yet." else sides.joinToString("\n") { side ->
            "$side · ${skiStateText[side] ?: "no state yet"}\n   ${skiEventText[side] ?: "no half-turn yet"}"
        }
    }

    private fun commandBoots(name: String, action: (SensorSessionService, String) -> Boolean) {
        val service = sensorService
        val sides = service?.connectedSides().orEmpty()
        skiCommand.text = if (service == null || sides.isEmpty()) "Connect a boot first."
        else "$name: " + sides.joinToString { side -> "$side ${if (action(service, side)) "sent" else "failed"}" }
    }

    private fun selectTab(index: Int) {
        selectedTab = index.coerceIn(0, 2)
        tabPages.forEachIndexed { i, page -> page.visibility = if (i == selectedTab) View.VISIBLE else View.GONE }
        tabButtons.forEachIndexed { i, button ->
            SkiUi.styleButton(button, if (i == selectedTab) SkiUi.ButtonStyle.PRIMARY else SkiUi.ButtonStyle.QUIET)
            button.isSelected = i == selectedTab
            button.contentDescription = "${button.text}${if (i == selectedTab) ", selected" else ""}"
        }
    }

    private fun disclosure(title: String, content: View, expanded: Boolean = false, inset: Boolean=false): LinearLayout =
        SkiUi.card(this,if(inset) 0 else 18).apply {
            if(inset) background=null
            val toggle = SkiUi.button(this@MainActivity, "", SkiUi.ButtonStyle.QUIET) {}
            fun update() { toggle.text = "${if (content.visibility == View.VISIBLE) "−" else "+"}  $title" }
            content.visibility = if (expanded) View.VISIBLE else View.GONE
            toggle.setOnClickListener {
                content.visibility = if (content.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                update()
            }
            update()
            addView(toggle, LinearLayout.LayoutParams(-1, -2))
            addView(content)
        }

    private fun sectionHeading(title: String, subtitle: String): View {
        val group = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(9))
        }
        group.addView(label(title, 11f, MUTED, true))
        group.addView(label(subtitle, 13f, SUBTLE).apply { setPadding(0, dp(3), 0, 0) })
        return group
    }

    private fun cardContainer(): LinearLayout = LinearLayout(this).apply {
        background = rounded(SURFACE, 18, BORDER)
    }

    private fun addDevice(result: ScanResult) {
        val name = result.scanRecord?.deviceName ?: "OpenSki sensor"
        val address = result.device.address
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(5), 0, dp(9))
        }
        val info = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val identity = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        identity.addView(label(name, 14f, WHITE, true))
        identity.addView(label("${address}  ·  ${result.rssi} dBm", 11f, MUTED).apply {
            setPadding(0, dp(3), 0, 0)
        })
        info.addView(identity)
        val savedSide = when (address) {
            leftAddress -> "L"
            rightAddress -> "R"
            else -> null
        }
        if (savedSide != null) {
            val left = savedSide == "L"
            identity.addView(label(if (left) "Saved left boot sensor" else "Saved right boot sensor",
                11f, if (left) ACCENT else SKY))
            info.addView(sideButton("RECONNECT", if (left) ACCENT else SKY) { connect(result, left) })
            sensorService?.reconnectSavedSensor(savedSide, result.device)
        } else {
            info.addView(sideButton("LEFT", ACCENT) { connect(result, true) })
            info.addView(sideButton("RIGHT", SKY) { connect(result, false) }.apply {
                setPadding(dp(7), 0, 0, 0)
            })
        }
        row.addView(info)
        devicesLayout.addView(row)
    }

    private fun refreshDiscoveredDevices() {
        devicesLayout.removeAllViews()
        discovered.values.toList().forEach(::addDevice)
    }

    private fun sideButton(textValue: String, tint: Int, action: () -> Unit): TextView = TextView(this).apply {
        text = textValue
        textSize = 12f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setTextColor(color(tint))
        background = rounded(BUTTON_SURFACE, 10, tint)
        setPadding(dp(10), dp(9), dp(10), dp(9))
        minHeight = dp(48)
        minWidth = dp(48)
        isClickable = true
        isFocusable = true
        contentDescription = if (textValue == "RECONNECT") "Reconnect saved sensor" else "Assign sensor to $textValue boot"
        setOnClickListener { action() }
    }

    private fun connect(result: ScanResult, left: Boolean) {
        val service = sensorService
        if (service == null) {
            (if (left) leftPanel else rightPanel).setStatus("Sensor service is starting; try again")
            return
        }
        val address = result.device.address
        if (!service.canAssign(if (left) "L" else "R", address)) {
            recordingStatus.text = "Stop recording and finish sensor recovery before replacing a sensor"
            return
        }
        if (left && rightAddress == address) {
            leftPanel.setStatus("Already assigned to right boot")
            return
        }
        if (!left && leftAddress == address) {
            rightPanel.setStatus("Already assigned to left boot")
            return
        }
        val panel = if (left) leftPanel else rightPanel
        if (left) {
            leftAddress = address
            latestLeft = null
        } else {
            rightAddress = address
            latestRight = null
        }
        panel.clearReadings()
        panel.setDevice("${result.scanRecord?.deviceName ?: "OpenSki sensor"} · label ${address.replace(":", "").takeLast(4)}")
        panel.setStatus("Connecting")
        val side = if (left) "L" else "R"
        service.connect(side, result.device)
        refreshDiscoveredDevices()
        stopScan()
    }

    private fun ensurePermissionAndScan() {
        if (!hasBluetoothPermissions()) requestBluetoothPermissions() else startScan()
    }

    private fun startSession(testSession: Boolean=false) {
        pendingTestSession=testSession
        if (leftAddress == null && rightAddress == null) {
            recordingStatus.text = "Connect at least one boot sensor before recording"
            if (testSession) testFeedback.text = "Connect a boot sensor in Record → Sensor setup & recovery, or explore the demo above."
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
            recordingStatus.text = "Allow notifications to see recording and reconnect status while the screen is off"
            return
        }
        beginRecording()
    }

    private fun beginRecording() {
        requestRecordingService(SensorSessionService.ACTION_START_RECORDING)
    }

    private fun stopSession() {
        if (activeSessionId == null) return
        requestRecordingService(SensorSessionService.ACTION_STOP_RECORDING)
    }

    private fun requestRecordingService(action: String) {
        val intent = Intent(this, SensorSessionService::class.java).setAction(action)
        if(action==SensorSessionService.ACTION_START_RECORDING) intent.putExtra("test_session",pendingTestSession)
        try {
            if (action == SensorSessionService.ACTION_START_RECORDING || action == SensorSessionService.ACTION_RESUME_RECORDING)
                startForegroundService(intent) else startService(intent)
        } catch (error: Exception) {
            recordingStatus.text = "Could not start sensor recording: ${error.message ?: "service error"}"
        }
    }

    private fun renderRecordingState(sessionId: String?, message: String) {
        recordingHeadline.text=if(sessionId==null) "Ready when you are" else "Recording your session"
        recordButton.text = if (sessionId == null) "Start recording" else "Stop recording"
        SkiUi.styleButton(recordButton, if (sessionId == null) SkiUi.ButtonStyle.PRIMARY else SkiUi.ButtonStyle.DANGER)
        recordingStatus.text = if(sessionId==null && message=="Ready to record" && latestLeft==null && latestRight==null)
            "Connect a boot sensor to start recording." else message
        if (selectedTab == 2 && pendingTestSession) testFeedback.text = message
        testButtons.forEach { it.isEnabled=sessionId!=null && sensorService?.isTestRecording()==true }
    }

    private fun refreshHistory() {
        if (!::historyLayout.isInitialized) return
        io.execute {
            val sessions = sessionStore.listSessions()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                historyLayout.removeAllViews()
                if (sessions.isEmpty()) {
                    historyLayout.addView(cardContainer().apply {
                        setPadding(dp(15), dp(14), dp(15), dp(14))
                        orientation = LinearLayout.VERTICAL
                        addView(label("Your first session starts here", 20f, WHITE, true))
                        addView(label("Record with a boot sensor, or explore a demo in Test lab.", 14f, SUBTLE).apply { setPadding(0, dp(8), 0, dp(14)) })
                        addView(SkiUi.button(this@MainActivity, "Explore Test lab") { selectTab(2) })
                    })
                } else {
                    var day = ""
                    sessions.forEach { session ->
                        val date = SimpleDateFormat("EEE, d MMM yyyy", Locale.getDefault()).format(Date(session.startedAtMs))
                        if (day != date) { day = date; historyLayout.addView(label(date, 14f, SUBTLE, true), bottomMargin(dp(8))) }
                        historyLayout.addView(sessionRow(session), bottomMargin(dp(10)))
                    }
                }
            }
        }
    }

    private fun sessionRow(session: SkiSession): View = cardContainer().apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(15), dp(13), dp(15), dp(13))
        isClickable = true
        isFocusable = true
        addView(label((if(session.origin=="synthetic") "DEMO · " else if(session.testSession) "TEST · " else "")+session.title.ifBlank { formatDate(session.startedAtMs) }, 14f, WHITE, true))
        val duration = ((session.endedAtMs ?: System.currentTimeMillis()) - session.startedAtMs).coerceAtLeast(0) / 1000
        addView(label("${duration / 60}m ${duration % 60}s  ·  L ${session.leftSamples}  ·  R ${session.rightSamples}", 12f, SUBTLE).apply {
            setPadding(0, dp(5), 0, 0)
        })
        addView(label(session.videoName?.let { "VIDEO  ·  $it" } ?: "NO VIDEO ATTACHED", 10f, ACCENT, true).apply {
            setPadding(0, dp(7), 0, 0)
        })
        setOnClickListener { showSession(session.id) }
    }

    private fun calibrateTestBoot(learnMounting: Boolean=false) {
        val service=sensorService ?: return
        var side="L"; var axis=0; var sign=1
        val form=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(16),dp(8),dp(16),dp(8)) }
        fun selector(items: List<String>,selected: (Int)->Unit)=android.widget.Spinner(this).apply {
            adapter=android.widget.ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,items)
            onItemSelectedListener=object: android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?,view: View?,position: Int,row: Long) { selected(position) }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
        }
        form.addView(selector(listOf("Left boot","Right boot")) { side=if(it==0) "L" else "R" })
        if(!learnMounting) form.addView(selector(listOf("+X toward toe","−X toward toe","+Y toward toe","−Y toward toe","+Z toward toe","−Z toward toe")) {
            axis=it/2; sign=if(it%2==0) 1 else -1
        })
        form.addView(label(if(learnMounting) "After neutral calibration, roll only left/right for five seconds. Avoid pitching or twisting. The saved toe-axis hint sets the sign." else "Hold the boot neutral and still for two seconds.",14f,SUBTLE))
        android.app.AlertDialog.Builder(this).setTitle(if(learnMounting) "Learn forward axis" else "Hold boot neutral and still").setView(form)
            .setNegativeButton("Cancel",null).setPositiveButton(if(learnMounting) "Learn last five seconds" else "Calibrate last two seconds") { _,_ ->
                if(learnMounting) service.learnTestMounting(side) { recordingStatus.text=it; testFeedback.text=it }
                else service.calibrateTest(side,axis,sign) { recordingStatus.text=it; testFeedback.text=it }
            }.show()
    }

    private fun showSession(id: String) {
        startActivity(Intent(this, SessionDetailActivity::class.java).putExtra("session_id", id))
    }

    private fun manageSensors() {
        val prefs = getSharedPreferences(SensorSessionService.PREFS, MODE_PRIVATE)
        fun identity(side: String): String = prefs.getString("sensor_$side", null)?.let {
            "${it.replace(":", "").takeLast(4)} ($it)"
        } ?: "Not assigned"
        val choices = arrayOf("Left: ${identity("L")}", "Right: ${identity("R")}", "Swap left and right")
        android.app.AlertDialog.Builder(this).setTitle("Saved sensors · label the cases with these codes")
            .setItems(choices) { _, position ->
                val service = sensorService ?: return@setItems
                if (position == 2) {
                    if (service.swapSensors()) reloadAssignments() else recordingStatus.text = "Stop recording and finish recovery before swapping sensors"
                } else {
                    val side = if (position == 0) "L" else "R"
                    android.app.AlertDialog.Builder(this).setTitle("Forget ${if (side == "L") "left" else "right"} sensor?")
                        .setMessage("You can assign its replacement during the next scan. Saved sessions remain on this phone.")
                        .setNegativeButton("Cancel", null).setPositiveButton("Forget") { _, _ ->
                            if (service.forgetSensor(side)) reloadAssignments() else recordingStatus.text = "Stop recording and finish recovery before replacing a sensor"
                        }.show()
                }
            }.setNegativeButton("Close", null).show()
    }

    private fun reloadAssignments() {
        val prefs = getSharedPreferences(SensorSessionService.PREFS, MODE_PRIVATE)
        leftAddress = prefs.getString("sensor_L", null)
        rightAddress = prefs.getString("sensor_R", null)
        latestLeft = null; latestRight = null
        leftPanel.clearReadings(); rightPanel.clearReadings()
        leftPanel.setDevice(leftAddress ?: "Not assigned")
        rightPanel.setDevice(rightAddress ?: "Not assigned")
        refreshDiscoveredDevices()
    }

    private fun pickVideo(sessionId: String) {
        pendingVideoSessionId = sessionId
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }, REQUEST_VIDEO)
    }

    @Deprecated("Picker result API retained for compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_VIDEO || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: SecurityException) { }
        val id = pendingVideoSessionId ?: return
        pendingVideoSessionId = null
        val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment ?: "Video"
        io.execute {
            sessionStore.attachVideo(id, uri.toString(), name)
            refreshHistory()
        }
    }

    private fun playVideo(uri: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(uri), "video/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (_: Exception) {
            recordingStatus.text = "Could not open this video. It may have been moved or removed."
        }
    }

    private fun formatDate(time: Long) = SimpleDateFormat("EEE, d MMM yyyy · HH:mm", Locale.getDefault()).format(Date(time))

    override fun onStart() {
        super.onStart()
        if (!hasBluetoothPermissions()) return
        val intent = Intent(this, SensorSessionService::class.java)
        serviceBound = bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        sensorService?.setUiVisible(false)
        sensorService?.setListener(null)
        if (serviceBound) unbindService(serviceConnection)
        serviceBound = false
        sensorService = null
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        sensorService?.setUiVisible(hasFocus)
        if (hasFocus && sensorService?.sessionId() != null) requestRecordingService(SensorSessionService.ACTION_RESUME_RECORDING)
    }

    private fun hasBluetoothPermissions(): Boolean {
        val required = if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return required.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun requestBluetoothPermissions() {
        val required = if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        requestPermissions(required, REQUEST_BLUETOOTH_PERMISSIONS)
    }

    @Deprecated("Permission callback used for compatibility with Android 8 through 12")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATIONS) {
            if (leftAddress != null || rightAddress != null) beginRecording()
            return
        }
        if (requestCode == REQUEST_BLUETOOTH_PERMISSIONS && hasBluetoothPermissions()) {
            startScan()
            val intent = Intent(this, SensorSessionService::class.java)
            serviceBound = bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    private fun startScan() {
        if (!hasBluetoothPermissions()) return
        discovered.clear()
        devicesLayout.removeAllViews()
        emptyDevices.visibility = View.VISIBLE
        emptyDevices.text = "Scanning for OpenSki sensors…"
        scanButton.isEnabled = false
        scanButton.text = "Searching…"
        scanHint.text = "Searching nearby for 10 seconds"
        try {
            val manager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            scanner = manager.adapter?.bluetoothLeScanner
            val activeScanner = scanner
            if (activeScanner == null) {
                scanButton.isEnabled = true
                scanButton.text = "Bluetooth unavailable"
                scanHint.text = "Turn on Bluetooth and try again"
                return
            }
            val filter = android.bluetooth.le.ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(BleSensorClient.SERVICE_UUID)).build()
            activeScanner.startScan(listOf(filter), android.bluetooth.le.ScanSettings.Builder().build(), scanCallback)
            scanButton.postDelayed({ stopScan() }, SCAN_DURATION_MS)
        } catch (error: SecurityException) {
            scanButton.isEnabled = true
            scanButton.text = "Permission required"
            scanHint.text = "Allow Nearby devices access to scan"
        }
    }

    private fun stopScan() {
        try { scanner?.stopScan(scanCallback) } catch (_: SecurityException) { }
        scanButton.isEnabled = true
        scanButton.text = "Search again"
        if (discovered.isEmpty()) {
            emptyDevices.visibility = View.VISIBLE
            emptyDevices.text = "No sensors found. Move closer and try another scan."
            scanHint.text = "No OpenSki sensors found"
        } else {
            emptyDevices.visibility = View.GONE
            val hasUnassigned = discovered.keys.any { it != leftAddress && it != rightAddress }
            scanHint.text = "${discovered.size} sensor${if (discovered.size == 1) "" else "s"} found · " +
                if (hasUnassigned) "choose a boot for new sensors" else "saved boot assignments restored"
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val address = result.device.address
            if (discovered.putIfAbsent(address, result) == null) runOnUiThread { addDevice(result) }
        }

        override fun onScanFailed(errorCode: Int) = runOnUiThread {
            scanButton.isEnabled = true
            scanButton.text = "Search again"
            scanHint.text = "Scan failed ($errorCode) · check Bluetooth permissions"
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(refreshReadings)
        io.shutdown()
        super.onDestroy()
    }

    private inner class SensorPanel(title: String, marker: String, private val accent: Int) {
        private val dot = View(this@MainActivity).apply {
            background = rounded(MUTED, 50)
            layoutParams = LinearLayout.LayoutParams(dp(8), dp(8))
        }
        private val status = label("Not connected", 12f, MUTED)
        private val device = label("Set up in Sensor setup & recovery", 12f, MUTED)
        private val info = label("Battery and flash status appear when connected", 12f, MUTED)
        private val sequence = label("WAITING FOR SAMPLE", 10f, MUTED, true)
        private val acceleration = axisValues("AX", "AY", "AZ", accent)
        private val gyroscope = axisValues("GX", "GY", "GZ", accent)

        val view: View = cardContainer().apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(15), dp(16), dp(16))

            val header = LinearLayout(this@MainActivity).apply { gravity = Gravity.CENTER_VERTICAL }
            val badge = TextView(this@MainActivity).apply {
                text = marker
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(color(accent))
                background = rounded(BUTTON_SURFACE, 12, accent)
                layoutParams = LinearLayout.LayoutParams(dp(38), dp(38))
            }
            header.addView(badge)
            val heading = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(11), 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            }
            heading.addView(label(title, 13f, WHITE, true))
            heading.addView(device.apply { setPadding(0, dp(3), 0, 0) })
            header.addView(heading)
            header.addView(dot)
            addView(header)
            addView(status.apply { setPadding(0, dp(8), 0, 0) })
            addView(info.apply { setPadding(0, dp(5), 0, 0) })
            val raw = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            raw.addView(sequence.apply { setPadding(0, dp(15), 0, dp(8)) })
            raw.addView(label("ACCELERATION  ·  m/s²", 10f, MUTED, true))
            raw.addView(acceleration.view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
            raw.addView(label("ANGULAR VELOCITY  ·  rad/s", 10f, MUTED, true).apply {
                setPadding(0, dp(13), 0, 0)
            })
            raw.addView(gyroscope.view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
            addView(disclosure("Raw sensor readings", raw,inset=true),LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(10) })
        }

        fun setStatus(message: String) {
            status.text = message
            val live = message == "Live stream ready"
            status.setTextColor(color(if (live) accent else MUTED))
            dot.background = rounded(if (live) accent else MUTED, 50)
        }

        fun setInfo(message: String) { info.text = message }

        fun setDevice(name: String) { device.text = name }

        fun clearReadings() {
            sequence.text = "WAITING FOR SAMPLE"
            acceleration.setValues("—", "—", "—")
            gyroscope.setValues("—", "—", "—")
        }

        fun render(sample: SensorSample) {
            sequence.text = "SAMPLE ${sample.sequence}  ·  SENSOR TIME ${sample.timestampMs} ms"
            acceleration.setValues(
                sample.accelX.asReading(), sample.accelY.asReading(), sample.accelZ.asReading(),
            )
            gyroscope.setValues(
                sample.gyroX.asReading(3), sample.gyroY.asReading(3), sample.gyroZ.asReading(3),
            )
        }
    }

    private data class AxisValues(val view: LinearLayout, val values: List<TextView>) {
        fun setValues(x: String, y: String, z: String) {
            values[0].text = x
            values[1].text = y
            values[2].text = z
        }
    }

    private fun axisValues(xLabel: String, yLabel: String, zLabel: String, accent: Int): AxisValues {
        val values = mutableListOf<TextView>()
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(xLabel, yLabel, zLabel).forEach { axis ->
            val column = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(7), dp(4), dp(5))
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            }
            column.addView(label(axis, 10f, accent, true))
            val reading = label("—", 18f, WHITE, true).apply { setPadding(0, dp(3), 0, 0) }
            values.add(reading)
            column.addView(reading)
            row.addView(column)
        }
        return AxisValues(row, values)
    }

    private fun label(textValue: String, size: Float, tint: Int, bold: Boolean = false) =
        SkiUi.label(this, textValue, size, tint, bold)

    private fun rounded(fill: Int, radiusDp: Int, stroke: Int? = null): GradientDrawable =
        SkiUi.rounded(this, fill, radiusDp, stroke)

    private fun color(rgb: Int): Int = Color.rgb(Color.red(rgb), Color.green(rgb), Color.blue(rgb))
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun bottomMargin(value: Int) = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = value }
    private fun Float.asReading(digits: Int = 2): String = String.format(Locale.US, "%.$digits" + "f", this)

    companion object {
        private const val REQUEST_BLUETOOTH_PERMISSIONS = 41
        private const val REQUEST_VIDEO = 42
        private const val REQUEST_NOTIFICATIONS = 43
        private const val SCAN_DURATION_MS = 10_000L
        private val CANVAS = SkiUi.CANVAS
        private val SURFACE = SkiUi.SURFACE
        private val BUTTON_SURFACE = SkiUi.SURFACE_RAISED
        private val BORDER = SkiUi.BORDER
        private val WHITE = SkiUi.TEXT
        private val SUBTLE = SkiUi.SECONDARY
        private val MUTED = SkiUi.MUTED
        private val ACCENT = SkiUi.ACCENT
        private val SKY = SkiUi.SKY
    }
}
