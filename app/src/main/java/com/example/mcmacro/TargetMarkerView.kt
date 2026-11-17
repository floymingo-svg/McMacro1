package com.example.mcmacro

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/** Перекрестие, которое пользователь перетаскивает на точку удара в игре. */
class TargetMarkerView(ctx: Context) : View(ctx) {

    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFFFFD600.toInt()
        strokeWidth = ctx.dp(3).toFloat()
    }
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55000000 }
    private val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = ctx.dp(2).toFloat()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) / 2f - ring.strokeWidth
        canvas.drawCircle(cx, cy, r, bg)
        canvas.drawCircle(cx, cy, r, ring)
        canvas.drawLine(cx - r * 0.6f, cy, cx + r * 0.6f, cy, cross)
        canvas.drawLine(cx, cy - r * 0.6f, cx, cy + r * 0.6f, cross)
    }
}
