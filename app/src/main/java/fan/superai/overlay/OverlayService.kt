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
import android.view.Gravity
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import fan.superai.EngineHost
import fan.superai.R
import fan.superai.data.AppSettings
import fan.superai.data.Settings
import fan.superai.ui.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

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

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        startAsForeground()
        _running.value = true
        lifecycleScope.launch {
            combine(EngineHost.state, EngineHost.busy, Settings.flow) { st, busy, s -> Triple(st, busy, s) }.collect { (st, busy, s) ->
                val b = builtWith
                if (b == null || b.overlayHorizontal != s.overlayHorizontal || b.overlayTextScale != s.overlayTextScale ||
                    b.overlayAlpha != s.overlayAlpha || b.showRecent != s.showRecent || b.vibrate != s.vibrate || b.overlayDetail != s.overlayDetail) {
                    buildView(s)
                }
                view?.update(st, busy)
            }
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
                params?.let { p ->
                    p.x += dx; p.y += dy; posX = p.x; posY = p.y
                    try { wm.updateViewLayout(view, p) } catch (_: Exception) {}
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
