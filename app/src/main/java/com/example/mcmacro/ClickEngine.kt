package com.example.mcmacro

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Цикл кликов с рандомизацией (jitter).
 *
 * Идея jitter: каждый клик привязан к "идеальной сетке" i * (1000 / CPS),
 * а шум добавляется к моменту клика, а не накапливается в интервалах.
 * Поэтому средний CPS остаётся равным заданному, а отдельные интервалы
 * плавают (гауссов шум, ограничен ±2σ).
 */
class ClickEngine(
    private val scope: CoroutineScope,
    private val backendProvider: () -> ClickBackend,
    private val targetProvider: () -> Pair<Float, Float>?,
) {
    @Volatile var cps: Int = 15
    @Volatile var jitterPercent: Int = 30

    private var job: Job? = null
    private val rnd = java.util.Random()

    val isRunning: Boolean get() = job?.isActive == true

    fun start() {
        if (isRunning) return
        job = scope.launch { loop() }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun loop() {
        val backend = backendProvider()
        val (x, y) = targetProvider() ?: return
        if (backend.isReady) backend.prepare()
        var batchStart = SystemClock.elapsedRealtime()

        while (currentCoroutineContext().isActive) {
            if (!backend.isReady) {
                delay(50)
                batchStart = SystemClock.elapsedRealtime()
                continue
            }

            val c = cps.coerceIn(1, 50)
            val base = 1000.0 / c                                   // идеальный интервал, мс
            val hold = (base * 0.4).roundToInt().coerceIn(6, 25)    // длительность касания
            val minGap = hold + 3                                   // тапы не должны перекрываться
            val n = backend.batchSizeFor(c).coerceAtLeast(1)
            val sigma = jitterPercent.coerceIn(0, 100) / 100.0 * base * 0.25

            val offsets = LongArray(n)
            var last = -minGap.toDouble()
            for (i in 0 until n) {
                val noise = if (sigma > 0) rnd.nextGaussian().coerceIn(-2.0, 2.0) * sigma else 0.0
                val t = max(i * base + noise, last + minGap)
                offsets[i] = t.roundToLong()
                last = t
            }

            val wait = batchStart - SystemClock.elapsedRealtime()
            if (wait > 0) delay(wait)

            backend.tapSequence(x, y, offsets, hold)

            batchStart += (n * base).roundToLong()
            // если отстали (например, система притормозила) — не пытаемся "догонять"
            val now = SystemClock.elapsedRealtime()
            if (now - batchStart > 60) batchStart = now
        }
    }
}
