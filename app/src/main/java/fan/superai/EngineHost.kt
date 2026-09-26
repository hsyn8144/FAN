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
 * Motorun tek sahibi. Tüm hesaplar tek bir arka plan iş parçacığında sırayla yapılır;
 * arayüz ve overlay yalnızca StateFlow'ları izler.
 */
object EngineHost {
    private const val TAG = "FAN_SUPER"
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "fan-engine").apply { priority = Thread.NORM_PRIORITY } }
    private lateinit var app: Context
    private lateinit var engine: FanEngine
    private var bridge: PythonBridge? = null
    private var recs = mutableListOf<Rec>()
    private var applied: AppSettings? = null
    private var sinceDiscovery = 0
    private var sinceBackup = 0

    private val _state = MutableStateFlow<EngineState?>(null)
    val state: StateFlow<EngineState?> = _state
    private val _busy = MutableStateFlow<String?>("Hazırlanıyor…")
    val busy: StateFlow<String?> = _busy
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
                recs = DataStore.load(app)
                engine = FanEngine(s.engineConfig(), null)
                recs.forEach { engine.values.add(it.value - 1); engine.times.add(it.time) }
                _busy.value = "🔵 Kotlin meclisi öğreniyor…"
                engine.replayPython(); engine.rebuildKotlin()
                publish()
                runDiscovery()
                if (s.pythonEnabled) startPython(s)
            } catch (e: Throwable) {
                Log.e(TAG, "init", e); _pyError.value = e.message
            } finally { _busy.value = null; publish() }
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

    private fun runDiscovery() {
        _discovery.value = Discovery.run(engine.values.toIntArray(), engine.times.toLongArray())
        sinceDiscovery = 0
    }

    fun rerunDiscovery() = exec.execute { runDiscovery() }

    /** Yeni sayı (1..4). */
    fun add(value: Int) = exec.execute {
        if (!::engine.isInitialized || value !in 1..4) return@execute
        val r = Rec(value, System.currentTimeMillis() / 1000)
        recs.add(r)
        DataStore.append(app, recs.size, r)
        engine.add(value - 1, r.time)
        publish()
        val s = Settings.value
        if (s.discoveryEvery > 0 && ++sinceDiscovery >= s.discoveryEvery) runDiscovery()
        if (s.backupDownload && ++sinceBackup >= 10) { sinceBackup = 0; DataStore.backupToDownload(app, recs) }
    }

    fun undo() = exec.execute {
        if (!::engine.isInitialized || recs.isEmpty()) return@execute
        _busy.value = "Geri alınıyor…"
        recs.removeAt(recs.size - 1)
        DataStore.save(app, recs)
        engine.undo()
        _busy.value = null
        publish()
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
        } finally { _busy.value = null; publish() }
    }

    /** İçe aktarılan veriyle değiştir. */
    fun replaceData(newRecs: List<Rec>) = exec.execute {
        recs = newRecs.toMutableList()
        DataStore.save(app, recs)
        rebuildFromRecs()
    }

    fun resetLearning() = exec.execute {
        bridge?.deleteState()
        rebuildFromRecs()
    }

    fun deleteAll() = exec.execute {
        recs.clear(); DataStore.save(app, recs)
        bridge?.deleteState()
        rebuildFromRecs()
    }

    private fun rebuildFromRecs() {
        try {
            engine.values.clear(); engine.times.clear()
            recs.forEach { engine.values.add(it.value - 1); engine.times.add(it.time) }
            _busy.value = if (bridge != null) "🐍 Python yeniden öğreniyor…" else "Yeniden kuruluyor…"
            engine.replayPython(); engine.rebuildKotlin()
            runDiscovery()
        } finally { _busy.value = null; publish() }
    }

    fun records(): List<Rec> = recs.toList()

    fun exportCsv(): String = DataStore.toCsv(recs.toList())

    fun persist() = exec.execute { bridge?.save() }
}
