package fan.superai.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fan.superai.EngineHost
import fan.superai.overlay.OverlayService

class MainActivity : ComponentActivity() {

    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent { FanTheme { Root(this) } }
    }

    fun toggleOverlay() {
        if (OverlayService.running.value) { OverlayService.stop(this); return }
        if (!OverlayService.canDraw(this)) {
            Toast.makeText(this, "Lütfen 'Diğer uygulamaların üzerinde göster' iznini verin", Toast.LENGTH_LONG).show()
            startActivity(Intent(AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        OverlayService.start(this)
    }

    override fun onStop() {
        super.onStop()
        EngineHost.persist()
    }
}

@Composable
private fun Root(act: MainActivity) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().background(C.bg)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                0 -> HomeScreen(onOverlay = { act.toggleOverlay() })
                1 -> CouncilScreen()
                2 -> DiscoveryScreen()
                3 -> ChartScreen()
                else -> SettingsScreen()
            }
        }
        Row(Modifier.fillMaxWidth().height(58.dp).background(C.bar)) {
            listOf("🏠" to "Ana", "🏛️" to "Meclisler", "🔍" to "Keşif", "📈" to "Grafik", "⚙️" to "Ayarlar")
                .forEachIndexed { i, (ic, l) ->
                    Column(Modifier.weight(1f).fillMaxSize().clickable { tab = i }.padding(top = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(ic, fontSize = 18.sp)
                        Text(l, fontSize = 10.sp, color = if (tab == i) C.lightBlue else C.dim, textAlign = TextAlign.Center)
                    }
                }
        }
    }
}

@Composable
fun BusyBanner(msg: String?) {
    if (msg == null) return
    Text(msg, color = Color.White, fontSize = 12.sp, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp).background(Color(0xFF3E2F10)).padding(8.dp))
}
