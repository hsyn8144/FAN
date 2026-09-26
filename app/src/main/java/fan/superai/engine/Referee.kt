package fan.superai.engine

import kotlin.math.ln
import kotlin.math.max

/** Güven kalibrasyonu: ham güveni kutulara ayırıp gerçek başarıya çeker. */
class Calibrator(private val lo: Double, private val hi: Double, private val bins: Int = 10) {
    private val hit = DoubleArray(bins); private val n = DoubleArray(bins)
    private fun b(p: Double) = (((p - lo) / (hi - lo)) * bins).toInt().coerceIn(0, bins - 1)
    fun add(p: Double, ok: Boolean) { val i = b(p); n[i]++; if (ok) hit[i]++ }
    fun calibrate(p: Double): Double { val i = b(p); val m = 25.0; return (hit[i] + p * m) / (n[i] + m) }
    fun table(): List<Triple<Double, Int, Double>> = (0 until bins).filter { n[it] > 0 }.map {
        Triple(lo + (hi - lo) * it / bins, n[it].toInt(), hit[it] / n[it])
    }

    /** Geri alma için tam kopya (2 × bins sayı). */
    class Snap(val hit: DoubleArray, val n: DoubleArray)
    fun snapshot() = Snap(hit.copyOf(), n.copyOf())
    fun restore(s: Snap) { s.hit.copyInto(hit); s.n.copyInto(n) }
}

data class Verdict(
    val probs: DoubleArray,
    val primary: Int,           // 0..3
    val secondary: Int?,        // çift tahminde ikinci
    val confidence: Double,     // kalibre edilmiş
    val oddProb: Double,
    val bigProb: Double,
    val sideConfidence: Double,
    val weightK: Double,
    val weightP: Double
) {
    val sideOdd get() = oddProb >= 0.5
    val sideBig get() = bigProb >= 0.5
    val sideLabel get() = (if (sideOdd) "T" else "Ç") + "•" + (if (sideBig) "B" else "K")
    val label get() = if (secondary == null) "${primary + 1}" else "${primary + 1}/${secondary + 1}"
}

/**
 * Baş Hakem: iki meclisi Fixed-Share ile tartar, istenirse stacking (öğrenilmiş
 * softmax birleştirici) uygular, konformal kuralla tek/çift kararını verir ve güveni kalibre eder.
 */
class Referee(private var cfg: EngineConfig) {
    private val hedge = FixedShareHedge(2, eta = 0.5, alpha = if (cfg.fixedShare) 0.03 else 0.0)
    private val W = Array(K) { k -> DoubleArray(2 * K + 1).also { it[k] = 0.5; it[K + k] = 0.5 } }
    private val lr = 0.02
    private val scoreCap = 300
    private val scores = ArrayDeque<Double>()
    val calSingle = Calibrator(0.25, 0.75)
    val calPair = Calibrator(0.45, 0.95)
    val calSide = Calibrator(0.5, 0.9, 8)
    private var lastFeat: DoubleArray? = null
    private var lastStack: DoubleArray? = null
    private var lastPreds: List<DoubleArray>? = null
    private var lastV: Verdict? = null

    /** Geri alma kaydı: hakemin ADIM ÖNCESİ durumu (tümü küçük diziler). */
    class Tok(
        val hedge: DoubleArray, val w: Array<DoubleArray>,
        val scoresSize: Int, val evicted: Double?,
        val cs: Calibrator.Snap, val cp: Calibrator.Snap, val csd: Calibrator.Snap,
        val lastFeat: DoubleArray?, val lastStack: DoubleArray?,
        val lastPreds: List<DoubleArray>?, val lastV: Verdict?
    )

    private var tok: Tok? = null
    fun undoToken(): Any? = tok

    /** Hakemi bir adım geri alır (kayıt yoksa false). */
    fun undo(token: Any?): Boolean {
        val t = token as? Tok ?: return false
        hedge.restore(t.hedge)
        for (k in 0 until K) t.w[k].copyInto(W[k])
        while (scores.size > t.scoresSize) scores.removeLast()
        t.evicted?.let { scores.addFirst(it) }
        calSingle.restore(t.cs); calPair.restore(t.cp); calSide.restore(t.csd)
        lastFeat = t.lastFeat; lastStack = t.lastStack; lastPreds = t.lastPreds; lastV = t.lastV
        return true
    }

    private fun feats(pk: DoubleArray, pp: DoubleArray) =
        DoubleArray(2 * K + 1) { i -> when { i < K -> ln(max(pk[i], 1e-6)); i < 2 * K -> ln(max(pp[i - K], 1e-6)); else -> 1.0 } }

    fun decide(pk: DoubleArray, ppIn: DoubleArray?): Verdict {
        val pp = ppIn ?: pk
        val preds = listOf(pk, pp)
        val active = booleanArrayOf(true, ppIn != null)
        val mix = hedge.mix(preds, active)
        var final = mix
        if (cfg.stacking && ppIn != null) {
            val f = feats(pk, pp)
            val st = P.softmax(DoubleArray(K) { k -> var s = 0.0; for (j in f.indices) s += W[k][j] * f[j]; s })
            lastFeat = f; lastStack = st
            final = P.normalize(DoubleArray(K) { 0.5 * st[it] + 0.5 * mix[it] })
        } else { lastFeat = null; lastStack = null }
        lastPreds = preds

        val o = P.order(final)
        // Konformal: uyumsuzluk skoru s = 1 - p[gerçek]; %50 kapsama eşiği
        val single = when (cfg.pairMode) {
            1 -> false; 2 -> true
            else -> {
                if (scores.size < 30) false else {
                    val q = scores.sorted()[(scores.size * 0.5).toInt().coerceAtMost(scores.size - 1)]
                    (0 until K).count { 1 - final[it] <= q } <= 1
                }
            }
        }
        val raw = if (single) final[o[0]] else final[o[0]] + final[o[1]]
        val conf = if (!cfg.calibration) raw else if (single) calSingle.calibrate(raw) else calPair.calibrate(raw)
        val odd = P.oddProb(final); val big = P.bigProb(final)
        val sideRaw = (max(odd, 1 - odd) + max(big, 1 - big)) / 2
        val sideConf = if (cfg.calibration) calSide.calibrate(sideRaw) else sideRaw
        val tw = hedge.w[0] + if (ppIn != null) hedge.w[1] else 0.0
        val v = Verdict(final, o[0], if (single) null else o[1], conf, odd, big, sideConf,
            hedge.w[0] / tw, if (ppIn != null) hedge.w[1] / tw else 0.0)
        lastV = v
        return v
    }

    fun update(actual: Int) {
        // Adım öncesi durum: geri alma için (aşağıdaki erken dönüşte de geçerli).
        tok = Tok(hedge.snapshot(), Array(K) { k -> W[k].copyOf() }, scores.size,
            if (scores.size >= scoreCap) scores.first() else null,
            calSingle.snapshot(), calPair.snapshot(), calSide.snapshot(),
            lastFeat, lastStack, lastPreds, lastV)
        val v = lastV ?: return
        lastPreds?.let { hedge.update(it, actual) }
        val f = lastFeat; val st = lastStack
        if (f != null && st != null) for (k in 0 until K) {
            val g = st[k] - if (k == actual) 1.0 else 0.0
            for (j in f.indices) W[k][j] -= lr * g * f[j]
        }
        scores.addLast(1 - v.probs[actual]); if (scores.size > scoreCap) scores.removeFirst()
        val o = P.order(v.probs)
        calSingle.add(v.probs[o[0]], o[0] == actual)
        calPair.add(v.probs[o[0]] + v.probs[o[1]], o[0] == actual || o[1] == actual)
        val sideRaw = (max(v.oddProb, 1 - v.oddProb) + max(v.bigProb, 1 - v.bigProb)) / 2
        // Yan güveni = iki bileşenin ortalama isabeti
        calSide.add(sideRaw, (actual % 2 == 0) == v.sideOdd)
        calSide.add(sideRaw, (actual >= 2) == v.sideBig)
    }
}
