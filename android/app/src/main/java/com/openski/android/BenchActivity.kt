package com.openski.android

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Geek-mode bench tools for a loose board: six-face accelerometer check, tilt accuracy against a reference
 * angle, and still gyro/accelerometer noise. Works on raw samples, so no calibration or recording is needed.
 */
class BenchActivity : Activity(), SensorSessionService.Listener {
    private val handler = Handler(Looper.getMainLooper())
    private var service: SensorSessionService? = null
    private var bound = false
    private val latest = HashMap<String, SensorSample>()
    private val lastSeen = HashMap<String, Long>()

    // Capture state: written from the BLE thread, read on the main thread.
    private val lock = Any()
    private var buffer = ArrayList<SensorSample>()
    @Volatile private var capturingSide: String? = null

    private lateinit var store: ProgressStore
    private val faces = LinkedHashMap<Face, BenchCapture>()
    private var flat: BenchCapture? = null
    private class TiltRow(val reference: Double, val flat: Vector3, val accel: Vector3)
    private val tiltRows = mutableListOf<TiltRow>()
    private val noiseRows = mutableListOf<String>()
    private var message = ""
    private var referenceText = ""
    private lateinit var column: LinearLayout
    private var liveView: TextView? = null
    private var progressView: TextView? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val connected = (binder as? SensorSessionService.LocalBinder)?.service ?: return
            service = connected
            connected.setListener(this@BenchActivity)
            connected.setUiVisible(true)
            connected.restoreConnections()
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SkiUi.configureWindow(this)
        store = ProgressStore(this)
        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val inset = SkiUi.dp(this@BenchActivity, 20)
            setPadding(inset, inset, inset, inset * 2)
        }
        val root = ScrollView(this).apply { setBackgroundColor(SkiUi.CANVAS); isFillViewport = true; addView(column) }
        SkiUi.applyInsets(root)
        setContentView(root)
        bound = bindService(Intent(this, SensorSessionService::class.java), connection, Context.BIND_AUTO_CREATE)
        render()
        handler.post(ticker)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (bound) { service?.setListener(null); unbindService(connection); bound = false }
        super.onDestroy()
    }

    // Data ------------------------------------------------------------------------------------------------

    override fun onSensorStatus(side: String, message: String) {}
    override fun onRecordingChanged(sessionId: String?, message: String) {}
    override fun onSensorSample(side: String, sample: SensorSample) {
        latest[side] = sample
        lastSeen[side] = System.currentTimeMillis()
        if (side == capturingSide) synchronized(lock) { buffer.add(sample) }
    }

    private fun activeSide(): String? {
        val now = System.currentTimeMillis()
        return listOf("L", "R").firstOrNull { (lastSeen[it] ?: 0L) > now - 1500 }
    }

    private val ticker = object : Runnable {
        override fun run() {
            val side = activeSide()
            val sample = side?.let { latest[it] }
            liveView?.text = if (sample == null) "No boot is streaming." else
                "${if (side == "L") "Left" else "Right"} boot\n" +
                    String.format(Locale.US, "ax %6.2f  ay %6.2f  az %6.2f  |a| %.2f m/s²", sample.accelX, sample.accelY, sample.accelZ,
                        Vector3(sample.accelX.toDouble(), sample.accelY.toDouble(), sample.accelZ.toDouble()).norm())
            handler.postDelayed(this, 200)
        }
    }

    /** Collect [seconds] of samples from the streaming boot, then hand the summary to [done]. */
    private fun capture(seconds: Int, done: (BenchCapture) -> Unit) {
        if (capturingSide != null) return
        val side = activeSide() ?: run { message = "No boot is streaming. Switch the sensor on and wait for it to connect."; render(); return }
        synchronized(lock) { buffer = ArrayList() }
        capturingSide = side
        val started = System.currentTimeMillis()
        val tick = object : Runnable {
            override fun run() {
                val elapsed = (System.currentTimeMillis() - started) / 1000.0
                if (elapsed < seconds) {
                    progressView?.text = String.format(Locale.US, "Capturing… %.0f of %d s. Keep the board still.", elapsed, seconds)
                    handler.postDelayed(this, 200)
                } else {
                    capturingSide = null
                    val samples = synchronized(lock) { buffer.toList() }
                    val summary = BenchMath.summarize(samples)
                    if (summary == null) { message = "No samples arrived. Check the connection."; render() } else done(summary)
                }
            }
        }
        message = ""
        render()
        handler.post(tick)
    }

    private fun steady(c: BenchCapture): Boolean {
        if (c.steady) return true
        message = String.format(Locale.US, "Not steady enough (gyro noise %.2f °/s, accel noise %.2f m/s², %d samples). Hold the board still and try again.",
            c.gyroNoiseDps, c.accelNoise, c.samples)
        render()
        return false
    }

    // Tools -----------------------------------------------------------------------------------------------

    private fun captureFace(face: Face) = capture(3) { c ->
        if (!steady(c)) return@capture
        val seen = BenchMath.face(c.accel)
        if (seen != face) {
            message = "That reading looks like ${seen.label}. Place the board with ${face.label} (the ${face.label.take(2)} axis pointing up) and try again."
        } else { faces[face] = c; message = "Captured ${face.label}." }
        render()
    }

    private fun captureHoles() = capture(3) { c ->
        if (!steady(c)) return@capture
        val seen = BenchMath.face(c.accel)
        if (BenchMath.offFace(c.accel, seen) > 20) {
            message = "The board is not standing square on its edge. Stand it straight on the holes edge and try again."
        } else {
            val direction = BenchMath.holesDirection(c.accel)
            store.setHoles(direction); message = "Captured. The holes edge is ${direction.label.take(2)}."
        }
        render()
    }

    private fun captureChip() = capture(3) { c ->
        if (!steady(c)) return@capture
        val seen = BenchMath.face(c.accel)
        if (BenchMath.offFace(c.accel, seen) > 20) {
            message = "The board is not lying flat. Rest it chip side up on a table and try again."
        } else {
            store.setChip(seen); message = "Captured. The chip side is ${seen.label.take(2)}."
        }
        render()
    }

    private fun captureFlat() = capture(3) { c ->
        if (!steady(c)) return@capture
        flat = c; tiltRows.clear(); message = "Flat reference saved. Tilt the board and capture at each reference angle."; render()
    }

    private fun captureTilt() {
        val reference = referenceText.toDoubleOrNull()
        val zero = flat
        if (zero == null) { message = "Capture a flat reference first."; render(); return }
        if (reference == null) { message = "Type the reference angle in degrees first."; render(); return }
        capture(3) { c ->
            if (!steady(c)) return@capture
            tiltRows.add(TiltRow(reference, zero.accel, c.accel))
            message = "Captured."; render()
        }
    }

    private fun captureNoise(seconds: Int) = capture(seconds) { c ->
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        noiseRows.add(String.format(Locale.US,
            "%s  %ds  gyro bias x %+.2f y %+.2f z %+.2f °/s  noise %.2f °/s  accel noise %.3f m/s²  %.1f Hz  %d gaps%s",
            time, seconds, c.gyroDps.x, c.gyroDps.y, c.gyroDps.z, c.gyroNoiseDps, c.accelNoise, c.effectiveHz ?: 0.0, c.gaps,
            if (c.steady) "" else "  (moved)"))
        message = "Captured."; render()
    }

    /** The saved correction for whichever boot is streaming, or for any assigned boot if none is. */
    private fun currentCorrection(): AccelCorrection? =
        activeSide()?.let { store.correctionForSide(it) } ?: listOf("L", "R").firstNotNullOfOrNull { store.correctionForSide(it) }

    private fun tiltText(row: TiltRow): String {
        val raw = BenchMath.angleBetween(row.flat, row.accel)
        val base = String.format(Locale.US, "reference %.1f°  raw %.1f° (%+.1f°)", row.reference, raw, raw - row.reference)
        val fix = currentCorrection() ?: return base
        val fixed = BenchMath.angleBetween(fix.apply(row.flat), fix.apply(row.accel))
        return base + String.format(Locale.US, "  corrected %.1f° (%+.1f°)", fixed, fixed - row.reference)
    }

    private fun saveCorrection() {
        val correction = AccelCorrection.fromAxisChecks(BenchMath.axisChecks(faces.mapValues { it.value.accel }))
        val address = activeSide()?.let { store.sensorAddress(it) }
        message = when {
            correction == null -> "Capture both faces of every axis first, with plausible readings."
            address == null -> "Connect the boot so the correction can be saved against its sensor."
            else -> { store.setCorrection(address, correction); service?.reloadCorrections(); "Captured. Correction saved for sensor ${address.replace(":", "").takeLast(4)}." }
        }
        render()
    }

    private fun clearCorrection() {
        val address = activeSide()?.let { store.sensorAddress(it) }
        if (address == null) { message = "Connect the boot first."; render(); return }
        store.setCorrection(address, null); service?.reloadCorrections(); message = "Captured. Correction cleared."; render()
    }

    private fun report(): String = buildString {
        appendLine("OpenSki bench results ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())}")
        appendLine("Six faces")
        faces.forEach { (face, c) ->
            appendLine(String.format(Locale.US, "  %s  |a| %.3f  off axis %.1f°  x %.3f y %.3f z %.3f", face.label, c.magnitude,
                BenchMath.offFace(c.accel, face), c.accel.x, c.accel.y, c.accel.z))
        }
        BenchMath.axisChecks(faces.mapValues { it.value.accel }).forEach {
            appendLine(String.format(Locale.US, "  axis %s offset %+.3f m/s²  scale %+.2f%%", "XYZ"[it.axis], it.offset, it.scalePercent))
        }
        currentCorrection()?.let { appendLine("  saved correction (offset x,y,z then scale x,y,z): ${it.toStorage()}") }
        appendLine("Tilt accuracy"); tiltRows.forEach { appendLine("  ${tiltText(it)}") }
        appendLine("Still noise"); noiseRows.forEach { appendLine("  $it") }
    }

    // UI --------------------------------------------------------------------------------------------------

    private fun render() {
        column.removeAllViews()
        fun text(value: String, size: Float = 14f, tint: Int = SkiUi.SECONDARY, bold: Boolean = false) = SkiUi.label(this, value, size, tint, bold)
        fun add(view: View, top: Int = 0) = column.addView(view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = SkiUi.dp(this@BenchActivity, top) })
        fun card(title: String, hint: String, content: LinearLayout.() -> Unit): View {
            val card = SkiUi.card(this)
            card.addView(text(title, 18f, SkiUi.TEXT, true))
            card.addView(text(hint, 13f, SkiUi.MUTED).apply { setPadding(0, SkiUi.dp(this@BenchActivity, 6), 0, SkiUi.dp(this@BenchActivity, 12)) })
            card.content()
            return card
        }
        fun LinearLayout.row(view: View, top: Int = 8) = addView(view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = SkiUi.dp(this@BenchActivity, top) })
        val busy = capturingSide != null

        add(text("GEEK MODE", 12f, SkiUi.ACCENT, true))
        add(text("Bench tools", 28f, SkiUi.TEXT, true), 4)
        add(text("Checks for a loose board, using raw readings. No calibration or recording needed.", 14f, SkiUi.SECONDARY), 6)
        liveView = text("", 13f, SkiUi.SKY)
        add(liveView!!, 14)
        progressView = text(if (busy) "Capturing…" else message, 13f, if (message.startsWith("Captured") || message.startsWith("Flat")) SkiUi.ACCENT else SkiUi.DANGER)
        add(progressView!!, 8)
        if (activeSide() == null && !busy) add(SkiUi.button(this, "Open Geek mode to connect a boot") {
            startActivity(Intent(this, MainActivity::class.java))
        }, 8)

        add(card("Six faces", "Rest the board on each face. The axis pointing up should read about +9.8 m/s². This finds which axis is which and any offset or scale error.") {
            Face.all.chunked(2).forEach { pair ->
                val line = LinearLayout(this@BenchActivity)
                pair.forEach { face ->
                    val done = faces[face]
                    line.addView(SkiUi.button(this@BenchActivity, (if (done != null) "✓ " else "") + face.label,
                        if (done != null) SkiUi.ButtonStyle.PRIMARY else SkiUi.ButtonStyle.SECONDARY) { if (!busy) captureFace(face) },
                        LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = SkiUi.dp(this@BenchActivity, 6) })
                }
                row(line, 6)
            }
            faces.forEach { (face, c) ->
                row(text(String.format(Locale.US, "%s   |a| %.2f   off axis %.1f°", face.label, c.magnitude, BenchMath.offFace(c.accel, face)), 12f, SkiUi.SECONDARY), 6)
            }
            BenchMath.axisChecks(faces.mapValues { it.value.accel }).forEach {
                row(text(String.format(Locale.US, "Axis %s: offset %+.3f m/s², scale %+.2f%%", "XYZ"[it.axis], it.offset, it.scalePercent), 13f, SkiUi.TEXT, true), 6)
            }
            val ready = AccelCorrection.fromAxisChecks(BenchMath.axisChecks(faces.mapValues { it.value.accel })) != null
            val saved = currentCorrection()
            row(SkiUi.button(this@BenchActivity, "Save correction for this sensor", if (ready) SkiUi.ButtonStyle.PRIMARY else SkiUi.ButtonStyle.SECONDARY) { if (!busy) saveCorrection() }, 14)
            row(text(if (saved != null) "A correction is saved and is used for orientation and tilt below. Raw data and exports stay raw."
                else if (ready) "All six faces captured. Save the correction to use it." else "Capture all six faces to enable a correction.", 12f, SkiUi.MUTED), 6)
            if (saved != null) row(SkiUi.button(this@BenchActivity, "Clear saved correction", SkiUi.ButtonStyle.QUIET) { if (!busy) clearCorrection() }, 4)
        }, 18)

        add(card("Landmarks", "Teach the app which sensor axis points at the board's holes edge and chip side. The mounting question can then be asked in plain words.") {
            val marks = store.landmarks()
            row(SkiUi.button(this@BenchActivity, if (marks.holes != null) "✓ Stand on the holes edge. Capture again" else "Stand on the holes edge",
                SkiUi.ButtonStyle.SECONDARY) { if (!busy) captureHoles() }, 0)
            row(SkiUi.button(this@BenchActivity, if (marks.chip != null) "✓ Chip side up, flat. Capture again" else "Chip side up, flat",
                SkiUi.ButtonStyle.SECONDARY) { if (!busy) captureChip() })
            marks.holes?.let { row(text("Holes edge: ${it.label.take(2)} direction", 13f, SkiUi.TEXT, true), 10) }
            marks.chip?.let { row(text("Chip side: ${it.label.take(2)} direction", 13f, SkiUi.TEXT, true), 4) }
        }, 18)

        add(card("Tilt accuracy", "Capture the board flat, then tilt it to known angles measured with a gauge, a hinged board or a phone lying beside the sensor.") {
            row(SkiUi.button(this@BenchActivity, if (flat == null) "Capture flat reference" else "✓ Flat reference. Capture again", SkiUi.ButtonStyle.SECONDARY) { if (!busy) captureFlat() })
            val input = EditText(this@BenchActivity).apply {
                hint = "Reference angle in degrees"
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                setTextColor(SkiUi.TEXT); setHintTextColor(SkiUi.MUTED)
                setText(referenceText)
                addTextChangedListener(object : TextWatcher {
                    override fun afterTextChanged(s: android.text.Editable?) { referenceText = s?.toString() ?: "" }
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                })
            }
            row(input)
            row(SkiUi.button(this@BenchActivity, "Capture at this angle", SkiUi.ButtonStyle.PRIMARY) { if (!busy) captureTilt() })
            tiltRows.forEach { row(text(tiltText(it), 13f, SkiUi.TEXT), 6) }
        }, 18)

        add(card("Still noise", "Leave the board untouched. Gyro bias is the average rate while still; repeat after the board has warmed up, since the sensor's bias moves with temperature.") {
            val line = LinearLayout(this@BenchActivity)
            line.addView(SkiUi.button(this@BenchActivity, "Still 30 s", SkiUi.ButtonStyle.SECONDARY) { if (!busy) captureNoise(30) },
                LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = SkiUi.dp(this@BenchActivity, 6) })
            line.addView(SkiUi.button(this@BenchActivity, "Still 60 s", SkiUi.ButtonStyle.SECONDARY) { if (!busy) captureNoise(60) },
                LinearLayout.LayoutParams(0, -2, 1f))
            row(line, 0)
            noiseRows.forEach { row(text(it, 12f, SkiUi.TEXT), 8) }
        }, 18)

        add(SkiUi.button(this, "Copy results", SkiUi.ButtonStyle.PRIMARY) {
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("OpenSki bench results", report()))
            message = "Captured. Results copied."; render()
        }, 20)
        add(SkiUi.button(this, "Share results") {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, report()), "Share bench results"))
        }, 8)
        add(SkiUi.button(this, "Clear all results", SkiUi.ButtonStyle.QUIET) {
            faces.clear(); flat = null; tiltRows.clear(); noiseRows.clear(); message = ""; render()
        }, 8)
    }
}
