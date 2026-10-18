package com.openski.android

import android.content.Context

/**
 * One trial of docs/garden-trial-protocol.md, condensed for the read-only checklist in Geek mode. [title] is the doc's
 * bold heading text after "T<n>. " (a JVM test checks they match), [steps] what to do, [exports] the export files the
 * doc says to look at, and [judge] the trial_report command. The doc stays the source of truth; change both together.
 */
data class GardenTrial(val id: String, val title: String, val steps: List<String>, val exports: List<String>, val judge: String)

object GardenTrials {
    const val SOURCE = "docs/garden-trial-protocol.md"
    const val INTRO = "Hardware and data-path checks for the new boots, from $SOURCE. Experimental: they check clocks, " +
        "streams and exports, not on-snow technique. Export the session ZIP after each trial."

    private fun judge(id: String) = "python tools/analysis/trial_report.py export.zip --trial $id"

    val all = listOf(
        GardenTrial("T0", "Bench soak (10 minutes, boots lying still, phone beside them).",
            listOf("Record 10 minutes with both boots lying still and the phone beside them.",
                "Read the battery at the start and end against a multimeter (checked by hand).",
                "Run tools/analysis/still_noise_report.py on the export for rate, bias, drift and noise."),
            listOf("session-health.json", "clock-sync.csv"), judge("T0")),
        GardenTrial("T1", "Stomp sync (2 minutes, both boots worn).",
            listOf("Stand, wait 5 s, stomp both boots together once on a hard floor.",
                "Stand 90 s, then stomp again as you stop.",
                "If the aligner refuses, note the reason and try a harder stomp or a firmer surface."),
            listOf("gesture-sync.json"), judge("T1")),
        GardenTrial("T2", "Turn on the spot and magnetometer (5 minutes).",
            listOf("Turn through full circles on the spot, slowly, left then right, several times, with the left boot at the usual stance.",
                "Note the field magnitude with and without the boot on the foot.",
                "Run tools/analysis/mag_heading_check.py on the export."),
            listOf("magnetometer.csv"), judge("T2")),
        GardenTrial("T3", "Roll drills on foot (about 20 minutes, both boots live).",
            listOf("Run two drills from the Today or Path tab, 20 repetitions each.",
                "Do one set smooth and one deliberately rough (jerky, uneven, one boot late).",
                "Judge the two exports together with --compare smooth.zip rough.zip."),
            listOf("turn-metrics.csv"), judge("T3") + " --compare smooth.zip rough.zip"),
        GardenTrial("T4", "Phone as upper-body reference (during T3).",
            listOf("Keep the phone in the jacket pocket, screen off, for the whole of T3.",
                "Note the phone battery drain over 30 minutes (checked by hand)."),
            listOf("phone-motion.csv"), judge("T4")),
        GardenTrial("T5", "Run flow dry run (one short run, garden or stairs).",
            listOf("Use the 'ready at the top' flow and stomp when asked.",
                "Make about ten turns, stop and wait for the auto end.",
                "Check the run saves, both downloads validate, the boots return to their mode and the GPS track (by hand)."),
            listOf("gesture-sync.json"), judge("T5")),
        GardenTrial("T6", "Mount slip and strap check.",
            listOf("After T3 to T5, check the Analysis tab's mount-slip line for each boot.",
                "If flagged, retighten and repeat the roll drill."),
            emptyList(), judge("T6")),
    )

    /** "3 of 7 done"; ids not in the list are ignored. */
    fun progress(done: Set<String>): String = "${all.count { it.id in done }} of ${all.size} done"
}

/** Tick boxes for the trials, one SharedPreferences flag per trial id. */
class GardenTrialStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("openski-garden-trials", Context.MODE_PRIVATE)
    fun done(): Set<String> = GardenTrials.all.map { it.id }.filter { prefs.getBoolean(key(it), false) }.toSet()
    fun setDone(id: String, done: Boolean) { prefs.edit().putBoolean(key(id), done).apply() }
    private fun key(id: String) = "done_$id"
}
