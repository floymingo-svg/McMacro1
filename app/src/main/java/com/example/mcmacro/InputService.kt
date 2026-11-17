package com.example.mcmacro

import android.content.Context
import android.os.IBinder
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import kotlin.system.exitProcess

/**
 * Shizuku UserService. Запускается в отдельном процессе с правами shell,
 * поэтому может вызывать скрытый IInputManager.injectInputEvent().
 *
 * ВАЖНО: нужен конструктор без аргументов или с Context.
 */
class InputService() : IInputService.Stub() {

    @Suppress("UNUSED_PARAMETER")
    constructor(context: Context) : this()

    private val inputManager: Any by lazy {
        val sm = Class.forName("android.os.ServiceManager")
        val binder = sm.getMethod("getService", String::class.java).invoke(null, "input") as IBinder
        val stub = Class.forName("android.hardware.input.IInputManager\$Stub")
        stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!
    }

    private val injectMethod by lazy {
        inputManager.javaClass.getMethod(
            "injectInputEvent",
            InputEvent::class.java,
            Int::class.javaPrimitiveType
        )
    }

    override fun destroy() {
        exitProcess(0)
    }

    override fun tap(x: Float, y: Float, holdMs: Int) {
        val down = SystemClock.uptimeMillis()
        inject(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0))
        SystemClock.sleep(holdMs.toLong())
        inject(MotionEvent.obtain(down, down + holdMs, MotionEvent.ACTION_UP, x, y, 0))
    }

    private fun inject(ev: MotionEvent) {
        try {
            ev.source = InputDevice.SOURCE_TOUCHSCREEN
            // 0 = INJECT_INPUT_EVENT_MODE_ASYNC
            injectMethod.invoke(inputManager, ev, 0)
        } catch (_: Throwable) {
            // сигнатура скрытого API могла измениться в новой версии Android
        } finally {
            ev.recycle()
        }
    }
}
