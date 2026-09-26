package fan.superai.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Geri alma (undo) regresyon testleri — saf JVM (Android/Chaquopy gerekmez).
 *
 * Doğrulanan iki şey:
 *  1) DOĞRULUK: hızlı geri alma, tüm geçmişi baştan kurmakla BİREBİR aynı durumu verir
 *     (veri bozulmaz; tahmin, ağırlık ve başarı oranları kaymaz).
 *  2) HIZ: geri alma, tam yeniden kurmadan belirgin şekilde hızlıdır.
 */
class FanEngineUndoTest {

    /** Deterministik sahte Python meclisi: verilen hedefe tam olarak geri dönebilir. */
    private class FakePython : ExternalCouncil {
        val values = ArrayList<Int>()
        val targets = ArrayList<Int>()
        var undoCalls = 0
        var replayCalls = 0
        var failUndo = false

        private fun dist(): DoubleArray {
            val p = DoubleArray(K) { 0.25 }
            if (values.isNotEmpty()) {
                val last = values[values.size - 1]
                p[last] += 0.5
                p[(last + 1) % K] += 0.2
            }
            val s = p.sum()
            return DoubleArray(K) { p[it] / s }
        }

        override fun replay(values: IntArray, times: LongArray): Pair<List<DoubleArray>, DoubleArray> {
            replayCalls++
            this.values.clear()
            val per = ArrayList<DoubleArray>()
            for (i in values.indices) { per.add(dist()); this.values.add(values[i]) }
            return per to dist()
        }

        override fun step(value: Int, time: Long): DoubleArray {
            values.add(value)
            return dist()
        }

        override fun undoTo(count: Int): DoubleArray? {
            undoCalls++
            targets.add(count)
            if (failUndo || count < 0 || count > values.size) return null
            while (values.size > count) values.removeAt(values.size - 1)
            return dist()
        }

        override fun stats(): List<MemberStat> = listOf(
            MemberStat("fake", "Sahte", 0.25, 0.5, 1.0, false, true, values.size)
        )

        override fun info(): Map<String, String> = mapOf("n" to values.size.toString())
    }

    private fun data(n: Int, seed: Int = 42): IntArray {
        val r = Random(seed)
        return IntArray(n) { r.nextInt(4) }
    }

    private fun build(n: Int, py: ExternalCouncil?, vals: IntArray): FanEngine {
        val e = FanEngine(EngineConfig(silentFirst = 20), py)
        val t0 = 1700000000L
        for (i in 0 until n) { e.values.add(vals[i]); e.times.add(t0 + i) }
        e.replayPython()
        e.rebuildKotlin()
        return e
    }

    /** Karşılaştırılabilir durum özeti: tahmin, hakem, üyeler, başarı, loglar. */
    private fun fingerprint(e: FanEngine): List<Double> {
        val st = e.state()
        val out = ArrayList<Double>()
        out.add(st.count.toDouble())
        out.addAll(st.kotlinProbs.toList())
        if (st.pythonProbs != null) out.addAll(st.pythonProbs!!.toList()) else out.addAll(List(K) { -1.0 })
        val v = st.verdict
        if (v != null) {
            out.addAll(v.probs.toList())
            out.add(v.primary.toDouble()); out.add((v.secondary ?: -1).toDouble())
            out.add(v.confidence); out.add(v.oddProb); out.add(v.bigProb)
            out.add(v.sideConfidence); out.add(v.weightK); out.add(v.weightP)
        } else out.addAll(List(12) { -2.0 })
        out.addAll(st.recent.map { it.toDouble() })
        out.add(e.logs.size.toDouble())
        st.kotlinStats.forEach { s ->
            out.add(s.top1); out.add(s.top2); out.add(s.weight)
            out.add(if (s.benched) 1.0 else 0.0); out.add(if (s.enabled) 1.0 else 0.0)
        }
        val sc = st.scores
        out.addAll(listOf(sc.n.toDouble(), sc.top1, sc.top2, sc.sideAny, sc.sideBoth, sc.kTop1, sc.kTop2, sc.pTop1, sc.pTop2))
        out.add(if (st.lastSideHit == true) 1.0 else if (st.lastSideHit == false) 0.0 else -1.0)
        out.addAll(e.logs.takeLast(8).map { if (it.top1) 1.0 else 0.0 })
        out.addAll(e.logs.takeLast(8).map { if (it.sideBoth) 1.0 else 0.0 })
        return out
    }

    private fun text(e: FanEngine): String {
        val st = e.state()
        return st.kalipInfo.entries.sortedBy { it.key }.joinToString(";") { "${it.key}=${it.value}" } +
            "|" + st.regime + "|" + (st.verdict?.label ?: "-") + "|" + (st.verdict?.sideLabel ?: "-")
    }

    private fun assertSameState(expected: FanEngine, actual: FanEngine, msg: String) {
        val a = fingerprint(expected); val b = fingerprint(actual)
        assertEquals("$msg: özet uzunluğu", a.size, b.size)
        for (i in a.indices) assertEquals("$msg [$i]", a[i], b[i], 1e-9)
        assertEquals("$msg: metin durumu", text(expected), text(actual))
    }

    @Test
    fun undoRestoresExactStateAndIsFast() {
        val n = 220
        val vals = data(n)
        val ref = build(n, FakePython(), vals)
        val py = FakePython()
        val e = build(n, py, vals)

        val before = fingerprint(e)
        val beforeText = text(e)

        // Kullanıcı bir sayı girer, sonra "orijinal veri bozulmasın" diye geri alır.
        e.add(2, 1700000000L + n)
        assertNotEquals("ekleme durumu değiştirmeli", before, fingerprint(e))

        val t0 = System.nanoTime()
        assertTrue(e.undo())
        val undoMs = (System.nanoTime() - t0) / 1_000_000L

        assertTrue("hızlı geri alma yolu kullanılmalı", e.lastUndoFast)
        assertEquals("kayıt sayısı", n, e.values.size)
        assertSameState(ref, e, "hızlı geri alma")
        assertEquals("durum birebir dönmeli", before, fingerprint(e))
        assertEquals("metin durumu birebir dönmeli", beforeText, text(e))

        assertTrue("python undo çağrılmalı", py.undoCalls > 0)
        assertEquals("python hedefi kayıt sayısı olmalı", n, py.targets.last().toInt())
        assertEquals("python kayıtları eşitlenmeli", n, py.values.size)
        assertTrue("geri alma hızlı olmalı (${undoMs} ms)", undoMs < 3000)
    }

    @Test
    fun undoIsMuchFasterThanFullRebuild() {
        val n = 400
        val vals = data(n, 7)
        val e = build(n, FakePython(), vals)
        e.add(3, 1700000000L + n)

        val t0 = System.nanoTime()
        assertTrue(e.undo())
        val undoNs = System.nanoTime() - t0
        assertTrue("hızlı yol kullanılmalı", e.lastUndoFast)

        val t1 = System.nanoTime()
        e.rebuildKotlin()
        val rebuildNs = System.nanoTime() - t1

        assertTrue(
            "undo (${undoNs / 1000} µs) tam yeniden kurmadan (${rebuildNs / 1000} µs) hızlı olmalı",
            undoNs * 2 < rebuildNs
        )
    }

    @Test
    fun addAfterUndoKeepsLearning() {
        val n = 120
        val vals = data(n, 11)
        val ref = build(n + 1, FakePython(), vals + intArrayOf(1))
        val e = build(n, FakePython(), vals)
        e.add(1, 1700000000L + n)
        assertTrue(e.undo())
        e.add(1, 1700000000L + n)      // aynı sayı yeniden girilir
        assertSameState(ref, e, "geri alma sonrası yeniden ekleme")
    }

    @Test
    fun repeatedUndoStaysExact() {
        val n = 160
        val vals = data(n, 3)
        val e = build(n, FakePython(), vals)
        val extra = intArrayOf(0, 1, 2, 3, 0, 2, 1, 3, 2, 0)
        for (i in extra.indices) e.add(extra[i], 1700000000L + n + i)
        assertEquals(n + extra.size, e.values.size)

        repeat(extra.size) { assertTrue("geri alma $it", e.undo()) }
        assertSameState(build(n, FakePython(), vals), e, "10 ardışık geri alma")

        repeat(3) { assertTrue(e.undo()) }
        assertEquals(n - 3, e.values.size)
        assertSameState(build(n - 3, FakePython(), vals.copyOf(n - 3)), e, "13 ardışık geri alma")
    }

    @Test
    fun deepUndoBeyondJournalFallsBackCorrectly() {
        val n = 200
        val vals = data(n, 5)
        val e = build(n, FakePython(), vals)
        val deep = FanEngine.UNDO_DEPTH + 6
        var sawSlow = false
        repeat(deep) {
            assertTrue("geri alma $it", e.undo())
            if (!e.lastUndoFast) sawSlow = true
        }
        assertTrue("kayıt yığını bitince tam yeniden kurmaya düşmeli", sawSlow)
        assertEquals(n - deep, e.values.size)
        assertSameState(build(n - deep, FakePython(), vals.copyOf(n - deep)), e, "derin geri alma")
    }

    @Test
    fun pythonFailureStillRestoresExactly() {
        val n = 100
        val vals = data(n, 9)
        val py = FakePython().apply { failUndo = true }
        val e = build(n, py, vals)
        e.add(2, 1700000000L + n)
        assertTrue(e.undo())

        // Python geri alamadı ama KOTLIN meclisi yine anında ve TAM döndü; ekranda o adımda
        // hesaplanmış Python tahmini kalır (yani kullanıcı hiç beklemez, veri bozulmaz).
        assertTrue("kotlin tarafı hızlı yoldan dönmeli", e.lastUndoFast)
        assertTrue("python geride kaldı işaretlenmeli", e.pyDirty)
        assertEquals(n, e.values.size)
        assertSameState(build(n, FakePython(), vals), e, "python hatasında geri alma")

        // Bir sonraki ekleme Python'u baştan öğrenip iki meclisi aynı adımda buluşturur.
        py.failUndo = false
        e.add(1, 1700000000L + n)
        assertTrue("python yeniden öğrenilmeli", py.replayCalls > 0)
        assertFalse(e.pyDirty)
        assertEquals("iki meclis aynı adımda olmalı", e.values.size, py.values.size)
        assertSameState(build(n + 1, FakePython(), vals + intArrayOf(1)), e, "python eşitlendikten sonra")
    }

    @Test
    fun withoutPythonUndoIsStillExact() {
        val n = 60
        val vals = data(n, 13)
        val e = build(n, null, vals)          // Python meclisi hiç yok
        e.add(3, 1700000000L + n)
        assertTrue(e.undo())
        assertEquals(n, e.values.size)
        assertSameState(build(n, null, vals), e, "python'suz geri alma")
    }

    @Test
    fun undoOnEmptyHistoryIsNoOp() {
        val e = build(0, FakePython(), IntArray(0))
        assertFalse(e.undo())
        assertEquals(0, e.values.size)
    }
}
