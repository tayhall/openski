package com.openski.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import java.util.concurrent.Executors

/** Owns BLE links and recording so Android can keep them alive with the screen off. */
class SensorSessionService : Service() {
    interface Listener {
        fun onSensorStatus(side: String, message: String)
        fun onSensorSample(side: String, sample: SensorSample)
        fun onRecordingChanged(sessionId: String?, message: String)
    }

    inner class LocalBinder : Binder() { val service: SensorSessionService get() = this@SensorSessionService }
    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val store by lazy { LocalSessionStore(this) }
    private val clients = mutableMapOf<String, BleSensorClient>()
    private val addresses = mutableMapOf<String, String>()
    private val reconnectAttempts = mutableMapOf<String, Int>()
    private val reconnectTasks = mutableMapOf<String, Runnable>()
    private val sensorStatuses = mutableMapOf<String, String>()
    private val pendingSamples = mutableListOf<RecordedSample>()
    private var recordingWakeLock: PowerManager.WakeLock? = null
    @Volatile private var activeSessionId: String? = null
    @Volatile private var listener: Listener? = null

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (activeSessionId == null) activeSessionId = store.latestOpenSessionId()
        if (activeSessionId != null) {
            startForeground(NOTIFICATION_ID, notification("Recording boot motion · sensors reconnect automatically"))
            acquireRecordingWakeLock()
            restoreConnections()
        }
        when (intent?.action) {
            ACTION_START_RECORDING -> startRecording()
            ACTION_STOP_RECORDING -> stopRecording()
        }
        return START_STICKY
    }

    fun setListener(value: Listener?) {
        listener = value
        if (value != null) {
            addresses.keys.forEach { side -> value.onSensorStatus(side, sensorStatuses[side] ?: "Connecting") }
            activeSessionId?.let { value.onRecordingChanged(it, "Recording continues in background" ) }
        }
    }

    fun sessionId(): String? = activeSessionId

    fun connect(side: String, device: BluetoothDevice) {
        addresses[side] = device.address
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("sensor_$side", device.address).apply()
        reconnectAttempts[side] = 0
        reconnectTasks.remove(side)?.let(mainHandler::removeCallbacks)
        connectAddress(side, device.address)
    }

    fun restoreConnections() {
        listOf("L", "R").forEach { side ->
            val address = getSharedPreferences(PREFS, MODE_PRIVATE).getString("sensor_$side", null) ?: return@forEach
            addresses[side] = address
            if (clients.containsKey(side)) return@forEach
            try {
                val manager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
                connectAddress(side, address, manager.adapter.getRemoteDevice(address))
            } catch (_: Exception) {
                reportStatus(side, "Saved sensor unavailable; scan to reconnect")
            }
        }
    }

    private fun connectAddress(side: String, address: String, device: BluetoothDevice? = null) {
        clients.remove(side)?.disconnect()
        val remote = device ?: try {
            val manager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
            manager.adapter.getRemoteDevice(address)
        } catch (_: Exception) { null }
        if (remote == null) {
            reportStatus(side, "Sensor unavailable")
            scheduleReconnect(side)
            return
        }
        reportStatus(side, "Connecting")
        val client = BleSensorClient(this, { message ->
            mainHandler.post {
                reportStatus(side, message)
                if (message == "Disconnected" || message.startsWith("Connection failed") ||
                    message.startsWith("OpenSki service not found") || message.startsWith("Subscription failed") ||
                    message.startsWith("Could not enable") || message.startsWith("Live notification descriptor missing")) {
                    scheduleReconnect(side)
                }
                if (message == "Live stream ready") reconnectAttempts[side] = 0
            }
        }, { sample ->
            mainHandler.post { listener?.onSensorSample(side, sample) }
            activeSessionId?.let { id -> synchronized(pendingSamples) {
                if (activeSessionId == id) pendingSamples.add(RecordedSample(side, System.currentTimeMillis(), sample))
            } }
        })
        clients[side] = client
        try { client.connect(remote) } catch (_: SecurityException) {
            reportStatus(side, "Bluetooth permission required")
        } catch (_: Exception) {
            reportStatus(side, "Could not connect; retrying")
            scheduleReconnect(side)
        }
    }

    private fun scheduleReconnect(side: String) {
        val address = addresses[side] ?: return
        reconnectTasks.remove(side)?.let(mainHandler::removeCallbacks)
        val attempt = (reconnectAttempts[side] ?: 0) + 1
        reconnectAttempts[side] = attempt
        val delay = (2_000L shl (attempt - 1).coerceAtMost(4)).coerceAtMost(30_000L)
        reportStatus(side, "Disconnected · reconnecting in ${delay / 1000}s")
        if (activeSessionId != null) updateNotification("$side boot disconnected · reconnecting in ${delay / 1000}s")
        val task = Runnable { connectAddress(side, address) }
        reconnectTasks[side] = task
        mainHandler.postDelayed(task, delay)
    }

    private fun startRecording() {
        if (activeSessionId != null) return
        try {
            startForeground(NOTIFICATION_ID, notification("Recording boot motion · keep sensors nearby"))
            val id = java.util.UUID.randomUUID().toString()
            store.createSession(id, System.currentTimeMillis())
            activeSessionId = id
            acquireRecordingWakeLock()
            listener?.onRecordingChanged(id, "Recording · continues with screen off")
        } catch (error: Exception) {
            releaseRecordingWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            listener?.onRecordingChanged(null, "Could not start recording: ${error.message ?: "storage error"}")
        }
    }

    private fun stopRecording() {
        val id = activeSessionId ?: return
        activeSessionId = null
        flush(id)
        io.execute {
            store.finishSession(id, System.currentTimeMillis())
            mainHandler.post { listener?.onRecordingChanged(null, "Session saved on this phone") }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        releaseRecordingWakeLock()
        stopSelf()
    }

    private val flushTask = object : Runnable {
        override fun run() { flush(activeSessionId); mainHandler.postDelayed(this, 500L) }
    }

    private fun flush(id: String?) {
        if (id == null) return
        val batch = synchronized(pendingSamples) { pendingSamples.toList().also { pendingSamples.clear() } }
        if (batch.isNotEmpty()) io.execute { store.saveSamples(id, batch) }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        mainHandler.post(flushTask)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(flushTask)
        reconnectTasks.values.forEach(mainHandler::removeCallbacks)
        activeSessionId?.let { id ->
            activeSessionId = null
            flush(id)
            io.execute { store.finishSession(id, System.currentTimeMillis()) }
        }
        clients.values.forEach(BleSensorClient::disconnect)
        clients.clear()
        releaseRecordingWakeLock()
        io.shutdown()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "OpenSki recording", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun notification(message: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        return builder.setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("OpenSki recording").setContentText(message).setOngoing(true).build()
    }

    private fun updateNotification(message: String) {
        if (activeSessionId == null) return
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(message))
    }

    private fun acquireRecordingWakeLock() {
        if (recordingWakeLock?.isHeld == true) return
        val power = getSystemService(POWER_SERVICE) as PowerManager
        recordingWakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:OpenSkiRecording").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseRecordingWakeLock() {
        recordingWakeLock?.let { if (it.isHeld) it.release() }
        recordingWakeLock = null
    }

    private fun reportStatus(side: String, message: String) {
        sensorStatuses[side] = message
        listener?.onSensorStatus(side, message)
    }

    companion object {
        const val ACTION_START_RECORDING = "com.openski.android.START_RECORDING"
        const val ACTION_STOP_RECORDING = "com.openski.android.STOP_RECORDING"
        const val CHANNEL_ID = "openski_recording"
        const val NOTIFICATION_ID = 107
        const val PREFS = "openski_sensor_assignments"
    }
}
