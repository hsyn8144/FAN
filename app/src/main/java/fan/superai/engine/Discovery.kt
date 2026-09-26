package fan.superai.engine

import java.util.zip.Deflater
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** İstatistik yardımcıları. */
object Stats {
    private fun lnGamma(x: Double): Double {
        val c = doubleArrayOf(76.18009172947146, -86.50532032941677, 24.01409824083091,
            -1.231739572450155, 0.1208650973866179e-2, -0.5395239384953e-5)
        var y = x; val tmp = x + 5.5 - (x + 0.5) * ln(x + 5.5)
        var ser = 1.000000000190015
        for (cc in c) { y += 1; ser += cc / y }
        return -tmp + ln(2.5066282746310005 * ser / x)
    }
    /** Düzenli üst eksik gama Q(a,x). */
    fun gammaQ(a: Double, x: Double): Double {
        if (x <= 0) return 1.0
        if (x < a + 1) {
            var sum = 1.0 / a; var del = sum; var ap = a
            repeat(300) { ap += 1; del *= x / ap; sum += del; if (abs(del) < abs(sum) * 1e-12) return@repeat }
            return 1 - sum * exp(-x + a * ln(x) - lnGamma(a))
        }
        var b = x + 1 - a; var c = 1e300; var d = 1 / b; var h = d
        for (i in 1..300) {
            val an = -i * (i - a); b += 2
            d = an * d + b; if (abs(d) < 1e-300) d = 1e-300
            c = b + an / c; if (abs(c) < 1e-300) c = 1e-300
            d = 1 / d; val del = d * c; h *= del
            if (abs(del - 1) < 1e-12) break
        }
        return exp(-x + a * ln(x) - lnGamma(a)) * h
    }
    fun chiP(chi: Double, df: Int) = gammaQ(df / 2.0, chi / 2.0)
    fun normP2(z: Double): Double { // iki yönlü
        val t = 1 / (1 + 0.5 * abs(z))
        val y = t * exp(-z * z / 2 - 1.26551223 + t * (1.00002368 + t * (0.37409196 + t * (0.09678418 + t * (-0.18628806 +
            t * (0.27886807 + t * (-1.13520398 + t * (1.48851587 + t * (-0.82215223 + t * 0.17087277)))))))))
        return y.coerceIn(0.0, 1.0)
    }
    fun contingencyP(t: Array<DoubleArray>): Double {
        val r = t.size; val c = t[0].size
        val rs = DoubleArray(r) { t[it].sum() }; val cs = DoubleArray(c) { j -> t.sumOf { it[j] } }
        val n = rs.sum(); if (n == 0.0) return 1.0
        var chi = 0.0; var dfR = 0; var dfC = 0
        for (i in 0 until r) if (rs[i] > 0) dfR++
        for (j in 0 until c) if (cs[j] > 0) dfC++
        for (i in 0 until r) for (j in 0 until c) { val e = rs[i] * cs[j] / n; if (e > 0) chi += (t[i][j] - e).pow(2) / e }
        return chiP(chi, max(1, (dfR - 1) * (dfC - 1)))
    }
}

data class TestRow(val name: String, val result: String, val status: Int) // 0 ok, 1 dikkat, 2 bilgi

data class DiscoveryReport(
    val n: Int,
    val tests: List<TestRow>,
    val extra: List<TestRow>,
    val mutualInfo: DoubleArray,   // gecikme 1..10
    val miThreshold: Double,
    val verdict: String,
    val lag2Watch: Boolean
)

/** 🔍 Keşif Laboratuvarı: verinin gerçekten rastgele olup olmadığını sınar. */
object Discovery {
    fun run(v: IntArray, times: LongArray): DiscoveryReport {
        val n = v.size
        if (n < 60) return DiscoveryReport(n, listOf(TestRow("Yeterli veri yok", "en az 60 kayıt", 2)), emptyList(), DoubleArray(10), 0.0, "Veri toplanıyor…", false)
        val tests = ArrayList<TestRow>(); val extra = ArrayList<TestRow>()
        var flags = 0
        val nTests = 9
        val alpha = 0.05 / nTests // Bonferroni
        fun fmt(p: Double) = "p=" + String.format("%.3f", p)
        fun row(name: String, p: Double, okText: String) {
            val st = if (p < alpha) 1 else if (p < 0.05) 1 else 0
            if (p < alpha) flags++
            tests += TestRow(name, if (st == 0) "✓ $okText · ${fmt(p)}" else "👀 iz · ${fmt(p)}", st)
        }
        // 1) Dağılım
        val cnt = DoubleArray(K); v.forEach { cnt[it]++ }
        val e = n / 4.0
        row("Dağılım (ki-kare)", Stats.chiP(cnt.sumOf { (it - e).pow(2) / e }, 3), "eşit")
        // 2) Gecikme 1..5 bağımlılık
        val lagP = DoubleArray(6)
        for (lag in 1..5) {
            val t = Array(K) { DoubleArray(K) }
            for (i in 0 until n - lag) t[v[i]][v[i + lag]]++
            lagP[lag] = Stats.contingencyP(t)
        }
        row("Gecikme 1", lagP[1], "yok"); row("Gecikme 2", lagP[2], "yok")
        val m35 = minOf(lagP[3], lagP[4], lagP[5]) * 3
        row("Gecikme 3–5", min(1.0, m35), "yok")
        // 3) Sıkıştırma
        fun comp(b: ByteArray): Int { val d = Deflater(9); d.setInput(b); d.finish(); val o = ByteArray(b.size * 2 + 64); val k = d.deflate(o); d.end(); return k }
        val real = comp(ByteArray(n) { v[it].toByte() })
        val rnd = Random(42)
        val sims = (0 until 20).map { comp(ByteArray(n) { rnd.nextInt(4).toByte() }) }
        val mu = sims.average(); val sd = sqrt(sims.sumOf { (it - mu).pow(2) } / 19).coerceAtLeast(1.0)
        val zc = (real - mu) / sd
        val pc = if (zc < 0) Stats.normP2(zc) else 1.0
        row("Sıkıştırma", pc, "rastgele gibi")
        // 4) Seri testleri
        fun runsP(f: (Int) -> Boolean): Double {
            val y = v.map(f); val m = y.count { it }.toDouble()
            val runs = 1 + (0 until n - 1).count { y[it] != y[it + 1] }
            val ex = 2 * m * (n - m) / n + 1
            val vr = (ex - 1) * (ex - 2) / (n - 1)
            return if (vr <= 0) 1.0 else Stats.normP2((runs - ex) / sqrt(vr))
        }
        row("Seri testi T/Ç", runsP { it % 2 == 0 }, "normal")
        row("Seri testi K/B", runsP { it >= 2 }, "normal")
        // 5) Saat etkisi
        val hourT = Array(4) { DoubleArray(K) }
        for (i in 0 until n) { val hr = ((times[i] / 3600) % 24).toInt(); hourT[(hr / 6).coerceIn(0, 3)][v[i]]++ }
        row("Saat etkisi", Stats.contingencyP(hourT), "yok")
        // Permütasyon entropisi (derece 3)
        val pat = HashMap<String, Int>()
        for (i in 0 until n - 2) {
            val w = listOf(v[i] * 10 + 0, v[i + 1] * 10 + 1, v[i + 2] * 10 + 2)
            val key = w.sortedBy { it / 10 * 100 + it % 10 }.joinToString("") { (it % 10).toString() }
            pat[key] = (pat[key] ?: 0) + 1
        }
        val tot = pat.values.sum().toDouble()
        val pe = -pat.values.sumOf { val p = it / tot; p * ln(p) } / ln(6.0)
        extra += TestRow("Permütasyon entropisi", String.format("%.2f / 1.00", pe), 2)
        // Spektral: her değer için gösterge dizisinin periodogramı (Fisher g)
        var bestP = 1.0; var bestPer = 0.0
        val L = min(n, 512); val start = n - L
        for (k in 0 until K) {
            val x = DoubleArray(L) { if (v[start + it] == k) 0.75 else -0.25 }
            val m = L / 2
            val pw = DoubleArray(m) { f ->
                val fr = 2 * Math.PI * (f + 1) / L
                var re = 0.0; var im = 0.0
                for (t in 0 until L) { re += x[t] * cos(fr * t); im += x[t] * sin(fr * t) }
                re * re + im * im
            }
            val g = pw.max() / pw.sum()
            val p = min(1.0, m * (1 - g).pow(m - 1))
            if (p < bestP) { bestP = p; bestPer = L / (pw.indices.maxBy { pw[it] } + 1.0) }
        }
        extra += TestRow("Spektral periyot", if (bestP * 4 < 0.05) "👀 ~${String.format("%.1f", bestPer)} tur · ${fmt(bestP * 4)}" else "anlamlı yok", if (bestP * 4 < 0.05) 1 else 0)
        // Değişim noktası (Bayesçi, Dirichlet-kategorik, basitleştirilmiş BOCPD)
        extra += TestRow("Değişim noktası (BOCPD)", bocpd(v)?.let { "#$it ?" } ?: "yok", 2)
        // "Gecikti gelir" (hazard): en uzun süredir gelmeyen sayının gelme oranı
        var hz = 0; var hzN = 0; val lastSeen = IntArray(K) { -1 }
        for (i in 0 until n) {
            if (i > 20) { val od = (0 until K).minBy { lastSeen[it] }; hzN++; if (v[i] == od) hz++ }
            lastSeen[v[i]] = i
        }
        val hzr = hz.toDouble() / max(1, hzN)
        val hzz = (hzr - 0.25) / sqrt(0.25 * 0.75 / max(1, hzN))
        extra += TestRow("\"Gecikti gelir\" (hazard)", if (abs(hzz) > 2.5) "👀 %${(hzr * 100).toInt()} (şans %25)" else "etki yok · %${(hzr * 100).toInt()}", if (abs(hzz) > 2.5) 1 else 0)
        // Sıcak-soğuk: son 20'de en sık gelen sayının gelme oranı
        var hot = 0; var hotN = 0
        for (i in 20 until n) {
            val c = IntArray(K); for (j in i - 20 until i) c[v[j]]++
            val hs = (0 until K).maxBy { c[it] }; hotN++; if (v[i] == hs) hot++
        }
        val hr = hot.toDouble() / max(1, hotN)
        val hzh = (hr - 0.25) / sqrt(0.25 * 0.75 / max(1, hotN))
        extra += TestRow("Sıcak-soğuk (Hawkes/Pólya)", if (abs(hzh) > 2.5) "👀 %${(hr * 100).toInt()} (şans %25)" else "etki yok · %${(hr * 100).toInt()}", if (abs(hzh) > 2.5) 1 else 0)

        // Karşılıklı bilgi
        val mi = DoubleArray(10)
        for (lag in 1..10) {
            val t = Array(K) { DoubleArray(K) }; var m = 0.0
            for (i in 0 until n - lag) { t[v[i]][v[i + lag]]++; m++ }
            var s = 0.0
            val rs = DoubleArray(K) { t[it].sum() }; val cs = DoubleArray(K) { j -> t.sumOf { it[j] } }
            for (a in 0 until K) for (b in 0 until K) if (t[a][b] > 0) s += t[a][b] / m * ln(t[a][b] * m / (rs[a] * cs[b]))
            mi[lag - 1] = s / ln(2.0)
        }
        // Rastgele veride MI ≈ (K-1)^2/(2 m ln2); %99 eşik için ki-kare(9) ~21.67
        val thr = 21.67 / (2.0 * n * ln(2.0))
        val lag2 = lagP[2] < 0.05
        val strong = flags > 0
        val verdict = when {
            strong -> "Anlamlı bir düzen izi var. İlgili üyeler yarışta öne çıkabilir."
            lag2 -> "Güçlü düzen yok. Gecikme-2 takipte; ilgili üyelerin ağırlığı otomatik ayarlanıyor."
            tests.any { it.status == 1 } -> "Güçlü düzen yok. Zayıf izler takip ediliyor."
            else -> "Veri şu an rastgele görünüyor. Tahmin edilebilir güçlü bir düzen yok."
        }
        return DiscoveryReport(n, tests, extra, mi, thr, verdict, lag2)
    }

    /** Basitleştirilmiş BOCPD: en olası koşu uzunluğunun ani düştüğü son noktayı döndürür. */
    private fun bocpd(v: IntArray, hazard: Double = 1.0 / 200, R: Int = 300): Int? {
        var probs = doubleArrayOf(1.0)
        var counts = arrayOf(DoubleArray(K))
        var lastCp: Int? = null
        var prevMap = 0
        for (t in v.indices) {
            val x = v[t]
            val pred = DoubleArray(probs.size) { r -> (counts[r][x] + 0.5) / (counts[r].sum() + 2.0) }
            val grow = DoubleArray(probs.size) { probs[it] * pred[it] * (1 - hazard) }
            val cp = probs.indices.sumOf { probs[it] * pred[it] * hazard }
            val np = DoubleArray(min(grow.size + 1, R))
            np[0] = cp
            for (i in 0 until np.size - 1) np[i + 1] = grow[i]
            val s = np.sum(); for (i in np.indices) np[i] /= s
            val nc = Array(np.size) { i -> if (i == 0) DoubleArray(K) else counts[i - 1].copyOf().also { it[x]++ } }
            probs = np; counts = nc
            val map = np.indices.maxBy { np[it] }
            if (t > 30 && map + 20 < prevMap) lastCp = t - map
            prevMap = map
        }
        return lastCp
    }
}
