package fan.superai.engine

import fan.superai.engine.kotlin.CtwMember
import fan.superai.engine.kotlin.EsnMember
import fan.superai.engine.kotlin.FreqGapMember
import fan.superai.engine.kotlin.GruMember
import fan.superai.engine.kotlin.KalipArama2
import fan.superai.engine.kotlin.PpmMember
import fan.superai.engine.kotlin.RegimeMember
import fan.superai.engine.kotlin.StreakWaveMember

/** Dış (Python) meclis arayüzü. Android'de Chaquopy ile uygulanır. */
interface ExternalCouncil {
    /** Tüm geçmişi baştan öğrenir. Dönüş: her kayıt i için, 0..i-1 bilinirken yapılan tahmin; ve son (bir sonraki) tahmin. */
    fun replay(values: IntArray, times: LongArray): Pair<List<DoubleArray>, DoubleArray>
    /** Yeni kaydı öğrenir, bir sonraki tahmini döndürür. */
    fun step(value: Int, time: Long): DoubleArray
    /**
     * Kayıt sayısını TAM OLARAK [count]'a döndürür ve o andaki (bir sonraki) tahmini verir.
     * Hedefe ulaşılamazsa null döner; çağıran taraf tam yeniden öğrenmeye düşer.
     * Hedefin açıkça verilmesi iki meclisin adım adım eşitlenmesini garanti eder.
     */
    fun undoTo(count: Int): DoubleArray?
    fun stats(): List<MemberStat>
    fun info(): Map<String, String>
}

data class Scores(
    val n: Int,
    val top1: Double, val top2: Double, val sideAny: Double, val sideBoth: Double,
    val kTop1: Double, val kTop2: Double, val pTop1: Double, val pTop2: Double
)

data class EngineState(
    val count: Int,
    val recent: List<Int>,             // 1..4
    val verdict: Verdict?,
    val kotlinProbs: DoubleArray,
    val pythonProbs: DoubleArray?,
    val kotlinStats: List<MemberStat>,
    val pythonStats: List<MemberStat>,
    val pythonInfo: Map<String, String>,
    val kalipInfo: Map<String, String>,
    val regime: String,
    val scores: Scores,
    val lastSideHit: Boolean?,         // son yan tahmin tamamen tuttu mu
    val learning: Boolean,
    val busy: String?
)

/** Adım kaydı (grafikler ve başarı için). */
class StepLog(val top1: Boolean, val top2: Boolean, val shownHit: Boolean, val sideAny: Boolean, val sideBoth: Boolean,
              val kTop1: Boolean, val kTop2: Boolean, val pTop1: Boolean, val pTop2: Boolean, val pAvail: Boolean)

/**
 * Ana motor: iki meclis + baş hakem. Android'den bağımsızdır; tek bir iş parçacığından çağrılmalıdır.
 */
class FanEngine(var cfg: EngineConfig, private var python: ExternalCouncil?) {
    val values = ArrayList<Int>()
    val times = ArrayList<Long>()
    private val pHist = ArrayList<DoubleArray?>()   // her kayıt için Python'un önceki tahmini
    private var pNext: DoubleArray? = null

    lateinit var kotlin: Council; private set
    lateinit var referee: Referee; private set
    val logs = ArrayList<StepLog>()
    var verdict: Verdict? = null; private set
    private var kNext: DoubleArray = P.uniform()
    private var lastSideHit: Boolean? = null

    /**
     * Bir adımı geri almak için gereken her şey: motorun o anki görünümü (kNext/verdict/
     * log sayısı/yan isabeti) + meclis ve hakemin adım öncesi kayıtları.
     */
    private class UndoRec(val count: Int, val logs: Int, val hit: Boolean?, val next: DoubleArray, val verdict: Verdict?) {
        var council: Any? = null
        var referee: Any? = null
        fun attach(c: Any?, r: Any?) { council = c; referee = r }
    }

    private val undoStack = ArrayDeque<UndoRec>()

    /** Anında geri alınabilecek adım sayısı (arayüz/tanılama için). */
    val undoDepth: Int get() = undoStack.size
    /** Son geri alma hızlı yoldan mı yapıldı? (false → tüm geçmiş baştan kuruldu) */
    var lastUndoFast: Boolean = true; private set
    /** Son geri alma süresi (ms). */
    var lastUndoMs: Long = 0; private set

    companion object {
        /** Kaç adımın geri alma kaydı bellekte tutulur (adım başına ~20 KB). */
        const val UNDO_DEPTH = 64

        fun kotlinMembers(): List<Member> = listOf(
            KalipArama2(), CtwMember(), PpmMember(), FreqGapMember(),
            StreakWaveMember(), RegimeMember(), GruMember(), EsnMember()
        )
        val KOTLIN_IDS = listOf("kalip2", "ctw", "ppm", "freqgap", "streak", "regime", "gru", "esn")
    }

    /**
     * Python geri alması başarısız olduğunda Kotlin tarafı yine de anında döner; bu bayrak
     * Python'un geride kaldığını işaretler ve bir sonraki eklemede onu yeniden öğreniriz.
     * Böylece iki meclis asla farklı adımda kalmaz (veri bozulmaz).
     */
    var pyDirty = false
        private set

    fun setPython(p: ExternalCouncil?) { python = p; pyDirty = false }
    fun hasPython() = python != null

    private fun hist(n: Int) = History(values.toIntArray(), times.toLongArray(), n)

    /** Python'u baştan öğretir (ağır). */
    fun replayPython() {
        val py = python
        pyDirty = false
        pHist.clear()
        undoStack.clear()   // Python geçmişi değişti: eski geri alma kayıtları geçersiz
        if (py == null) { repeat(values.size) { pHist.add(null) }; pNext = null; return }
        try {
            val (per, next) = py.replay(values.toIntArray(), times.toLongArray())
            pHist.addAll(per); pNext = next
            while (pHist.size < values.size) pHist.add(null)
        } catch (e: Exception) {
            repeat(values.size) { pHist.add(null) }; pNext = null
        }
    }

    /** Kotlin meclisi ve hakemi (Python geçmişini kullanarak) baştan kurar. Hızlıdır. */
    fun rebuildKotlin() {
        kotlin = Council("Kotlin", kotlinMembers(), cfg)
        referee = Referee(cfg)
        logs.clear(); lastSideHit = null
        undoStack.clear()
        val vArr = values.toIntArray(); val tArr = times.toLongArray()
        // Geri alma kayıtları yalnızca SON UNDO_DEPTH adım için tutulur (bellek sınırı).
        val from = maxOf(0, values.size - UNDO_DEPTH)
        for (i in 0 until values.size) {
            val h = History(vArr, tArr, i)
            val pk = kotlin.predict(h)
            val pp = pHist.getOrNull(i)
            val vd = referee.decide(pk, pp)
            val rec = if (i >= from) pushUndo(i, pk, vd) else null
            record(i, vd, pk, pp, vArr[i])
            kotlin.update(History(vArr, tArr, i + 1))
            referee.update(vArr[i])
            rec?.attach(kotlin.undoToken(), referee.undoToken())
        }
        kNext = kotlin.predict(History(vArr, tArr, values.size))
        verdict = referee.decide(kNext, pNext)
    }

    /** [count] kayıt öğrenilmiş durumun geri alma kaydını yığına ekler. */
    private fun pushUndo(count: Int, next: DoubleArray, verdict: Verdict?): UndoRec? {
        if (!::kotlin.isInitialized || !kotlin.undoable) return null
        val rec = UndoRec(count, logs.size, lastSideHit, next.copyOf(), verdict)
        undoStack.addLast(rec)
        while (undoStack.size > UNDO_DEPTH) undoStack.removeFirst()
        return rec
    }

    private fun record(i: Int, vd: Verdict, pk: DoubleArray, pp: DoubleArray?, a: Int) {
        val o = P.order(vd.probs)
        val t1 = o[0] == a; val t2 = t1 || o[1] == a
        val shown = a == vd.primary || a == vd.secondary
        val oddOk = (a % 2 == 0) == vd.sideOdd; val bigOk = (a >= 2) == vd.sideBig
        val ok = P.order(pk)
        val op = pp?.let { P.order(it) }
        lastSideHit = oddOk && bigOk
        if (i >= cfg.silentFirst) logs.add(StepLog(t1, t2, shown, oddOk || bigOk, oddOk && bigOk,
            ok[0] == a, ok[0] == a || ok[1] == a,
            op != null && op[0] == a, op != null && (op[0] == a || op[1] == a), op != null))
    }

    fun rebuildAll() { replayPython(); rebuildKotlin() }

    /** Yeni kayıt (0..3). */
    fun add(v: Int, t: Long) {
        // Python bir önceki geri almada eşitlenemediyse önce onu tam öğren (nadir, ama şart).
        if (pyDirty) { replayPython(); verdict = referee.decide(kNext, pNext) }
        val vd = verdict ?: referee.decide(kNext, pNext)
        val i = values.size
        val rec = pushUndo(i, kNext, vd)     // bu adımın geri alma kaydı
        values.add(v); times.add(t)
        pHist.add(pNext)
        record(i, vd, kNext, pNext, v)
        val vArr = values.toIntArray(); val tArr = times.toLongArray()
        kotlin.update(History(vArr, tArr, values.size))
        referee.update(v)
        rec?.attach(kotlin.undoToken(), referee.undoToken())
        pNext = try { python?.step(v, t) } catch (e: Exception) { null }
        kNext = kotlin.predict(History(vArr, tArr, values.size))
        verdict = referee.decide(kNext, pNext)
    }

    /**
     * Son kaydı geri alır.
     *
     * HIZLI YOL: her adım için tutulan küçük kayıtlar sayesinde meclis, hakem ve Python
     * meclisi tam olarak bir adım geriye sarılır → milisaniyeler.
     * YAVAŞ YOL: kayıt yoksa (ör. [UNDO_DEPTH] adımdan derin geri alma) tüm geçmiş baştan
     * kurulur; doğru ama ağırdır.
     */
    fun undo(): Boolean {
        if (values.isEmpty()) return false
        val t0 = System.nanoTime()
        val target = values.size - 1
        // Üyelerin undo()'su bu geçmişi (geri alınan kayıt DAHİL) yeniden hesaplamak için ister.
        val hFull = History(values.toIntArray(), times.toLongArray(), values.size)
        val pPrev = pHist.getOrNull(target)          // silinen kayıt için yapılmış Python tahmini
        val py = python
        val back = if (py != null) (try { py.undoTo(target) } catch (e: Exception) { null }) else null
        val rec = undoStack.lastOrNull()?.takeIf { it.count == target && it.council != null }
        if (rec != null && kotlin.undo(rec.council, hFull) && referee.undo(rec.referee)) {
            undoStack.removeLast()
            values.removeAt(target); times.removeAt(target)
            while (pHist.size > target) pHist.removeAt(pHist.size - 1)
            while (logs.size > rec.logs) logs.removeAt(logs.size - 1)
            lastSideHit = rec.hit
            kNext = rec.next.copyOf()
            pNext = back ?: pPrev
            // Python geri alınamadıysa hedefi kaçtı: bir sonraki eklemede yeniden öğrenilecek.
            if (py != null && back == null) pyDirty = true
            verdict = referee.decide(kNext, pNext)
            lastUndoFast = true
            lastUndoMs = (System.nanoTime() - t0) / 1_000_000L
            return true
        }
        // Kayıt yok ya da geri alma başarısız: tam yeniden kurma durumu baştan onarır.
        values.removeAt(target); times.removeAt(target)
        while (pHist.size > target) pHist.removeAt(pHist.size - 1)
        if (py != null && back == null) replayPython()
        rebuildKotlin()
        lastUndoFast = false
        lastUndoMs = (System.nanoTime() - t0) / 1_000_000L
        return true
    }

    fun clearAll() { values.clear(); times.clear(); pHist.clear(); pNext = null; rebuildAll() }

    private fun rate(sel: (StepLog) -> Boolean, last: Int = 100, filter: (StepLog) -> Boolean = { true }): Double {
        val l = logs.takeLast(last).filter(filter)
        return if (l.isEmpty()) 0.0 else l.count(sel).toDouble() / l.size
    }

    fun scores(last: Int = 100) = Scores(
        minOf(last, logs.size),
        rate({ it.top1 }, last), rate({ it.top2 }, last), rate({ it.sideAny }, last), rate({ it.sideBoth }, last),
        rate({ it.kTop1 }, last), rate({ it.kTop2 }, last),
        rate({ it.pTop1 }, last) { it.pAvail }, rate({ it.pTop2 }, last) { it.pAvail }
    )

    fun state(busy: String? = null): EngineState {
        val kInfo = kotlin.members.first { it.id == "kalip2" }.info()
        val reg = kotlin.members.first { it.id == "regime" }.info()["regime"] ?: "-"
        return EngineState(
            values.size, values.takeLast(6).map { it + 1 }, verdict, kNext, pNext,
            kotlin.stats(), python?.let { try { it.stats() } catch (e: Exception) { emptyList() } } ?: emptyList(),
            python?.let { try { it.info() } catch (e: Exception) { emptyMap() } } ?: emptyMap(),
            kInfo, reg, scores(), lastSideHit, values.size < cfg.silentFirst, busy
        )
    }

    /** Kayan başarı serisi (grafik için). */
    fun rollingSeries(sel: (StepLog) -> Boolean, win: Int = 50, filter: (StepLog) -> Boolean = { true }): List<Double> {
        val l = logs.filter(filter)
        if (l.size < win) return emptyList()
        val out = ArrayList<Double>(); var s = 0
        for (i in l.indices) {
            if (sel(l[i])) s++
            if (i >= win && sel(l[i - win])) s--
            if (i >= win - 1) out.add(s.toDouble() / win)
        }
        return out
    }
}
