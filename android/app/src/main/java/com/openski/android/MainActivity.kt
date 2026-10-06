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
            mainHandler.postDelayed(this, 200L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = color(CANVAS)
        window.navigationBarColor = color(CANVAS)
        window.decorView.systemUiVisibility = 0
        sessionStore = LocalSessionStore(this)
        buildScreen()
        mainHandler.post(refreshReadings)
        refreshHistory()
        if (!hasBluetoothPermissions()) requestBluetoothPermissions()
    }

    private fun buildScreen() {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(28))
            setBackgroundColor(color(CANVAS))
        }

        val brandRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val mark = TextView(this).apply {
            text = "O"
            textSize = 20f
            setTextColor(color(CANVAS))
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            background = rounded(ACCENT, 14)
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(42))
        }
        brandRow.addView(mark)
        val brandText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        brandText.addView(label("OPENSKI  /  FIELD TEST", 11f, MUTED, true))
        brandText.addView(label("Motion dashboard", 25f, WHITE, true).apply {
            setPadding(0, dp(2), 0, 0)
        })
        brandRow.addView(brandText)
        page.addView(brandRow)

        page.addView(label(
            "Live boot motion, flash recovery and session review.",
            14f, SUBTLE,
        ).apply { setPadding(0, dp(12), 0, dp(20)) })

        scanButton = Button(this).apply {
            text = "Search for sensors"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            setTextColor(color(CANVAS))
            background = rounded(ACCENT, 16)
            minHeight = dp(54)
            setOnClickListener { ensurePermissionAndScan() }
        }
        page.addView(scanButton, LinearLayout.LayoutParams(-1, dp(54)))
        scanHint = label("Scans for nearby OpenSki boot sensors", 12f, MUTED).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(7), 0, dp(18))
        }
        page.addView(scanHint)

        page.addView(sectionHeading("NEARBY SENSORS", "Saved sensors reconnect to their boot automatically"))
        page.addView(Button(this).apply {
            text = "Manage saved sensors"; isAllCaps = false
            setOnClickListener { manageSensors() }
        }, bottomMargin(dp(8)))
        val devicesCard = cardContainer().apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(13), dp(16), dp(13))
        }
        emptyDevices = label("No sensors found. Start a scan with the boot sensors powered on.", 13f, MUTED)
        devicesCard.addView(emptyDevices)
        devicesLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        devicesCard.addView(devicesLayout)
        page.addView(devicesCard, bottomMargin(dp(18)))

        page.addView(sectionHeading("BOOT STREAMS", "Live samples arrive at up to 50 Hz"))
        leftPanel = SensorPanel("LEFT BOOT", "L", ACCENT)
        rightPanel = SensorPanel("RIGHT BOOT", "R", SKY)
        page.addView(leftPanel.view, bottomMargin(dp(12)))
        page.addView(rightPanel.view, bottomMargin(dp(18)))

        page.addView(sectionHeading("SKI SESSION", "Record both boot streams, even with the screen off"))
        val sessionCard = cardContainer().apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        recordingStatus = label("Ready to record when sensors are connected", 13f, SUBTLE)
        sessionCard.addView(recordingStatus)
        recordButton = Button(this).apply {
            text = "Start recording"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            setTextColor(color(CANVAS))
            background = rounded(ACCENT, 14)
            minHeight = dp(50)
            setOnClickListener { if (activeSessionId == null) startSession() else stopSession() }
        }
        sessionCard.addView(recordButton, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(12) })
        sessionCard.addView(Button(this).apply {
            text = "Recover sensor flash"; isAllCaps = false
            setOnClickListener { sensorService?.recoverFlash() ?: run { recordingStatus.text = "Wait for the sensor service to connect" } }
        })
        sessionCard.addView(label("Phone free space: ${android.os.StatFs(filesDir.absolutePath).availableBytes / (1024 * 1024)} MB", 12f, MUTED))
        page.addView(sessionCard, bottomMargin(dp(18)))

        page.addView(sectionHeading("SESSION HISTORY", "Stored locally on this phone"))
        historyLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(historyLayout)

        val footer = cardContainer().apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(13), dp(15), dp(13))
        }
        footer.addView(label("LIVE TELEMETRY  ·  RAW IMU DATA", 11f, ACCENT, true))
        footer.addView(label(
            "Keep sensors powered after stopping while flash is recovered. Session details include quality, graphs, video alignment, export and experimental turn candidates.",
            12f, SUBTLE,
        ).apply { setPadding(0, dp(6), 0, 0) })
        page.addView(footer)

        setContentView(ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(color(CANVAS))
            addView(page)
        })
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
        textSize = 10f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setTextColor(color(tint))
        background = rounded(BUTTON_SURFACE, 10, tint)
        setPadding(dp(10), dp(9), dp(10), dp(9))
        isClickable = true
        isFocusable = true
        contentDescription = "Assign sensor to $textValue boot"
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

    private fun startSession() {
        if (leftAddress == null && rightAddress == null) {
            recordingStatus.text = "Connect at least one boot sensor before recording"
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
        try {
            if (action == SensorSessionService.ACTION_START_RECORDING || action == SensorSessionService.ACTION_RESUME_RECORDING)
                startForegroundService(intent) else startService(intent)
        } catch (error: Exception) {
            recordingStatus.text = "Could not start sensor recording: ${error.message ?: "service error"}"
        }
    }

    private fun renderRecordingState(sessionId: String?, message: String) {
        recordButton.text = if (sessionId == null) "Start recording" else "Stop recording"
        recordButton.background = rounded(if (sessionId == null) ACCENT else 0xFFFF806E.toInt(), 14)
        recordingStatus.text = message
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
                        addView(label("No sessions yet. Start recording with a sensor connected.", 13f, MUTED))
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
        addView(label(session.title.ifBlank { formatDate(session.startedAtMs) }, 14f, WHITE, true))
        val duration = ((session.endedAtMs ?: System.currentTimeMillis()) - session.startedAtMs).coerceAtLeast(0) / 1000
        addView(label("${duration / 60}m ${duration % 60}s  ·  L ${session.leftSamples}  ·  R ${session.rightSamples}", 12f, SUBTLE).apply {
            setPadding(0, dp(5), 0, 0)
        })
        addView(label(session.videoName?.let { "VIDEO  ·  $it" } ?: "NO VIDEO ATTACHED", 10f, ACCENT, true).apply {
            setPadding(0, dp(7), 0, 0)
        })
        setOnClickListener { showSession(session.id) }
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
        private val device = label("Assign a sensor from the list above", 11f, MUTED)
        private val info = label("Battery not reported · checking flash", 11f, MUTED)
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
            addView(sequence.apply { setPadding(0, dp(15), 0, dp(8)) })
            addView(label("ACCELERATION  ·  m/s²", 10f, MUTED, true))
            addView(acceleration.view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
            addView(label("ANGULAR VELOCITY  ·  rad/s", 10f, MUTED, true).apply {
                setPadding(0, dp(13), 0, 0)
            })
            addView(gyroscope.view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
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
        TextView(this).apply {
            text = textValue
            textSize = size
            setTextColor(color(tint))
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun rounded(fill: Int, radiusDp: Int, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color(fill))
            cornerRadius = dp(radiusDp).toFloat()
            stroke?.let { setStroke(dp(1), color(it)) }
        }

    private fun color(rgb: Int): Int = Color.rgb(Color.red(rgb), Color.green(rgb), Color.blue(rgb))
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun bottomMargin(value: Int) = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = value }
    private fun Float.asReading(digits: Int = 2): String = String.format(Locale.US, "%.$digits" + "f", this)

    companion object {
        private const val REQUEST_BLUETOOTH_PERMISSIONS = 41
        private const val REQUEST_VIDEO = 42
        private const val REQUEST_NOTIFICATIONS = 43
        private const val SCAN_DURATION_MS = 10_000L
        private val CANVAS = 0xFF0B1116.toInt()
        private val SURFACE = 0xFF141D24.toInt()
        private val BUTTON_SURFACE = 0xFF1C2931.toInt()
        private val BORDER = 0xFF26343D.toInt()
        private val WHITE = 0xFFF1F5F5.toInt()
        private val SUBTLE = 0xFFB3C0C6.toInt()
        private val MUTED = 0xFF71818A.toInt()
        private val ACCENT = 0xFFC7F36B.toInt()
        private val SKY = 0xFF74D5E8.toInt()
    }
}
