package com.openski.android

import android.app.Instrumentation
import android.database.sqlite.SQLiteDatabase
import java.util.UUID

/** Called by the shared instrumentation runner; no real sensor or app history is modified. */
object PracticeReviewStorageRegression {
    fun run(instrumentation: Instrumentation) {
        val context = instrumentation.targetContext
        for (upgrade in listOf(false, true)) {
            val name = "practice-review-${UUID.randomUUID()}.db"
            try {
                if (upgrade) {
                    LocalSessionStore(context, name).use { it.writableDatabase }
                    SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                        db.execSQL("DROP TABLE practice_reflections")
                        db.execSQL("DROP TABLE practice_attempts")
                        db.execSQL("CREATE TABLE practice_attempts(id TEXT PRIMARY KEY,drill_id TEXT NOT NULL,time_ms INTEGER NOT NULL,score INTEGER NOT NULL,stars INTEGER NOT NULL,points INTEGER NOT NULL,demo INTEGER NOT NULL,session_id TEXT,status TEXT NOT NULL,scoring_version INTEGER NOT NULL,target_movements INTEGER,target_beat REAL,target_depth REAL)")
                        db.execSQL("CREATE INDEX practice_time_idx ON practice_attempts(time_ms)")
                        db.execSQL("INSERT INTO practice_attempts(id,drill_id,time_ms,score,stars,points,demo,status,scoring_version) VALUES('v14','drill',0,91,3,182,0,'COMPLETED',1)")
                        db.version = 14
                    }
                }
                LocalSessionStore(context, name).use { database ->
                    val store = PracticeStore(database)
                    if (upgrade) check(store.get("v14")!!.let { it.review == null && it.score == 91 && it.vert == 182 && it.eligible })
                    val target = PracticeTarget(4, 2.0, 20.0)
                    val legacy = Attempt("drill", 1, 95, 3, 190, false, id = "legacy", target = null)
                    store.record(legacy)
                    check(store.get("legacy") == legacy)
                    database.createSession("raw", 1); database.finishSession("raw", 2)
                    val active = Attempt("drill", 2, 0, 0, 0, false, id = "new", sessionId = "raw", status = PracticeStatus.ACTIVE, target = target)
                    store.record(active)
                    val moves = (0..3).map { DryMovement(it * 2000.0, it * 2000.0 + 1000, it % 2 == 0, 18.0) }
                    val run = DrillRun(DryDetection(moves, emptyList()), RollTrace(floatArrayOf(), floatArrayOf()), false)
                    val terminal = active.copy(status = PracticeStatus.COMPLETED, score = 80, stars = 2,
                        review = PracticeReview.capture(active, run, "L", Mounting(0, 1)))
                    database.writableDatabase.execSQL("CREATE TRIGGER fail_review BEFORE UPDATE ON practice_attempts BEGIN SELECT RAISE(ABORT,'review failed'); END")
                    check(runCatching { store.record(terminal, true) }.isFailure)
                    check(store.get("new")?.status == PracticeStatus.ACTIVE)
                    database.writableDatabase.execSQL("DROP TRIGGER fail_review")
                    val saved = store.record(terminal, true)
                    check(saved.review == terminal.review)
                    check(store.record(terminal, true) == saved)
                    store.saveReflection("new", PracticeReflection("Count the beat", "Felt uneven"))
                    check(store.reflection("new").intention == "Count the beat")
                    val exported = database.loadData("raw")!!.practiceReviews.single().json()
                    check(exported.getInt("format_version") == 1)
                    check(exported.getJSONObject("review").getInt("known_beat_count") == 3)
                    check(exported.getString("user_reported_reflection") == "Felt uneven")
                    val insufficient = terminal.copy(id = "poor", sessionId = null, review = terminal.review!!.copy(
                        evidence = terminal.review.evidence.copy(scoringQuality = ScoringEvidenceQuality.INSUFFICIENT)))
                    val unscored = store.record(insufficient, true)
                    check(unscored.stars == 0 && unscored.vert == 0 && !unscored.eligible)
                    check(HistoryStore(database).page(HistoryKind.DRILLS, false).first { it.id == "poor" }.detail.contains("cannot judge"))
                    // Removing raw data preserves review and optional user notes.
                    database.writableDatabase.delete("sessions", "id='raw'", null)
                    check(store.get("new")!!.review == saved.review)
                    check(store.reflection("new").reflection == "Felt uneven")
                }
                LocalSessionStore(context, name).use { database ->
                    check(database.readableDatabase.version == 15)
                    check(PracticeStore(database).get("new")!!.review != null)
                    check(PracticeStore(database).reflection("new").intention == "Count the beat")
                }
            } finally { context.deleteDatabase(name) }
        }
    }
}
