package com.openski.android

import android.app.Application

/** Reconcile orphaned practice before Home can display it as active after process death. */
class OpenSkiApplication : Application() {
    private var practiceRecovered = false

    override fun onCreate() {
        super.onCreate()
        // A storage failure must remain retryable from the normal history-loading error path.
        runCatching { ensurePracticeRecovered() }
    }

    @Synchronized fun ensurePracticeRecovered() {
        if (practiceRecovered) return
        LocalSessionStore(this).use { store ->
            PracticeStore(store).interruptActive(System.currentTimeMillis())
        }
        practiceRecovered = true
    }
}
