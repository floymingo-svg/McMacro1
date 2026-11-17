package com.example.mcmacro

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View

/**
 * Кнопка "HIT". Пока палец на ней — идут клики.
 *
 * МУЛЬТИТАЧ: эта view живёт в СВОЁМ маленьком окне оверлея. Система сама
 * раздаёт касания по окнам (FLAG_SPLIT_TOUCH + FLAG_NOT_TOUCH_MODAL),
 * поэтому второй палец (камера) уходит напрямую в игру — пересылать или
 * "пропускать" касания вручную не нужно. Здесь мы только следим за СВОИМ
 * указателем (activePointerId) и игнорируем чужие ACTION_POINTER_DOWN.
 */
class HitButtonView(ctx: Context, private val host: Host) : View(ctx) {

    interface Host {
        fun onHold(down: Boolean)
        fun isMoveMode(): Boolean
        fun moveListener(): OnTouchListener
    }

    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var holding = false

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = ctx.dp(3).toFloat()
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = ctx.dp(18).toFloat()
        typeface = Typeface.DEFAULT_BOLD
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) / 2f - stroke.strokeWidth
        fill.color = when {
            host.isMoveMode() -> 0xCC1E88E5.toInt()
            holding -> 0xEEE53935.toInt()
            else -> 0xCC37474F.toInt()
        }
        canvas.drawCircle(cx, cy, r, fill)
        canvas.drawCircle(cx, cy, r, stroke)
        val label = if (host.isMoveMode()) "MOVE" else "HIT"
        canvas.drawText(label, cx, cy - (text.descent() + text.ascent()) / 2f, text)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        // Режим перемещения кнопки — отдаём события в перетаскиватель окна
        if (host.isMoveMode()) {
            host.moveListener().onTouch(this, e)
            return true
        }

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointerId = e.getPointerId(0)
                setHolding(true)
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            }

            // Второй палец на этой же кнопке — игнорируем
            MotionEvent.ACTION_POINTER_DOWN -> Unit

            // Палец ушёл ВНЕ кнопки, но не отпущен: окно продолжает получать
            // MOVE этого указателя, hold сохраняется.
            MotionEvent.ACTION_MOVE -> Unit

            MotionEvent.ACTION_POINTER_UP -> {
                if (e.getPointerId(e.actionIndex) == activePointerId) release()
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> release()
        }
        return true
    }

    private fun release() {
        activePointerId = MotionEvent.INVALID_POINTER_ID
        setHolding(false)
    }

    private fun setHolding(value: Boolean) {
        if (holding == value) return
        holding = value
        invalidate()
        host.onHold(value)
    }

    /** Вызывается при смене режима (MOVE/HIT) для перерисовки. */
    fun refresh() = invalidate()

    /** Страховка: если окно скрыто/уничтожено во время удержания. */
    override fun onDetachedFromWindow() {
        if (holding) release()
        super.onDetachedFromWindow()
    }
}
