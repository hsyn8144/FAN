package fan.superai.data

import android.content.Context
import android.content.SharedPreferences
import fan.superai.engine.EngineConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

data class AppSettings(
    // Tahmin
    val pairMode: Int = 0,              // 0 otomatik, 1 her zaman çift, 2 her zaman tek
    val silentFirst: Int = 50,
    val calibration: Boolean = true,
    // Meclis & yarış
    val window: Int = 100,
    val benchThreshold: Double = 0.20,
    val fixedShare: Boolean = true,
    val stacking: Boolean = true,
    val forgetting: Int = 1,
    val disabled: Set<String> = emptySet(),
    // Python
    val pythonEnabled: Boolean = true,
    val dlEnabled: Boolean = true,
    val battery: Boolean = false,
    // Overlay
    val overlayHorizontal: Boolean = false,
    val overlayDetail: Boolean = true,
    val overlayAlpha: Float = 0.8f,
    val overlayTextScale: Float = 1.0f,
    val showRecent: Boolean = true,
    val vibrate: Boolean = false,
    // Keşif
    val discoveryEvery: Int = 50,
    // Veri
    val backupDownload: Boolean = true
) {
    fun engineConfig() = EngineConfig(
        window = window, benchThreshold = benchThreshold, fixedShare = fixedShare, stacking = stacking,
        forgetting = forgetting, pairMode = pairMode, calibration = calibration, silentFirst = silentFirst,
        disabled = disabled
    )

    fun pythonJson(): String = JSONObject().apply {
        put("window", window); put("bench", benchThreshold)
        put("alpha", engineConfig().alpha); put("dl", dlEnabled); put("battery", battery)
        put("disabled", JSONArray(disabled.sorted()))
    }.toString()
}

object Settings {
    private lateinit var prefs: SharedPreferences
    private val _flow = MutableStateFlow(AppSettings())
    val flow: StateFlow<AppSettings> = _flow
    val value get() = _flow.value

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("fan_super_settings", Context.MODE_PRIVATE)
        _flow.value = load()
    }

    private fun load(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            pairMode = prefs.getInt("pairMode", d.pairMode),
            silentFirst = prefs.getInt("silentFirst", d.silentFirst),
            calibration = prefs.getBoolean("calibration", d.calibration),
            window = prefs.getInt("window", d.window),
            benchThreshold = prefs.getFloat("bench", d.benchThreshold.toFloat()).toDouble(),
            fixedShare = prefs.getBoolean("fixedShare", d.fixedShare),
            stacking = prefs.getBoolean("stacking", d.stacking),
            forgetting = prefs.getInt("forgetting", d.forgetting),
            disabled = prefs.getStringSet("disabled", emptySet())?.toSet() ?: emptySet(),
            pythonEnabled = prefs.getBoolean("python", d.pythonEnabled),
            dlEnabled = prefs.getBoolean("dl", d.dlEnabled),
            battery = prefs.getBoolean("battery", d.battery),
            overlayHorizontal = prefs.getBoolean("ovH", d.overlayHorizontal),
            overlayDetail = prefs.getBoolean("ovDetail", d.overlayDetail),
            overlayAlpha = prefs.getFloat("ovAlpha", d.overlayAlpha),
            overlayTextScale = prefs.getFloat("ovText", d.overlayTextScale),
            showRecent = prefs.getBoolean("ovRecent", d.showRecent),
            vibrate = prefs.getBoolean("vibrate", d.vibrate),
            discoveryEvery = prefs.getInt("discEvery", d.discoveryEvery),
            backupDownload = prefs.getBoolean("backup", d.backupDownload)
        )
    }

    fun update(f: (AppSettings) -> AppSettings) {
        val s = f(_flow.value)
        prefs.edit().apply {
            putInt("pairMode", s.pairMode); putInt("silentFirst", s.silentFirst); putBoolean("calibration", s.calibration)
            putInt("window", s.window); putFloat("bench", s.benchThreshold.toFloat()); putBoolean("fixedShare", s.fixedShare)
            putBoolean("stacking", s.stacking); putInt("forgetting", s.forgetting); putStringSet("disabled", s.disabled)
            putBoolean("python", s.pythonEnabled); putBoolean("dl", s.dlEnabled); putBoolean("battery", s.battery)
            putBoolean("ovH", s.overlayHorizontal); putBoolean("ovDetail", s.overlayDetail); putFloat("ovAlpha", s.overlayAlpha)
            putFloat("ovText", s.overlayTextScale); putBoolean("ovRecent", s.showRecent); putBoolean("vibrate", s.vibrate)
            putInt("discEvery", s.discoveryEvery); putBoolean("backup", s.backupDownload)
        }.apply()
        _flow.value = s
    }
}
