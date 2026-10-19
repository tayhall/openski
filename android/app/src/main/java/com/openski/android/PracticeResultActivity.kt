package com.openski.android

import android.app.AlertDialog
import android.widget.EditText
import android.text.InputFilter
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
                Triple(attempt, rawExists, LocalSessionStore(this).use { database ->
                    val practices = PracticeStore(database)
                    Triple(practices.reflection(id), practices.list().filter { it.id != id && it.timeMs <= (attempt?.timeMs ?: 0) && it.drillId == attempt?.drillId }.lastOrNull(),
                        attempt?.sessionId?.let { raw -> database.recoveryCaptures(raw).any { RecoverySummary.item(it).pending } } ?: false)
                })
            }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                loaded.onSuccess { (attempt, rawExists, context) -> if (attempt == null) missing() else result(attempt, rawExists, context.first, context.second, context.third) }
                    .onFailure { page {
                        line("Could not load saved practice. Your saved history has been kept.")
                        addView(Snow.button(this@PracticeResultActivity, "Retry") { load() })
                    } }
            }
        }
    }

    private fun missing() = page { line("Saved practice unavailable", Snow.Type.HERO); line("This attempt could not be found on this phone.") }

    private fun result(attempt: Attempt, rawExists: Boolean, reflection: PracticeReflection, previous: Attempt?, recoveryPending: Boolean) = page {
        line(Programme.drill(attempt.drillId)?.title ?: "Saved practice", Snow.Type.HERO)
        line(if (attempt.demo) "Demo · simulated cuff roll" else "My practice · measured cuff-roll exercise", Snow.Type.STRONG)
        line(DateFormat.getDateTimeInstance().format(Date(attempt.timeMs)))
        val eligibility = PracticeEligibility.evaluate(attempt, (attempt.review?.evidence ?: PracticeEvidence()).copy(recoveryPending = recoveryPending))
        line(when (eligibility.state) {
            PracticeEligibilityState.COMPLETE_JUDGEABLE -> "Completed · scoring evidence available"
            PracticeEligibilityState.INCOMPLETE -> "Incomplete set"
            PracticeEligibilityState.INSUFFICIENT_DATA -> "Cannot judge this set"
            PracticeEligibilityState.AWAITING_RECOVERY -> "Awaiting sensor recovery"
            PracticeEligibilityState.INTERRUPTED -> "Practice interrupted"
            PracticeEligibilityState.LEGACY_UNKNOWN -> "Saved result · evidence unknown"
            PracticeEligibilityState.ACTIVE -> "Practice in progress"
        }, Snow.Type.TITLE)
        line(eligibility.reason)
        attempt.review?.let { line(it.observation(attempt.target)); line(it.nextAction()) }
            ?: line(eligibility.nextAction)
        if (attempt.review == null && attempt.eligible) line("Historical score retained; scoring evidence was not recorded. It is excluded from evidence-backed comparisons.")
        if (attempt.eligible) {
            addView(StarRow(this@PracticeResultActivity, attempt.stars, 28))
            line("${attempt.score} / 100 · +${attempt.vert} practice points", Snow.Type.TITLE)
        }
        line(attempt.target?.let { "Saved target: ${it.movements} rolls · ${it.beatSeconds} s beat · ${it.depthDegrees}° cuff roll" }
            ?: "The target was not recorded for this historical attempt.")
        attempt.review?.let { review ->
            review.focus?.let { focus ->
                line("Practice focus: ${focus.label}", Snow.Type.STRONG)
                line(focus.cue)
                line(PracticeFocus.SCORING_NOTICE, Snow.Type.CAPTION)
            }
            line("Target source: ${PracticeTargetSource.label(review.targetSource)}", Snow.Type.CAPTION)
            review.activeIntervals?.let { intervals ->
                val seconds = intervals.sumOf { it.endedAtMs - it.startedAtMs } / 1000.0
                line("${intervals.size} active interval${if (intervals.size == 1) "" else "s"} · ${String.format(java.util.Locale.US, "%.1f", seconds)} s. Paused time is excluded; beats never cross a pause.", Snow.Type.CAPTION)
            }
        }
        previous?.let { prior ->
            val comparison = PracticeComparisonPolicy.evaluate(attempt, attempt.review?.evidence ?: PracticeEvidence(),
                prior, prior.review?.evidence ?: PracticeEvidence())
            if (comparison.comparable) line("Compared with the previous compatible set: mean cuff-roll peak changed by ${String.format(java.util.Locale.US, "%.1f", attempt.review!!.meanPeakDegrees!! - prior.review!!.meanPeakDegrees!!)}°. This describes the exercise, not improvement in skiing technique.")
            else line("Previous set excluded from comparison: ${comparison.exclusions.joinToString { when (it) {
                ComparisonExclusion.NOT_JUDGEABLE -> "scoring evidence is incomplete or unknown"
                ComparisonExclusion.DIFFERENT_SOURCE -> "different measured/simulated or scoring source"
                ComparisonExclusion.DIFFERENT_DRILL -> "different exercise"
                ComparisonExclusion.UNKNOWN_TARGET -> "original target unknown"
                ComparisonExclusion.DIFFERENT_TARGET -> "different saved target"
                ComparisonExclusion.UNSUPPORTED_SCORING_VERSION -> "scoring version is not supported for comparison"
                ComparisonExclusion.DIFFERENT_SCORING_VERSION -> "different scoring versions"
                ComparisonExclusion.UNKNOWN_CONTEXT -> "original boot or mounting unknown"
                ComparisonExclusion.DIFFERENT_CONTEXT -> "different boot or mounting"
                ComparisonExclusion.UNKNOWN_TIMING_POLICY -> "original timing rules unknown"
                ComparisonExclusion.DIFFERENT_TIMING_POLICY -> "different continuous/paused timing rules"
            } }}.", Snow.Type.CAPTION)
        }
        line("Your intention: ${reflection.intention.ifBlank { "not added" }}")
        line("Your reflection: ${reflection.reflection.ifBlank { "not added" }}")
        line("These optional notes are your own context, not a sensor diagnosis. Existing ski-day/run condition tags remain in the ski-day viewer.", Snow.Type.CAPTION)
        addView(Snow.button(this@PracticeResultActivity, "Add or edit intention / reflection", Snow.ButtonKind.SECONDARY) {
            editReflection(attempt.id, reflection)
        })
        line("Cuff roll is not an on-snow technique score.", Snow.Type.CAPTION)
        if (attempt.demo) line("Demo results stay separate from My practice achievements.")
        if (attempt.status == PracticeStatus.ACTIVE) {
            addView(Snow.button(this@PracticeResultActivity, "Return to active practice") {
                startActivity(Intent(this@PracticeResultActivity, DrillActivity::class.java).putExtra("drill", attempt.drillId).putExtra("resume_active", true))
            })
        } else if (runCatching { PracticeRepeat.drill(attempt) }.isSuccess) {
            addView(Snow.button(this@PracticeResultActivity, "Repeat this target") {
                startActivity(Intent(this@PracticeResultActivity, DrillActivity::class.java).putExtra("drill", attempt.drillId).putExtra("repeat_attempt_id", attempt.id))
            })
        } else {
            line(runCatching { PracticeRepeat.drill(attempt) }.exceptionOrNull()?.message ?: "Exact repeat unavailable.")
            Programme.drill(attempt.drillId)?.let { preset ->
                addView(Snow.button(this@PracticeResultActivity, "Use current preset", Snow.ButtonKind.SECONDARY) {
                    startActivity(Intent(this@PracticeResultActivity, DrillActivity::class.java).putExtra("drill", preset.id))
                })
            }
        }
        if (rawExists) addView(RecoveryCard(this@PracticeResultActivity, attempt.sessionId))
        if (rawExists) addView(Snow.button(this@PracticeResultActivity, "Open raw session", Snow.ButtonKind.SECONDARY) {
            startActivity(Intent(this@PracticeResultActivity, SessionDetailActivity::class.java).putExtra("session_id", attempt.sessionId))
        }) else line(if (attempt.sessionId != null) "The linked raw session is no longer on this phone. This practice result is retained."
            else "No raw session is linked to this attempt.")
    }
    private fun editReflection(id: String, value: PracticeReflection) {
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun field(label: String, initial: String) = EditText(this).apply {
            hint = label; contentDescription = label; setText(initial); filters = arrayOf(InputFilter.LengthFilter(2000)); form.addView(this)
        }
        val intention = field("Intention (optional)", value.intention)
        val reflection = field("Reflection (optional)", value.reflection)
        AlertDialog.Builder(this).setTitle("Your practice notes").setView(form).setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val notes = PracticeReflection(intention.text.toString(), reflection.text.toString())
                io.execute {
                    val saved = runCatching { LocalSessionStore(this).use { PracticeStore(it).saveReflection(id, notes) } }
                    runOnUiThread { if (!isDestroyed && !isFinishing) saved.onSuccess { load() }.onFailure {
                        AlertDialog.Builder(this).setMessage("Could not save these notes. Previously saved notes are retained.").setPositiveButton("OK", null).show()
                    } }
                }
            }.show()
    }

}
