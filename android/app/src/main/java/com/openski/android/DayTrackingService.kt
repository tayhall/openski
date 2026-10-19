package com.openski.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import java.util.UUID
import java.util.concurrent.Executors

/** What day tracking is doing, for the screens. [error] is shown as is; null means nothing is wrong. */
data class DayTrackingStatus(
    val dayId: String? = null,
    val tracking: Boolean = false,
    val startedAtMs: Long? = null,
    /** Fixes received since tracking (re)started in this process. */
    val fixes: Int = 0,
    val lastFixElapsedMs: Long? = null,
    val lastAccuracyM: Float? = null,
    val intervalMs: Long = DayTrackingConfig.MOVING_INTERVAL_MS,
    /** True when the phone has a pressure sensor; null before tracking has looked. */
    val barometer: Boolean? = null,
    val gpsOn: Boolean = false,
    val error: String? = null,
)

/**
 * Records a whole ski day from the phone's location (and barometer where present), independent of the boot sensors
 * and of [SensorSessionService]. A foreground service of type location with its own notification and a Stop action.
 * Fixes are buffered ([FixBatcher]) and written in small transactions on one background thread; the interval follows
 * [CadencePlanner]. A partial wake lock is held while tracking so the barometer keeps reading with the screen off.
 * The open day is resumed when the system restarts the service (START_STICKY) or when the day screen asks.
 * EXPERIMENTAL: the battery cost of whole-day tracking is unmeasured.
 */
class DayTrackingService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val store by lazy { LocalSessionStore(this) }
    private val days by lazy { DayStore(store) }
    private val gps by lazy { GpsListener(this, ::onFix) }
    private val cadence = CadencePlanner()
    private val batcher = FixBatcher()
    private val baroWindow = BaroWindow()
    private var sensorManager: SensorManager? = null
    private var baroListener: SensorEventListener? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var dayId: String? = null
    private var foreground = false
    private var lastNotification: String? = null

    private val tick = object : Runnable {
        override fun run() {
            val id = dayId ?: return
            if (!gps.running) startGps()   // location switched back on, or a refused start
            acquireWakeLock()
            if (batcher.pending > 0 && lastFlushAt + DayTrackingConfig.BATCH_AGE_MS < SystemClock.elapsedRealtime()) write(id, batcher.drain())
            updateNotification()
            main.postDelayed(this, TICK_MS)
        }
    }
    private var lastFlushAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "OpenSki ski day tracking", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopDay(); return START_NOT_STICKY }
            ACTION_START -> beginOrResume(newDay = true)
            else -> beginOrResume(newDay = false)   // ACTION_RESUME, or a restart by the system after the process died
        }
        return if (dayId != null) START_STICKY else START_NOT_STICKY
    }

    /** Resumes the open phone day, or (with [newDay]) starts one. Refuses visibly when location is not allowed. */
    private fun beginOrResume(newDay: Boolean) {
        if (dayId != null) { goForeground(); return }
        if (!gps.permitted) {
            fail("Location permission is missing, so the day is not being tracked. Allow precise location for OpenSki, then start again.")
            return
        }
        val open = try { days.openPhoneDay() } catch (e: Exception) { fail("Could not read saved days: ${e.message}"); return }
        val day = open ?: if (newDay) {
            val now = System.currentTimeMillis()
            SkiDay(UUID.randomUUID().toString(), DayRules.defaultTitle(now), now, null, DaySource.PHONE, null, "", DayTags(), now)
                .also { d -> try { days.create(d) } catch (e: Exception) { fail("Could not create the day: ${e.message}"); return } }
        } else null
        if (day == null) { status = DayTrackingStatus(); stopSelf(); return }
        dayId = day.id
        status = DayTrackingStatus(day.id, true, day.startedAtMs, barometer = null)
        if (!goForeground()) return
        startBarometer()
        startGps()
        acquireWakeLock()
        main.removeCallbacks(tick)
        main.postDelayed(tick, TICK_MS)
    }

    /** False (and stopped, with the reason shown) when the platform refused the foreground start. */
    private fun goForeground(): Boolean {
        return try {
            val n = notification(notificationText())
            if (android.os.Build.VERSION.SDK_INT >= 29)
                startForeground(NOTIFICATION_ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            else startForeground(NOTIFICATION_ID, n)
            foreground = true
            true
        } catch (e: Exception) {
            // SecurityException without location permission (Android 14+), or a start from the background refused.
            teardown(finishDay = false)
            fail("Android refused to start day tracking (${e.javaClass.simpleName}: ${e.message}). Open the ski day screen and start again.")
            false
        }
    }

    private fun startGps() {
        val ok = gps.start(cadence.intervalMs)
        val error = when {
            ok -> null
            !gps.permitted -> "Location permission was removed; tracking is paused. Allow precise location for OpenSki."
            !gps.providerOn -> "Location is switched off; tracking resumes when it is on."
            else -> "The phone refused location updates; retrying."
        }
        status = status.copy(gpsOn = ok, error = error ?: status.error?.takeUnless { it.startsWith("Location") || it.startsWith("The phone refused") })
    }

    private fun startBarometer() {
        val manager = getSystemService(SensorManager::class.java)
        val sensor = manager?.getDefaultSensor(Sensor.TYPE_PRESSURE)
        if (manager == null || sensor == null) { status = status.copy(barometer = false); return }
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                event.values.firstOrNull()?.let { baroWindow.add(it.toDouble(), SystemClock.elapsedRealtime()) }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        val ok = manager.registerListener(listener, sensor, DayTrackingConfig.BARO_PERIOD_US, main)
        sensorManager = manager
        baroListener = if (ok) listener else null
        status = status.copy(barometer = ok)
    }

    private fun onFix(fix: GpsFix) {
        val id = dayId ?: return
        val now = SystemClock.elapsedRealtime()
        val row = DayFix(fix, baroWindow.altitudeAt(now))
        val interval = cadence.onFix(fix)
        if (interval != status.intervalMs) { gps.start(interval) }
        status = status.copy(fixes = status.fixes + 1, lastFixElapsedMs = now, lastAccuracyM = fix.accuracyM, intervalMs = interval)
        batcher.add(row, now)?.let { write(id, it) }
    }

    /** Writes [rows] on the io thread; a failed batch goes back to the buffer and is retried with the next one. */
    private fun write(id: String, rows: List<DayFix>) {
        if (rows.isEmpty()) return
        lastFlushAt = SystemClock.elapsedRealtime()
        io.execute {
            try {
                days.addFixes(id, rows)
                main.post { if (status.error?.startsWith(SAVE_ERROR) == true) status = status.copy(error = null) }
            } catch (e: Exception) {
                main.post {
                    batcher.requeue(rows, SystemClock.elapsedRealtime())
                    status = status.copy(error = "$SAVE_ERROR, retrying: ${e.message ?: e.javaClass.simpleName}")
                }
            }
        }
    }

    private fun stopDay() {
        val id = dayId
        teardown(finishDay = true)
        status = DayTrackingStatus(id, false)
        stopSelf()
    }

    /** Stops listening and writes what is buffered; with [finishDay] the day is closed at its last fix (or now). */
    private fun teardown(finishDay: Boolean) {
        main.removeCallbacks(tick)
        gps.stop()
        baroListener?.let { l -> sensorManager?.unregisterListener(l) }
        baroListener = null
        val id = dayId
        dayId = null
        if (id != null) {
            val rest = batcher.drain()
            io.execute {
                try { if (rest.isNotEmpty()) days.addFixes(id, rest) } catch (_: Exception) { /* reported below if the finish fails too */ }
                if (finishDay) try {
                    val end = days.fixes(id).lastOrNull()?.fix?.timeMs ?: System.currentTimeMillis()
                    days.finish(id, maxOf(end, days.day(id)?.startedAtMs ?: end))
                } catch (e: Exception) { main.post { status = status.copy(error = "Could not close the day: ${e.message}") } }
            }
        }
        releaseWakeLock()
        if (foreground) { stopForeground(STOP_FOREGROUND_REMOVE); foreground = false }
    }

    private fun fail(message: String) {
        status = status.copy(tracking = false, error = message)
        // A plain notification as well, so the refusal is visible when no screen is open.
        try {
            getSystemService(NotificationManager::class.java).notify(ERROR_NOTIFICATION_ID, Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert).setContentTitle("Ski day not tracking").setContentText(message)
                .setStyle(Notification.BigTextStyle().bigText(message)).setContentIntent(openDay()).setAutoCancel(true).build())
        } catch (_: Exception) { /* notifications not allowed: the day screen shows the error */ }
        stopSelf()
    }

    private fun notificationText(): String {
        val s = status
        val started = s.startedAtMs?.let { java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(it)) }
        return when {
            s.error != null -> s.error
            !s.gpsOn -> "Ski day · waiting for location"
            else -> "Ski day since ${started ?: "today"} · ${s.fixes} fixes · " +
                if (s.intervalMs > DayTrackingConfig.MOVING_INTERVAL_MS) "standing still, slower updates" else "one fix a second"
        }
    }

    private fun openDay(): PendingIntent = PendingIntent.getActivity(this, 0, Intent(this, SkiDayActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun notification(text: String): Notification {
        val stop = PendingIntent.getService(this, 1, Intent(this, DayTrackingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL_ID).setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("OpenSki ski day").setContentText(text).setContentIntent(openDay()).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop day", stop).build()).build()
    }

    private fun updateNotification() {
        if (!foreground) return
        val text = notificationText()
        if (text == lastNotification) return
        lastNotification = text
        try { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text)) } catch (_: Exception) {}
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:OpenSkiDay").apply {
                setReferenceCounted(false); acquire(WAKE_LOCK_MS)
            }
    }

    private fun releaseWakeLock() { wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null }

    override fun onDestroy() {
        // Leaves the day open, so a restart resumes it; only Stop closes a day.
        teardown(finishDay = false)
        io.execute { store.close() }
        io.shutdown()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.openski.android.DAY_START"
        const val ACTION_STOP = "com.openski.android.DAY_STOP"
        const val ACTION_RESUME = "com.openski.android.DAY_RESUME"
        const val CHANNEL_ID = "openski_ski_day"
        const val NOTIFICATION_ID = 211
        const val ERROR_NOTIFICATION_ID = 212
        private const val TICK_MS = 10_000L
        /** Renewed every tick while tracking; the timeout only bounds a lock leaked by a crash. */
        private const val WAKE_LOCK_MS = 60 * 60 * 1000L
        private const val SAVE_ERROR = "Could not save fixes"

        /** The latest status, read by the screens on the main thread. */
        @Volatile var status = DayTrackingStatus()
            private set

        fun start(context: Context) = context.startForegroundService(Intent(context, DayTrackingService::class.java).setAction(ACTION_START))
        fun resume(context: Context) = context.startForegroundService(Intent(context, DayTrackingService::class.java).setAction(ACTION_RESUME))
        fun stop(context: Context) = context.startService(Intent(context, DayTrackingService::class.java).setAction(ACTION_STOP))
    }
}
