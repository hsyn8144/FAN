package fan.superai.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fan.superai.EngineHost

@Composable
fun DiscoveryScreen() {
    val d by EngineHost.discovery.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        val r = d
        if (r == null) { FCard("🔍 Keşif Laboratuvarı") { Muted("Testler hazırlanıyor…") }; return@Column }
        FCard("🔍 Rastgelelik testleri · ${r.n} kayıt") {
            r.tests.forEach { KV(it.name, it.result, vColor = if (it.status == 1) C.orange else if (it.status == 0) C.lightGreen else C.text, bold = it.status == 2) }
        }
        FCard("Periyot · Değişim · Gecikme") {
            r.extra.forEach { KV(it.name, it.result, vColor = if (it.status == 1) C.orange else if (it.status == 0) C.muted else C.text, bold = it.status == 2) }
        }
        FCard("Karşılıklı bilgi (gecikme 1–10)") {
            val mx = maxOf(r.mutualInfo.maxOrNull() ?: 0.0, r.miThreshold) * 1.15
            Canvas(Modifier.fillMaxWidth().height(90.dp)) {
                val bw = size.width / 10f
                r.mutualInfo.forEachIndexed { i, m ->
                    val h = (m / mx * size.height).toFloat()
                    drawRect(if (m > r.miThreshold) C.orange else C.lightBlue, Offset(i * bw + bw * 0.15f, size.height - h),
                        androidx.compose.ui.geometry.Size(bw * 0.7f, h))
                }
                val ty = (size.height - r.miThreshold / mx * size.height).toFloat()
                drawLine(C.dim, Offset(0f, ty), Offset(size.width, ty), 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
            }
            Row(Modifier.fillMaxWidth()) { (1..10).forEach { Text("$it", color = C.dim, fontSize = 10.sp, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center) } }
            Muted("Kesikli çizgi: anlamlılık sınırı (%99)")
        }
        FCard("🧭 Karar", borderColor = C.orange.copy(alpha = 0.35f)) {
            Text(r.verdict, color = C.text, fontSize = 13.sp)
            Row(Modifier.fillMaxWidth().padding(top = 8.dp)) { NumButton("▶ Testleri yeniden çalıştır", C.blue, 14) { EngineHost.rerunDiscovery() } }
        }
    }
}

@Composable
private fun LineChart(series: List<Pair<List<Double>, Color>>, chance: Double) {
    val all = series.flatMap { it.first } + chance
    if (series.all { it.first.isEmpty() }) { Muted("Grafik için yeterli veri yok (en az 50 değerlendirilmiş tahmin)."); return }
    val lo = (all.minOrNull() ?: 0.0) - 0.03; val hi = (all.maxOrNull() ?: 1.0) + 0.03
    Row {
        Column(Modifier.width(32.dp).height(160.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text(pct(hi), color = C.dim, fontSize = 9.sp); Text(pct((hi + lo) / 2), color = C.dim, fontSize = 9.sp); Text(pct(lo), color = C.dim, fontSize = 9.sp)
        }
        Canvas(Modifier.weight(1f).height(160.dp)) {
            fun y(v: Double) = (size.height - (v - lo) / (hi - lo) * size.height).toFloat()
            drawLine(Color(0xFFAAAAAA), Offset(0f, y(chance)), Offset(size.width, y(chance)), 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f)))
            series.forEach { (s, c) ->
                if (s.size < 2) return@forEach
                val p = Path()
                s.forEachIndexed { i, v -> val x = i * size.width / (s.size - 1); if (i == 0) p.moveTo(x, y(v)) else p.lineTo(x, y(v)) }
                drawPath(p, c, style = Stroke(width = if (c == C.orange) 5f else 3f))
            }
        }
    }
}

@Composable
fun ChartScreen() {
    val ch by EngineHost.charts.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Tabs(listOf("Tek", "Çift", "Yan"), tab) { tab = it }
        val c = ch
        FCard("Kayan başarı (50'lik pencere)") {
            if (c != null) {
                val chance = when (tab) { 0 -> 0.25; 1 -> 0.5; else -> 0.75 }
                LineChart(listOf(c.kot[tab] to C.lightBlue, c.py[tab] to C.lightGreen, c.ref[tab] to C.orange), chance)
            }
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("━ Hakem", color = C.orange, fontSize = 12.sp); Text("━ Kotlin", color = C.lightBlue, fontSize = 12.sp)
                Text("━ Python", color = C.lightGreen, fontSize = 12.sp); Text("┄ şans", color = C.muted, fontSize = 12.sp)
            }
        }
        FCard("Sayı dağılımı") {
            val dist = c?.dist ?: IntArray(4)
            val mx = (dist.maxOrNull() ?: 1).coerceAtLeast(1)
            val cols = listOf(C.b1, C.b2, C.b3, C.b4)
            dist.forEachIndexed { i, n ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}", color = C.text, fontSize = 13.sp, modifier = Modifier.width(18.dp))
                    Box(Modifier.weight(1f).height(9.dp).clip(RoundedCornerShape(4.dp)).background(C.border)) {
                        Box(Modifier.fillMaxWidth(n.toFloat() / mx).fillMaxHeight().background(cols[i]))
                    }
                    Text("$n", color = C.text, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp).width(42.dp))
                }
            }
        }
        FCard("Güven kalibrasyonu") {
            val t = when (tab) { 0 -> c?.calSingle; 1 -> c?.calPair; else -> c?.calSide } ?: emptyList()
            if (t.isEmpty()) Muted("Henüz veri yok.")
            t.forEach { (lo, n, rate) -> KV("%${(lo * 100).toInt()}–${(lo * 100).toInt() + 5} denilen ($n)", "${pct(rate)} tuttu") }
            Muted("Kalibrasyon açıkken ekrandaki yüzde bu tabloya göre düzeltilir.", Modifier.padding(top = 4.dp))
        }
    }
}
