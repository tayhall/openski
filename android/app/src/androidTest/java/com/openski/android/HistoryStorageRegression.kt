package com.openski.android
import android.app.Instrumentation
import java.util.UUID
/** Root runner invokes this helper; uses an isolated database and no hardware. */
object HistoryStorageRegression {
    fun run(instrumentation: Instrumentation) {
        val context=instrumentation.targetContext; val name="history-${UUID.randomUUID()}.db"
        try { LocalSessionStore(context,name).use { db ->
            val practice=PracticeStore(db)
            repeat(7) { n -> practice.record(Attempt("slow-rolls",100,80,2,0,false,id="a$n",target=PracticeTarget(3,2.0,10.0))) }
            practice.record(Attempt("slow-rolls",200,80,2,0,true,id="demo"))
            val history=HistoryStore(db); val ids=mutableListOf<String>(); var cursor:HistoryCursor?=null
            do {
                val page=history.page(HistoryKind.DRILLS,false,cursor,2)
                ids.addAll(page.map { it.id }); cursor=page.lastOrNull()?.let { HistoryCursor(it.time,it.id) }
            } while(cursor != null)
            check(ids == (6 downTo 0).map { "a$it" }); check(ids.distinct().size==7)
            check(history.page(HistoryKind.DRILLS,true).single().id=="demo")
            db.createSession("raw-drill",1,true)
            db.createSession("real-run",2,kind="run")
            db.createSession("demo-run",3,origin="synthetic",kind="run")
            check(history.page(HistoryKind.RUNS,false).single().id=="real-run")
            check(history.page(HistoryKind.RUNS,true).single().id=="demo-run")
            DayStore(db).create(SkiDay("day-phone","Phone day",10,20,DaySource.PHONE,null,"",DayTags(),10))
            DayStore(db).create(SkiDay("day-gpx","GPX day",10,20,DaySource.GPX,null,"",DayTags(),10))
            check(history.page(HistoryKind.DAYS,false).map { it.id }.toSet()==setOf("day-phone","day-gpx"))
            check(history.page(HistoryKind.DAYS,true)==history.page(HistoryKind.DAYS,false))
            db.writableDatabase.delete("sessions","id=?",arrayOf("raw-drill"))
            check(history.page(HistoryKind.DRILLS,false).size==7)
            check(runCatching { history.page(HistoryKind.DRILLS,false,limit=0) }.isFailure)
        } } finally { context.deleteDatabase(name) }
    }
}
