package com.example.mcmacro

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/**
 * Тапы через AccessibilityService.dispatchGesture().
 *
 * Каждый тап — отдельный stroke со своим startTime, поэтому несколько кликов
 * упаковываются в один GestureDescription (до getMaxStrokeCount() штук).
 * Это снижает накладные расходы на IPC и позволяет держать высокий CPS.
 * Инъецированный жест — отдельный "палец" и не мешает реальным касаниям
 * (камера, джойстик).
 */
class AccessibilityClickBackend : ClickBackend {

    override val label = "Accessibility"

    override val isReady: Boolean
        get() = MacroAccessibilityService.instance != null

    override fun batchSizeFor(cps: Int): Int {
        val maxStrokes = GestureDescription.getMaxStrokeCount()
        // ~100 мс клика на батч: задержка остановки после отпускания кнопки мала
        return (cps * 0.10).roundToInt().coerceIn(1, maxStrokes)
    }

    override suspend fun tapSequence(x: Float, y: Float, offsetsMs: LongArray, holdMs: Int) {
        val svc = MacroAccessibilityService.instance ?: return

        val builder = GestureDescription.Builder()
        for (offset in offsetsMs) {
            val path = Path().apply { moveTo(x, y) }
            builder.addStroke(
                GestureDescription.StrokeDescription(path, offset, holdMs.toLong())
            )
        }
        val gesture = builder.build()

        val accepted = suspendCancellableCoroutine<Boolean> { cont ->
            val ok = svc.dispatchGesture(
                gesture,
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(g: GestureDescription?) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onCancelled(g: GestureDescription?) {
                        if (cont.isActive) cont.resume(true)
                    }
                },
                null
            )
            if (!ok && cont.isActive) cont.resume(false)
        }
        // если система отклонила жест — не крутим цикл вхолостую
        if (!accepted) delay(5)
    }
}
