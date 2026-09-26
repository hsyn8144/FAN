package fan.superai.engine

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

/**
 * Tüm motor 0..3 aralığında çalışır (kullanıcı sayısı 1..4 → iç değer 0..3).
 * Olasılık dizileri her zaman 4 elemanlıdır.
 */
const val K = 4

/** Bir kaydın bağlam bilgisi (zaman damgası, epoch saniye). */
class History(
    val values: IntArray,
    val times: LongArray,
    val size: Int
) {
    operator fun get(i: Int) = values[i]
    fun last(back: Int = 1) = values[size - back]
}

/**
 * Meclis üyesi. predict() bir sonraki değer için olasılık, update() gerçek sonucu öğrenir.
 *
 * GERİ ALMA (undo): Bir üye [undoable] = true bildiriyorsa, update() çağrısının hemen
 * ardından [undoToken] ile o adımın ÖNCESİNE ait küçük bir kayıt döndürür; [undo] bu
 * kayıtla üyeyi bir adım geriye alır. Kayıtlar yalnızca küçük diziler tutar (ağırlıklar,
 * sayaçlar); büyük tablolar (kalıp indeksi, CTW ağacı, PPM sözlüğü) kopyalanmaz, onların
 * yerine adımda değişen hücreler geri alınır. Böylece "Geri al" tüm geçmişi baştan
 * öğrenmek zorunda kalmaz.
 */
interface Member {
    val id: String
    val name: String
    /** h.size kadar geçmiş biliniyorken bir sonraki değer için dağılım. */
    fun predict(h: History): DoubleArray
    /** h.size-1 elemanlık geçmişten sonra h.last() geldi; öğren. */
    fun update(h: History)
    /** Arayüzde gösterilecek ek bilgi (isteğe bağlı). */
    fun info(): Map<String, String> = emptyMap()
    /** Geri alma destekleniyor mu. false ise motor tam yeniden kurmaya düşer. */
    val undoable: Boolean get() = false
    /** Son update() adımını geri almak için gereken kayıt (adım öncesi durum). */
    fun undoToken(): Any? = null
    /** [undoToken] kaydını uygular. h: geri alınan kayıt DAHİL geçmiş (update'e verilen). */
    fun undo(token: Any?, h: History) {}
}

object P {
    fun uniform() = DoubleArray(K) { 1.0 / K }

    fun normalize(p: DoubleArray, floor: Double = 1e-4): DoubleArray {
        var s = 0.0
        for (i in 0 until K) {
            if (p[i].isNaN() || p[i] < floor) p[i] = floor
            s += p[i]
        }
        for (i in 0 until K) p[i] /= s
        return p
    }

    fun order(p: DoubleArray): IntArray = (0 until K).sortedByDescending { p[it] }.toIntArray()

    fun softmax(z: DoubleArray): DoubleArray {
        val m = z.max()
        val e = DoubleArray(z.size) { exp(z[it] - m) }
        val s = e.sum()
        for (i in e.indices) e[i] /= s
        return e
    }

    fun logLoss(p: DoubleArray, a: Int) = -ln(max(p[a], 1e-9))

    /** Tek/çift (1,3 tek) ve küçük/büyük (3,4 büyük) olasılıkları. */
    fun oddProb(p: DoubleArray) = p[0] + p[2]
    fun bigProb(p: DoubleArray) = p[2] + p[3]
}

/** Kayan pencere sayacı. */
class Rolling(private val cap: Int) {
    private val buf = BooleanArray(cap)
    private var n = 0
    private var pos = 0
    private var hits = 0
    fun add(b: Boolean) {
        if (n == cap) { if (buf[pos]) hits-- } else n++
        buf[pos] = b; if (b) hits++
        pos = (pos + 1) % cap
    }
    val count get() = n
    fun rate(): Double = if (n == 0) 0.0 else hits.toDouble() / n

    /** Geri alma için tam durum kopyası (cap bayt + 3 sayı). */
    class Snap(val n: Int, val pos: Int, val hits: Int, val buf: BooleanArray)
    fun snapshot() = Snap(n, pos, hits, buf.copyOf())
    fun restore(s: Snap) { n = s.n; pos = s.pos; hits = s.hits; s.buf.copyInto(buf) }
}

/**
 * Fixed-Share Hedge: log-loss ile ağırlık günceller, her adımda ağırlığın bir kısmını
 * herkese dağıtır. Böylece lider değişince (kalıp değişimi) sistem hızla yeni lidere geçer.
 */
class FixedShareHedge(n: Int, var eta: Double = 0.6, var alpha: Double = 0.02) {
    val w = DoubleArray(n) { 1.0 / n }

    fun mix(preds: List<DoubleArray>, active: BooleanArray): DoubleArray {
        val out = DoubleArray(K)
        var tw = 0.0
        for (i in preds.indices) if (active[i]) {
            tw += w[i]
            for (k in 0 until K) out[k] += w[i] * preds[i][k]
        }
        if (tw <= 0) return P.uniform()
        for (k in 0 until K) out[k] /= tw
        return P.normalize(out)
    }

    fun update(preds: List<DoubleArray>, actual: Int) {
        val n = w.size
        for (i in 0 until n) w[i] *= exp(-eta * P.logLoss(preds[i], actual))
        var s = w.sum()
        if (s <= 0 || s.isNaN()) { w.fill(1.0 / n); s = 1.0 }
        for (i in 0 until n) w[i] /= s
        for (i in 0 until n) w[i] = (1 - alpha) * w[i] + alpha / n
    }

    /** Ortalama 1 olacak şekilde ölçeklenmiş ağırlık (arayüz için). */
    fun scaled(i: Int) = w[i] * w.size

    /** Geri alma: ağırlıkların kopyası. */
    fun snapshot(): DoubleArray = w.copyOf()
    fun restore(s: DoubleArray) { if (s.size == w.size) s.copyInto(w) }
}
