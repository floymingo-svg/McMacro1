package com.example.mcmacro

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.IBinder
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Display
import android.view.InputDevice
import kotlinx.coroutines.delay
import rikka.shizuku.Shizuku

/**
 * Тапы через Shizuku: IInputManager.injectInputEvent() из процесса shell.
 * Это единственный способ доставки тапов в приложении.
 */
class ShizukuClickBackend(private val ctx: Context) : ClickBackend {

    override val label = "Shizuku"

    @Volatile
    private var svc: IInputService? = null

    @Volatile
    private var deviceId = -1

    override val isReady: Boolean
        get() = svc?.asBinder()?.pingBinder() == true

    // version(2): при смене кода сервиса Shizuku обязан перезапустить процесс
    private val args = Shizuku.UserServiceArgs(
        ComponentName(ctx.packageName, InputService::class.java.name)
    )
        .daemon(false)
        .processNameSuffix("input")
        .debuggable(false)
        .version(3)

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            svc = if (binder != null && binder.pingBinder()) IInputService.Stub.asInterface(binder) else null
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            svc = null
        }
    }

    fun permissionGranted(): Boolean = try {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    /** Привязать UserService. Возвращает false, если Shizuku недоступен. */
    fun bind(): Boolean {
        if (!permissionGranted()) return false
        if (svc == null) {
            try {
                Shizuku.bindUserService(args, conn)
            } catch (_: Throwable) {
                return false
            }
        }
        return true
    }

    fun unbind() {
        try {
            Shizuku.unbindUserService(args, conn, true)
        } catch (_: Throwable) {
        }
        svc = null
    }

    /** Короткий текст состояния для панели (блокирующий вызов — не из UI-потока). */
    fun statusText(): String = try {
        svc?.status() ?: "сервис не запущен"
    } catch (_: Throwable) {
        "сервис не отвечает"
    }

    fun diagnose(): String = try {
        svc?.diagnose() ?: "сервис не запущен"
    } catch (t: Throwable) {
        "диагностика недоступна: ${t.javaClass.simpleName}"
    }

    /** Передаёт сервису размер/поворот экрана и id тачскрина. */
    override suspend fun prepare() {
        val s = svc ?: return
        try {
            if (deviceId < 0) {
                var name = ""
                var tries = 0
                while (name.isEmpty() && tries < 10) {
                    name = s.touchName() ?: ""
                    if (name.isEmpty()) delay(150)
                    tries++
                }
                deviceId = resolveDevice(name)
            }
            val dm = DisplayMetrics()
            val display = (ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
                .getDisplay(Display.DEFAULT_DISPLAY)
            @Suppress("DEPRECATION")
            display.getRealMetrics(dm)
            s.configure(dm.widthPixels, dm.heightPixels, display.rotation, deviceId)
        } catch (_: Throwable) {
            // без prepare сервис работает в режиме совместимости
        }
    }

    private fun resolveDevice(name: String): Int {
        if (name.isEmpty()) return -1
        for (id in InputDevice.getDeviceIds()) {
            val d = InputDevice.getDevice(id) ?: continue
            if (d.name == name && d.supportsSource(InputDevice.SOURCE_TOUCHSCREEN)) return id
        }
        return -1
    }

    override fun batchSizeFor(cps: Int): Int = 1

    override suspend fun tapSequence(x: Float, y: Float, offsetsMs: LongArray, holdMs: Int) {
        val s = svc ?: return
        val t0 = SystemClock.elapsedRealtime()
        for (offset in offsetsMs) {
            val wait = t0 + offset - SystemClock.elapsedRealtime()
            if (wait > 0) delay(wait)
            try {
                s.tap(x, y, holdMs)
            } catch (_: Throwable) {
                svc = null
                return
            }
        }
    }
}
