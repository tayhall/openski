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
import java.util.concurrent.RejectedExecutionException

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
    /** True while a stopped day is still trying to save its last fixes. */
    val closing: Boolean = false,
    val error: String? = null,
)

/**
 * Records a whole ski day from the phone's location (and barometer where present), independent of the boot sensors
 * and of [SensorSessionService]. A foreground service of type location with its own notification and a Stop action.
 *
 * Threads: location, barometer and the batcher live on the main thread; every database call runs on one io thread,
 * which owns the [FixBacklog] of rows not yet stored (capped, with a backoff between failed writes). Stopping writes the
 * backlog with retries ([DayCloser]) and closes the day only when that worked; otherwise the day stays open, the
 * service keeps its notification with the error and keeps retrying. The open day is resumed when the system restarts
 * the service (START_STICKY) or when the day screen asks; a day whose last fix is over 12 h old is closed instead.
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
    /** Only touched on the io thread. */
    private val backlog = FixBacklog()
    private val baroWindow = BaroWindow()
    private var sensorManager: SensorManager? = null
    private var baroListener: SensorEventListener? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var dayId: String? = null
    /** The day being closed after Stop, while its last fixes are still being saved. */
    private var closingDayId: String? = null
    private var starting = false
    private var foreground = false
    private var lastNotification: String? = null
    private var lastFlushAt = 0L

    private val tick = object : Runnable {
        override fun run() {
            val id = dayId ?: return
            if (!gps.running) startGps()   // location switched back on, or a refused start
            acquireWakeLock()
            if (batcher.pending > 0 && lastFlushAt + DayTrackingConfig.BATCH_AGE_MS < SystemClock.elapsedRealtime()) write(id, batcher.drain())
            else write(id, emptyList())   // lets a backlog left by a failed write retry once its backoff has passed
            updateNotification()
            main.postDelayed(this, TICK_MS)
        }
    }

    private val retryClose = Runnable { closingDayId?.let(::closeDay) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "OpenSki ski day tracking", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopDay(); return if (closingDayId != null) START_STICKY else START_NOT_STICKY }
            // START and RESUME come from startForegroundService: go foreground at once, before any check can bail out.
            ACTION_START -> { if (goForeground()) beginOrResume(newDay = true) }
            ACTION_RESUME -> { if (goForeground()) beginOrResume(newDay = false) }
            else -> beginOrResume(newDay = false)   // a restart by the system after the process died
        }
        return if (dayId != null || starting || closingDayId != null) START_STICKY else START_NOT_STICKY
    }

    /** Resumes the open phone day, or (with [newDay]) starts one. Database work runs on the io thread. */
    private fun beginOrResume(newDay: Boolean) {
        if (dayId != null || starting) { goForeground(); return }
        if (closingDayId != null) return   // still saving a stopped day; Start again once it has closed
        if (!gps.permitted) {
            fail("Location permission is missing, so the day is not being tracked. Allow precise location for OpenSki, then start again.")
            return
        }
        starting = true
        bg {
            val day = try {
                val now = System.currentTimeMillis()
                days.settleOpenDays(now) ?: if (newDay)
                    SkiDay(UUID.randomUUID().toString(), DayRules.defaultTitle(now), now, null, DaySource.PHONE, null, "", DayTags(), now).also { days.create(it) }
                else null
            } catch (e: Exception) { main.post { starting = false; fail("Could not read or create the day: ${e.message}") }; return@bg }
            main.post {
                starting = false
                if (day == null) { status = DayTrackingStatus(); stopSelf(); return@post }
                track(day)
            }
        }
    }

    private fun track(day: SkiDay) {
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
            stopListening()
            dayId = null
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

    /** Hands [rows] to the io thread's backlog and writes what is due; failures wait out a growing backoff. */
    private fun write(id: String, rows: List<DayFix>) {
        if (rows.isNotEmpty()) lastFlushAt = SystemClock.elapsedRealtime()
        bg {
            backlog.add(rows)
            if (backlog.size == 0) return@bg
            val ok = backlog.flush(SystemClock.elapsedRealtime(), { days.addFixes(id, it) })
            val error = if (ok) null else "$SAVE_ERROR (${backlog.size} waiting, next try in ${backlog.backoff / 1000} s): ${backlog.lastError}" +
                if (backlog.dropped > 0) "; ${backlog.dropped} oldest fixes dropped because the backlog was full" else ""
            main.post {
                if (error != null) status = status.copy(error = error)
                else if (status.error?.startsWith(SAVE_ERROR) == true) status = status.copy(error = null)
            }
        }
    }

    private fun stopDay() {
        val id = dayId ?: closingDayId
        if (id == null) { status = DayTrackingStatus(); if (!starting) stopSelf(); return }
        stopListening()
        dayId = null
        closingDayId = id
        val rest = batcher.drain()
        status = status.copy(tracking = false, gpsOn = false, closing = true)
        bg { backlog.add(rest) }
        closeDay(id)
    }

    /** Saves the backlog with retries and closes the day; on failure keeps it open, shows why and tries again later. */
    private fun closeDay(id: String) {
        bg {
            val outcome = DayCloser.close(backlog, { days.addFixes(id, it) }, {
                val end = days.lastFixTime(id) ?: System.currentTimeMillis()
                days.finish(id, maxOf(end, days.day(id)?.startedAtMs ?: end))
            }, pause = { attempt -> Thread.sleep(500L * attempt) })
            main.post {
                when (outcome) {
                    is CloseOutcome.Closed -> {
                        closingDayId = null
                        status = DayTrackingStatus(id, false)
                        releaseWakeLock()
                        if (foreground) { stopForeground(STOP_FOREGROUND_REMOVE); foreground = false }
                        stopSelf()
                    }
                    is CloseOutcome.KeptOpen -> {
                        status = status.copy(closing = true, error = "Stopping: ${outcome.reason}.")
                        updateNotification()
                        main.removeCallbacks(retryClose)
                        main.postDelayed(retryClose, CLOSE_RETRY_MS)
                    }
                }
            }
        }
    }

    private fun stopListening() {
        main.removeCallbacks(tick)
        gps.stop()
        baroListener?.let { l -> sensorManager?.unregisterListener(l) }
        baroListener = null
    }

    private fun fail(message: String) {
        status = status.copy(tracking = false, error = message)
        // A plain notification as well, so the refusal is visible when no screen is open.
        try {
            getSystemService(NotificationManager::class.java).notify(ERROR_NOTIFICATION_ID, Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert).setContentTitle("Ski day not tracking").setContentText(message)
                .setStyle(Notification.BigTextStyle().bigText(message)).setContentIntent(openDay()).setAutoCancel(true).build())
        } catch (_: Exception) { /* notifications not allowed: the day screen shows the error */ }
        releaseWakeLock()
        if (foreground) { stopForeground(STOP_FOREGROUND_REMOVE); foreground = false }
        stopSelf()
    }

    /** Runs [block] on the io thread; after onDestroy the executor is shut and the work is dropped. */
    private fun bg(block: () -> Unit) {
        try { io.execute(block) } catch (_: RejectedExecutionException) { }
    }

    private fun notificationText(): String {
        val s = status
        val started = s.startedAtMs?.let { java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(it)) }
        return when {
            s.error != null -> s.error
            s.closing -> "Saving the day's last fixes…"
            dayId == null -> "Ski day · starting"
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
        // Leaves the day open, so a restart resumes it; only Stop closes a day. The buffered fixes get one last write.
        stopListening()
        main.removeCallbacks(retryClose)
        val id = dayId ?: closingDayId
        val rest = batcher.drain()
        dayId = null
        if (id != null) bg {
            backlog.add(rest)
            DayCloser.close(backlog, { days.addFixes(id, it) }, finish = {}, pause = { Thread.sleep(200) })
        }
        bg { store.close() }
        io.shutdown()
        releaseWakeLock()
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
        /** After a failed close, the next attempt waits this long. */
        private const val CLOSE_RETRY_MS = 30_000L
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
