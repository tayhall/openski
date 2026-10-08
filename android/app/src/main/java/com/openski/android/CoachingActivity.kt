package com.openski.android

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Audio coaching: a metronome and a chirp after every few half-turns. A simple Snow-look screen that the
 * run screen will absorb later. It compares dry-ski boot roll with a training target, nothing more.
 */
class CoachingActivity : Activity() {
    private lateinit var store: CoachSettingsStore
    private lateinit var frame: FrameLayout
    private var service: SensorSessionService? = null
    private var bound = false
    private var settings = CoachSettings()
    private var state = CoachState()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val connected = (binder as? SensorSessionService.LocalBinder)?.service ?: return
            service = connected
            state = connected.coachState()
            connected.setCoachListener { update -> runOnUiThread { state = update; render() } }
            render()
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Snow.configureWindow(this)
        store = CoachSettingsStore(this)
        settings = store.settings
        frame = FrameLayout(this).apply { setBackgroundColor(Snow.SNOW) }
        SkiUi.applyInsets(frame)
        setContentView(frame)
        render()
    }

    override fun onStart() {
        super.onStart()
        if (!bound) bound = bindService(Intent(this, SensorSessionService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        service?.setCoachListener(null)
        if (bound) { unbindService(connection); bound = false }
        service = null
        super.onStop()
    }

    private fun t(text: String, type: Snow.Type = Snow.Type.BODY, tint: Int = Snow.INK) = Snow.text(this, text, type, tint)
    private fun LinearLayout.add(view: View, top: Int = 0, bottom: Int = 0) =
        addView(view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Snow.dp(this@CoachingActivity, top); bottomMargin = Snow.dp(this@CoachingActivity, bottom) })

    private fun change(update: (CoachSettings) -> CoachSettings) {
        settings = update(settings).normalised()
        store.settings = settings
        render()
    }

    private fun start(demo: Boolean) {
        val running = service
        if (running == null) {
            state = CoachState(message = "The sensor service is not ready yet. Try again in a moment.")
            render()
            return
        }
        val problem = running.startCoaching(settings, demo)  // null means it started; the listener delivers the new state
        if (problem != null) { state = CoachState(message = problem); render() }
    }

    private fun render() {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val inset = Snow.dp(this@CoachingActivity, 20)
            setPadding(inset, inset, inset, Snow.dp(this@CoachingActivity, 28))
        }
        column.add(t("Coaching", Snow.Type.HERO), bottom = 8)
        column.add(t("Audio cues for pace and depth, so you can ski with the phone in a pocket and listen.", Snow.Type.BODY, Snow.INK_SOFT), bottom = 16)

        val status = Snow.card(this, 18)
        status.add(t(when { state.running && state.paused -> "Paused"; state.running -> "Coaching"; else -> "Ready" }, Snow.Type.TITLE))
        status.add(t(state.message.ifEmpty { settings.targetLabel() }, Snow.Type.BODY, Snow.INK_SOFT), top = 4)
        state.last?.let { status.add(t(verdictLine(it), Snow.Type.BODY), top = 10) }
        column.add(status, bottom = 12)

        if (state.running) {
            column.add(Snow.button(this, "Stop coaching") { service?.stopCoaching() }, bottom = 10)
        } else {
            column.add(Snow.button(this, "Start coaching") { start(false) }, bottom = 10)
            column.add(Snow.button(this, "Try with demo boots", Snow.ButtonKind.SECONDARY) { start(true) }, bottom = 10)
        }
        column.add(Snow.button(this, "Play test sounds", Snow.ButtonKind.QUIET) { service?.playCoachTestSounds(settings) }, bottom = 16)

        val explainer = Snow.card(this, 18)
        explainer.add(t("What you will hear", Snow.Type.TITLE))
        explainer.add(t("Ticks set the beat you are aiming for. After every ${settings.windowSize} half-turns: a rising chirp means they matched the target, a falling chirp means they did not, and no sound means close or not enough to judge.", Snow.Type.BODY, Snow.INK_SOFT), top = 4)
        explainer.add(t("This compares dry-ski boot roll with a training target. It is not an on-snow technique score.", Snow.Type.CAPTION, Snow.INK_SOFT), top = 8)
        column.add(explainer, bottom = 20)

        if (!state.running) {
            column.add(t("Target and sounds", Snow.Type.DISPLAY), bottom = 8)
            column.add(setting("Target", settings.targetLabel()) { chooseTarget() })
            column.add(setting("Metronome", onOff(settings.metronome)) { change { it.copy(metronome = !it.metronome) } })
            column.add(setting("Count-in of 4 ticks", onOff(settings.countIn)) { change { it.copy(countIn = !it.countIn) } })
            column.add(setting("Chirps", onOff(settings.chirps)) { change { it.copy(chirps = !it.chirps) } })
            column.add(setting("Turns per chirp", "${settings.windowSize}") { change { it.copy(windowSize = if (it.windowSize >= Coach.MAX_WINDOW) Coach.MIN_WINDOW else it.windowSize + 1) } })
            column.add(setting("Follow boot", settings.coachBoot.name.lowercase().replaceFirstChar { it.uppercase() }) {
                change { it.copy(coachBoot = CoachBoot.entries[(it.coachBoot.ordinal + 1) % CoachBoot.entries.size]) } })
            column.add(setting("Volume", "${settings.gainPercent}%") {
                change { it.copy(gainPercent = VOLUMES.firstOrNull { v -> v > it.gainPercent } ?: VOLUMES.first()) } })
            column.add(setting("Allow phone speaker", onOff(settings.allowPhoneSpeaker)) { change { it.copy(allowPhoneSpeaker = !it.allowPhoneSpeaker) } })
        }
        frame.removeAllViews()
        frame.addView(ScrollView(this).apply { isFillViewport = true; addView(column) })
    }

    private fun setting(label: String, value: String, action: () -> Unit) =
        Snow.button(this, "$label: $value", Snow.ButtonKind.SECONDARY, action).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Snow.dp(this@CoachingActivity, 10) }
        }

    private fun onOff(value: Boolean) = if (value) "on" else "off"

    private fun verdictLine(v: CoachVerdict): String {
        val target = state.target
        val word = when (v.verdict) { Verdict.POSITIVE -> "matched the target"; Verdict.NEGATIVE -> "off target"; Verdict.NONE -> "close" }
        val beat = "%.1f".format(Locale.US, v.meanBeatSeconds)
        val aim = target?.let { " (aim ${"%.1f".format(Locale.US, it.beatSeconds)} s, ${it.depthDegrees.roundToInt()}°)" } ?: ""
        return "Last turns: $word · beat $beat s, roll ${v.meanDepthDegrees.roundToInt()}°$aim · score ${v.score}"
    }

    private fun chooseTarget() {
        val drills = Programme.drills
        val names = drills.map { "${it.title} · ${"%.1f".format(Locale.US, it.paceSeconds)} s, ${it.depthDegrees.roundToInt()}°" } + "Custom beat and depth…"
        AlertDialog.Builder(this).setTitle("Coaching target").setItems(names.toTypedArray()) { _, index ->
            if (index < drills.size) change { it.copy(presetDrillId = drills[index].id) } else customTarget()
        }.show()
    }

    private fun customTarget() {
        val beat = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "Seconds between turns (0.8 to 3.5)"
            setText("%.1f".format(Locale.US, settings.customBeat))
        }
        val depth = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "Roll in degrees (10 to 45)"
            setText("%.0f".format(Locale.US, settings.customDepth))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = Snow.dp(this@CoachingActivity, 20)
            setPadding(pad, pad, pad, 0)
            addView(beat); addView(depth)
        }
        AlertDialog.Builder(this).setTitle("Custom target").setView(box)
            .setPositiveButton("Save") { _, _ ->
                val b = beat.text.toString().toDoubleOrNull() ?: settings.customBeat
                val d = depth.text.toString().toDoubleOrNull() ?: settings.customDepth
                change { it.copy(presetDrillId = null, customBeat = b, customDepth = d) }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private companion object {
        val VOLUMES = listOf(30, 45, 60, 75, 90)
    }
}
