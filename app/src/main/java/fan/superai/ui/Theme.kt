package fan.superai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object C {
    val bg = Color(0xFF0F1726)
    val bar = Color(0xFF0D1320)
    val card = Color(0xFF16223A)
    val border = Color(0xFF22324F)
    val line = Color(0xFF1E2A3D)
    val text = Color(0xFFE6EDF6)
    val muted = Color(0xFF8A97AA)
    val dim = Color(0xFF6F7F96)
    val head = Color(0xFF8AA3C4)
    val blue = Color(0xFF1565C0)
    val kotlin = Color(0xFF1976D2)
    val python = Color(0xFF388E3C)
    val lightBlue = Color(0xFF64B5F6)
    val lightGreen = Color(0xFF81C784)
    val orange = Color(0xFFFFB74D)
    val purple = Color(0xFFE1BEE7)
    val danger = Color(0xFFFF8A80)
    val b1 = Color(0xFF4CAF50); val b2 = Color(0xFF2196F3); val b3 = Color(0xFFFF9800); val b4 = Color(0xFFF44336)
    val del = Color(0xFF78909C)
}

@Composable
fun FanTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = C.blue, background = C.bg, surface = C.card, onSurface = C.text, onBackground = C.text,
            secondary = C.lightBlue
        ),
        content = content
    )
}

@Composable
fun FCard(title: String? = null, borderColor: Color = C.border, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(14.dp)).background(C.card)
            .border(1.dp, borderColor, RoundedCornerShape(14.dp)).padding(12.dp)
    ) {
        if (title != null) Text(title.uppercase(), color = C.head, fontSize = 11.sp, letterSpacing = 0.6.sp,
            modifier = Modifier.padding(bottom = 8.dp))
        content()
    }
}

@Composable
fun KV(k: String, v: String, vColor: Color = C.text, bold: Boolean = true, mono: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        Text(k, color = C.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(v, color = vColor, fontSize = 13.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default)
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(C.line))
}

@Composable
fun Muted(t: String, modifier: Modifier = Modifier) = Text(t, color = C.muted, fontSize = 12.sp, modifier = modifier)

@Composable
fun Pill(t: String, bg: Color = C.border, fg: Color = Color(0xFF9FB3CC), onClick: (() -> Unit)? = null) {
    Text(t, color = fg, fontSize = 11.sp,
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(bg)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 9.dp, vertical = 4.dp))
}

@Composable
fun Tabs(items: List<String>, sel: Int, onSel: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEachIndexed { i, t ->
            Text(t, color = if (i == sel) Color.White else C.muted, fontSize = 12.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(9.dp))
                    .background(if (i == sel) C.blue else C.card).clickable { onSel(i) }.padding(vertical = 8.dp))
        }
    }
}

@Composable
fun RowScope.NumButton(label: String, color: Color, fontSize: Int = 22, onClick: () -> Unit) {
    Box(Modifier.weight(1f).height(54.dp).clip(RoundedCornerShape(12.dp)).background(color).clickable { onClick() },
        contentAlignment = Alignment.Center) {
        Text(label, color = Color.White, fontSize = fontSize.sp, fontWeight = FontWeight.Bold)
    }
}

fun pct(x: Double) = "%${(x * 100).toInt()}"
