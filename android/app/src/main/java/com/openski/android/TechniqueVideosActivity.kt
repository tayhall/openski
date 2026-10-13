package com.openski.android

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.ScrollView
import android.widget.VideoView

/** One short animated technique video bundled with the app (rendered from the skivids drill definitions). */
data class TechniqueVideo(val id: String, val title: String, val line: String, val resName: String)

object TechniqueVideos {
    val all = listOf(
        TechniqueVideo("linked-carved-turns", "Linked carved turns", "Edge to edge, one turn flowing into the next.", "technique_linked_carved_turns"),
        TechniqueVideo("long-turns", "Long, round turns", "A wide radius with a smooth, progressive edge.", "technique_long_turns"),
        TechniqueVideo("short-turns", "Short turns", "Quick, tidy turns down the fall line.", "technique_short_turns"),
        TechniqueVideo("parallel-turns", "Basic parallel turns", "Both skis turning together.", "technique_parallel_turns"),
        TechniqueVideo("steered-turns", "Steered (skidded) turns", "Turning by steering the skis rather than carving.", "technique_steered_turns"),
        TechniqueVideo("leg-steering", "Steering with the legs", "The legs turn under a quiet upper body.", "technique_leg_steering"),
        TechniqueVideo("uneven-turns", "Uneven, stiff turns", "What an uneven, stiff rhythm looks like, to avoid it.", "technique_uneven_turns"),
        TechniqueVideo("moguls", "Skiing bumps", "Absorbing the terrain through the bumps.", "technique_moguls"),
    )

    fun find(id: String?) = all.firstOrNull { it.id == id }

    /** The video that goes with a dry-ski drill, where one clearly does. */
    fun forDrill(drillId: String): TechniqueVideo? = find(when (drillId) {
        "short-radius-rhythm", "tighter-quicker", "short-turn-set" -> "short-turns"
        "link-the-edges" -> "linked-carved-turns"
        else -> null
    })

    fun open(context: Context, id: String? = null) {
        context.startActivity(Intent(context, TechniqueVideosActivity::class.java).putExtra("video", id))
    }
}

/** The bundled technique videos: pick one and it plays above the list. Demonstrations, not scored. */
class TechniqueVideosActivity : Activity() {
    private lateinit var content: LinearLayout
    private var selected: TechniqueVideo? = null
    private var video: VideoView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Snow.configureWindow(this)
        selected = TechniqueVideos.find(intent.getStringExtra("video")) ?: TechniqueVideos.all.first()
        val frame = FrameLayout(this).apply { setBackgroundColor(Snow.SNOW) }
        SkiUi.applyInsets(frame)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val inset = Snow.dp(this@TechniqueVideosActivity, 20)
            setPadding(inset, inset, inset, Snow.dp(this@TechniqueVideosActivity, 28))
        }
        frame.addView(ScrollView(this).apply { isFillViewport = true; addView(content) })
        setContentView(frame)
        render()
    }

    override fun onPause() { video?.pause(); super.onPause() }

    private fun add(view: View, top: Int = 0, bottom: Int = 0) =
        content.addView(view, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = Snow.dp(this@TechniqueVideosActivity, top); bottomMargin = Snow.dp(this@TechniqueVideosActivity, bottom)
        })

    private fun render() {
        content.removeAllViews()
        add(Snow.button(this, "Back", Snow.ButtonKind.QUIET) { finish() }, bottom = 8)
        add(Snow.text(this, "Technique", Snow.Type.HERO))
        add(Snow.text(this, "Short animated demonstrations. They show the movement; your drills score dry-ski boot roll, not these.",
            Snow.Type.BODY, Snow.INK_SOFT), top = 4, bottom = 16)
        val current = selected!!
        val resId = resources.getIdentifier(current.resName, "raw", packageName)
        val player = VideoView(this).also { video = it }
        player.setMediaController(MediaController(this).also { it.setAnchorView(player) })
        player.setVideoURI(Uri.parse("android.resource://$packageName/$resId"))
        player.setOnPreparedListener { it.isLooping = true; player.start() }
        add(player, bottom = 6)
        // The renders are portrait: give them room and centre them.
        (player.layoutParams as LinearLayout.LayoutParams).apply { height = Snow.dp(this@TechniqueVideosActivity, 460); gravity = android.view.Gravity.CENTER_HORIZONTAL }
        add(Snow.text(this, current.title, Snow.Type.TITLE), top = 8)
        add(Snow.text(this, current.line, Snow.Type.BODY, Snow.INK_SOFT), top = 2, bottom = 20)
        TechniqueVideos.all.filter { it.id != current.id }.forEach { other ->
            val card = Snow.card(this, 16)
            card.addView(Snow.text(this, other.title, Snow.Type.STRONG))
            card.addView(Snow.text(this, other.line, Snow.Type.CAPTION, Snow.INK_SOFT))
            card.setOnClickListener { selected = other; render() }
            card.isClickable = true
            card.contentDescription = "Play ${other.title}"
            add(card, bottom = 10)
        }
    }
}
