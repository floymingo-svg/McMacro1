package com.example.mcmacro

/**
 * Способ доставки тапа в игру. Движок (ClickEngine) не знает, как именно
 * тап попадает в систему — это решает бэкенд.
 */
interface ClickBackend {
    val label: String
    val isReady: Boolean

    /** Сколько кликов бэкенд хочет получать за один вызов (батч). */
    fun batchSizeFor(cps: Int): Int

    /**
     * Выполнить тапы в точке (x, y). offsetsMs — моменты кликов в миллисекундах
     * относительно начала вызова (с jitter). holdMs — сколько длится касание.
     * Возвращается, когда последний тап завершён.
     */
    suspend fun tapSequence(x: Float, y: Float, offsetsMs: LongArray, holdMs: Int)
}
