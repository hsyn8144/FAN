package fan.superai.engine.kotlin

import fan.superai.engine.History
import fan.superai.engine.K
import fan.superai.engine.Member
import fan.superai.engine.P
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.math.tanh
import kotlin.random.Random

private fun sig(x: Double) = 1.0 / (1.0 + exp(-x))

/** Giriş vektörü: sayı one-hot (4) + tek/çift + küçük/büyük + sabit. */
private const val IN = 7
private fun input(v: Int): DoubleArray {
    val x = DoubleArray(IN); x[v] = 1.0
    x[4] = if (v % 2 == 0) 1.0 else -1.0
    x[5] = if (v >= 2) 1.0 else -1.0
    x[6] = 1.0
    return x
}

/**
 * GRU — küçük kapılı tekrarlayan ağ, son T adım üzerinde tam geri yayılım (BPTT) ile
 * her yeni sayıda çevrimiçi eğitilir.
 */
class GruMember(private val H: Int = 16, private val T: Int = 10, private val lr: Double = 0.05, seed: Int = 7) : Member {
    override val id = "gru"
    override val name = "GRU"
    private val rnd = Random(seed)
    private fun mat(r: Int, c: Int, s: Double) = Array(r) { DoubleArray(c) { (rnd.nextDouble() * 2 - 1) * s } }
    private val sx = 1.0 / sqrt(IN.toDouble()); private val sh = 1.0 / sqrt(H.toDouble())
    private val Wz = mat(H, IN, sx); private val Uz = mat(H, H, sh)
    private val Wr = mat(H, IN, sx); private val Ur = mat(H, H, sh)
    private val Wn = mat(H, IN, sx); private val Un = mat(H, H, sh)
    private val Wy = mat(K, H, sh); private val by = DoubleArray(K)

    private class Step(val x: DoubleArray, val hp: DoubleArray, val z: DoubleArray, val r: DoubleArray, val n: DoubleArray, val h: DoubleArray)

    private fun cell(x: DoubleArray, hp: DoubleArray): Step {
        val z = DoubleArray(H); val r = DoubleArray(H); val n = DoubleArray(H); val h = DoubleArray(H)
        for (i in 0 until H) {
            var az = 0.0; var ar = 0.0
            for (j in 0 until IN) { az += Wz[i][j] * x[j]; ar += Wr[i][j] * x[j] }
            for (j in 0 until H) { az += Uz[i][j] * hp[j]; ar += Ur[i][j] * hp[j] }
            z[i] = sig(az); r[i] = sig(ar)
        }
        for (i in 0 until H) {
            var an = 0.0
            for (j in 0 until IN) an += Wn[i][j] * x[j]
            for (j in 0 until H) an += Un[i][j] * (r[j] * hp[j])
            n[i] = tanh(an)
            h[i] = (1 - z[i]) * n[i] + z[i] * hp[i]
        }
        return Step(x, hp, z, r, n, h)
    }

    private fun out(h: DoubleArray) = P.softmax(DoubleArray(K) { k -> var s = by[k]; for (j in 0 until H) s += Wy[k][j] * h[j]; s })

    private fun forward(h: History, end: Int): List<Step> {
        val start = maxOf(0, end - T)
        var hp = DoubleArray(H)
        val steps = ArrayList<Step>()
        for (i in start until end) { val s = cell(input(h[i]), hp); steps.add(s); hp = s.h }
        return steps
    }

    override fun predict(h: History): DoubleArray {
        if (h.size < 2) return P.uniform()
        return P.normalize(out(forward(h, h.size).last().h))
    }

    // ---- geri alma: tüm ağırlıkların kopyası (~9 KB) → BPTT adımı tam olarak geri alınır ----
    override val undoable = true
    private class Tok(val mats: List<Array<DoubleArray>>, val by: DoubleArray)
    private var tok: Tok? = null
    override fun undoToken(): Any? = tok
    private fun mats() = listOf(Wz, Uz, Wr, Ur, Wn, Un, Wy)
    private fun copyMats() = mats().map { m -> Array(m.size) { i -> m[i].copyOf() } }
    private fun restoreMats(src: List<Array<DoubleArray>>) {
        val dst = mats()
        for (i in dst.indices) for (r in dst[i].indices) src[i][r].copyInto(dst[i][r])
    }

    override fun undo(token: Any?, h: History) {
        val t = token as? Tok ?: return
        restoreMats(t.mats)
        t.by.copyInto(by)
    }

    override fun update(h: History) {
        tok = Tok(copyMats(), by.copyOf())
        val end = h.size - 1
        if (end < 2) return
        val steps = forward(h, end)
        val hT = steps.last().h
        val y = out(hT)
        val dy = DoubleArray(K) { y[it] - if (it == h.last()) 1.0 else 0.0 }
        var dh = DoubleArray(H)
        for (k in 0 until K) { for (j in 0 until H) { dh[j] += Wy[k][j] * dy[k]; Wy[k][j] -= lr * dy[k] * hT[j] }; by[k] -= lr * dy[k] }
        // BPTT (gradyanlar biriktirilir)
        val gWz = Array(H) { DoubleArray(IN) }; val gUz = Array(H) { DoubleArray(H) }
        val gWr = Array(H) { DoubleArray(IN) }; val gUr = Array(H) { DoubleArray(H) }
        val gWn = Array(H) { DoubleArray(IN) }; val gUn = Array(H) { DoubleArray(H) }
        for (t in steps.indices.reversed()) {
            val s = steps[t]
            val dhp = DoubleArray(H)
            val dan = DoubleArray(H); val daz = DoubleArray(H); val dar = DoubleArray(H)
            for (i in 0 until H) {
                val dn = dh[i] * (1 - s.z[i])
                val dz = dh[i] * (s.hp[i] - s.n[i])
                dhp[i] += dh[i] * s.z[i]
                dan[i] = dn * (1 - s.n[i] * s.n[i])
                daz[i] = dz * s.z[i] * (1 - s.z[i])
            }
            val drh = DoubleArray(H)
            for (j in 0 until H) { var acc = 0.0; for (i in 0 until H) acc += Un[i][j] * dan[i]; drh[j] = acc }
            for (j in 0 until H) { dar[j] = drh[j] * s.hp[j] * s.r[j] * (1 - s.r[j]); dhp[j] += drh[j] * s.r[j] }
            for (i in 0 until H) {
                for (j in 0 until IN) { gWn[i][j] += dan[i] * s.x[j]; gWz[i][j] += daz[i] * s.x[j]; gWr[i][j] += dar[i] * s.x[j] }
                for (j in 0 until H) {
                    gUn[i][j] += dan[i] * s.r[j] * s.hp[j]; gUz[i][j] += daz[i] * s.hp[j]; gUr[i][j] += dar[i] * s.hp[j]
                    dhp[j] += Uz[i][j] * daz[i] + Ur[i][j] * dar[i]
                }
            }
            dh = dhp
        }
        fun apply(W: Array<DoubleArray>, g: Array<DoubleArray>) {
            for (i in W.indices) for (j in W[i].indices) { val gg = g[i][j].coerceIn(-1.0, 1.0); W[i][j] -= lr * gg }
        }
        apply(Wz, gWz); apply(Uz, gUz); apply(Wr, gWr); apply(Ur, gUr); apply(Wn, gWn); apply(Un, gUn)
    }
}

/** Echo State Network — sabit rastgele rezervuar (60 nöron), çıkış katmanı çevrimiçi öğrenir. */
class EsnMember(private val N: Int = 60, private val lr: Double = 0.03, seed: Int = 11) : Member {
    override val id = "esn"
    override val name = "ESN"
    private val rnd = Random(seed)
    private val Win = Array(N) { DoubleArray(IN) { (rnd.nextDouble() * 2 - 1) * 0.5 } }
    private val W = Array(N) { DoubleArray(N) { if (rnd.nextDouble() < 0.1) rnd.nextDouble() * 2 - 1 else 0.0 } }
    private val Wo = Array(K) { DoubleArray(N + 1) }
    private var state = DoubleArray(N)
    private var stateSize = 0

    init {
        // Spektral yarıçapı ~0.9'a ölçekle (güç yinelemesi)
        var v = DoubleArray(N) { 1.0 }
        var lam = 1.0
        repeat(50) {
            val nv = DoubleArray(N) { i -> var s = 0.0; for (j in 0 until N) s += W[i][j] * v[j]; s }
            lam = sqrt(nv.sumOf { it * it }) / sqrt(v.sumOf { it * it }).coerceAtLeast(1e-9)
            v = nv.map { it / (nv.maxOf { x -> abs(x) }.coerceAtLeast(1e-9)) }.toDoubleArray()
        }
        val sc = 0.9 / lam.coerceAtLeast(1e-6)
        for (i in 0 until N) for (j in 0 until N) W[i][j] *= sc
    }

    private fun advance(v: Int) {
        val x = input(v)
        val ns = DoubleArray(N)
        for (i in 0 until N) {
            var s = 0.0
            for (j in 0 until IN) s += Win[i][j] * x[j]
            for (j in 0 until N) if (W[i][j] != 0.0) s += W[i][j] * state[j]
            ns[i] = 0.7 * state[i] + 0.3 * tanh(s)
        }
        state = ns
    }

    private fun read(): DoubleArray = P.softmax(DoubleArray(K) { k -> var s = Wo[k][N]; for (j in 0 until N) s += Wo[k][j] * state[j]; s })

    override fun predict(h: History): DoubleArray {
        if (stateSize != h.size) { state = DoubleArray(N); for (i in maxOf(0, h.size - 40) until h.size) advance(h[i]); stateSize = h.size }
        return P.normalize(read())
    }

    // ---- geri alma: çıkış katmanı + rezervuar durumu (Win/W sabittir, kopyalanmaz) ----
    override val undoable = true
    private class Tok(val wo: Array<DoubleArray>, val state: DoubleArray, val stateSize: Int)
    private var tok: Tok? = null
    override fun undoToken(): Any? = tok

    override fun undo(token: Any?, h: History) {
        val t = token as? Tok ?: return
        for (k in Wo.indices) t.wo[k].copyInto(Wo[k])
        if (t.state.size == state.size) t.state.copyInto(state) else state = t.state.copyOf()
        stateSize = t.stateSize
    }

    override fun update(h: History) {
        tok = Tok(Array(K) { k -> Wo[k].copyOf() }, state.copyOf(), stateSize)
        val end = h.size - 1
        if (stateSize != end) { state = DoubleArray(N); for (i in maxOf(0, end - 40) until end) advance(h[i]) }
        val y = read()
        for (k in 0 until K) {
            val g = y[k] - if (k == h.last()) 1.0 else 0.0
            for (j in 0 until N) Wo[k][j] -= lr * g * state[j]
            Wo[k][N] -= lr * g
        }
        advance(h.last()); stateSize = h.size
    }
}
