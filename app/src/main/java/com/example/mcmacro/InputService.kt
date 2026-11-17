package com.example.mcmacro

import android.content.Context
import android.os.Build
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
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.system.exitProcess

/**
 * Shizuku UserService. Запускается в отдельном процессе с правами shell,
 * поэтому может вызывать скрытый IInputManager.injectInputEvent().
 *
 * ПРОБЛЕМА: при зажатой кнопке удара не двигалась камера. Обычный инъецированный
 * ACTION_DOWN от «чужого» устройства (deviceId = 0) система может принять за новый жест.
 *
 * РЕШЕНИЕ (режим «мульти-тач»): читаем сырые касания тачскрина из /dev/input/eventN,
 * знаем, где настоящие пальцы, и шлём тап как ещё один палец (POINTER_DOWN/UP) от
 * настоящего тачскрина. Режим включается ТОЛЬКО если сырые события реально приходят
 * и в момент тапа виден хотя бы один настоящий палец. Иначе — безопасный откат
 * на обычный одиночный тап (так клики работают всегда).
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
        val direct: Boolean,
    )

    /** Один /dev/input/eventN с мультитачем и его собственное состояние слотов. */
    private class Node(val touch: Touch) {
        val slots = Array(32) { Slot() }
        val usedIds = BooleanArray(32)
        var wasDown = false
        var downTime = 0L
        @Volatile var events = 0L
        @Volatile var error = ""
    }

    private class Finger(val id: Int, val x: Float, val y: Float)

    private val lock = Any()
    private val nodes = CopyOnWriteArrayList<Node>()

    @Volatile private var note = "запуск…"
    @Volatile private var dispW = 0
    @Volatile private var dispH = 0
    @Volatile private var rotation = 0
    @Volatile private var deviceId = -1
    @Volatile private var lastError = ""
    @Volatile private var taps = 0
    @Volatile private var mergedTaps = 0
    @Volatile private var lastReal = -1

    init {
        Thread({
            try {
                startReaders()
            } catch (t: Throwable) {
                note = "чтение тача: ${t.javaClass.simpleName} ${t.message ?: ""}".trim()
            }
        }, "mc-touch-init").apply {
            isDaemon = true
            start()
        }
    }

    // ------------------------------------------------------------------ AIDL

    override fun destroy() {
        exitProcess(0)
    }

    /** Имя тачскрина, который реально присылает события (иначе — первого найденного). */
    override fun touchName(): String = bestNode()?.touch?.name ?: ""

    override fun configure(width: Int, height: Int, rotation: Int, deviceId: Int) {
        dispW = width
        dispH = height
        this.rotation = rotation
        this.deviceId = deviceId
    }

    override fun status(): String {
        val n = bestNode() ?: return "тач: $note"
        val mode = when {
            isMerged() -> "мульти-тач ✔"
            n.events == 0L -> "сырые касания не идут ⚠"
            else -> "режим совместимости ⚠"
        }
        val err = if (lastError.isEmpty()) "" else " | $lastError"
        return "тач: ${n.touch.name} • $mode$err"
    }

    private fun bestNode(): Node? =
        nodes.maxByOrNull { it.events * 2 + if (it.touch.direct) 1 else 0 }

    private fun isMerged(): Boolean {
        val n = bestNode() ?: return false
        return n.events > 0 && deviceId >= 0 && dispW > 0
    }

    override fun tap(x: Float, y: Float, holdMs: Int) {
        taps++
        if (!isMerged()) {
            simpleTap(x, y, holdMs)
            return
        }

        val (real1, downTime) = synchronized(lock) { realFingers() to gestureDownTime() }
        lastReal = real1.size
        // Настоящих пальцев не видно (хотя HIT зажата) — данным не доверяем, шлём обычный тап.
        if (real1.isEmpty()) {
            simpleTap(x, y, holdMs)
            return
        }
        mergedTaps++

        val inj = freeId(real1)
        val t0 = SystemClock.uptimeMillis()
        val dt = if (downTime > 0) downTime else t0
        send(MotionEvent.ACTION_POINTER_DOWN, dt, t0, real1, inj, x, y, deviceId)

        SystemClock.sleep(holdMs.toLong())

        val real2 = synchronized(lock) { realFingers() }
        val t1 = SystemClock.uptimeMillis()
        if (real2.isEmpty()) {
            send(MotionEvent.ACTION_UP, t0, t1, emptyList(), inj, x, y, 0)
        } else {
            send(MotionEvent.ACTION_POINTER_UP, dt, t1, real2, inj, x, y, deviceId)
        }
    }

    // ------------------------------------------------------------------ инъекция

    /** Старый режим: одиночный тап от «виртуального» устройства (deviceId = 0). */
    private fun simpleTap(x: Float, y: Float, holdMs: Int) {
        val down = SystemClock.uptimeMillis()
        send(MotionEvent.ACTION_DOWN, down, down, emptyList(), 0, x, y, 0)
        SystemClock.sleep(holdMs.toLong())
        send(MotionEvent.ACTION_UP, down, down + holdMs, emptyList(), 0, x, y, 0)
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
        dev: Int,
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

    // ------------------------------------------------------------------ диагностика

    override fun diagnose(): String {
        val sb = StringBuilder()
        sb.append("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ")
            .append("${Build.MANUFACTURER} ${Build.MODEL}\n")
        sb.append(status()).append('\n')
        sb.append("taps=$taps mergedTaps=$mergedTaps realFingersAtLastTap=$lastReal ")
            .append("deviceId=$deviceId disp=${dispW}x$dispH rot=$rotation\n")
        for (n in nodes) {
            sb.append("node ${n.touch.path} \"${n.touch.name}\" direct=${n.touch.direct} ")
                .append("x=${n.touch.minX}..${n.touch.maxX} y=${n.touch.minY}..${n.touch.maxY} ")
                .append("events=${n.events}")
            if (n.error.isNotEmpty()) sb.append(" error=${n.error}")
            sb.append('\n')
        }
        val now = synchronized(lock) { realFingers() }
        sb.append("fingersNow=").append(now.joinToString { "${it.id}@${it.x.toInt()},${it.y.toInt()}" }).append('\n')
        try {
            val p = ProcessBuilder("logcat", "-d", "-t", "1500", "-s", "InputDispatcher")
                .redirectErrorStream(true).start()
            val lines = p.inputStream.bufferedReader().readLines()
            p.waitFor()
            val re = Regex("drop|inconsist|conflict|cancel|different device|switch|ignor|no touching|split", RegexOption.IGNORE_CASE)
            val hit = lines.filter { re.containsMatchIn(it) }.takeLast(8)
            sb.append("InputDispatcher: строк в логе ${lines.size}, подозрительных ${hit.size}\n")
            hit.forEach { sb.append(it.takeLast(170)).append('\n') }
        } catch (t: Throwable) {
            sb.append("logcat: ${t.javaClass.simpleName}\n")
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------ чтение сырых касаний

    private fun startReaders() {
        val found = findTouches()
        if (found.isEmpty()) {
            note = "не найден через getevent"
            return
        }
        for (t in found) {
            val node = Node(t)
            nodes.add(node)
            Thread({
                try {
                    FileInputStream(t.path).use { readLoop(it, node) }
                } catch (e: Throwable) {
                    node.error = "${e.javaClass.simpleName} ${e.message ?: ""}".trim()
                }
            }, "mc-touch-${t.path.substringAfterLast('/')}").apply {
                isDaemon = true
                start()
            }
        }
        note = "ok"
    }

    private fun readLoop(input: FileInputStream, node: Node) {
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
                node.events++
                synchronized(lock) {
                    when (type) {
                        EV_ABS -> when (code) {
                            ABS_MT_SLOT -> cur = value.coerceIn(0, node.slots.size - 1)
                            ABS_MT_TRACKING_ID -> node.slots[cur].tracking = value
                            ABS_MT_POSITION_X -> node.slots[cur].x = value
                            ABS_MT_POSITION_Y -> node.slots[cur].y = value
                        }

                        EV_SYN -> if (code == SYN_REPORT) commit(node)
                    }
                }
            }
        }
    }

    /** Конец пакета событий: освобождаем/выдаём pointer id так же, как InputReader. */
    private fun commit(node: Node) {
        for (s in node.slots) {
            if (s.tracking < 0 && s.id >= 0) {
                node.usedIds[s.id] = false
                s.id = -1
            }
        }
        for (s in node.slots) {
            if (s.tracking >= 0 && s.id < 0) {
                val free = node.usedIds.indexOfFirst { !it }
                if (free >= 0) {
                    s.id = free
                    node.usedIds[free] = true
                }
            }
        }
        val any = node.slots.any { it.id >= 0 }
        if (any && !node.wasDown) node.downTime = SystemClock.uptimeMillis()
        node.wasDown = any
    }

    private fun gestureDownTime(): Long = nodes.maxOfOrNull { it.downTime } ?: 0L

    /** Настоящие пальцы в координатах экрана. Вызывать под lock. */
    private fun realFingers(): List<Finger> {
        val w = dispW.toFloat()
        val h = dispH.toFloat()
        val result = ArrayList<Finger>()
        for (node in nodes) {
            val t = node.touch
            for (s in node.slots) {
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
        }
        result.sortBy { it.id }
        return result
    }

    // ------------------------------------------------------------------ поиск тачскринов

    /** Все устройства с мультитач-слотами и осями X/Y (без пера по имени). */
    private fun findTouches(): List<Touch> {
        val text = try {
            val p = ProcessBuilder("/system/bin/getevent", "-lp").redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor()
            out
        } catch (t: Throwable) {
            note = "getevent: ${t.javaClass.simpleName}"
            return emptyList()
        }

        val pathRe = Regex("""^\d+:\s*(\S+)""")
        val nameRe = Regex("""name:\s*"(.*)"""")
        val slotRe = Regex("""(?:ABS_MT_SLOT|002f)\s*:\s*value\s+-?\d+,\s*min\s+-?\d+,\s*max\s+(-?\d+)""")
        val xRe = Regex("""(?:ABS_MT_POSITION_X|0035)\s*:\s*value\s+-?\d+,\s*min\s+(-?\d+),\s*max\s+(-?\d+)""")
        val yRe = Regex("""(?:ABS_MT_POSITION_Y|0036)\s*:\s*value\s+-?\d+,\s*min\s+(-?\d+),\s*max\s+(-?\d+)""")

        val result = ArrayList<Touch>()
        for (block in text.split("add device ").drop(1)) {
            val path = pathRe.find(block)?.groupValues?.get(1) ?: continue
            val name = nameRe.find(block)?.groupValues?.get(1) ?: ""
            if (slotRe.find(block) == null) continue // нужен протокол B (слоты)
            val x = xRe.find(block) ?: continue
            val y = yRe.find(block) ?: continue
            if (Regex("pen|stylus|pencil", RegexOption.IGNORE_CASE).containsMatchIn(name)) continue
            result.add(
                Touch(
                    path, name,
                    x.groupValues[1].toInt(), x.groupValues[2].toInt(),
                    y.groupValues[1].toInt(), y.groupValues[2].toInt(),
                    block.contains("INPUT_PROP_DIRECT")
                )
            )
        }
        return result
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
