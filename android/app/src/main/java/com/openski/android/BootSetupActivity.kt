package com.openski.android

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** Preparation uses the same service commands and capture protections as advanced tools. */
class BootSetupActivity : Activity() {
    private var service: SensorSessionService? = null
    private var bound = false
    private var visible = false
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var notice: TextView
    private lateinit var issues: TextView
    private lateinit var recovery: TextView
    private lateinit var devices: LinearLayout
    private lateinit var recoverButton: View
    private lateinit var connectButton: View
    private val bootLabels = mutableMapOf<String, TextView>()
    private val mountingButtons = mutableMapOf<String, View>()
    private val zeroButtons = mutableMapOf<String, View>()
    private val modeButtons = mutableMapOf<String, View>()
    private val forgetButtons = mutableMapOf<String, View>()
    private var last: PreparationSnapshot? = null
    private var loading = false
    private var pendingAction: String? = null
    private var advancedExpanded = false
    private var scanner: android.bluetooth.le.BluetoothLeScanner? = null
    private val found = mutableSetOf<String>()
    private var picker: AlertDialog? = null
    private val store by lazy { ProgressStore(this) }
    private val stopScanTask = Runnable { stopScan(); notice.text = if (found.isEmpty()) "No sensors found. Keep them powered and nearby, then search again." else "Choose a sensor and assign it to a boot." }
    private val refresh = object : Runnable {
        override fun run() {
            if (!visible) return
            if (!loading) service?.let { connected ->
                loading = true
                connected.loadPreparation { result ->
                    loading = false
                    if (visible) result.onSuccess(::update).onFailure {
                        recovery.text = "Could not read recovery status. Saved data has been kept. Return to this screen to retry."
                    }
                }
            }
            handler.postDelayed(this, 1000)
        }
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as? SensorSessionService.LocalBinder)?.service
            pendingAction?.let { pendingAction = null; perform(it) }
            handler.removeCallbacks(refresh); handler.post(refresh)
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null; loading = false; notice.text = "Reconnecting to the sensor service…" }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        Snow.configureWindow(this)
        pendingAction = state?.getString("pending_action")
        advancedExpanded = state?.getBoolean("advanced_expanded") ?: false
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = Snow.dp(this@BootSetupActivity, 20)
            setPadding(pad, pad, pad, pad)
        }
        fun text(value: String, type: Snow.Type = Snow.Type.BODY): TextView = Snow.text(this, value, type).also {
            column.addView(it, Snow.fullWidth(0, this, 12))
        }
        fun action(label: String, kind: Snow.ButtonKind = Snow.ButtonKind.SECONDARY, parent: LinearLayout = column, block: () -> Unit) = Snow.button(this, label, kind, block).also {
            parent.addView(it, Snow.fullWidth(0, this, 10))
        }
        text("Prepare your boots", Snow.Type.HERO)
        text("Power the sensors and keep them nearby. A saved assignment is separate from a live connection.")
        notice = text("Connect saved sensors or search for a new pair.")
        issues = text("Checking readiness…", Snow.Type.STRONG)
        recovery = text("Checking saved recordings…")
        connectButton = action("Connect saved boots and resume recovery") { permittedAction("connect") }
        action("Search for OpenSki sensors", Snow.ButtonKind.PRIMARY) { permittedAction("scan") }
        devices = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(devices)
        val advanced = PreparationDetails(this, "More preparation options", advancedExpanded) { advancedExpanded = it }
        for (side in RunController.SIDES) {
            val name = if (side == "L") "Left" else "Right"
            text("$name boot", Snow.Type.TITLE)
            bootLabels[side] = text("Checking saved assignment…")
            mountingButtons[side] = action("$name: choose mounting") {
                if (last?.captureActive == false && picker?.isShowing != true)
                    picker = MountingPicker.show(this, store, side, onCancel = { picker = null },
                        canSave = { service?.canConfigureMounting() == true }, onDone = { picker = null })
            }
            zeroButtons[side] = action("$name: set neutral zero") {
                AlertDialog.Builder(this).setTitle("Stand upright and hold still")
                    .setMessage("Keep the boot still while its sensor captures neutral. Watch the status for confirmation.")
                    .setPositiveButton("Set neutral") { _, _ -> notice.text = if (service?.zeroSensor(side) == true) "Neutral requested. Wait for the boot to confirm." else "Neutral could not be requested. Finish active capture/recovery and reconnect first." }
                    .setNegativeButton("Cancel", null).show()
            }
            modeButtons[side] = action("$name: return to Training", parent = advanced.body) {
                notice.text = if (service?.setSensorMode(side, false) == true) "Training requested. Waiting for the boot's reported mode."
                    else "Finish capture and recovery, then reconnect before changing mode."
            }
            forgetButtons[side] = action("$name: forget assignment", parent = advanced.body) {
                AlertDialog.Builder(this).setTitle("Forget $name boot?")
                    .setMessage("Recovery and active recordings protect their sensor assignments. The sensor's recorded data is not erased.")
                    .setPositiveButton("Forget") { _, _ -> notice.text = if (service?.forgetSensor(side) == true) "Assignment removed." else "Assignment is protected while capture or recovery is pending." }
                    .setNegativeButton("Cancel", null).show()
            }
        }
        action("Swap left and right assignments", parent = advanced.body) { notice.text = if (service?.swapSensors() == true) "Assignments swapped. Connect saved boots to reconnect." else "Assignments are protected while capture or recovery is pending." }
        recoverButton = action("Recover saved recordings / retry") { permittedAction("recover") }
        recoverButton.isEnabled = false
        action("Return to active practice or recording") { returnToCapture() }
        action("Coaching and test sounds") { startActivity(Intent(this, CoachingActivity::class.java)) }
        action("Bluetooth and audio devices", parent = advanced.body) { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
        action("Phone storage", parent = advanced.body) { startActivity(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)) }
        action("App permissions", parent = advanced.body) { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName"))) }
        action("Review/export recordings and advanced tools", parent = advanced.body) { startActivity(Intent(this, MainActivity::class.java)) }
        column.addView(advanced, Snow.fullWidth(0, this, 12))
        text("Recovery keeps the sensor copy until the phone copy passes validation. Returning to Training is available after recovery; the displayed mode changes only when the boot reports it.", Snow.Type.CAPTION)
        val layout = PhaseLayout(this)
        layout.content(column, true)
        layout.controls(listOf(PhaseLayout.Action("done", "Done", Snow.ButtonKind.SECONDARY) { finish() }))
        SkiUi.applyInsets(layout, ime = true); setContentView(layout)
    }

    override fun onStart() {
        super.onStart(); visible = true; loading = false
        bound = bindService(Intent(this, SensorSessionService::class.java), connection, Context.BIND_AUTO_CREATE)
        handler.post(refresh)
    }
    override fun onStop() {
        visible = false; stopScan(); handler.removeCallbacks(refresh)
        picker?.dismiss(); picker = null
        if (bound) unbindService(connection)
        bound = false; service = null
        super.onStop()
    }
    override fun onSaveInstanceState(state: Bundle) {
        state.putString("pending_action", pendingAction)
        state.putBoolean("advanced_expanded", advancedExpanded)
        super.onSaveInstanceState(state)
    }

    private fun update(snapshot: PreparationSnapshot) {
        last = snapshot
        recoverButton.isEnabled = !snapshot.captureActive && snapshot.recovery.items.any { it.canRetry }
        recoverButton.visibility = if (snapshot.recovery.items.any { it.canRetry }) View.VISIBLE else View.GONE
        recoverButton.alpha = if (recoverButton.isEnabled) 1f else .45f
        connectButton.visibility = if (snapshot.boots.any { it.address != null }) View.VISIBLE else View.GONE
        issues.text = if (snapshot.readiness.issues.isEmpty()) "Ready for a run. Neutral zero is captured when you start."
            else snapshot.readiness.issues.joinToString("\n") { it.message }
        recovery.text = if (snapshot.recovery.items.isEmpty()) "No sensor recovery is pending."
            else snapshot.recovery.items.joinToString("\n\n") { "${it.label()}${if (it.detail.isBlank()) "" else "\n${it.detail}"}" }
        snapshot.boots.forEach { b ->
            bootLabels[b.side]?.text = "${b.address?.let { "Assigned sensor · ${it.takeLast(5)}" } ?: "No sensor assigned"}\n" +
                "${if (b.connected) "Live connection ready" else "Not connected"}\nMounting: ${b.mounting ?: "not set"}\n" +
                "Reported mode: ${when (b.onSnow) { true -> "On snow"; false -> "Training"; null -> "unknown" }}\n" +
                "Neutral zero: ${if (b.zeroed) "confirmed" else "not confirmed"}${b.battery?.let { "\nBattery: $it%" } ?: ""}"
            mountingButtons[b.side]?.isEnabled = b.address != null && !snapshot.captureActive
            zeroButtons[b.side]?.isEnabled = b.connected && !snapshot.captureActive && !snapshot.recovery.pending
            modeButtons[b.side]?.isEnabled = b.connected && b.onSnow != false && !snapshot.captureActive && !snapshot.recovery.pending
            forgetButtons[b.side]?.isEnabled = b.address != null && !snapshot.captureActive && !snapshot.recovery.pending
            listOf(mountingButtons[b.side], zeroButtons[b.side], modeButtons[b.side], forgetButtons[b.side]).forEach { control ->
                control?.visibility = if (b.address != null) View.VISIBLE else View.GONE
                control?.alpha = if (control?.isEnabled == true) 1f else .45f
            }
        }
    }

    private fun permitted(): Boolean = (if (Build.VERSION.SDK_INT >= 31)
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else listOf(Manifest.permission.ACCESS_FINE_LOCATION)).all { checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED }

    private fun permittedAction(action: String) {
        if (!permitted()) {
            pendingAction = action
            requestPermissions(if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
                else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 71)
        } else perform(action)
    }
    @Deprecated("Platform permission callback")
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code != 71) return
        if (permitted()) pendingAction?.let { if (service != null) { pendingAction = null; perform(it) } }
        else { pendingAction = null; notice.text = "Allow Nearby devices (or Location on older Android) in App permissions, then retry. Demo needs no Bluetooth permission." }
    }
    private fun perform(action: String) {
        val connected = service ?: run { pendingAction = action; notice.text = "Waiting for the sensor service…"; return }
        runCatching {
            when (action) {
                "scan" -> scan()
                "recover" -> { connected.recoverFlash(); notice.text = "Recovery requested. Keep sensors powered and nearby; progress appears above." }
                else -> { connected.resumeSavedConnections(); notice.text = "Reconnecting saved sensors…" }
            }
        }.onFailure { notice.text = "Could not start. Check Bluetooth, permissions and phone storage, then retry." }
    }
    private fun scan() {
        stopScan(); found.clear(); devices.removeAllViews()
        scanner = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter?.bluetoothLeScanner
        val active = scanner ?: run { notice.text = "Turn on Bluetooth in Bluetooth and audio devices, then search again."; return }
        try {
            active.startScan(listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(BleSensorClient.SERVICE_UUID)).build()), ScanSettings.Builder().build(), scanCallback)
        } catch (_: SecurityException) { scanner = null; notice.text = "Bluetooth permission changed. Open App permissions, then retry."; return }
        notice.text = "Searching for 10 seconds…"
        handler.postDelayed(stopScanTask, 10_000)
    }
    private fun stopScan() {
        handler.removeCallbacks(stopScanTask)
        try { scanner?.stopScan(scanCallback) } catch (_: SecurityException) { /* permission may be revoked while searching */ }
        scanner = null
    }
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(type: Int, result: ScanResult) { runOnUiThread {
            if (!visible || scanner == null || !found.add(result.device.address)) return@runOnUiThread
            val label = "${result.scanRecord?.deviceName ?: "OpenSki sensor"} · ${result.device.address.takeLast(5)}"
            devices.addView(Snow.button(this@BootSetupActivity, "Assign $label", Snow.ButtonKind.SECONDARY) {
                AlertDialog.Builder(this@BootSetupActivity).setTitle("Which boot holds this sensor?")
                    .setItems(arrayOf("Left boot", "Right boot")) { _, index ->
                        val side = RunController.SIDES[index]
                        if (service?.canAssign(side, result.device.address) == true) {
                            runCatching { service?.connect(side, result.device) }
                                .onSuccess { notice.text = "Assigned ${if (side == "L") "left" else "right"} boot. Waiting for a live connection." }
                                .onFailure { notice.text = "Could not connect. Check Bluetooth and permissions." }
                        } else notice.text = "This sensor is already assigned to the other boot, or the assignment is protected by active capture/recovery."
                    }.show()
            }, Snow.fullWidth(0, this@BootSetupActivity, 10))
        } }
        override fun onScanFailed(code: Int) { runOnUiThread { stopScan(); notice.text = "Search failed ($code). Check Bluetooth and permissions, then retry." } }
    }
    private fun returnToCapture() {
        val connected = service ?: return
        val drill = connected.drillState()
        when {
            drill.phase !in listOf(DrillPhase.IDLE, DrillPhase.DONE) -> startActivity(Intent(this, DrillActivity::class.java).putExtra("drill", drill.drill?.id))
            connected.runState().phase !in listOf(RunPhase.READY, RunPhase.DONE) -> startActivity(Intent(this, RunActivity::class.java))
            connected.coachState().running -> startActivity(Intent(this, CoachingActivity::class.java))
            connected.sessionId() != null -> startActivity(Intent(this, MainActivity::class.java))
            else -> notice.text = "No practice or recording is active."
        }
    }
}
