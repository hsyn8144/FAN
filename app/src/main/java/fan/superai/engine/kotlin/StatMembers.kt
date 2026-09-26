package fan.superai.engine.kotlin

import fan.superai.engine.History
import fan.superai.engine.K
import fan.superai.engine.Member
import fan.superai.engine.P
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

private fun lse(a: Double, b: Double): Double {
    val m = max(a, b); return m + ln(exp(a - m) + exp(b - m))
}
private val LN_HALF = ln(0.5)

/**
 * CTW (Context Tree Weighting) — 4'lü alfabe, derinlik D.
 * Tüm bağlam uzunluklarını aynı anda Bayesçi olarak tartar; sonlu Markov düzenleri için
 * evrensel (asimptotik olarak en iyi) tahmincidir.
 */
class CtwMember(private val depth: Int = 6) : Member {
    override val id = "ctw"
    override val name = "CTW"

    private class Node { val c = IntArray(K); var n = 0; var logPe = 0.0; var logPw = 0.0; var childSum = 0.0 }
    private val nodes = HashMap<Long, Node>(1 shl 14)
    private val root = Node()

    private fun ctxSym(h: History, end: Int, j: Int): Int { val i = end - 1 - j; return if (i >= 0) h[i] else 0 }
    private fun keyOf(path: IntArray, d: Int): Long {
        var x = d.toLong() + 7
        for (j in 0 until d) x = x * 5 + path[j] + 1
        return x
    }
    private fun pathOf(h: History, end: Int) = IntArray(depth) { ctxSym(h, end, it) }
    private fun nodesOf(path: IntArray, create: Boolean): Array<Node?> = Array(depth + 1) { d ->
        if (d == 0) root else {
            val k = keyOf(path, d)
            if (create) nodes.getOrPut(k) { Node() } else nodes[k]
        }
    }
    private fun pathNodes(h: History, end: Int, create: Boolean): Array<Node?> = nodesOf(pathOf(h, end), create)

    // ---- geri alma: ağaç kopyalanmaz, yalnızca bu adımda değişen düğümler saklanır ----
    override val undoable = true
    private class Tok(val s: Int, val nodes: Array<Node?>, val keys: LongArray, val pe: DoubleArray,
                      val pw: DoubleArray, val cs: DoubleArray, val cnt: IntArray, val n: IntArray)
    private var tok: Tok? = null
    override fun undoToken(): Any? = tok

    override fun undo(token: Any?, h: History) {
        val t = token as? Tok ?: return
        for (d in 0..depth) {
            val nd = t.nodes[d] ?: continue
            nd.logPe = t.pe[d]; nd.logPw = t.pw[d]; nd.childSum = t.cs[d]
            nd.c[t.s] = t.cnt[d]; nd.n = t.n[d]
            // n == 0 ise düğüm bu adımda oluşturulmuştu → indeksten sil
            if (d > 0 && t.n[d] == 0) nodes.remove(t.keys[d])
        }
    }

    override fun predict(h: History): DoubleArray {
        val ns = pathNodes(h, h.size, false)
        val base = root.logPw
        val out = DoubleArray(K)
        for (s in 0 until K) {
            var childOld = 0.0; var childNew = 0.0
            var pw = 0.0
            for (d in depth downTo 0) {
                val nd = ns[d]
                val c = nd?.c?.get(s) ?: 0; val n = nd?.n ?: 0
                val pe = (nd?.logPe ?: 0.0) + ln((c + 0.5) / (n + 2.0))
                val oldPw = nd?.logPw ?: 0.0
                pw = if (d == depth) pe else {
                    val cs = (nd?.childSum ?: 0.0) - childOld + childNew
                    lse(LN_HALF + pe, LN_HALF + cs)
                }
                childOld = oldPw; childNew = pw
            }
            out[s] = exp(pw - base)
        }
        return P.normalize(out)
    }

    override fun update(h: History) {
        val end = h.size - 1
        val s = h.last()
        val path = pathOf(h, end)
        val ns = nodesOf(path, true)
        // adım öncesi düğüm değerleri (geri alma için)
        val t = Tok(s, ns, LongArray(depth + 1) { d -> if (d == 0) 0L else keyOf(path, d) },
            DoubleArray(depth + 1), DoubleArray(depth + 1), DoubleArray(depth + 1),
            IntArray(depth + 1), IntArray(depth + 1))
        for (d in 0..depth) {
            val nd = ns[d] ?: continue
            t.pe[d] = nd.logPe; t.pw[d] = nd.logPw; t.cs[d] = nd.childSum
            t.cnt[d] = nd.c[s]; t.n[d] = nd.n
        }
        tok = t
        var childOld = 0.0; var childNew = 0.0
        for (d in depth downTo 0) {
            val nd = ns[d]!!
            nd.logPe += ln((nd.c[s] + 0.5) / (nd.n + 2.0))
            nd.c[s]++; nd.n++
            val oldPw = nd.logPw
            if (d == depth) nd.logPw = nd.logPe else {
                nd.childSum += childNew - childOld
                nd.logPw = lse(LN_HALF + nd.logPe, LN_HALF + nd.childSum)
            }
            childOld = oldPw; childNew = nd.logPw
        }
    }
}

/** PPM-C (dışlamalı) — maksimum derece 5. */
class PpmMember(private val maxOrder: Int = 5) : Member {
    override val id = "ppm"
    override val name = "PPM"
    private val table = HashMap<Long, IntArray>()

    private fun key(h: History, end: Int, o: Int): Long {
        var x = o.toLong() + 11
        for (j in end - o until end) x = x * 5 + h[j] + 1
        return x
    }

    override fun predict(h: History): DoubleArray {
        val p = DoubleArray(K)
        val excl = BooleanArray(K)
        var mass = 1.0
        for (o in min(maxOrder, h.size) downTo 0) {
            val c = table[key(h, h.size, o)] ?: continue
            var n = 0; var q = 0
            for (k in 0 until K) if (!excl[k] && c[k] > 0) { n += c[k]; q++ }
            if (n == 0) continue
            for (k in 0 until K) if (!excl[k] && c[k] > 0) p[k] += mass * c[k] / (n + q).toDouble()
            mass *= q / (n + q).toDouble()
            for (k in 0 until K) if (c[k] > 0) excl[k] = true
        }
        val rest = (0 until K).count { !excl[it] }
        if (rest > 0) for (k in 0 until K) if (!excl[k]) p[k] += mass / rest
        else { val s = p.sum(); for (k in 0 until K) p[k] /= s }
        return P.normalize(p)
    }

    override fun update(h: History) {
        val end = h.size - 1
        val s = h.last()
        val m = min(maxOrder, end)
        // geri alma kaydı: bu adımda dokunulan anahtarlar ve sayaçların adım öncesi değeri
        val keys = LongArray(m + 1); val pre = IntArray(m + 1); val created = BooleanArray(m + 1)
        for (o in 0..m) {
            val k = key(h, end, o)
            keys[o] = k
            var c = table[k]
            if (c == null) { c = IntArray(K); table[k] = c; created[o] = true }
            pre[o] = c[s]
            c[s]++
        }
        tok = Tok(keys, pre, created, s)
    }

    // ---- geri alma: tablo kopyalanmaz, dokunulan hücreler eski değerine döner ----
    override val undoable = true
    private class Tok(val keys: LongArray, val pre: IntArray, val created: BooleanArray, val s: Int)
    private var tok: Tok? = null
    override fun undoToken(): Any? = tok

    override fun undo(token: Any?, h: History) {
        val t = token as? Tok ?: return
        for (o in t.keys.indices) {
            val k = t.keys[o]
            if (t.created[o]) { table.remove(k); continue }   // bu adımda oluşmuş anahtar
            val c = table[k] ?: continue
            c[t.s] = t.pre[o]
        }
    }
}

/**
 * Frekans / Aralık: "Bir sayı kaç turdur gelmedi" (hazard) ile unutan frekansı birleştirir.
 * Tablolar veriden öğrenilir; "gecikti gelir" etkisi varsa yakalar, yoksa tarafsız kalır.
 */
class FreqGapMember : Member {
    override val id = "freqgap"
    override val name = "Frekans / Aralık"
    private val bucketsOf = intArrayOf(0, 1, 2, 3, 4, 4, 5, 5, 5, 6, 6, 6, 6)
    private fun bucket(g: Int) = if (g < bucketsOf.size) bucketsOf[g] else 7
    private val hits = DoubleArray(8); private val trials = DoubleArray(8)
    private val freq = DoubleArray(K) { 1.0 }
    private val lastSeen = IntArray(K) { -1 }
    private val lambda = 0.97

    private fun gap(v: Int, size: Int) = if (lastSeen[v] < 0) 99 else size - 1 - lastSeen[v]

    override fun predict(h: History): DoubleArray {
        val fs = freq.sum()
        return P.normalize(DoubleArray(K) { v ->
            val b = bucket(gap(v, h.size))
            val hz = (hits[b] + 1.0) / (trials[b] + 4.0) * 4.0
            val fr = (freq[v] / fs) * 4.0
            hz * Math.pow(fr, 0.5)
        })
    }

    override fun update(h: History) {
        tok = Tok(hits.copyOf(), trials.copyOf(), freq.copyOf(), lastSeen.copyOf())
        val a = h.last(); val size = h.size - 1
        for (v in 0 until K) {
            val b = bucket(gap(v, size))
            trials[b] += 1.0; if (v == a) hits[b] += 1.0
        }
        for (v in 0 until K) freq[v] = freq[v] * lambda + if (v == a) 1.0 else 0.0
        lastSeen[a] = size
    }

    // ---- geri alma: tüm durum küçük diziler, tam kopya yeterli ----
    override val undoable = true
    private class Tok(val hits: DoubleArray, val trials: DoubleArray, val freq: DoubleArray, val lastSeen: IntArray)
    private var tok: Tok? = null
    override fun undoToken(): Any? = tok

    override fun undo(token: Any?, h: History) {
        val t = token as? Tok ?: return
        t.hits.copyInto(hits); t.trials.copyInto(trials); t.freq.copyInto(freq); t.lastSeen.copyInto(lastSeen)
    }
}

/** Seri / Dalga: aynı sayı, tek/çift ve küçük/büyük serilerinin devam/kırılma olasılığını öğrenir. */
class StreakWaveMember : Member {
    override val id = "streak"
    override val name = "Seri / Dalga"
    private fun par(v: Int) = v % 2
    private fun big(v: Int) = if (v >= 2) 1 else 0
    private val rep = Array(6) { doubleArrayOf(1.0, 4.0) }       // [hit, total]
    private val parC = Array(36) { doubleArrayOf(1.0, 2.0) }
    private val bigC = Array(36) { doubleArrayOf(1.0, 2.0) }
    private fun b(x: Int) = min(x, 5)

    private data class St(val run: Int, val pr: Int, val pa: Int, val br: Int, val ba: Int)
    private fun state(h: History, end: Int): St {
        fun runLen(f: (Int) -> Int): Int { var r = 1; var i = end - 2; while (i >= 0 && f(h[i]) == f(h[end - 1])) { r++; i-- }; return r }
        fun altLen(f: (Int) -> Int): Int { var r = 0; var i = end - 1; while (i >= 1 && f(h[i]) != f(h[i - 1])) { r++; i-- }; return r }
        return St(runLen { it }, runLen(::par), altLen(::par), runLen(::big), altLen(::big))
    }

    override fun predict(h: History): DoubleArray {
        if (h.size < 2) return P.uniform()
        val s = state(h, h.size); val last = h.last()
        val r = rep[b(s.run)].let { it[0] / it[1] }
        val pc = parC[b(s.pr) * 6 + b(s.pa)].let { it[0] / it[1] }
        val bc = bigC[b(s.br) * 6 + b(s.ba)].let { it[0] / it[1] }
        return P.normalize(DoubleArray(K) { v ->
            (if (v == last) r else (1 - r) / 3) *
                (if (par(v) == par(last)) pc else 1 - pc) *
                (if (big(v) == big(last)) bc else 1 - bc)
        })
    }

    override fun update(h: History) {
        tok = Tok(copy2(rep), copy2(parC), copy2(bigC))
        val end = h.size - 1
        if (end < 2) return
        val s = state(h, end); val a = h.last(); val last = h[end - 1]
        rep[b(s.run)][1]++; if (a == last) rep[b(s.run)][0]++
        parC[b(s.pr) * 6 + b(s.pa)].let { it[1]++; if (par(a) == par(last)) it[0]++ }
        bigC[b(s.br) * 6 + b(s.ba)].let { it[1]++; if (big(a) == big(last)) it[0]++ }
    }

    // ---- geri alma: üç küçük tablonun tam kopyası ----
    override val undoable = true
    private class Tok(val rep: Array<DoubleArray>, val parC: Array<DoubleArray>, val bigC: Array<DoubleArray>)
    private var tok: Tok? = null
    override fun undoToken(): Any? = tok
    private fun copy2(a: Array<DoubleArray>) = Array(a.size) { i -> a[i].copyOf() }

    override fun undo(token: Any?, h: History) {
        val t = token as? Tok ?: return
        for (i in rep.indices) t.rep[i].copyInto(rep[i])
        for (i in parC.indices) t.parC[i].copyInto(parC[i])
        for (i in bigC.indices) t.bigC[i].copyInto(bigC[i])
    }
}

/**
 * Rejim: son 30 kayıttaki tekrar oranı ve tek/çift alternasyonuna göre 9 rejimden birini seçer,
 * her rejim için ayrı geçiş tablosu tutar.
 */
class RegimeMember(private val win: Int = 30) : Member {
    override val id = "regime"
    override val name = "Rejim"
    private val tables = Array(9) { Array(K) { DoubleArray(K) { 0.5 } } }
    private var current = 0
    private val names = arrayOf("sakin", "dalgalı", "zikzak", "karışık", "dengeli", "hareketli", "tekrarcı", "tekrar+dalga", "tekrar+zikzak")

    private fun regime(h: History, end: Int): Int {
        val s = max(1, end - win)
        if (end - s < 5) return 4
        var rep = 0; var alt = 0; val n = end - s
        for (i in s until end) { if (h[i] == h[i - 1]) rep++; if (h[i] % 2 != h[i - 1] % 2) alt++ }
        val rr = rep.toDouble() / n; val ar = alt.toDouble() / n
        val rb = when { rr < 0.17 -> 0; rr < 0.33 -> 1; else -> 2 }
        val ab = when { ar < 0.4 -> 0; ar < 0.6 -> 1; else -> 2 }
        return rb * 3 + ab
    }

    override fun predict(h: History): DoubleArray {
        if (h.size < 2) return P.uniform()
        current = regime(h, h.size)
        return P.normalize(tables[current][h.last()].copyOf())
    }

    override fun update(h: History) {
        tok = Tok(Array(tables.size) { r -> Array(tables[r].size) { c -> tables[r][c].copyOf() } }, current)
        val end = h.size - 1
        if (end < 1) return
        val r = regime(h, end)
        val row = tables[r][h[end - 1]]
        for (k in 0 until K) row[k] *= 0.995
        row[h.last()] += 1.0
    }

    // ---- geri alma: 9 × 4 × 4 geçiş tablosu + aktif rejim ----
    override val undoable = true
    private class Tok(val tables: Array<Array<DoubleArray>>, val current: Int)
    private var tok: Tok? = null
    override fun undoToken(): Any? = tok

    override fun undo(token: Any?, h: History) {
        val t = token as? Tok ?: return
        for (r in tables.indices) for (c in tables[r].indices) t.tables[r][c].copyInto(tables[r][c])
        current = t.current
    }

    override fun info() = mapOf("regime" to names[current])
}
