package com.openski.android

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.View
import android.widget.LinearLayout

/**
 * Observes shared recovery facts without taking a telemetry listener or issuing recovery commands. [dark] draws it in the
 * SkiUi look for the recording and review screens; the default is the Snow look of the training screens.
 */
class RecoveryCard(context: Context, private val sessionId: String? = null, private val pendingOnly: Boolean = false,
    private val dark: Boolean = false) : LinearLayout(context) {
    private val handler = Handler(Looper.getMainLooper())
    private var service: SensorSessionService? = null
    private var bound = false
    private var busy = false
    private var generation = 0
    private val body = if (dark) SkiUi.label(context, "Checking recording recovery…", 13f, SkiUi.SECONDARY)
        else Snow.text(context, "Checking recording recovery…")
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as? SensorSessionService.LocalBinder)?.service
            handler.removeCallbacks(refresh); handler.post(refresh)
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null; busy = false; body.text = "Recovery status unavailable. Open preparation to retry." }
    }
    private val refresh = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow) return
            if (!busy) service?.let { connected ->
                busy = true
                val token = generation
                connected.loadPreparation(sessionId) { loaded ->
                    if (!isAttachedToWindow || token != generation) return@loadPreparation
                    busy = false
                    loaded.onSuccess { snapshot ->
                        val items = snapshot.recovery.items
                        visibility = if (items.isEmpty() || (pendingOnly && !snapshot.recovery.pending)) View.GONE else View.VISIBLE
                        val text = items.joinToString("\n\n") { "${it.label()}${if (it.detail.isBlank()) "" else "\n${it.detail}"}" }
                        if (body.text.toString() != text) body.text = text
                    }.onFailure { visibility = View.VISIBLE; body.text = "Could not read recovery status. Saved data has been kept. Open preparation to retry." }
                }
            }
            handler.postDelayed(this, 1000)
        }
    }
    init {
        orientation = VERTICAL
        val open = { context.startActivity(Intent(context, BootSetupActivity::class.java)) }
        if (dark) {
            background = SkiUi.rounded(context, SkiUi.SURFACE)
            val pad = SkiUi.dp(context, 18); setPadding(pad, pad, pad, pad)
            val gap = { LayoutParams(-1, -2).apply { bottomMargin = SkiUi.dp(context, 8) } }
            addView(SkiUi.label(context, "RECORDING RECOVERY", 11f, SkiUi.MUTED, true), gap())
            addView(body, gap())
            addView(SkiUi.button(context, "Open preparation and recovery") { open() })
        } else {
            background = Snow.rounded(context, Snow.PAPER, 24, Snow.GLACIER)
            val pad = Snow.dp(context, 18); setPadding(pad, pad, pad, pad)
            addView(Snow.text(context, "Recording recovery", Snow.Type.TITLE), Snow.fullWidth(0, context, 8))
            addView(body, Snow.fullWidth(0, context, 8))
            addView(Snow.button(context, "Open preparation and recovery", Snow.ButtonKind.SECONDARY) { open() })
        }
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow(); generation++; busy = false
        bound = context.bindService(Intent(context, SensorSessionService::class.java), connection, Context.BIND_AUTO_CREATE)
    }
    override fun onDetachedFromWindow() {
        generation++; handler.removeCallbacks(refresh)
        if (bound) context.unbindService(connection)
        bound = false; service = null; busy = false
        super.onDetachedFromWindow()
    }
}
