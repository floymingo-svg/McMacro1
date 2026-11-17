package com.example.mcmacro

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/**
 * Панель настроек: сворачивается в круглую иконку.
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
        /** Панель развернули — хозяин может обновить статус. */
        fun onExpanded()
        fun onClose()
    }

    private val collapsedBtn = ImageView(ctx)
    private val expanded = LinearLayout(ctx)
    private val cpsValue = TextView(ctx)
    private val jitterValue = TextView(ctx)
    private val cpsBar = SeekBar(ctx)
    private val jitterBar = SeekBar(ctx)
    private val shizukuChip = TextView(ctx)
    private val infoText = TextView(ctx)
    private val targetBtn: TextView
    private val moveBtn: TextView

    init {
        val pad = ctx.dp(12)

        // ---- свернутое состояние: круглая иконка ----
        collapsedBtn.apply {
            setImageResource(R.drawable.ic_launcher_foreground)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(ctx.dp(2), ctx.dp(2), ctx.dp(2), ctx.dp(2))
            background = gradientCircle(Ui.accent, Ui.accent2)
            setOnTouchListener(host.dragListener { setCollapsed(false) })
        }
        addView(collapsedBtn, LayoutParams(ctx.dp(50), ctx.dp(50)))

        // ---- развернутое состояние ----
        expanded.apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            background = roundBg(Ui.panel, ctx.dp(18).toFloat()).apply {
                setStroke(ctx.dp(1), 0x33FFFFFF)
            }
        }

        // Заголовок: логотип + название. За него тянем окно.
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setOnTouchListener(host.dragListener(null))
        }
        val logo = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_launcher_foreground)
            background = gradientCircle(Ui.accent, Ui.accent2)
        }
        header.addView(logo, LinearLayout.LayoutParams(ctx.dp(34), ctx.dp(34)))
        val titles = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(10), 0, 0, 0)
            addView(text("Vpn Xasky", 15f, Ui.text, bold = true))
            addView(text("тяни за заголовок", 10f, Ui.muted))
        }
        header.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(iconButton("—") { setCollapsed(true) })
        expanded.addView(header)

        // Статус
        shizukuChip.apply {
            textSize = 11f
            setPadding(ctx.dp(10), ctx.dp(4), ctx.dp(10), ctx.dp(4))
        }
        setStatus(false, "проверка…")
        expanded.addView(
            shizukuChip,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = ctx.dp(10) }
        )
        infoText.apply {
            textSize = 10f
            setTextColor(Ui.muted)
            setPadding(ctx.dp(2), ctx.dp(4), 0, 0)
        }
        expanded.addView(infoText)

        // CPS
        expanded.addView(sectionRow("Скорость (CPS)", cpsValue, ctx.dp(10)))
        styleSeek(cpsBar)
        cpsBar.max = 49
        cpsBar.progress = prefs.cps.coerceIn(1, 50) - 1
        val cpsRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(stepper("−") { cpsBar.progress = (cpsBar.progress - 1).coerceAtLeast(0) })
            addView(cpsBar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(stepper("+") { cpsBar.progress = (cpsBar.progress + 1).coerceAtMost(49) })
        }
        expanded.addView(cpsRow)
        cpsValue.text = "${prefs.cps}"
        cpsBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                cpsValue.text = "${p + 1}"
                host.onCps(p + 1)
            }

            override fun onStartTrackingTouch(s: SeekBar?) = Unit
            override fun onStopTrackingTouch(s: SeekBar?) = Unit
        })

        // Jitter
        expanded.addView(sectionRow("Разброс интервалов", jitterValue, ctx.dp(6)))
        styleSeek(jitterBar)
        jitterBar.max = 100
        jitterBar.progress = prefs.jitter.coerceIn(0, 100)
        expanded.addView(jitterBar)
        jitterValue.text = "${prefs.jitter}%"
        jitterBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                jitterValue.text = "$p%"
                host.onJitter(p)
            }

            override fun onStartTrackingTouch(s: SeekBar?) = Unit
            override fun onStopTrackingTouch(s: SeekBar?) = Unit
        })

        // Кнопки
        targetBtn = action("🎯 Цель", primary = true) {
            val on = host.onTargetToggle()
            it.text = if (on) "✔ Сохранить" else "🎯 Цель"
        }
        moveBtn = action("✥ HIT: выкл", primary = false) {
            val on = host.onMoveToggle()
            it.text = if (on) "✥ HIT: двигаю" else "✥ HIT: выкл"
        }
        val buttons = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(targetBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = ctx.dp(4)
            })
            addView(moveBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = ctx.dp(4)
            })
        }
        expanded.addView(
            buttons,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = ctx.dp(12) }
        )
        val close = action("✕ Закрыть", primary = false) { host.onClose() }
        close.setTextColor(Ui.bad)
        expanded.addView(
            close,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = ctx.dp(8) }
        )

        addView(expanded, LayoutParams(ctx.dp(250), ViewGroup.LayoutParams.WRAP_CONTENT))
        setCollapsed(prefs.collapsed)
    }

    /** Обновить строки состояния (вызывать из UI-потока). */
    fun setStatus(shizukuOk: Boolean, info: String) {
        shizukuChip.text = if (shizukuOk) "●  Shizuku подключён" else "●  Shizuku не готов"
        shizukuChip.setTextColor(if (shizukuOk) Ui.ok else Ui.bad)
        shizukuChip.background = roundBg(
            if (shizukuOk) 0x222ED573 else 0x22FF5C5C, context.dp(12).toFloat()
        )
        infoText.text = info
    }

    private fun setCollapsed(c: Boolean) {
        prefs.collapsed = c
        collapsedBtn.visibility = if (c) View.VISIBLE else View.GONE
        expanded.visibility = if (c) View.GONE else View.VISIBLE
        if (!c) host.onExpanded()
    }

    private fun styleSeek(s: SeekBar) {
        s.progressTintList = ColorStateList.valueOf(Ui.accent2)
        s.thumbTintList = ColorStateList.valueOf(Color.WHITE)
        s.progressBackgroundTintList = ColorStateList.valueOf(0x33FFFFFF)
    }

    private fun text(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
        text = t
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun sectionRow(title: String, value: TextView, topMarginPx: Int): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, topMarginPx, 0, 0)
            addView(text(title, 12f, Ui.muted), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            value.apply {
                textSize = 15f
                setTextColor(Ui.text)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            addView(value)
        }

    private fun iconButton(t: String, onClick: () -> Unit) = TextView(context).apply {
        text = t
        textSize = 16f
        gravity = Gravity.CENTER
        setTextColor(Ui.text)
        background = circleBg(Ui.cardHi)
        layoutParams = LinearLayout.LayoutParams(context.dp(32), context.dp(32))
        setOnClickListener { onClick() }
    }

    private fun stepper(t: String, onClick: () -> Unit) = TextView(context).apply {
        text = t
        textSize = 18f
        gravity = Gravity.CENTER
        setTextColor(Ui.text)
        background = roundBg(Ui.cardHi, context.dp(10).toFloat())
        layoutParams = LinearLayout.LayoutParams(context.dp(34), context.dp(34))
        setOnClickListener { onClick() }
    }

    private fun action(t: String, primary: Boolean, onClick: (TextView) -> Unit): TextView =
        TextView(context).apply {
            text = t
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Ui.text)
            setPadding(0, context.dp(10), 0, context.dp(10))
            background = if (primary) gradientBg(Ui.accent, Ui.accent2, context.dp(12).toFloat())
            else roundBg(Ui.cardHi, context.dp(12).toFloat())
            setOnClickListener { onClick(this) }
        }
}
