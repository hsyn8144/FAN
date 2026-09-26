package fan.superai.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings as AndroidSettings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import fan.superai.Echo
import fan.superai.EngineHost
import fan.superai.R
import fan.superai.data.AppSettings
import fan.superai.data.Settings
import fan.superai.engine.EngineState
import fan.superai.ui.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Kartı ekran sınırları içinde tutar: sürükleme ya da ekran döndürme sonrası
 * sayı düğmelerinin ekran dışında kalıp "çalışmıyor" gibi görünmesini engeller.
 * Kart ekrandan büyükse sol/üst kenara yapıştırılır (düğmeler yine görünür).
 */
fun clampOverlayPos(x: Int, y: Int, w: Int, h: Int, screenW: Int, screenH: Int): IntArray {
    val maxX = (screenW - w).coerceAtLeast(0)
    val maxY = (screenH - h).coerceAtLeast(0)
    return intArrayOf(x.coerceIn(0, maxX), y.coerceIn(0, maxY))
}

class OverlayService : LifecycleService() {

    companion object {
        private const val CHANNEL = "fan_super_overlay"
        private const val NOTIF_ID = 42
        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running

        fun canDraw(ctx: Context) = AndroidSettings.canDrawOverlays(ctx)

        fun start(ctx: Context) {
            val i = Intent(ctx, OverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i) else ctx.startService(i)
        }
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, OverlayService::class.java)) }
    }

    private lateinit var wm: WindowManager
    private var view: OverlayView? = null
    private var params: WindowManager.LayoutParams? = null
    private var builtWith: AppSettings? = null
    private var posX = 40
    private var posY = 240
    private var lastW = -1
    private var lastH = -1

    private fun screenSize(): IntArray {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        return intArrayOf(dm.widthPixels, dm.heightPixels)
    }

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        startAsForeground()
        _running.value = true
        lifecycleScope.launch {
            combine(EngineHost.state, EngineHost.busy, EngineHost.echo, Settings.flow) { st, busy, e, s ->
                Quad(st, busy, e, s)
            }.collect { (st, busy, e, s) ->
                val b = builtWith
                if (b == null || b.overlayHorizontal != s.overlayHorizontal || b.overlayTextScale != s.overlayTextScale ||
                    b.overlayAlpha != s.overlayAlpha || b.showRecent != s.showRecent || b.vibrate != s.vibrate || b.overlayDetail != s.overlayDetail) {
                    buildView(s)
                }
                view?.update(st, busy, e.values)
                // Kart büyüdü/küçüldüyse (detay paneli, döndürme) ekran içinde kalmasını sağla.
                val v = view
                if (v != null && (v.width != lastW || v.height != lastH)) {
                    lastW = v.width; lastH = v.height
                    clampIntoScreen()
                }
            }
        }
    }

    /** combine() dört akış için kendi veri sınıfımız (Triple yetmiyor). */
    private data class Quad(val st: EngineState?, val busy: String?, val echo: Echo, val s: AppSettings)

    /** Ölçümden sonra kartı ekran içine çeker. */
    private fun clampIntoScreen() {
        val v = view ?: return
        val p = params ?: return
        val scr = screenSize()
        val w = if (v.width > 0) v.width else v.measuredWidth
        val h = if (v.height > 0) v.height else v.measuredHeight
        if (w <= 0 || h <= 0) return
        val c = clampOverlayPos(p.x, p.y, w, h, scr[0], scr[1])
        if (c[0] != p.x || c[1] != p.y) {
            p.x = c[0]; p.y = c[1]; posX = p.x; posY = p.y
            try { wm.updateViewLayout(v, p) } catch (_: Exception) {}
        }
    }

    private fun startAsForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.overlay_channel), NotificationManager.IMPORTANCE_LOW))
        }
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.overlay_running))
            .setSmallIcon(R.drawable.ic_fan_super)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIF_ID, n, type)
    }

    private fun buildView(s: AppSettings) {
        view?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        val v = OverlayView(this, s,
            onNumber = { EngineHost.add(it) },
            onDelete = { EngineHost.undo() },
            onDrag = { dx, dy ->
                val p = params
                val cur = view
                if (p != null && cur != null) {
                    val scr = screenSize()
                    val w = if (cur.width > 0) cur.width else cur.measuredWidth
                    val h = if (cur.height > 0) cur.height else cur.measuredHeight
                    val c = clampOverlayPos(p.x + dx, p.y + dy, w, h, scr[0], scr[1])
                    p.x = c[0]; p.y = c[1]; posX = p.x; posY = p.y
                    try { wm.updateViewLayout(cur, p) } catch (_: Exception) {}
                }
            })
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = posX; y = posY }
        try {
            wm.addView(v, p)
            view = v; params = p; builtWith = s
            // Ölçüm bittikten sonra kartı ekran içine al (düğmeler erişilebilir kalsın).
            v.post { clampIntoScreen() }
        } catch (e: Exception) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        view?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        view = null
        _running.value = false
        EngineHost.persist()
        super.onDestroy()
    }
}
