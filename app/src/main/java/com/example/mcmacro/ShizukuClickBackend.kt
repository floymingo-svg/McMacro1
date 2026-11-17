package com.example.mcmacro

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.SystemClock
import kotlinx.coroutines.delay
import rikka.shizuku.Shizuku

/**
 * Тапы через Shizuku: IInputManager.injectInputEvent() из процесса shell.
 * Нет лимита на stroke'и и задержки жестов Accessibility, поэтому
 * подходит для высоких CPS.
 */
class ShizukuClickBackend(private val ctx: Context) : ClickBackend {

    override val label = "Shizuku"

    @Volatile
    private var svc: IInputService? = null

    override val isReady: Boolean
        get() = svc?.asBinder()?.pingBinder() == true

    private val args = Shizuku.UserServiceArgs(
        ComponentName(ctx.packageName, InputService::class.java.name)
    )
        .daemon(false)
        .processNameSuffix("input")
        .debuggable(false)
        .version(1)

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
