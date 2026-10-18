package com.openski.android

/** Wording that asks the skier for the stomps the offline stomp sync needs (see [GestureSync]). Experimental. */
object GestureSyncPrompts {
    const val READY = "Once the run has started and your boots are zeroed, stomp both boots together once. Stomp again as you stop."
    const val RUNNING = "Stomp both boots together once more as you stop. The app can find it up to 45 seconds before the recording ends."
    const val DONE = "Stomp sync compares the two boots' clocks using one stomp at the start and one at the end. " +
        "The logbook's Details tab says whether it worked. Experimental."
    const val RECORD = "Stomp both boots together once just after you start and once as you stop (up to 45 seconds before the recording ends), so the app can check the boots' clocks against each other (experimental)."
}
