package com.openski.android

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.NumberFormat
import java.time.format.TextStyle
import java.util.Locale

/** Launcher. Training first: onboarding, today's drill, the piste path and progress. Sensors live under Boots. */
class HomeActivity : Activity() {
    private lateinit var store: ProgressStore
    private lateinit var body: FrameLayout
    private val navLabels = listOf("Today", "Path", "Progress", "Boots")
    private val navItems = mutableListOf<TextView>()
    private var tab = 0
    private var onboardingStep = 0
    private var pickedGoal: Goal? = null
    private var pickedExperience: Experience? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Snow.configureWindow(this)
        store = ProgressStore(this)
        tab = savedInstanceState?.getInt("tab") ?: 0
        onboardingStep = savedInstanceState?.getInt("onboarding") ?: 0
        pickedGoal = savedInstanceState?.getString("goal")?.let { name -> Goal.entries.firstOrNull { it.name == name } }
        pickedExperience = savedInstanceState?.getString("experience")?.let { name -> Experience.entries.firstOrNull { it.name == name } }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Snow.SNOW) }
        body = FrameLayout(this)
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(buildNav())
        SkiUi.applyInsets(root)
        setContentView(root)
    }

    override fun onResume() { super.onResume(); render() }

    override fun onSaveInstanceState(state: Bundle) {
        state.putInt("tab", tab); state.putInt("onboarding", onboardingStep)
        pickedGoal?.let { state.putString("goal", it.name) }
        pickedExperience?.let { state.putString("experience", it.name) }
        super.onSaveInstanceState(state)
    }

    private fun render() {
        val nav = (body.parent as LinearLayout).getChildAt(1)
        nav.visibility = if (store.onboarded) View.VISIBLE else View.GONE
        body.removeAllViews()
        body.addView(if (!store.onboarded) onboarding() else when (tab) {
            0 -> today(); 1 -> path(); 2 -> progress(); else -> boots()
        })
        navItems.forEachIndexed { index, item ->
            val selected = index == tab
            item.setTextColor(if (selected) Snow.INK else Snow.INK_SOFT)
            item.setTypeface(resources.getFont(if (selected) R.font.barlow_condensed_bold else R.font.barlow_condensed_semibold))
            item.background = if (selected) Snow.rounded(this, Snow.GLACIER, 20) else null
            item.contentDescription = navLabels[index] + if (selected) ", selected" else ""
        }
    }

    private fun buildNav() = LinearLayout(this).apply {
        setBackgroundColor(Snow.PAPER)
        setPadding(Snow.dp(this@HomeActivity, 8), Snow.dp(this@HomeActivity, 6), Snow.dp(this@HomeActivity, 8), Snow.dp(this@HomeActivity, 6))
        navLabels.forEachIndexed { index, label ->
            val item = TextView(this@HomeActivity).apply {
                text = label; textSize = 17f; gravity = Gravity.CENTER; includeFontPadding = false
                minHeight = Snow.dp(this@HomeActivity, 48); isClickable = true; isFocusable = true
                setOnClickListener { tab = index; render() }
            }
            navItems.add(item)
            addView(item, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = Snow.dp(this@HomeActivity, 3); marginEnd = Snow.dp(this@HomeActivity, 3) })
        }
    }

    private fun page(content: LinearLayout.() -> Unit): ScrollView {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Snow.dp(this@HomeActivity, 20), Snow.dp(this@HomeActivity, 20), Snow.dp(this@HomeActivity, 20), Snow.dp(this@HomeActivity, 28))
            content()
        }
        return ScrollView(this).apply { isFillViewport = true; addView(column) }
    }

    private fun LinearLayout.add(view: View, top: Int = 0, bottom: Int = 0) =
        addView(view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Snow.dp(this@HomeActivity, top); bottomMargin = Snow.dp(this@HomeActivity, bottom) })

    private fun t(text: String, type: Snow.Type = Snow.Type.BODY, tint: Int = Snow.INK) = Snow.text(this, text, type, tint)
    private fun openDrill(drill: Drill) = startActivity(Intent(this, DrillActivity::class.java).putExtra("drill", drill.id))
    private fun openBoots() = startActivity(Intent(this, MainActivity::class.java))
    private fun openBench() = startActivity(Intent(this, BenchActivity::class.java))

    // Onboarding ----------------------------------------------------------------------------------------

    private fun onboarding() = page {
        add(t("OpenSki", Snow.Type.TITLE, Snow.ORANGE), bottom = 24)
        when (onboardingStep) {
            0 -> {
                add(t("What do you want to ski better?", Snow.Type.HERO), bottom = 8)
                add(t("Pick one. You can change it later.", Snow.Type.BODY, Snow.INK_SOFT), bottom = 20)
                Goal.entries.forEach { goal ->
                    add(choice(goal.piste, goal.title, goal.blurb, pickedGoal == goal) { pickedGoal = goal; render() }, bottom = 12)
                }
                add(Snow.button(this@HomeActivity, "Continue") {
                    if (pickedGoal != null) { onboardingStep = 1; render() }
                }.apply { alpha = if (pickedGoal == null) .4f else 1f; isEnabled = pickedGoal != null }, top = 8)
            }
            1 -> {
                add(t("Where are you now?", Snow.Type.HERO), bottom = 8)
                add(t("This sets where your path starts.", Snow.Type.BODY, Snow.INK_SOFT), bottom = 20)
                Experience.entries.forEach { level ->
                    add(choice(level.startsOn, level.title, level.blurb, pickedExperience == level) { pickedExperience = level; render() }, bottom = 12)
                }
                add(Snow.button(this@HomeActivity, "Continue") {
                    if (pickedExperience != null) { onboardingStep = 2; render() }
                }.apply { alpha = if (pickedExperience == null) .4f else 1f; isEnabled = pickedExperience != null }, top = 8)
                add(Snow.button(this@HomeActivity, "Back", Snow.ButtonKind.QUIET) { onboardingStep = 0; render() }, top = 4)
            }
            else -> {
                add(t("Your boots", Snow.Type.HERO), bottom = 8)
                add(t("Drills score how you roll from boot to boot. Start with demo boots now and pair real sensors when you are ready.", Snow.Type.BODY, Snow.INK_SOFT), bottom = 24)
                add(Snow.button(this@HomeActivity, "Start with demo boots") { finishOnboarding(false) }, bottom = 12)
                add(Snow.button(this@HomeActivity, "Pair my boots first", Snow.ButtonKind.SECONDARY) { finishOnboarding(true) })
                add(t("Demo boots are simulated and always labelled. They are for learning the drills, not a measurement of your skiing.", Snow.Type.CAPTION, Snow.INK_SOFT), top = 16)
            }
        }
    }

    private fun finishOnboarding(pair: Boolean) {
        store.goal = pickedGoal ?: Goal.CARVING
        store.experience = pickedExperience ?: Experience.NEW
        tab = 0; render()
        if (pair) openBoots()
    }

    private fun choice(piste: Piste, title: String, blurb: String, selected: Boolean, onPick: () -> Unit) =
        LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true; isFocusable = true
            contentDescription = "$title. $blurb" + if (selected) " Selected." else ""
            val inset = Snow.dp(this@HomeActivity, 18)
            setPadding(inset, inset, inset, inset)
            background = Snow.rounded(this@HomeActivity, if (selected) Snow.GLACIER else Snow.PAPER, 24, if (selected) Snow.INK else Snow.GLACIER, if (selected) 2 else 1)
            addView(PisteMarker(this@HomeActivity, piste, 30))
            addView(LinearLayout(this@HomeActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(t(title, Snow.Type.TITLE))
                addView(t(blurb, Snow.Type.BODY, Snow.INK_SOFT).apply { setPadding(0, Snow.dp(this@HomeActivity, 3), 0, 0) })
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = Snow.dp(this@HomeActivity, 16) })
            setOnClickListener { onPick() }
        }

    // Today ---------------------------------------------------------------------------------------------

    private fun today() = page {
        val progress = store.progress()
        val goal = store.goal ?: Goal.CARVING
        val now = System.currentTimeMillis()
        val streak = progress.streak(now)
        val header = LinearLayout(this@HomeActivity).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(t("OpenSki", Snow.Type.TITLE, Snow.ORANGE), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(t("${NumberFormat.getInstance().format(progress.vert)} vert", Snow.Type.TITLE))
        add(header, bottom = 20)

        val drill = progress.next(goal)
        if (drill != null) {
            val done = progress.stars(drill.id) > 0
            val hero = Snow.card(this@HomeActivity, 22, radius = 28)
            val kicker = LinearLayout(this@HomeActivity).apply { gravity = Gravity.CENTER_VERTICAL }
            kicker.addView(PisteMarker(this@HomeActivity, drill.piste, 22))
            kicker.addView(t(if (done) "Replay to improve" else "Today's drill", Snow.Type.CAPTION, Snow.INK_SOFT),
                LinearLayout.LayoutParams(-2, -2).apply { marginStart = Snow.dp(this@HomeActivity, 8) })
            hero.addView(kicker)
            hero.add(t(drill.title, Snow.Type.HERO), top = 10)
            hero.add(t(drill.summary, Snow.Type.BODY, Snow.INK_SOFT), top = 6)
            hero.add(t("${drill.minutes} min  ·  ${drill.movements} rolls  ·  aim for ${drill.depthDegrees.toInt()}°", Snow.Type.STRONG), top = 14)
            hero.add(Snow.button(this@HomeActivity, "Start drill") { openDrill(drill) }, top = 18)
            add(hero, bottom = 16)
        }

        val streakCard = Snow.card(this@HomeActivity, 18)
        streakCard.addView(t(if (streak == 0) "Start a streak today" else if (streak == 1) "1 day streak" else "$streak day streak", Snow.Type.TITLE))
        streakCard.add(weekStrip(progress, now), top = 12)
        add(streakCard, bottom = 16)

        val target = Programme.drills(goal.piste)
        val passed = target.count { progress.stars(it.id) >= 1 }
        val pathCard = Snow.card(this@HomeActivity, 18)
        val pathRow = LinearLayout(this@HomeActivity).apply { gravity = Gravity.CENTER_VERTICAL }
        pathRow.addView(PisteMarker(this@HomeActivity, goal.piste, 22))
        pathRow.addView(LinearLayout(this@HomeActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(t("Heading for ${goal.piste.title.lowercase()}", Snow.Type.TITLE))
            addView(t("$passed of ${target.size} drills passed", Snow.Type.BODY, Snow.INK_SOFT).apply { setPadding(0, Snow.dp(this@HomeActivity, 2), 0, 0) })
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = Snow.dp(this@HomeActivity, 12) })
        pathCard.addView(pathRow)
        pathCard.add(bar(passed.toFloat() / target.size, Snow.pisteColor(goal.piste)), top = 14)
        pathCard.isClickable = true
        pathCard.setOnClickListener { tab = 1; render() }
        add(pathCard, bottom = 16)

        val boots = Snow.card(this@HomeActivity, 18, Snow.SNOW)
        val paired = pairedBoots()
        boots.addView(t(if (paired.isEmpty()) "Practising with demo boots" else "Boots paired", Snow.Type.TITLE))
        boots.add(t(if (paired.isEmpty()) "Pair your real sensors to score your own rolls."
            else "Start a drill and choose \"Start with my boots\".", Snow.Type.BODY, Snow.INK_SOFT), top = 4)
        boots.add(Snow.button(this@HomeActivity, if (paired.isEmpty()) "Set up boots" else "Boots and Geek mode", Snow.ButtonKind.SECONDARY) {
            if (paired.isEmpty()) openBoots() else { tab = 3; render() }
        }, top = 12)
        add(boots)
    }

    private fun weekStrip(progress: Progress, now: Long): View {
        val today = java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        return LinearLayout(this).apply {
            progress.week(now).forEach { (day, trained) ->
                val isToday = day == today
                val cell = TextView(this@HomeActivity).apply {
                    text = day.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault())
                    textSize = 15f; gravity = Gravity.CENTER; includeFontPadding = false
                    typeface = resources.getFont(R.font.barlow_condensed_bold)
                    setTextColor(if (trained) Snow.INK else Snow.INK_SOFT)
                    background = Snow.rounded(this@HomeActivity, if (trained) Snow.ORANGE else Snow.SNOW, 20,
                        if (isToday) Snow.INK else Snow.GLACIER, if (isToday) 2 else 1)
                    contentDescription = day.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault()) +
                        if (trained) ", trained" else if (isToday) ", today" else ""
                }
                addView(cell, LinearLayout.LayoutParams(0, Snow.dp(this@HomeActivity, 40), 1f).apply {
                    marginStart = Snow.dp(this@HomeActivity, 2); marginEnd = Snow.dp(this@HomeActivity, 2)
                })
            }
        }
    }

    private fun bar(fraction: Float, color: Int) = FrameLayout(this).apply {
        val height = Snow.dp(this@HomeActivity, 8)
        background = Snow.rounded(this@HomeActivity, Snow.GLACIER, 4)
        val fill = View(this@HomeActivity).apply { background = Snow.rounded(this@HomeActivity, color, 4) }
        addView(fill, FrameLayout.LayoutParams(0, height))
        layoutParams = LinearLayout.LayoutParams(-1, height)
        post { fill.layoutParams = FrameLayout.LayoutParams((width * fraction.coerceIn(0f, 1f)).toInt(), height) }
    }

    // Path ----------------------------------------------------------------------------------------------

    private fun path() = page {
        val progress = store.progress()
        add(t("Your path", Snow.Type.HERO), bottom = 4)
        add(t("Pass a drill with one star to open the next.", Snow.Type.BODY, Snow.INK_SOFT), bottom = 20)
        Piste.entries.forEach { piste ->
            val reachable = piste.ordinal >= store.experience.startsOn.ordinal
            val heading = LinearLayout(this@HomeActivity).apply { gravity = Gravity.CENTER_VERTICAL }
            heading.addView(PisteMarker(this@HomeActivity, piste, 28, !reachable))
            heading.addView(LinearLayout(this@HomeActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(t(piste.title, Snow.Type.DISPLAY))
                addView(t(if (reachable) piste.tagline else "Skipped for your level", Snow.Type.BODY, Snow.INK_SOFT))
            }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = Snow.dp(this@HomeActivity, 12) })
            add(heading, bottom = 10)
            val drills = Programme.drills(piste)
            drills.forEachIndexed { index, drill -> add(drillRow(progress, drill, index == drills.lastIndex), bottom = 0) }
            add(Snow.gap(this@HomeActivity, 20))
        }
    }

    private fun drillRow(progress: Progress, drill: Drill, last: Boolean): View {
        val open = progress.unlocked(drill)
        val stars = progress.stars(drill.id)
        val best = progress.best(drill.id)
        val row = LinearLayout(this).apply {
            isClickable = open; isFocusable = open
            contentDescription = "${drill.title}, ${if (open) "$stars of 3 stars" else "locked"}"
            if (open) setOnClickListener { openDrill(drill) }
        }
        val rail = FrameLayout(this)
        val color = Snow.pisteColor(drill.piste)
        rail.addView(View(this).apply { setBackgroundColor(Snow.GLACIER) },
            FrameLayout.LayoutParams(Snow.dp(this, 4), -1, Gravity.CENTER_HORIZONTAL).apply { if (last) bottomMargin = Snow.dp(this@HomeActivity, 36) })
        rail.addView(View(this).apply {
            background = Snow.rounded(this@HomeActivity, if (stars > 0) color else if (open) Snow.PAPER else Snow.SNOW, 12, if (open) color else Snow.GLACIER_DEEP, 3)
        }, FrameLayout.LayoutParams(Snow.dp(this, 24), Snow.dp(this, 24), Gravity.CENTER_HORIZONTAL).apply { topMargin = Snow.dp(this@HomeActivity, 16) })
        row.addView(rail, LinearLayout.LayoutParams(Snow.dp(this, 36), -1))
        val card = Snow.card(this, 16, if (open) Snow.PAPER else Snow.SNOW, 20).apply { alpha = if (open) 1f else .6f }
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(t(drill.title, Snow.Type.TITLE), LinearLayout.LayoutParams(0, -2, 1f))
        if (open) top.addView(StarRow(this, stars, 16))
        card.addView(top)
        card.add(t(when {
            !open -> "Locked. Pass the drill before this one."
            best != null -> "Best ${best.score}  ·  ${drill.movements} rolls at ${drill.paceSeconds} s"
            else -> "${drill.movements} rolls at ${drill.paceSeconds} s  ·  ${drill.depthDegrees.toInt()}°"
        }, Snow.Type.BODY, Snow.INK_SOFT), top = 4)
        row.addView(card, LinearLayout.LayoutParams(0, -2, 1f).apply { bottomMargin = Snow.dp(this@HomeActivity, 12); marginStart = Snow.dp(this@HomeActivity, 8) })
        return row
    }

    // Progress ------------------------------------------------------------------------------------------

    private fun progress() = page {
        val progress = store.progress()
        val now = System.currentTimeMillis()
        add(t("Progress", Snow.Type.HERO), bottom = 16)
        val stats = LinearLayout(this@HomeActivity)
        fun stat(value: String, label: String) = Snow.card(this@HomeActivity, 16).apply {
            addView(t(value, Snow.Type.DISPLAY)); addView(t(label, Snow.Type.BODY, Snow.INK_SOFT))
        }
        stats.addView(stat(NumberFormat.getInstance().format(progress.vert), "vert earned"), LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = Snow.dp(this@HomeActivity, 6) })
        stats.addView(stat("${progress.streak(now)}", "day streak"), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = Snow.dp(this@HomeActivity, 6) })
        add(stats, bottom = 12)
        val week = Snow.card(this@HomeActivity, 18)
        week.addView(t("This week", Snow.Type.TITLE))
        week.add(weekStrip(progress, now), top = 12)
        week.add(t("${progress.week(now).count { it.second }} of 3 training days", Snow.Type.BODY, Snow.INK_SOFT), top = 10)
        add(week, bottom = 20)
        add(t("Best scores", Snow.Type.DISPLAY), bottom = 10)
        val scores = Snow.card(this@HomeActivity, 18)
        Programme.drills.forEach { drill ->
            val best = progress.best(drill.id)
            val line = LinearLayout(this@HomeActivity).apply { gravity = Gravity.CENTER_VERTICAL }
            line.addView(PisteMarker(this@HomeActivity, drill.piste, 18))
            line.addView(t(drill.title, Snow.Type.STRONG), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = Snow.dp(this@HomeActivity, 10) })
            line.addView(t(best?.score?.toString() ?: "–", Snow.Type.TITLE, if (best == null) Snow.INK_SOFT else Snow.INK))
            scores.add(line, bottom = 14)
        }
        add(scores, bottom = 20)
        val recent = progress.attempts.sortedByDescending { it.timeMs }.take(5)
        add(t("Recent drills", Snow.Type.DISPLAY), bottom = 10)
        if (recent.isEmpty()) add(t("Finish a drill and it will show up here.", Snow.Type.BODY, Snow.INK_SOFT))
        recent.forEach { attempt ->
            val drill = Programme.drill(attempt.drillId) ?: return@forEach
            val date = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(attempt.timeMs))
            add(t("${drill.title}  ·  ${attempt.score}  ·  $date" + if (attempt.demo) "  ·  demo boots" else "", Snow.Type.BODY), bottom = 8)
        }
    }

    // Boots ---------------------------------------------------------------------------------------------

    private fun pairedBoots(): List<String> = getSharedPreferences(SensorSessionService.PREFS, MODE_PRIVATE).let { prefs ->
        listOf("L", "R").filter { prefs.getString("sensor_$it", null) != null }
    }

    private fun boots() = page {
        val paired = pairedBoots()
        val prefs = getSharedPreferences(SensorSessionService.PREFS, MODE_PRIVATE)
        add(t("Your boots", Snow.Type.HERO), bottom = 8)
        add(t(if (paired.isEmpty()) "No sensors paired yet. Drills run on demo boots until you pair them." else "Drills follow whichever paired boot is streaming.", Snow.Type.BODY, Snow.INK_SOFT), bottom = 16)
        val status = Snow.card(this@HomeActivity, 18)
        listOf("L" to "Left boot", "R" to "Right boot").forEachIndexed { index, (side, name) ->
            val address = prefs.getString("sensor_$side", null)
            status.add(t(name, Snow.Type.TITLE), top = if (index == 0) 0 else 16)
            status.add(t(if (address == null) "Not paired" else "Sensor ${address.replace(":", "").takeLast(4)}", Snow.Type.BODY, Snow.INK_SOFT), top = 2)
            if (address != null) {
                status.add(t("Mounting: ${store.mounting(side)?.label(store.landmarks()) ?: "not set yet"}", Snow.Type.BODY, Snow.INK_SOFT), top = 2)
                status.add(Snow.button(this@HomeActivity, "Set mounting", Snow.ButtonKind.QUIET) {
                    MountingPicker.show(this@HomeActivity, store, side) { render() }
                }.apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL; setPadding(0, paddingTop, paddingRight, paddingBottom) })
            }
        }
        add(status, bottom = 12)
        add(Snow.button(this@HomeActivity, if (paired.isEmpty()) "Pair my boots" else "Manage sensors", if (paired.isEmpty()) Snow.ButtonKind.PRIMARY else Snow.ButtonKind.SECONDARY) { openBoots() }, bottom = 28)

        add(t("Coaching", Snow.Type.DISPLAY), bottom = 6)
        val coaching = Snow.card(this@HomeActivity, 18)
        coaching.add(t("Metronome and turn chirps", Snow.Type.TITLE))
        coaching.add(t("Audio cues for pace and depth, for skiing with the phone in a pocket. Needs earbuds or a helmet speaker.", Snow.Type.BODY, Snow.INK_SOFT), top = 6)
        coaching.add(Snow.button(this@HomeActivity, "Open coaching", Snow.ButtonKind.SECONDARY) {
            startActivity(Intent(this@HomeActivity, CoachingActivity::class.java))
        }, top = 14)
        add(coaching, bottom = 28)

        add(t("Geek mode", Snow.Type.DISPLAY), bottom = 6)
        val geek = Snow.card(this@HomeActivity, 18, Snow.INK)
        geek.addView(t("Raw telemetry and the logbook", Snow.Type.TITLE, Snow.SNOW))
        geek.add(t("Live acceleration, gyro and orientation, the dry-ski test lab, flash recovery, and every saved session with replay, analysis and export.", Snow.Type.BODY, Snow.GLACIER), top = 6)
        geek.add(Snow.button(this@HomeActivity, "Open Geek mode", Snow.ButtonKind.SECONDARY) { openBoots() }, top = 14)
        geek.add(Snow.button(this@HomeActivity, "Bench tools for a loose board", Snow.ButtonKind.SECONDARY) { openBench() }, top = 10)
        add(geek, bottom = 28)

        add(t("Your plan", Snow.Type.DISPLAY), bottom = 8)
        add(t("Goal: ${store.goal?.title ?: "not set"}\nStarting piste: ${store.experience.startsOn.title}", Snow.Type.BODY), bottom = 12)
        add(Snow.button(this@HomeActivity, "Change goal", Snow.ButtonKind.SECONDARY) {
            pickedGoal = store.goal; pickedExperience = store.experience; onboardingStep = 0
            store.goal = null; render()
        })
        add(t("Drills use dry-ski boot roll. Carving and turn shape on snow are not measured yet.", Snow.Type.CAPTION, Snow.INK_SOFT), top = 20)
    }
}
