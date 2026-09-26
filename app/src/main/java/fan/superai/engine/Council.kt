package fan.superai.engine

/** Motor ayarları (Android'den bağımsız). */
data class EngineConfig(
    val window: Int = 100,            // değerlendirme penceresi
    val benchThreshold: Double = 0.20, // yedeğe düşme eşiği (tek başarı)
    val fixedShare: Boolean = true,    // false → klasik Hedge
    val stacking: Boolean = true,      // hakem birleştirme
    val forgetting: Int = 1,           // 0 yavaş, 1 orta, 2 hızlı
    val pairMode: Int = 0,             // 0 otomatik (konformal), 1 her zaman çift, 2 her zaman tek
    val calibration: Boolean = true,
    val silentFirst: Int = 50,
    val disabled: Set<String> = emptySet()
) {
    val alpha get() = if (!fixedShare) 0.0 else when (forgetting) { 0 -> 0.005; 2 -> 0.05; else -> 0.02 }
}

data class MemberStat(
    val id: String,
    val name: String,
    val top1: Double,
    val top2: Double,
    val weight: Double,
    val benched: Boolean,
    val enabled: Boolean,
    val n: Int
)

/**
 * Meclis: üyeler kendi içinde yarışır. Ağırlık Fixed-Share Hedge ile, yedeğe düşme ise
 * kayan penceredeki tek-tahmin başarısıyla belirlenir.
 */
class Council(val name: String, val members: List<Member>, private var cfg: EngineConfig) {
    private val hedge = FixedShareHedge(members.size, alpha = cfg.alpha)
    private var r1 = members.map { Rolling(cfg.window) }
    private var r2 = members.map { Rolling(cfg.window) }
    private val benched = BooleanArray(members.size)
    private var lastPreds: List<DoubleArray>? = null
    private var lastSize = -1
    val selfTop1 = Rolling(100)
    val selfTop2 = Rolling(100)
    var lastMix: DoubleArray = P.uniform(); private set

    fun enabled(i: Int) = members[i].id !in cfg.disabled

    private fun active(): BooleanArray {
        val a = BooleanArray(members.size) { enabled(it) && !benched[it] }
        if (a.none { it }) for (i in a.indices) a[i] = enabled(i)
        return a
    }

    fun predict(h: History): DoubleArray {
        val preds = members.map { m ->
            try { P.normalize(m.predict(h).copyOf()) } catch (e: Exception) { P.uniform() }
        }
        lastPreds = preds
        lastSize = h.size
        lastMix = hedge.mix(preds, active())
        return lastMix
    }

    /**
     * Geri alma kaydı: meclisin ADIM ÖNCESİ durumu (ağırlıklar, kayan pencereler,
     * yedek bayrakları) + her üyenin kendi kaydı. Büyük üye tabloları kopyalanmaz.
     */
    class Snap(
        val hedge: DoubleArray,
        val r1: List<Rolling.Snap>, val r2: List<Rolling.Snap>,
        val benched: BooleanArray,
        val self1: Rolling.Snap, val self2: Rolling.Snap,
        val lastPreds: List<DoubleArray>?, val lastMix: DoubleArray, val lastSize: Int,
        val members: Array<Any?>
    )

    private var snap: Snap? = null

    /** Tüm üyeler geri almayı destekliyorsa meclis de destekler. */
    val undoable: Boolean get() = members.all { it.undoable }

    fun undoToken(): Any? = snap

    /** h, gerçek sonuç dahil edilmiş geçmiş. predict() h.size-1 iken çağrılmış olmalı. */
    fun update(h: History) {
        val before = Snap(hedge.snapshot(), r1.map { it.snapshot() }, r2.map { it.snapshot() },
            benched.copyOf(), selfTop1.snapshot(), selfTop2.snapshot(),
            lastPreds, lastMix, lastSize, arrayOfNulls(members.size))
        val a = h.last()
        val preds = lastPreds
        if (preds != null && lastSize == h.size - 1) {
            val o = P.order(lastMix)
            selfTop1.add(o[0] == a); selfTop2.add(o[0] == a || o[1] == a)
            for (i in members.indices) {
                val oi = P.order(preds[i])
                r1[i].add(oi[0] == a); r2[i].add(oi[0] == a || oi[1] == a)
                benched[i] = r1[i].count >= cfg.window && r1[i].rate() < cfg.benchThreshold
            }
            hedge.update(preds, a)
        }
        for (i in members.indices) {
            val m = members[i]
            try { m.update(h) } catch (_: Exception) {}
            before.members[i] = m.undoToken()
        }
        snap = before
    }

    /**
     * Meclisi tam olarak bir adım geri alır (h: geri alınan kayıt dahil geçmiş).
     * Kayıt eksikse hiçbir şey değiştirmeden false döner; motor o zaman tam
     * yeniden kurmaya düşer.
     */
    fun undo(token: Any?, h: History): Boolean {
        val s = token as? Snap ?: return false
        if (s.members.size != members.size || s.r1.size != members.size || s.r2.size != members.size) return false
        for (i in members.indices) if (members[i].undoable && s.members[i] == null) return false
        hedge.restore(s.hedge)
        for (i in members.indices) { r1[i].restore(s.r1[i]); r2[i].restore(s.r2[i]) }
        s.benched.copyInto(benched)
        selfTop1.restore(s.self1); selfTop2.restore(s.self2)
        lastPreds = s.lastPreds; lastMix = s.lastMix; lastSize = s.lastSize
        for (i in members.indices) try { members[i].undo(s.members[i], h) } catch (e: Exception) { return false }
        return true
    }

    fun memberPreds(): List<DoubleArray> = lastPreds ?: members.map { P.uniform() }

    fun stats(): List<MemberStat> = members.indices.map { i ->
        MemberStat(members[i].id, members[i].name, r1[i].rate(), r2[i].rate(),
            hedge.scaled(i), benched[i], enabled(i), r1[i].count)
    }.sortedWith(compareBy<MemberStat>({ !it.enabled }, { it.benched }).thenByDescending { it.weight })
}
