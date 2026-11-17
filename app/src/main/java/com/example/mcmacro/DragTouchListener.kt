package com.example.mcmacro

import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Перетаскивание ОКНА оверлея (а не view внутри него).
 * Работает по первому указателю; ACTION_POINTER_* намеренно игнорируются.
 * Если палец сдвинулся меньше slop — это тап (onTap).
 */
class DragTouchListener(
    private val wm: WindowManager,
    private val lp: WindowManager.LayoutParams,
    private val slopPx: Int,
    private val onTap: (() -> Unit)?,
    private val onMoved: () -> Unit,
) : View.OnTouchListener {

    private var startRawX = 0f
    private var startRawY = 0f
    private var startX = 0
    private var startY = 0
    private var dragging = false

    override fun onTouch(v: View, e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startRawX = e.rawX
                startRawY = e.rawY
                startX = lp.x
                startY = lp.y
                dragging = false
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - startRawX
                val dy = e.rawY - startRawY
                if (!dragging && hypot(dx, dy) > slopPx) dragging = true
                if (dragging) {
                    lp.x = startX + dx.roundToInt()
                    lp.y = startY + dy.roundToInt()
                    wm.updateViewLayout(v.rootView, lp)
                }
            }

            MotionEvent.ACTION_UP -> {
                if (dragging) onMoved() else onTap?.invoke()
            }
        }
        return true
    }
}
