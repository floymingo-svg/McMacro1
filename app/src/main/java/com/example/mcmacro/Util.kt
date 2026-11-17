package com.example.mcmacro

import android.content.Context
import android.graphics.drawable.GradientDrawable
import kotlin.math.roundToInt

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

fun roundBg(color: Int, radiusPx: Float): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusPx
    }

fun circleBg(color: Int): GradientDrawable =
    GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

fun gradientBg(c1: Int, c2: Int, radiusPx: Float): GradientDrawable =
    GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(c1, c2)).apply {
        cornerRadius = radiusPx
    }

fun gradientCircle(c1: Int, c2: Int): GradientDrawable =
    GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(c1, c2)).apply {
        shape = GradientDrawable.OVAL
    }

/** Общая палитра приложения и панели. */
object Ui {
    val window = 0xFF0B0E17.toInt()
    val panel = 0xF2101320.toInt()
    val card = 0xFF161B2E.toInt()
    val cardHi = 0xFF222844.toInt()
    val accent = 0xFF6C5CE7.toInt()
    val accent2 = 0xFF00B4D8.toInt()
    val text = 0xFFFFFFFF.toInt()
    val muted = 0xFF9AA4B2.toInt()
    val ok = 0xFF2ED573.toInt()
    val bad = 0xFFFF5C5C.toInt()
    val warn = 0xFFFFB84D.toInt()
}
