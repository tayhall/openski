package com.openski.android

/** Wording that asks the skier for the stomps the offline stomp sync needs (see [GestureSync]). Experimental. */
object GestureSyncPrompts {
    const val READY = "Stomp both boots together now, and again when you stop."
    const val RUNNING = "Stomp both boots together once more before you stop."
    const val DONE = "Stomp sync compares the two boots' clocks using one stomp at the start and one at the end. " +
        "The logbook's Details tab says whether it worked. Experimental."
    const val RECORD = "Stomp both boots together once after you start and once before you stop, so the app can check the boots' clocks against each other (experimental)."
}
