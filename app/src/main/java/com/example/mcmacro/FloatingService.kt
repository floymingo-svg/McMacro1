package com.example.mcmacro

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Владеет тремя НЕЗАВИСИМЫМИ окнами оверлея:
 *   1. панель настроек (PanelView)
 *   2. кнопка HIT (HitButtonView)
 *   3. маркер цели (TargetMarkerView) — только в режиме выбора цели
 *
 * Каждое окно маленькое (wrap_content) и имеет флаги
 * FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL | FLAG_SPLIT_TOUCH.
 * Касания вне окон уходят в игру напрямую — камера работает параллельно
 * с зажатой кнопкой HIT без какого-либо проброса событий.
 */
class FloatingService : Service(), PanelView.Host, HitButtonView.Host {

    companion object {
        const val ACTION_STOP = "com.example.mcmacro.STOP"
        private const val CHANNEL_ID = "macro"
        private const val NOTIF_ID = 1

        @Volatile
        var running = false
    }

    private lateinit var wm: WindowManager
    private lateinit var prefs: Prefs
    private lateinit var engine: ClickEngine
    private lateinit var acc: AccessibilityClickBackend
    private lateinit var shz: ShizukuClickBackend
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var panel: PanelView? = null
    private lateinit var panelLp: WindowManager.LayoutParams
    private var hit: HitButtonView? = null
    private lateinit var hitLp: WindowManager.LayoutParams
    private var marker: TargetMarkerView? = null
    private var markerLp: WindowManager.LayoutParams? = null
    private var moveMode = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs = Prefs(this)
        acc = AccessibilityClickBackend()
        shz = ShizukuClickBackend(this)
        engine = ClickEngine(scope, { currentBackend() }, { target() }).also {
            it.cps = prefs.cps
            it.jitterPercent = prefs.jitter
        }
        if (prefs.useShizuku) shz.bind()
        running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground()
        if (panel == null) setupWindows()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        engine.stop()
        runCatching { panel?.let { wm.removeView(it) } }
        runCatching { hit?.let { wm.removeView(it) } }
        runCatching { marker?.let { wm.removeView(it) } }
        panel = null; hit = null; marker = null
        shz.unbind()
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ backend

    private fun currentBackend(): ClickBackend = if (prefs.useShizuku) shz else acc

    private fun target(): Pair<Float, Float>? =
        if (prefs.targetX >= 0f && prefs.targetY >= 0f) prefs.targetX to prefs.targetY else null

    // ------------------------------------------------------------------ windows

    private fun newLp(x: Int, y: Int, w: Int, h: Int) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or        // не забирает фокус у игры
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or      // касания вне окна идут вниз
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_SPLIT_TOUCH,            // разные пальцы -> разные окна
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        this.x = x
        this.y = y
        if (Build.VERSION.SDK_INT >= 28) {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private fun setupWindows() {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        val dm = resources.displayMetrics

        // 1. панель
        panelLp = newLp(
            prefs.panelX, prefs.panelY,
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT
        )
        panel = PanelView(this, prefs, this).also { wm.addView(it, panelLp) }

        // 2. кнопка HIT
        val size = dp(84)
        val hx = if (prefs.hitX >= 0) prefs.hitX else dm.widthPixels - dp(220)
        val hy = if (prefs.hitY >= 0) prefs.hitY else dm.heightPixels / 2
        hitLp = newLp(hx, hy, size, size)
        hit = HitButtonView(this, this).also { wm.addView(it, hitLp) }
    }

    // --- PanelView.Host

    override fun dragListener(onTap: (() -> Unit)?): View.OnTouchListener =
        DragTouchListener(wm, panelLp, ViewConfiguration.get(this).scaledTouchSlop, onTap) {
            prefs.panelX = panelLp.x
            prefs.panelY = panelLp.y
        }

    override fun onCps(value: Int) {
        engine.cps = value
        prefs.cps = value
    }

    override fun onJitter(value: Int) {
        engine.jitterPercent = value
        prefs.jitter = value
    }

    override fun onTargetToggle(): Boolean {
        return if (marker == null) {
            showMarker()
            true
        } else {
            saveMarker()
            false
        }
    }

    override fun onMoveToggle(): Boolean {
        moveMode = !moveMode
        hit?.refresh()
        return moveMode
    }

    override fun onBackend(wantShizuku: Boolean): Boolean {
        if (wantShizuku) {
            if (shz.bind()) {
                prefs.useShizuku = true
            } else {
                prefs.useShizuku = false
                toast("Shizuku не запущен или нет разрешения — оставляю Accessibility")
            }
        } else {
            prefs.useShizuku = false
        }
        return prefs.useShizuku
    }

    override fun onClose() {
        stopSelf()
    }

    // --- HitButtonView.Host

    override fun onHold(down: Boolean) {
        if (!down) {
            engine.stop()
            return
        }
        val backend = currentBackend()
        when {
            target() == null -> toast("Сначала задай цель (🎯)")
            !backend.isReady -> toast("Метод «${backend.label}» не готов — проверь разрешения в приложении")
            else -> engine.start()
        }
    }

    override fun isMoveMode(): Boolean = moveMode

    override fun moveListener(): View.OnTouchListener =
        DragTouchListener(wm, hitLp, ViewConfiguration.get(this).scaledTouchSlop, null) {
            prefs.hitX = hitLp.x
            prefs.hitY = hitLp.y
        }

    // ------------------------------------------------------------------ target marker

    private fun showMarker() {
        val size = dp(56)
        val dm = resources.displayMetrics
        val startX = if (prefs.targetX >= 0f) (prefs.targetX - size / 2f).toInt() else dm.widthPixels / 2
        val startY = if (prefs.targetY >= 0f) (prefs.targetY - size / 2f).toInt() else dm.heightPixels / 2
        val lp = newLp(startX, startY, size, size)
        val view = TargetMarkerView(this)
        view.setOnTouchListener(
            DragTouchListener(wm, lp, ViewConfiguration.get(this).scaledTouchSlop, null) {}
        )
        wm.addView(view, lp)
        marker = view
        markerLp = lp
        toast("Перетащи перекрестие на точку удара и нажми «Сохранить цель»")
    }

    private fun saveMarker() {
        val view = marker ?: return
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        prefs.targetX = loc[0] + view.width / 2f
        prefs.targetY = loc[1] + view.height / 2f
        // Маркер ОБЯЗАТЕЛЬНО убираем: иначе он перехватит тапы макроса в этой точке.
        runCatching { wm.removeView(view) }
        marker = null
        markerLp = null
        toast("Цель сохранена: ${prefs.targetX.toInt()}, ${prefs.targetY.toInt()}")
    }

    // ------------------------------------------------------------------ notification

    private fun startInForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Макрос", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, FloatingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("MC Macro работает")
            .setContentText("Плавающее окно активно")
            .setOngoing(true)
            .addAction(0, "Остановить", stop)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIF_ID, n, type)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int) = this.run { (v * resources.displayMetrics.density).toInt() }
}
