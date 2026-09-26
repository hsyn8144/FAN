package fan.superai

import android.content.Context
import android.util.Log
import fan.superai.data.AppSettings
import fan.superai.data.DataStore
import fan.superai.data.Rec
import fan.superai.data.Settings
import fan.superai.engine.Discovery
import fan.superai.engine.DiscoveryReport
import fan.superai.engine.EngineState
import fan.superai.engine.FanEngine
import fan.superai.engine.PythonBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Grafik ekranı verisi (motor iş parçacığında hazırlanır). */
data class ChartData(
    val ref: List<List<Double>>,   // [tek, çift, yan] için hakem serileri
    val kot: List<List<Double>>,
    val py: List<List<Double>>,
    val dist: IntArray,
    val calSingle: List<Triple<Double, Int, Double>>,
    val calPair: List<Triple<Double, Int, Double>>,
    val calSide: List<Triple<Double, Int, Double>>
)

/**
 * Düğmeye basıldığı anda ekranda gösterilecek sayılar (motor hesabı bitene kadar).
 * [base]: bu liste oluşurken motorda bulunan kayıt sayısı.
 */
data class Echo(val base: Int, val values: List<Int>)

/** Motor durumu + anında geri bildirimi birleştirir (son 6 sayı). */
fun recentWithEcho(st: EngineState?, echo: Echo): List<Int> {
    val base = st?.recent ?: emptyList()
    return if (echo.values.isEmpty()) base else (base + echo.values).takeLast(6)
}

/**
 * Motorun tek sahibi. Tüm hesaplar tek bir arka plan iş parçacığında sırayla yapılır;
 * arayüz ve overlay yalnızca StateFlow'ları izler.
 *
 * DÜĞME GECİKMESİ: Sayı ve geri al düğmeleri bu sınıfa ANINDA işlenir — giriş önce
 * [echo] ile ekrana yansır, ardından motor iş parçacığında sıraya girer. Motor hazır
 * değilse (ilk açılış) girişler [waiting] içine alınır ve hazırlık bitince işlenir;
 * hiçbir düğme basışı sessizce kaybolmaz.
 */
object EngineHost {
    private const val TAG = "FAN_SUPER"
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "fan-engine").apply { priority = Thread.NORM_PRIORITY } }
    private lateinit var app: Context
    private lateinit var engine: FanEngine
    private var bridge: PythonBridge? = null
    private val lock = Any()
    private var recs = mutableListOf<Rec>()
    private var applied: AppSettings? = null
    private var sinceDiscovery = 0
    private var sinceBackup = 0
    private val waiting = ArrayDeque<Rec>()
    private val inFlight = AtomicInteger(0)

    private val _state = MutableStateFlow<EngineState?>(null)
    val state: StateFlow<EngineState?> = _state
    private val _busy = MutableStateFlow<String?>("Hazırlanıyor…")
    val busy: StateFlow<String?> = _busy
    private val _echo = MutableStateFlow(Echo(0, emptyList()))
    val echo: StateFlow<Echo> = _echo
    private val _pending = MutableStateFlow(0)
    val pending: StateFlow<Int> = _pending
    private val _discovery = MutableStateFlow<DiscoveryReport?>(null)
    val discovery: StateFlow<DiscoveryReport?> = _discovery
    private val _charts = MutableStateFlow<ChartData?>(null)
    val charts: StateFlow<ChartData?> = _charts
    private val _pyError = MutableStateFlow<String?>(null)
    val pyError: StateFlow<String?> = _pyError

    fun init(ctx: Context) {
        app = ctx.applicationContext
        exec.execute {
            try {
                val s = Settings.value
                applied = s
                val loaded = DataStore.load(app)
                synchronized(lock) { recs = loaded }
                engine = FanEngine(s.engineConfig(), null)
                loaded.forEach { engine.values.add(it.value - 1); engine.times.add(it.time) }
                _busy.value = "🔵 Kotlin meclisi öğreniyor…"
                engine.replayPython(); engine.rebuildKotlin()
                publish()
                runDiscovery()
                if (s.pythonEnabled) startPython(s)
                flushWaiting()          // hazırlık sırasında basılan düğmeler şimdi işlenir
            } catch (e: Throwable) {
                Log.e(TAG, "init", e); _pyError.value = e.message
            } finally { _busy.value = null; publish(); syncEcho() }
        }
    }

    private fun startPython(s: AppSettings) {
        _busy.value = "🐍 Python meclisi öğreniyor… (ilk açılışta biraz sürer)"
        try {
            val b = PythonBridge(app, s.pythonJson())
            bridge = b
            engine.setPython(b)
            engine.replayPython()
            _busy.value = "⚖️ Hakem hazırlanıyor…"
            engine.rebuildKotlin()
            _pyError.value = null
        } catch (e: Throwable) {
            Log.e(TAG, "python", e)
            _pyError.value = "Python başlatılamadı: ${e.message}"
            bridge = null; engine.setPython(null)
            engine.replayPython(); engine.rebuildKotlin()
        }
    }

    /** Motor hazır olmadan gelen girişleri sırayla işler. */
    private fun flushWaiting() {
        while (waiting.isNotEmpty()) {
            val r = waiting.removeFirst()
            try { addNow(r) } catch (e: Throwable) { Log.e(TAG, "flush", e) }
        }
    }

    private fun publish() {
        if (!::engine.isInitialized) return
        try {
            _state.value = engine.state()
            val sel = listOf<(fan.superai.engine.StepLog) -> Boolean>({ it.top1 }, { it.top2 }, { it.sideAny })
            val kSel = listOf<(fan.superai.engine.StepLog) -> Boolean>({ it.kTop1 }, { it.kTop2 }, { it.sideAny })
            val pSel = listOf<(fan.superai.engine.StepLog) -> Boolean>({ it.pTop1 }, { it.pTop2 }, { it.sideAny })
            val dist = IntArray(4); engine.values.forEach { dist[it]++ }
            _charts.value = ChartData(
                sel.map { engine.rollingSeries(it) },
                kSel.map { engine.rollingSeries(it) },
                pSel.mapIndexed { i, f -> if (i == 2) emptyList() else engine.rollingSeries(f) { it.pAvail } },
                dist, engine.referee.calSingle.table(), engine.referee.calPair.table(), engine.referee.calSide.table()
            )
        } catch (e: Throwable) { Log.e(TAG, "publish", e) }
    }

    /** Echo listesini motor durumuna göre temizler (hepsi işlendiyse). */
    private fun syncEcho() {
        val e = _echo.value
        if (e.values.isEmpty()) return
        val c = _state.value?.count ?: 0
        if (inFlight.get() <= 0 && c >= e.base + e.values.size) _echo.value = Echo(c, emptyList())
    }

    private fun runDiscovery() {
        _discovery.value = Discovery.run(engine.values.toIntArray(), engine.times.toLongArray())
        sinceDiscovery = 0
    }

    fun rerunDiscovery() = exec.execute { runDiscovery() }

    /**
     * Yeni sayı (1..4). Çağrı ANINDA döner: sayı önce ekranda belirir, motor arkada işler.
     */
    fun add(value: Int) {
        if (value !in 1..4) return
        val base = _state.value?.count ?: 0
        val e = _echo.value
        _echo.value = Echo(if (e.values.isEmpty()) base else e.base, (e.values + value).takeLast(6))
        _pending.value = inFlight.incrementAndGet()
        exec.execute {
            val t0 = System.nanoTime()
            try {
                val r = Rec(value, System.currentTimeMillis() / 1000)
                if (!::engine.isInitialized) { waiting.addLast(r); return@execute }
                addNow(r)
                Log.i(TAG, "add($value) ${(System.nanoTime() - t0) / 1_000_000L} ms")
            } catch (ex: Throwable) {
                Log.e(TAG, "add", ex)
            } finally {
                _pending.value = inFlight.decrementAndGet()
                syncEcho()
            }
        }
    }

    private fun addNow(r: Rec) {
        synchronized(lock) {
            recs.add(r)
            DataStore.append(app, recs.size, r)
        }
        engine.add(r.value - 1, r.time)
        publish()
        val s = Settings.value
        if (s.discoveryEvery > 0 && ++sinceDiscovery >= s.discoveryEvery) runDiscovery()
        if (s.backupDownload && ++sinceBackup >= 10) { sinceBackup = 0; DataStore.backupToDownload(app, recs) }
    }

    /**
     * Son sayıyı geri alır. Geri alma artık adım adım tutulan kayıtlarla yapılır:
     * iki meclis de tam olarak bir adım geriye sarılır, tüm geçmiş baştan öğrenilmez.
     */
    fun undo() {
        val e = _echo.value
        if (e.values.isNotEmpty()) _echo.value = Echo(e.base, e.values.dropLast(1))
        _pending.value = inFlight.incrementAndGet()
        exec.execute {
            try {
                if (!::engine.isInitialized) {
                    // Motor daha hazır değil: bekleyen girişlerden sonuncusunu geri al.
                    if (waiting.isNotEmpty()) waiting.removeLast()
                    return@execute
                }
                if (synchronized(lock) { recs.isEmpty() }) return@execute
                _busy.value = "Geri alınıyor…"
                synchronized(lock) { recs.removeAt(recs.size - 1) }
                if (!DataStore.removeLast(app)) synchronized(lock) { DataStore.save(app, recs) }
                engine.undo()
                if (engine.lastUndoFast) Log.i(TAG, "undo ${engine.lastUndoMs} ms (anında)")
                else Log.w(TAG, "undo ${engine.lastUndoMs} ms (tam yeniden kurma gerekti)")
            } catch (ex: Throwable) {
                Log.e(TAG, "undo", ex)
            } finally {
                _busy.value = null
                publish()
                _pending.value = inFlight.decrementAndGet()
                syncEcho()
            }
        }
    }

    /** Ayarlar değişince motoru gerekli ölçüde yeniden kurar. */
    fun applySettings(s: AppSettings) = exec.execute {
        if (!::engine.isInitialized) return@execute
        val old = applied ?: s
        applied = s
        val engineChanged = old.engineConfig() != s.engineConfig()
        val pyChanged = old.pythonEnabled != s.pythonEnabled || old.pythonJson() != s.pythonJson()
        if (!engineChanged && !pyChanged) return@execute
        engine.cfg = s.engineConfig()
        try {
            if (pyChanged) {
                if (s.pythonEnabled) startPython(s) else {
                    bridge = null; engine.setPython(null)
                    _busy.value = "Yeniden kuruluyor…"; engine.replayPython(); engine.rebuildKotlin()
                }
            } else {
                _busy.value = "Yeniden kuruluyor…"; engine.rebuildKotlin()
            }
        } finally { _busy.value = null; publish(); syncEcho() }
    }

    /** İçe aktarılan veriyle değiştir. */
    fun replaceData(newRecs: List<Rec>) = exec.execute {
        synchronized(lock) { recs = newRecs.toMutableList(); DataStore.save(app, recs) }
        _echo.value = Echo(0, emptyList())
        waiting.clear()
        if (!::engine.isInitialized) return@execute
        rebuildFromRecs()
    }

    fun resetLearning() = exec.execute {
        if (!::engine.isInitialized) return@execute
        bridge?.deleteState()
        rebuildFromRecs()
    }

    fun deleteAll() = exec.execute {
        synchronized(lock) { recs.clear(); DataStore.save(app, recs) }
        _echo.value = Echo(0, emptyList())
        waiting.clear()
        if (!::engine.isInitialized) return@execute
        bridge?.deleteState()
        rebuildFromRecs()
    }

    private fun rebuildFromRecs() {
        try {
            engine.values.clear(); engine.times.clear()
            synchronized(lock) { recs.forEach { engine.values.add(it.value - 1); engine.times.add(it.time) } }
            _busy.value = if (bridge != null) "🐍 Python yeniden öğreniyor…" else "Yeniden kuruluyor…"
            engine.replayPython(); engine.rebuildKotlin()
            runDiscovery()
        } finally { _busy.value = null; publish(); syncEcho() }
    }

    fun records(): List<Rec> = synchronized(lock) { recs.toList() }

    fun exportCsv(): String = DataStore.toCsv(records())

    fun persist() = exec.execute { bridge?.save() }
}
