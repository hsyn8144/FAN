package fan.superai.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fan.superai.EngineHost
import fan.superai.data.AppSettings
import fan.superai.data.DataStore
import fan.superai.data.Settings
import fan.superai.engine.FanEngine
import kotlinx.coroutines.flow.drop

private val KOTLIN_NAMES = mapOf("kalip2" to "Kalıp Arama 2.0", "ctw" to "CTW", "ppm" to "PPM", "freqgap" to "Frekans / Aralık",
    "streak" to "Seri / Dalga", "regime" to "Rejim", "gru" to "GRU", "esn" to "ESN")
private val PYTHON_NAMES = mapOf("lstm" to "LSTM", "transformer" to "Transformer", "cnn" to "1D-CNN", "kalip_fuzzy" to "Kalıp 2.0 bulanık",
    "knn_dtw" to "kNN-DTW", "spectral" to "Spektral/BOCPD", "gboost" to "Gradient Boost", "context" to "Bağlam modeli",
    "motif" to "Motif keşfi", "hmm" to "HMM")

@Composable
private fun SetRow(title: String, sub: String? = null, onClick: (() -> Unit)? = null, titleColor: Color = C.text, trailing: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable { onClick() } else Modifier).padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = titleColor, fontSize = 13.sp)
            if (sub != null) Text(sub, color = C.dim, fontSize = 11.sp)
        }
        trailing()
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(C.line))
}

@Composable
private fun Toggle(title: String, sub: String? = null, value: Boolean, onChange: (Boolean) -> Unit) =
    SetRow(title, sub, onClick = { onChange(!value) }) {
        Switch(checked = value, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedTrackColor = C.blue))
    }

@Composable
private fun <T> Choice(title: String, sub: String? = null, value: T, options: List<Pair<T, String>>, onChange: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    SetRow(title, sub, onClick = { open = true }) {
        Box {
            Text((options.firstOrNull { it.first == value }?.second ?: value.toString()) + " ▾", color = Color(0xFFCFE0F5), fontSize = 12.sp,
                modifier = Modifier.clip(RoundedCornerShape(7.dp)).background(C.border).padding(horizontal = 9.dp, vertical = 4.dp))
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { (v, l) -> DropdownMenuItem(text = { Text(l) }, onClick = { onChange(v); open = false }) }
            }
        }
    }
}

@Composable
private fun Action(title: String, sub: String? = null, color: Color = C.text, onClick: () -> Unit) =
    SetRow(title, sub, onClick = onClick, titleColor = color) { Text("›", color = C.muted, fontSize = 18.sp) }

@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val s by Settings.flow.collectAsState()
    val st by EngineHost.state.collectAsState()
    val busy by EngineHost.busy.collectAsState()
    fun set(f: (AppSettings) -> AppSettings) = Settings.update(f)

    // Ayar değişikliklerini motora uygula
    LaunchedEffect(Unit) { Settings.flow.drop(1).collect { EngineHost.applySettings(it) } }

    var membersDialog by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    var deleteStep by remember { mutableIntStateOf(0) }
    var pendingImport by remember { mutableStateOf<List<fan.superai.data.Rec>?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) {
            try { DataStore.exportTo(ctx, uri, EngineHost.records()); Toast.makeText(ctx, "Dışa aktarıldı", Toast.LENGTH_SHORT).show() }
            catch (e: Exception) { Toast.makeText(ctx, "Hata: ${e.message}", Toast.LENGTH_LONG).show() }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val recs = try { DataStore.parse(DataStore.readUri(ctx, uri)) } catch (e: Exception) { emptyList() }
            if (recs.isEmpty()) Toast.makeText(ctx, "Dosyada geçerli kayıt bulunamadı", Toast.LENGTH_LONG).show()
            else pendingImport = recs
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        BusyBanner(busy)
        FCard("🎯 Tahmin") {
            Choice("Çift tahmin kararı", "Konformal: gerektiğinde 2 rakam", s.pairMode,
                listOf(0 to "Otomatik", 1 to "Her zaman çift", 2 to "Her zaman tek")) { v -> set { it.copy(pairMode = v) } }
            Choice("İlk sessiz tur", "Başarı hesabına katılmayan öğrenme başlangıcı", s.silentFirst,
                listOf(0 to "0", 20 to "20", 50 to "50", 100 to "100")) { v -> set { it.copy(silentFirst = v) } }
            Toggle("Güven kalibrasyonu", "Yazan yüzde ≈ gerçek başarı", s.calibration) { v -> set { it.copy(calibration = v) } }
        }
        FCard("🏛️ Meclis & Yarış") {
            Choice("Değerlendirme penceresi", null, s.window, listOf(50 to "50", 100 to "100", 200 to "200")) { v -> set { it.copy(window = v) } }
            Choice("Yedeğe düşme eşiği", "Tek başarısı bunun altına inen üye oy vermez", s.benchThreshold,
                listOf(0.0 to "Kapalı", 0.18 to "%18", 0.20 to "%20", 0.22 to "%22", 0.24 to "%24")) { v -> set { it.copy(benchThreshold = v) } }
            Choice("Ağırlık yöntemi", null, s.fixedShare, listOf(true to "Fixed-Share", false to "Hedge")) { v -> set { it.copy(fixedShare = v) } }
            Choice("Hakem birleştirme", "Stacking / ağırlıklı ortalama", s.stacking, listOf(true to "Stacking", false to "Ağırlıklı")) { v -> set { it.copy(stacking = v) } }
            Choice("Eski veriyi unutma hızı", "Kalıp değişimine uyum hızı", s.forgetting, listOf(0 to "Yavaş", 1 to "Orta", 2 to "Hızlı")) { v -> set { it.copy(forgetting = v) } }
            val total = KOTLIN_NAMES.size + PYTHON_NAMES.size
            SetRow("Üyeleri aç/kapat", onClick = { membersDialog = true }) {
                Text("${total - s.disabled.size} aktif ›", color = Color(0xFFCFE0F5), fontSize = 12.sp)
            }
        }
        FCard("🐍 Python motoru") {
            Toggle("Python meclisi", "Uygulamaya gömülü (Chaquopy)", s.pythonEnabled) { v -> set { it.copy(pythonEnabled = v) } }
            Toggle("Derin öğrenme modelleri", "LSTM · Transformer · 1D-CNN", s.dlEnabled) { v -> set { it.copy(dlEnabled = v) } }
            Toggle("Pil tasarrufu", "Ağır modelleri 5 turda bir eğit", s.battery) { v -> set { it.copy(battery = v) } }
        }
        FCard("🪟 Overlay") {
            Choice("Yerleşim", null, s.overlayHorizontal, listOf(false to "Dikey", true to "Yatay")) { v -> set { it.copy(overlayHorizontal = v) } }
            Toggle("Uzun basınca detay paneli", null, s.overlayDetail) { v -> set { it.copy(overlayDetail = v) } }
            Choice("Saydamlık", null, s.overlayAlpha, listOf(0.6f to "%60", 0.7f to "%70", 0.8f to "%80", 0.9f to "%90", 1.0f to "%100")) { v -> set { it.copy(overlayAlpha = v) } }
            Choice("Yazı boyutu", null, s.overlayTextScale, listOf(0.85f to "Küçük", 1.0f to "Normal", 1.2f to "Büyük")) { v -> set { it.copy(overlayTextScale = v) } }
            Toggle("Son sayı dizisini göster", null, s.showRecent) { v -> set { it.copy(showRecent = v) } }
            Toggle("Titreşimli buton", null, s.vibrate) { v -> set { it.copy(vibrate = v) } }
        }
        FCard("🔍 Keşif") {
            Choice("Testleri otomatik çalıştır", null, s.discoveryEvery, listOf(0 to "Kapalı", 25 to "25 turda bir", 50 to "50 turda bir", 100 to "100 turda bir")) { v -> set { it.copy(discoveryEvery = v) } }
            Action("Testleri şimdi çalıştır") { EngineHost.rerunDiscovery(); Toast.makeText(ctx, "Keşif testleri çalışıyor", Toast.LENGTH_SHORT).show() }
        }
        FCard("💾 Veri") {
            Action("📥 CSV içe aktar", "Eski FAN verisi (fan_data_live.csv) veya satır başına bir sayı") { importLauncher.launch(arrayOf("*/*")) }
            Action("📤 CSV dışa aktar") { exportLauncher.launch("fan_super_data.csv") }
            Toggle("Download/FAN yedekle", "10 kayıtta bir otomatik", s.backupDownload) { v -> set { it.copy(backupDownload = v) } }
            Action("🟠 Öğrenmeyi sıfırla", "Veri kalır, modeller baştan öğrenir", C.orange) {
                confirm = "Tüm modeller baştan öğrenecek. Devam edilsin mi?" to { EngineHost.resetLearning() }
            }
            Action("🔴 Tüm veriyi sil", "Çift onay", C.danger) { deleteStep = 1 }
        }
        FCard("ℹ️ Hakkında") {
            KV("Uygulama", "FAN SUPER")
            KV("Paket", "fan.superai")
            KV("Sürüm", "1.0")
            KV("Üyeler", "🔵 ${KOTLIN_NAMES.size} · 🐍 ${PYTHON_NAMES.size}")
            KV("Kayıt", "${st?.count ?: 0}")
            Muted("Başarı her zaman şans çizgisiyle (tek %25, çift %50) karşılaştırılır. Hiçbir yöntem rastgele bir veride şansı kalıcı olarak geçemez.",
                Modifier.padding(top = 6.dp))
        }
    }

    if (membersDialog) {
        AlertDialog(onDismissRequest = { membersDialog = false }, containerColor = C.card,
            confirmButton = { TextButton(onClick = { membersDialog = false }) { Text("Tamam") } },
            title = { Text("Üyeleri aç/kapat") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("🔵 Kotlin", color = C.head, fontSize = 12.sp)
                    KOTLIN_NAMES.forEach { (id, name) ->
                        MemberCheck(name, id !in s.disabled) { on -> set { it.copy(disabled = if (on) it.disabled - id else it.disabled + id) } }
                    }
                    Text("🐍 Python", color = C.head, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                    PYTHON_NAMES.forEach { (id, name) ->
                        MemberCheck(name, id !in s.disabled) { on -> set { it.copy(disabled = if (on) it.disabled - id else it.disabled + id) } }
                    }
                }
            })
    }
    confirm?.let { (msg, act) ->
        AlertDialog(onDismissRequest = { confirm = null }, containerColor = C.card, text = { Text(msg) },
            confirmButton = { TextButton(onClick = { act(); confirm = null }) { Text("Evet") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Vazgeç") } })
    }
    if (deleteStep > 0) {
        AlertDialog(onDismissRequest = { deleteStep = 0 }, containerColor = C.card,
            text = { Text(if (deleteStep == 1) "Tüm kayıtlar silinecek. Emin misiniz?" else "GERİ ALINAMAZ. Son kez onaylıyor musunuz?") },
            confirmButton = {
                TextButton(onClick = {
                    if (deleteStep == 1) deleteStep = 2 else { EngineHost.deleteAll(); deleteStep = 0 }
                }) { Text(if (deleteStep == 1) "Evet" else "SİL", color = C.danger) }
            },
            dismissButton = { TextButton(onClick = { deleteStep = 0 }) { Text("Vazgeç") } })
    }
    pendingImport?.let { recs ->
        AlertDialog(onDismissRequest = { pendingImport = null }, containerColor = C.card,
            text = { Text("${recs.size} kayıt bulundu. Mevcut veri bu dosyayla değiştirilsin mi?") },
            confirmButton = { TextButton(onClick = { EngineHost.replaceData(recs); pendingImport = null }) { Text("Değiştir") } },
            dismissButton = { TextButton(onClick = { pendingImport = null }) { Text("Vazgeç") } })
    }
}

@Composable
private fun MemberCheck(name: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!on) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = on, onCheckedChange = onChange)
        Text(name, color = C.text, fontSize = 13.sp)
    }
}

@Suppress("unused")
private val keepIds = FanEngine.KOTLIN_IDS
