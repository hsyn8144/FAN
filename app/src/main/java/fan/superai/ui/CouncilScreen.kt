package fan.superai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fan.superai.EngineHost
import fan.superai.engine.MemberStat
import fan.superai.engine.P

@Composable
fun CouncilScreen() {
    val st by EngineHost.state.collectAsState()
    val busy by EngineHost.busy.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var detail by remember { mutableStateOf<MemberStat?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Tabs(listOf("🔵 Kotlin", "🐍 Python", "⭐ Yan"), tab) { tab = it }
        BusyBanner(busy)
        when (tab) {
            0 -> {
                MemberTable("🔵 Kotlin Meclisi · ${st?.kotlinStats?.size ?: 0} üye · " +
                    (if (fan.superai.data.Settings.value.fixedShare) "Fixed-Share Hedge" else "Hedge"),
                    st?.kotlinStats ?: emptyList()) { detail = it }
                FCard("🎯 Aktif kalıp (Kalıp Arama 2.0)") {
                    val k = st?.kalipInfo ?: emptyMap()
                    KV("Önde olan görünüm", k["view"] ?: "-")
                    KV("Uzunluk", k["len"] ?: "-")
                    KV("Eşleşme sayısı", k["matches"] ?: "-")
                    KV("Liderlik süresi", (k["since"] ?: "-") + " tur", bold = false)
                    Muted("Görünümler: Ham · Delta · Şekil · T/Ç · K/B · Ayna", Modifier.padding(top = 6.dp))
                }
                FCard("🌊 Rejim") { KV("Şu anki rejim", st?.regime ?: "-") }
            }
            1 -> {
                if (st?.pythonStats.isNullOrEmpty()) {
                    FCard("🐍 Python Meclisi") { Muted("Python meclisi hazırlanıyor ya da Ayarlar'dan kapatılmış.") }
                } else {
                    MemberTable("🐍 Python Meclisi · ${st?.pythonStats?.size ?: 0} üye · gömülü (Chaquopy)", st?.pythonStats ?: emptyList()) { detail = it }
                }
                FCard("⚙️ Python motoru") {
                    val i = st?.pythonInfo ?: emptyMap()
                    KV("Durum", i["status"]?.let { "● $it" } ?: "kapalı", vColor = if (i["status"] == "çalışıyor") C.lightGreen else C.muted)
                    KV("Ortalama adım süresi", i["ms"]?.let { "$it ms" } ?: "-")
                    KV("Öğrenilen kayıt", i["n"] ?: "-")
                    KV("numpy", i["numpy"] ?: "-")
                    KV("Derin öğrenme", if (i["dl"] == "true") "açık" else if (i["dl"] == "false") "kapalı" else "-")
                }
            }
            else -> {
                val v = st?.verdict
                FCard("⭐ Yan meclisi") {
                    fun oe(p: DoubleArray?) = p?.let { val o = P.oddProb(it); (if (o >= .5) "T" else "Ç") + " · " + pct(maxOf(o, 1 - o)) } ?: "--"
                    fun kb(p: DoubleArray?) = p?.let { val b = P.bigProb(it); (if (b >= .5) "B" else "K") + " · " + pct(maxOf(b, 1 - b)) } ?: "--"
                    Text("Tek / Çift", color = C.head, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                    KV("🔵 Kotlin", oe(st?.kotlinProbs)); KV("🐍 Python", oe(st?.pythonProbs)); KV("⚖️ Hakem", oe(v?.probs), vColor = C.purple)
                    Text("Küçük / Büyük", color = C.head, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                    KV("🔵 Kotlin", kb(st?.kotlinProbs)); KV("🐍 Python", kb(st?.pythonProbs)); KV("⚖️ Hakem", kb(v?.probs), vColor = C.purple)
                    Text("Sonuç", color = C.head, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                    KV("Rakamdan türetilen", "${v?.label ?: "--"} → ${v?.sideLabel ?: "--"}")
                }
                FCard("Yan başarısı (son ${st?.scores?.n ?: 0})") {
                    KV("Herhangi biri tuttu", "${st?.scores?.sideAny?.let { pct(it) } ?: "--"}  / şans %75")
                    KV("İkisi birden tuttu", "${st?.scores?.sideBoth?.let { pct(it) } ?: "--"}  / şans %25")
                }
            }
        }
    }

    detail?.let { m ->
        AlertDialog(onDismissRequest = { detail = null }, confirmButton = { TextButton(onClick = { detail = null }) { Text("Kapat") } },
            title = { Text(m.name) },
            text = {
                Column {
                    KV("Tek başarı (son ${m.n})", pct(m.top1)); KV("Çift başarı", pct(m.top2))
                    KV("Ağırlık", String.format("%.2f", m.weight))
                    KV("Durum", if (!m.enabled) "kapalı" else if (m.benched) "💤 yedek kulübesi" else "oyunda")
                    Muted("Şans çizgisi: tek %25, çift %50. Üye, son pencerede eşiğin altında kalırsa yedeğe düşer; formu düzelince döner.",
                        Modifier.padding(top = 8.dp))
                }
            }, containerColor = C.card)
    }
}

@Composable
private fun MemberTable(title: String, list: List<MemberStat>, onClick: (MemberStat) -> Unit) {
    FCard(title) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text("#", color = C.head, fontSize = 11.sp, modifier = Modifier.width(28.dp))
            Text("Üye", color = C.head, fontSize = 11.sp, modifier = Modifier.weight(1f))
            Text("Tek", color = C.head, fontSize = 11.sp, modifier = Modifier.width(42.dp))
            Text("Çift", color = C.head, fontSize = 11.sp, modifier = Modifier.width(42.dp))
            Text("Ağr.", color = C.head, fontSize = 11.sp, modifier = Modifier.width(40.dp))
        }
        var rank = 0
        list.forEach { m ->
            val active = m.enabled && !m.benched
            if (active) rank++
            val medal = when { !m.enabled -> "⛔"; m.benched -> "💤"; rank == 1 -> "🥇"; rank == 2 -> "🥈"; rank == 3 -> "🥉"; else -> "$rank" }
            val col = if (active) C.text else C.dim
            Box(Modifier.fillMaxWidth().height(1.dp).background(C.line))
            Row(Modifier.fillMaxWidth().clickable { onClick(m) }.padding(vertical = 6.dp)) {
                Text(medal, color = col, fontSize = 12.sp, modifier = Modifier.width(28.dp))
                Text(m.name, color = col, fontSize = 12.sp, modifier = Modifier.weight(1f), fontWeight = if (rank == 1 && active) FontWeight.Bold else FontWeight.Normal)
                Text(pct(m.top1), color = col, fontSize = 12.sp, modifier = Modifier.width(42.dp))
                Text(pct(m.top2), color = col, fontSize = 12.sp, modifier = Modifier.width(42.dp))
                Text(String.format("%.2f", m.weight), color = col, fontSize = 12.sp, modifier = Modifier.width(40.dp))
            }
        }
        Muted("💤 = yedek kulübesi (oy vermiyor, formu düzelirse döner) · Üyeye dokun: detay", Modifier.padding(top = 6.dp))
    }
}
