package fan.superai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fan.superai.EngineHost
import fan.superai.overlay.OverlayService
import fan.superai.recentWithEcho

@Composable
fun HomeScreen(onOverlay: () -> Unit) {
    val st by EngineHost.state.collectAsState()
    val busy by EngineHost.busy.collectAsState()
    val disc by EngineHost.discovery.collectAsState()
    val pyErr by EngineHost.pyError.collectAsState()
    val running by OverlayService.running.collectAsState()
    val echo by EngineHost.echo.collectAsState()
    val pending by EngineHost.pending.collectAsState()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("FAN SUPER", color = C.text, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(6.dp)); Pill("v1.0")
            Spacer(Modifier.weight(1f))
            Pill(if (running) "● Overlay AÇIK" else "○ Overlay KAPALI", bg = if (running) C.blue else C.border,
                fg = Color.White, onClick = onOverlay)
        }
        BusyBanner(busy)
        pyErr?.let { Text("⚠️ $it", color = C.danger, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp)) }

        val v = st?.verdict
        FCard("🎯 Tahmin") {
            if (st?.learning == true) {
                Text("Öğreniyor… ${st?.count}/50", color = C.muted, fontSize = 22.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            } else {
                Text(v?.label?.replace("/", " / ") ?: "--", color = if (v?.secondary == null) Color(0xFFA5D6A7) else C.text,
                    fontSize = 54.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                Muted("güven ${v?.let { pct(it.confidence) } ?: "--"}", Modifier.fillMaxWidth().then(Modifier))
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Text("⭐ ", fontSize = 18.sp)
                val hit = st?.lastSideHit == true
                Text((v?.sideLabel?.replace("•", " • ") ?: "--") + if (hit) " =" else "", color = if (hit) C.lightGreen else C.purple,
                    fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("  ${v?.let { pct(it.sideConfidence) } ?: ""}", color = C.muted, fontSize = 13.sp)
            }
        }

        FCard("⚖️ Baş Hakem") {
            val wk = v?.weightK ?: 1.0
            Row(Modifier.fillMaxWidth().height(22.dp).clip(RoundedCornerShape(8.dp))) {
                if (wk > 0.001) Box(Modifier.weight(wk.toFloat().coerceAtLeast(0.05f)).fillMaxHeight().background(C.kotlin), contentAlignment = Alignment.Center) {
                    Text("🔵 ${pct(wk)}", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold)
                }
                if (1 - wk > 0.001) Box(Modifier.weight((1 - wk).toFloat().coerceAtLeast(0.05f)).fillMaxHeight().background(C.python), contentAlignment = Alignment.Center) {
                    Text("🐍 ${pct(1 - wk)}", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(6.dp))
            fun lab(p: DoubleArray?): String {
                if (p == null) return "--"
                val o = fan.superai.engine.P.order(p)
                return "${o[0] + 1}/${o[1] + 1} · ${pct(p[o[0]] + p[o[1]])}"
            }
            KV("🔵 Kotlin Meclisi", lab(st?.kotlinProbs))
            KV("🐍 Python Meclisi", if (st?.pythonProbs == null) "hazırlanıyor / kapalı" else lab(st?.pythonProbs))
        }

        val sc = st?.scores
        FCard("Başarı (son ${sc?.n ?: 0}) · şans çizgisiyle") {
            @Composable fun row(k: String, x: Double?, chance: Int) = KV(k, "${x?.let { pct(it) } ?: "--"}  / şans %$chance",
                vColor = when { x == null -> C.text; x > chance / 100.0 + 0.02 -> C.lightGreen; x < chance / 100.0 - 0.02 -> C.danger; else -> C.text })
            row("Tek", sc?.top1, 25)
            row("Çift (ikisinden biri)", sc?.top2, 50)
            row("Yan (herhangi)", sc?.sideAny, 75)
            row("Yan (ikisi birden)", sc?.sideBoth, 25)
        }

        FCard(if (pending > 0) "Veri · ⏳ $pending işlem sırada" else "Veri") {
            KV("Kayıt", "${(st?.count ?: 0) + echo.values.size}")
            // Düğmeye basıldığı anda görünür (echo), motor sonucu gelince gerçek değerle değişir.
            KV("Son 6", recentWithEcho(st, echo).joinToString("  ").ifEmpty { "-" }, mono = true)
            KV("🔍 Keşif özeti", disc?.let { if (it.lag2Watch) "gecikme-2 takipte" else it.verdict.take(28) } ?: "-", vColor = C.orange, bold = false)
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumButton("1", C.b1) { EngineHost.add(1) }
                NumButton("2", C.b2) { EngineHost.add(2) }
                NumButton("3", C.b3) { EngineHost.add(3) }
                NumButton("4", C.b4) { EngineHost.add(4) }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp)) { NumButton("⌫ Geri al", C.del, 15) { EngineHost.undo() } }
            Muted("Sayı düğmeleri anında işlenir; ⌫ son girişi veriyi bozmadan geri alır.", Modifier.padding(top = 6.dp))
        }
    }
}
