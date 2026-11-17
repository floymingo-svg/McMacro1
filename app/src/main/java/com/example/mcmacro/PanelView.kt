package com.example.mcmacro

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/**
 * Панель настроек в стиле Game Space: сворачивается в кружок.
 * Поля ввода с клавиатурой в оверлее без фокуса недоступны, поэтому
 * числа задаются ползунком и кнопками −/+.
 */
class PanelView(ctx: Context, private val prefs: Prefs, private val host: Host) : FrameLayout(ctx) {

    interface Host {
        fun dragListener(onTap: (() -> Unit)?): View.OnTouchListener
        fun onCps(value: Int)
        fun onJitter(value: Int)
        /** true — режим выбора цели включён */
        fun onTargetToggle(): Boolean
        /** true — режим перемещения HIT включён */
        fun onMoveToggle(): Boolean
        /** Возвращает true, если Shizuku реально используется после переключения */
        fun onBackend(wantShizuku: Boolean): Boolean
        fun onClose()
    }

    private val collapsedBtn = TextView(ctx)
    private val expanded = LinearLayout(ctx)
    private val cpsLabel = TextView(ctx)
    private val jitterLabel = TextView(ctx)
    private val cpsBar = SeekBar(ctx)
    private val jitterBar = SeekBar(ctx)
    private val backendBtn: TextView
    private val targetBtn: TextView
    private val moveBtn: TextView

    init {
        val pad = ctx.dp(10)

        // ---- свернутое состояние ----
        collapsedBtn.apply {
            text = "⚙"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = circleBg(0xCC1B1B1F.toInt())
            setOnTouchListener(host.dragListener { setCollapsed(false) })
        }
        addView(collapsedBtn, LayoutParams(ctx.dp(46), ctx.dp(46)))

        // ---- развернутое состояние ----
        expanded.apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            background = roundBg(0xE61B1B1F.toInt(), ctx.dp(14).toFloat())
        }

        val header = label("☰  MC Macro  (тяни за заголовок)", 12f).apply {
            setPadding(0, 0, 0, ctx.dp(6))
            setOnTouchListener(host.dragListener(null))
        }
        expanded.addView(header)

        // CPS
        expanded.addView(cpsLabel.styled(13f))
        cpsBar.apply {
            max = 49
            progress = prefs.cps.coerceIn(1, 50) - 1
        }
        val cpsRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(stepper("−") { cpsBar.progress = (cpsBar.progress - 1).coerceAtLeast(0) })
            addView(cpsBar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(stepper("+") { cpsBar.progress = (cpsBar.progress + 1).coerceAtMost(49) })
        }
        expanded.addView(cpsRow)
        updateCpsLabel(prefs.cps)
        cpsBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                updateCpsLabel(p + 1)
                host.onCps(p + 1)
            }

            override fun onStartTrackingTouch(s: SeekBar?) = Unit
            override fun onStopTrackingTouch(s: SeekBar?) = Unit
        })

        // Jitter
        expanded.addView(jitterLabel.styled(13f).apply { setPadding(0, ctx.dp(6), 0, 0) })
        jitterBar.apply {
            max = 100
            progress = prefs.jitter.coerceIn(0, 100)
        }
        expanded.addView(jitterBar)
        updateJitterLabel(prefs.jitter)
        jitterBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                updateJitterLabel(p)
                host.onJitter(p)
            }

            override fun onStartTrackingTouch(s: SeekBar?) = Unit
            override fun onStopTrackingTouch(s: SeekBar?) = Unit
        })

        // Кнопки
        targetBtn = action("🎯 Задать цель") {
            val on = host.onTargetToggle()
            it.text = if (on) "✔ Сохранить цель" else "🎯 Задать цель"
        }
        moveBtn = action("✥ Двигать HIT: выкл") {
            val on = host.onMoveToggle()
            it.text = if (on) "✥ Двигать HIT: ВКЛ" else "✥ Двигать HIT: выкл"
        }
        backendBtn = action(backendText()) {
            host.onBackend(!prefs.useShizuku)
            it.text = backendText()
        }
        expanded.addView(targetBtn)
        expanded.addView(moveBtn)
        expanded.addView(backendBtn)
        expanded.addView(action("▾ Свернуть") { setCollapsed(true) })
        expanded.addView(action("✕ Закрыть") { host.onClose() })

        addView(expanded, LayoutParams(ctx.dp(230), ViewGroup.LayoutParams.WRAP_CONTENT))
        setCollapsed(prefs.collapsed)
    }

    private fun backendText() = "Метод: " + if (prefs.useShizuku) "Shizuku" else "Accessibility"

    private fun setCollapsed(c: Boolean) {
        prefs.collapsed = c
        collapsedBtn.visibility = if (c) View.VISIBLE else View.GONE
        expanded.visibility = if (c) View.GONE else View.VISIBLE
    }

    private fun updateCpsLabel(v: Int) {
        cpsLabel.text = "CPS: $v"
    }

    private fun updateJitterLabel(v: Int) {
        jitterLabel.text = "Jitter (рандом): $v%"
    }

    private fun TextView.styled(size: Float): TextView = apply {
        setTextColor(Color.WHITE)
        textSize = size
    }

    private fun label(t: String, size: Float) = TextView(context).apply {
        text = t
        setTextColor(0xFFB0BEC5.toInt())
        textSize = size
    }

    private fun stepper(t: String, onClick: () -> Unit) = TextView(context).apply {
        text = t
        textSize = 20f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        background = roundBg(0xFF37474F.toInt(), context.dp(8).toFloat())
        layoutParams = LinearLayout.LayoutParams(context.dp(36), context.dp(36))
        setOnClickListener { onClick() }
    }

    private fun action(t: String, onClick: (TextView) -> Unit): TextView = TextView(context).apply {
        text = t
        textSize = 13f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setPadding(0, context.dp(9), 0, context.dp(9))
        background = roundBg(0xFF37474F.toInt(), context.dp(8).toFloat())
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = context.dp(6) }
        setOnClickListener { onClick(this) }
    }
}
