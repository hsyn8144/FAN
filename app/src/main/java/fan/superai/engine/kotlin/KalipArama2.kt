package fan.superai.engine.kotlin

import fan.superai.engine.History
import fan.superai.engine.K
import fan.superai.engine.Member
import fan.superai.engine.P
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

/**
 * Kalıp Arama 2.0
 * - Tüm geçmiş, hash indeksiyle anında aranır (tarama yok).
 * - 1..maxLen arası tüm uzunluklar aynı anda.
 * - 6 görünüm: Ham, Delta, Şekil (sayıdan bağımsız), Tek/Çift, Küçük/Büyük, Ayna.
 * - Her (görünüm, uzunluk) çifti bir alt-uzmandır; Fixed-Share ile yarışır → kalıp değişince
 *   lider otomatik değişir.
 */
class KalipArama2(private val maxLen: Int = 30) : Member {
    override val id = "kalip2"
    override val name = "Kalıp Arama 2.0"

    companion object {
        val VIEW_NAMES = arrayOf("Ham", "Delta", "Şekil", "T/Ç", "K/B", "Ayna")
        const val V = 6
    }

    // Anahtar → sonuç sayaçları (4'lük)
    private val table = HashMap<Long, IntArray>(1 shl 16)
    private val nSub = V * maxLen
    private val w = DoubleArray(nSub) { 1.0 }
    private val alpha = 0.01
    private val eta = 0.5

    // Son tahmindeki alt-uzman dağılımları (sonuç uzayında 0..3)
    private var lastSub = arrayOfNulls<DoubleArray>(nSub)
    private var lastCount = IntArray(nSub)
    private var lastSize = -1

    // Aktif kalıp bilgisi
    private var leader = -1
    private var leaderSince = 0
    private var step = 0

    // ---- geri alma ----
    override val undoable = true
    private class Tok(val w: DoubleArray, val step: Int, val leader: Int, val leaderSince: Int,
                      val lastSize: Int, val lastSub: Array<DoubleArray?>, val lastCount: IntArray)
    private var tok: Tok? = null
    override fun undoToken(): Any? = tok

    override fun undo(token: Any?, h: History) {
        val t = token as? Tok ?: return
        t.w.copyInto(w)
        step = t.step; leader = t.leader; leaderSince = t.leaderSince; lastSize = t.lastSize
        t.lastSub.copyInto(lastSub)
        t.lastCount.copyInto(lastCount)
        // Bu adımın indekse yazdığı sayaçları geri al: aynı anahtarlar yeniden hesaplanır,
        // yalnızca bu adımda oluşmuş (sayacı sıfırlanan) anahtarlar silinir.
        val end = h.size - 1
        for (view in 0 until V) for (len in 1..maxLen) {
            if (end - len < 0) break
            if (view == 1 && end - len < 1) break
            val kk = key(view, len, h, end)
            val code = outcomeCode(view, h, len)
            if (code >= K) continue
            val c = table[kk] ?: continue
            if (c[code] > 0) c[code]--
            if (c.sum() == 0) table.remove(kk)
        }
    }

    private fun seq(h: History, view: Int, i: Int): Int {
        val v = h[i]
        return when (view) {
            0 -> v
            1 -> if (i == 0) 0 else (v - h[i - 1] + K) % K
            3 -> v % 2 // 0,2 → 1 ve 3 (tek) = 0 ; 1,3 → çift =1
            4 -> if (v >= 2) 1 else 0
            5 -> 3 - v // ayna (1↔4, 2↔3)
            else -> v
        }
    }

    private fun key(view: Int, len: Int, h: History, end: Int): Long {
        // end: bağlamın bittiği indeks (dahil değil)
        var x = 1469598103934665603L xor (view.toLong() * 1000003L) xor (len.toLong() shl 40)
        if (view == 2) {
            // Şekil: ilk görülme sırasına göre yeniden etiketle
            val map = IntArray(K) { -1 }; var nxt = 0
            for (j in end - len until end) {
                val v = h[j]
                if (map[v] == -1) map[v] = nxt++
                x = (x xor map[v].toLong()) * 1099511628211L
            }
        } else {
            for (j in end - len until end) x = (x xor seq(h, view, j).toLong()) * 1099511628211L
        }
        return x
    }

    /** Sonuç kodunu gerçek değere çevir (tahmin için). */
    private fun outcomeToDist(view: Int, c: IntArray, h: History, len: Int): DoubleArray? {
        val n = c.sum()
        if (n == 0) return null
        val p = DoubleArray(K)
        val last = h.last()
        when (view) {
            0 -> for (k in 0 until K) p[k] = c[k].toDouble()
            1 -> for (d in 0 until K) p[(last + d) % K] += c[d].toDouble()
            5 -> for (k in 0 until K) p[3 - k] += c[k].toDouble() // ayna sonucu geri çevir
            3 -> { // 0 → tek (0,2), 1 → çift (1,3)
                p[0] += c[0] / 2.0; p[2] += c[0] / 2.0; p[1] += c[1] / 2.0; p[3] += c[1] / 2.0
            }
            4 -> { p[0] += c[0] / 2.0; p[1] += c[0] / 2.0; p[2] += c[1] / 2.0; p[3] += c[1] / 2.0 }
            2 -> {
                val map = IntArray(K) { -1 }; var nxt = 0
                val inv = IntArray(K) { -1 }
                for (j in h.size - len until h.size) {
                    val v = h[j]; if (map[v] == -1) { map[v] = nxt; inv[nxt] = v; nxt++ }
                }
                val free = (0 until K).filter { map[it] == -1 }
                for (lab in 0 until K) {
                    if (c[lab] == 0) continue
                    if (lab < nxt) p[inv[lab]] += c[lab].toDouble()
                    else if (free.isNotEmpty()) for (f in free) p[f] += c[lab].toDouble() / free.size
                }
            }
        }
        // KT tahmincisi
        val tot = p.sum()
        for (k in 0 until K) p[k] = (p[k] + 0.5) / (tot + 2.0)
        return P.normalize(p)
    }

    private fun outcomeCode(view: Int, h: History, len: Int): Int {
        val i = h.size - 1
        if (view == 2) {
            val map = IntArray(K) { -1 }; var nxt = 0
            for (j in i - len until i) { val v = h[j]; if (map[v] == -1) map[v] = nxt++ }
            return if (map[h[i]] == -1) nxt else map[h[i]]
        }
        return seq(h, view, i)
    }

    override fun predict(h: History): DoubleArray {
        lastSize = h.size
        val out = DoubleArray(K)
        var tw = 0.0
        for (view in 0 until V) for (len in 1..maxLen) {
            val s = view * maxLen + (len - 1)
            lastSub[s] = null; lastCount[s] = 0
            if (h.size < len + 1) continue
            val c = table[key(view, len, h, h.size)] ?: continue
            val d = outcomeToDist(view, c, h, len) ?: continue
            lastSub[s] = d; lastCount[s] = c.sum()
            tw += w[s]
            for (k in 0 until K) out[k] += w[s] * d[k]
        }
        if (tw == 0.0) return P.uniform()
        for (k in 0 until K) out[k] /= tw
        // lider
        var best = -1; var bw = -1.0
        for (s in 0 until nSub) if (lastSub[s] != null && w[s] > bw) { bw = w[s]; best = s }
        if (best != -1 && (leader == -1 || best / maxLen != leader / maxLen)) { leaderSince = step }
        if (best != -1) leader = best
        return P.normalize(out)
    }

    override fun update(h: History) {
        // Geri alma kaydı: adım öncesi durum (indeks tablosu kopyalanmaz, undo'da
        // bu adımın eklediği sayaçlar geri alınır).
        tok = Tok(w.copyOf(), step, leader, leaderSince, lastSize, lastSub.copyOf(), lastCount.copyOf())
        step++
        val a = h.last()
        // Alt-uzman ağırlıkları
        if (lastSize == h.size - 1) {
            var tot = 0.0
            for (s in 0 until nSub) {
                val d = lastSub[s] ?: continue
                w[s] *= exp(-eta * -ln(max(d[a], 1e-9)))
            }
            for (s in 0 until nSub) tot += w[s]
            val m = tot / nSub
            for (s in 0 until nSub) w[s] = (1 - alpha) * (w[s] / m) + alpha
        }
        // İndeksi güncelle: bağlam h[..size-1), sonuç h[size-1]
        val end = h.size - 1
        for (view in 0 until V) for (len in 1..maxLen) {
            if (end - len < 0) break
            if (view == 1 && end - len < 1) break
            val kk = key(view, len, h, end)
            val code = outcomeCode(view, h, len)
            if (code >= K) continue
            table.getOrPut(kk) { IntArray(K) }[code]++
        }
    }

    override fun info(): Map<String, String> {
        if (leader < 0) return mapOf("view" to "-", "len" to "-", "matches" to "0", "since" to "-")
        return mapOf(
            "view" to VIEW_NAMES[leader / maxLen],
            "len" to "${leader % maxLen + 1}",
            "matches" to "${lastCount[leader]}",
            "since" to "${step - leaderSince}"
        )
    }
}
