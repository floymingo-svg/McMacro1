package com.example.mcmacro

import android.content.Context
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import java.io.FileInputStream
import java.lang.reflect.Method
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.system.exitProcess

/**
 * Shizuku UserService. Запускается в отдельном процессе с правами shell,
 * поэтому может вызывать скрытый IInputManager.injectInputEvent().
 *
 * ПОЧЕМУ КАМЕРА ГЛОХЛА ПРИ ЗАЖАТОЙ КНОПКЕ УДАРА
 * Если инъецировать обычный ACTION_DOWN от «чужого» устройства (deviceId = 0),
 * InputDispatcher считает это началом НОВОГО жеста: он сбрасывает состояние касаний
 * и отбрасывает дальнейшие MOVE настоящего пальца на камере.
 *
 * КАК ЭТО ЧИНИТСЯ ЗДЕСЬ
 * 1. Находим настоящий тачскрин (getevent -lp) и читаем его сырые события
 *    из /dev/input/eventN (shell имеет на это права) — так знаем, где сейчас
 *    настоящие пальцы и какие pointer id им назначил Android.
 * 2. Тап шлём с deviceId настоящего тачскрина как ACTION_POINTER_DOWN / POINTER_UP
 *    для свободного pointer id, а в событие кладём ещё и текущие позиции
 *    настоящих пальцев. Для системы это выглядит как ещё один палец на том же экране.
 * Если что-то из этого недоступно — откат на старый режим (одиночный тап).
 *
 * ВАЖНО: нужен конструктор без аргументов или с Context.
 */
class InputService() : IInputService.Stub() {

    @Suppress("UNUSED_PARAMETER")
    constructor(context: Context) : this()

    private class Slot {
        var tracking = -1 // ABS_MT_TRACKING_ID, -1 = палец поднят
        var x = 0
        var y = 0
        var id = -1       // pointer id, как его назначит InputReader
    }

    private class Touch(
        val path: String,
        val name: String,
        val minX: Int,
        val maxX: Int,
        val minY: Int,
        val maxY: Int,
    )

    private class Finger(val id: Int, val x: Float, val y: Float)

    private val lock = Any()
    private val slots = Array(32) { Slot() }
    private val usedIds = BooleanArray(32)
    private var wasDown = false
    private var gestureDownTime = 0L

    @Volatile private var touch: Touch? = null
    @Volatile private var reading = false
    @Volatile private var note = "запуск…"
    @Volatile private var dispW = 0
    @Volatile private var dispH = 0
    @Volatile private var rotation = 0
    @Volatile private var deviceId = -1
    @Volatile private var lastError = ""

    init {
        Thread({
            try {
                readerMain()
            } catch (t: Throwable) {
                reading = false
                note = "чтение тача: ${t.javaClass.simpleName} ${t.message ?: ""}".trim()
            }
        }, "mc-touch").apply {
            isDaemon = true
            start()
        }
    }

    // ------------------------------------------------------------------ AIDL

    override fun destroy() {
        exitProcess(0)
    }

    override fun touchName(): String = touch?.name ?: ""

    override fun configure(width: Int, height: Int, rotation: Int, deviceId: Int) {
        dispW = width
        dispH = height
        this.rotation = rotation
        this.deviceId = deviceId
    }

    override fun status(): String {
        val t = touch
        val mode = if (isMerged()) "мульти-тач ✔" else "режим совместимости ⚠"
        val err = if (lastError.isEmpty()) "" else " | $lastError"
        return if (t == null) "тач: $note" else "тач: ${t.name} • $mode$err"
    }

    private fun isMerged(): Boolean = reading && touch != null && deviceId >= 0 && dispW > 0

    override fun tap(x: Float, y: Float, holdMs: Int) {
        if (!isMerged()) {
            simpleTap(x, y, holdMs)
            return
        }

        val (real1, downTime) = synchronized(lock) { realFingers() to gestureDownTime }
        val inj = freeId(real1)
        val t0 = SystemClock.uptimeMillis()

        if (real1.isEmpty()) {
            send(MotionEvent.ACTION_DOWN, t0, t0, emptyList(), inj, x, y)
        } else {
            send(MotionEvent.ACTION_POINTER_DOWN, if (downTime > 0) downTime else t0, t0, real1, inj, x, y)
        }

        SystemClock.sleep(holdMs.toLong())

        val real2 = synchronized(lock) { realFingers() }
        val t1 = SystemClock.uptimeMillis()
        if (real2.isEmpty()) {
            send(MotionEvent.ACTION_UP, t0, t1, emptyList(), inj, x, y)
        } else {
            send(MotionEvent.ACTION_POINTER_UP, if (downTime > 0) downTime else t0, t1, real2, inj, x, y)
        }
    }

    // ------------------------------------------------------------------ инъекция

    /** Старый режим: одиночный тап от «виртуального» устройства. */
    private fun simpleTap(x: Float, y: Float, holdMs: Int) {
        val down = SystemClock.uptimeMillis()
        send(MotionEvent.ACTION_DOWN, down, down, emptyList(), 0, x, y, simple = true)
        SystemClock.sleep(holdMs.toLong())
        send(MotionEvent.ACTION_UP, down, down + holdMs, emptyList(), 0, x, y, simple = true)
    }

    /**
     * Собирает событие: настоящие пальцы [real] + наш палец [injId] в точке (x, y).
     * Для POINTER_DOWN/UP индекс нашего пальца кодируется в action.
     */
    private fun send(
        action: Int,
        downTime: Long,
        eventTime: Long,
        real: List<Finger>,
        injId: Int,
        x: Float,
        y: Float,
        simple: Boolean = false,
    ) {
        val count = real.size + 1
        val props = Array(count) { MotionEvent.PointerProperties() }
        val coords = Array(count) { MotionEvent.PointerCoords() }
        for ((i, f) in real.withIndex()) {
            props[i].id = f.id
            props[i].toolType = MotionEvent.TOOL_TYPE_FINGER
            coords[i].x = f.x
            coords[i].y = f.y
            coords[i].pressure = 1f
            coords[i].size = 1f
        }
        val last = count - 1
        props[last].id = injId
        props[last].toolType = MotionEvent.TOOL_TYPE_FINGER
        coords[last].x = x
        coords[last].y = y
        coords[last].pressure = 1f
        coords[last].size = 1f

        val fullAction =
            if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP)
                action or (last shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            else action

        val dev = if (simple) 0 else deviceId
        val ev = MotionEvent.obtain(
            downTime, eventTime, fullAction, count, props, coords,
            0, 0, 1f, 1f, dev, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
        )
        try {
            injectEvent(ev)
        } catch (t: Throwable) {
            lastError = "inject: ${t.javaClass.simpleName}"
        } finally {
            ev.recycle()
        }
    }

    private fun freeId(real: List<Finger>): Int {
        var id = 5
        while (id < 31 && real.any { it.id == id }) id++
        return id
    }

    private val inputManager: Any by lazy {
        val sm = Class.forName("android.os.ServiceManager")
        val binder = sm.getMethod("getService", String::class.java).invoke(null, "input") as IBinder
        val stub = Class.forName("android.hardware.input.IInputManager\$Stub")
        stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!
    }

    private var injectMethod: Method? = null
    private var injectToTarget = false

    private fun injectEvent(ev: InputEvent) {
        val im = inputManager
        var m = injectMethod
        if (m == null) {
            try {
                m = im.javaClass.getMethod(
                    "injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType
                )
                injectToTarget = false
            } catch (_: NoSuchMethodException) {
                m = im.javaClass.getMethod(
                    "injectInputEventToTarget", InputEvent::class.java,
                    Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
                )
                injectToTarget = true
            }
            injectMethod = m
        }
        // 0 = INJECT_INPUT_EVENT_MODE_ASYNC; -1 = любой целевой uid
        if (injectToTarget) m!!.invoke(im, ev, 0, -1) else m!!.invoke(im, ev, 0)
    }

    // ------------------------------------------------------------------ чтение сырых касаний

    private fun readerMain() {
        val t = findTouch()
        if (t == null) {
            note = "не найден через getevent"
            return
        }
        touch = t
        note = "открываю ${t.path}"
        FileInputStream(t.path).use { input ->
            reading = true
            note = "ok"
            readLoop(input)
        }
        reading = false
    }

    private fun readLoop(input: FileInputStream) {
        val size = if (Process.is64Bit()) 24 else 16
        val buf = ByteArray(size * 64)
        val bb = ByteBuffer.wrap(buf).order(ByteOrder.nativeOrder())
        var cur = 0
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            var off = 0
            while (off + size <= n) {
                val base = off + size - 8 // type(2) code(2) value(4) идут после timeval
                val type = bb.getShort(base).toInt() and 0xFFFF
                val code = bb.getShort(base + 2).toInt() and 0xFFFF
                val value = bb.getInt(base + 4)
                off += size
                synchronized(lock) {
                    when (type) {
                        EV_ABS -> when (code) {
                            ABS_MT_SLOT -> cur = value.coerceIn(0, slots.size - 1)
                            ABS_MT_TRACKING_ID -> slots[cur].tracking = value
                            ABS_MT_POSITION_X -> slots[cur].x = value
                            ABS_MT_POSITION_Y -> slots[cur].y = value
                        }

                        EV_SYN -> if (code == SYN_REPORT) commit()
                    }
                }
            }
        }
    }

    /** Конец пакета событий: освобождаем/выдаём pointer id так же, как InputReader. */
    private fun commit() {
        for (s in slots) {
            if (s.tracking < 0 && s.id >= 0) {
                usedIds[s.id] = false
                s.id = -1
            }
        }
        for (s in slots) {
            if (s.tracking >= 0 && s.id < 0) {
                val free = usedIds.indexOfFirst { !it }
                if (free >= 0) {
                    s.id = free
                    usedIds[free] = true
                }
            }
        }
        val any = slots.any { it.id >= 0 }
        if (any && !wasDown) gestureDownTime = SystemClock.uptimeMillis()
        wasDown = any
    }

    /** Настоящие пальцы в координатах экрана. Вызывать под lock. */
    private fun realFingers(): List<Finger> {
        val t = touch ?: return emptyList()
        val w = dispW.toFloat()
        val h = dispH.toFloat()
        val result = ArrayList<Finger>()
        for (s in slots) {
            if (s.id < 0) continue
            val nx = (s.x - t.minX).toFloat() / (t.maxX - t.minX + 1)
            val ny = (s.y - t.minY).toFloat() / (t.maxY - t.minY + 1)
            val sx: Float
            val sy: Float
            when (rotation) {
                1 -> { sx = ny * w; sy = (1f - nx) * h }
                2 -> { sx = (1f - nx) * w; sy = (1f - ny) * h }
                3 -> { sx = (1f - ny) * w; sy = nx * h }
                else -> { sx = nx * w; sy = ny * h }
            }
            result.add(Finger(s.id, sx, sy))
        }
        result.sortBy { it.id }
        return result
    }

    // ------------------------------------------------------------------ поиск тачскрина

    private fun findTouch(): Touch? {
        val text = try {
            val p = ProcessBuilder("/system/bin/getevent", "-lp").redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor()
            out
        } catch (t: Throwable) {
            note = "getevent: ${t.javaClass.simpleName}"
            return null
        }

        val pathRe = Regex("""^\d+:\s*(\S+)""")
        val nameRe = Regex("""name:\s*"(.*)"""")
        val slotRe = Regex("""(?:ABS_MT_SLOT|002f)\s*:\s*value\s+-?\d+,\s*min\s+-?\d+,\s*max\s+(-?\d+)""")
        val xRe = Regex("""(?:ABS_MT_POSITION_X|0035)\s*:\s*value\s+-?\d+,\s*min\s+(-?\d+),\s*max\s+(-?\d+)""")
        val yRe = Regex("""(?:ABS_MT_POSITION_Y|0036)\s*:\s*value\s+-?\d+,\s*min\s+(-?\d+),\s*max\s+(-?\d+)""")

        var fallback: Touch? = null
        for (block in text.split("add device ").drop(1)) {
            val path = pathRe.find(block)?.groupValues?.get(1) ?: continue
            val name = nameRe.find(block)?.groupValues?.get(1) ?: ""
            if (slotRe.find(block) == null) continue // нужен протокол B (слоты)
            val x = xRe.find(block) ?: continue
            val y = yRe.find(block) ?: continue
            if (Regex("pen|stylus|pencil", RegexOption.IGNORE_CASE).containsMatchIn(name)) continue
            val t = Touch(
                path, name,
                x.groupValues[1].toInt(), x.groupValues[2].toInt(),
                y.groupValues[1].toInt(), y.groupValues[2].toInt()
            )
            if (block.contains("INPUT_PROP_DIRECT")) return t
            if (fallback == null) fallback = t
        }
        return fallback
    }

    private companion object {
        const val EV_SYN = 0
        const val EV_ABS = 3
        const val SYN_REPORT = 0
        const val ABS_MT_SLOT = 0x2f
        const val ABS_MT_POSITION_X = 0x35
        const val ABS_MT_POSITION_Y = 0x36
        const val ABS_MT_TRACKING_ID = 0x39
    }
}
