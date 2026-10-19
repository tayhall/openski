package com.openski.android

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

/** Reads a committed attempt by identity; opening a result never scores or awards it again. */
class PracticeResultActivity : Activity() {
    private val io = Executors.newSingleThreadExecutor()

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        Snow.configureWindow(this)
    }

    override fun onResume() { super.onResume(); load() }
    override fun onDestroy() { io.shutdown(); super.onDestroy() }

    private fun page(content: LinearLayout.() -> Unit) {
        if (isDestroyed || isFinishing) return
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = Snow.dp(this@PracticeResultActivity, 20)
            setPadding(pad, pad, pad, pad)
            addView(Snow.button(this@PracticeResultActivity, "‹ Back", Snow.ButtonKind.QUIET) { finish() })
            content()
        }
        val scroll = ScrollView(this).apply { setBackgroundColor(Snow.SNOW); addView(column); isFillViewport = true }
        SkiUi.applyInsets(scroll)
        setContentView(scroll)
    }

    private fun LinearLayout.line(value: String, type: Snow.Type = Snow.Type.BODY) {
        addView(Snow.text(this@PracticeResultActivity, value, type), LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = Snow.dp(this@PracticeResultActivity, 16)
        })
    }

    private fun load() {
        page { line("Loading saved practice…") }
        val id = intent.getStringExtra("attempt_id")
        if (id.isNullOrBlank()) { missing(); return }
        io.execute {
            val loaded = runCatching {
                val attempt = ProgressStore(this).attempt(id)
                val rawExists = attempt?.sessionId?.let { raw -> LocalSessionStore(this).use { it.getSession(raw) != null } } ?: false
                attempt to rawExists
            }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                loaded.onSuccess { (attempt, rawExists) -> if (attempt == null) missing() else result(attempt, rawExists) }
                    .onFailure { page {
                        line("Could not load saved practice. Your saved history has been kept.")
                        addView(Snow.button(this@PracticeResultActivity, "Retry") { load() })
                    } }
            }
        }
    }

    private fun missing() = page { line("Saved practice unavailable", Snow.Type.HERO); line("This attempt could not be found on this phone.") }

    private fun result(attempt: Attempt, rawExists: Boolean) = page {
        line(Programme.drill(attempt.drillId)?.title ?: "Saved practice", Snow.Type.HERO)
        line(if (attempt.demo) "Demo · simulated cuff roll" else "My practice · measured cuff-roll exercise", Snow.Type.STRONG)
        line(DateFormat.getDateTimeInstance().format(Date(attempt.timeMs)))
        line(when (attempt.status) {
            PracticeStatus.COMPLETED -> "Set completed"
            PracticeStatus.INCOMPLETE -> "Incomplete set · no completion reward"
            PracticeStatus.INTERRUPTED -> "Practice interrupted · no completion reward"
            PracticeStatus.ACTIVE -> "Practice is still active in the sensor service"
        }, Snow.Type.TITLE)
        if (attempt.eligible) {
            addView(StarRow(this@PracticeResultActivity, attempt.stars, 28))
            line("${attempt.score} / 100 · +${attempt.vert} practice points", Snow.Type.TITLE)
        }
        line(attempt.target?.let { "Saved target: ${it.movements} rolls · ${it.beatSeconds} s beat · ${it.depthDegrees}° cuff roll" }
            ?: "The target was not recorded for this historical attempt.")
        line("Cuff roll is not an on-snow technique score.", Snow.Type.CAPTION)
        if (attempt.demo) line("Demo results stay separate from My practice achievements.")
        if (rawExists) addView(Snow.button(this@PracticeResultActivity, "Open raw session", Snow.ButtonKind.SECONDARY) {
            startActivity(Intent(this@PracticeResultActivity, SessionDetailActivity::class.java).putExtra("session_id", attempt.sessionId))
        }) else line(if (attempt.sessionId != null) "The linked raw session is no longer on this phone. This practice result is retained."
            else "No raw session is linked to this attempt.")
    }
}
