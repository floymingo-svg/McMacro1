package com.example.mcmacro

import android.content.Context

/** Все настройки макроса. Хранятся в SharedPreferences. */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("macro", Context.MODE_PRIVATE)

    var cps: Int
        get() = sp.getInt("cps", 15)
        set(v) { sp.edit().putInt("cps", v).apply() }

    /** 0..100 — сила рандомизации интервалов */
    var jitter: Int
        get() = sp.getInt("jitter", 30)
        set(v) { sp.edit().putInt("jitter", v).apply() }

    /** Точка на экране (в пикселях), куда бьёт макрос. -1 = не задана. */
    var targetX: Float
        get() = sp.getFloat("tx", -1f)
        set(v) { sp.edit().putFloat("tx", v).apply() }

    var targetY: Float
        get() = sp.getFloat("ty", -1f)
        set(v) { sp.edit().putFloat("ty", v).apply() }

    var hitX: Int
        get() = sp.getInt("hx", -1)
        set(v) { sp.edit().putInt("hx", v).apply() }

    var hitY: Int
        get() = sp.getInt("hy", -1)
        set(v) { sp.edit().putInt("hy", v).apply() }

    var panelX: Int
        get() = sp.getInt("px", 40)
        set(v) { sp.edit().putInt("px", v).apply() }

    var panelY: Int
        get() = sp.getInt("py", 40)
        set(v) { sp.edit().putInt("py", v).apply() }

    var collapsed: Boolean
        get() = sp.getBoolean("collapsed", false)
        set(v) { sp.edit().putBoolean("collapsed", v).apply() }

    /** Пакет игры/клиента, который запускает кнопка «Запустить игру». */
    var gamePkg: String
        get() = sp.getString("gamePkg", "") ?: ""
        set(v) { sp.edit().putString("gamePkg", v).apply() }
}
