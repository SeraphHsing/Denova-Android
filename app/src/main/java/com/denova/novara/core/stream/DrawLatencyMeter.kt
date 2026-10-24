package com.denova.novara.core.stream

import java.util.Locale

/**
 * 采样「事件接收 → 投影可见」延迟（毫秒）。
 *
 * 口径：每批 chunk 到达时记录时间戳，下一次叙事面板绘制完成后取差值。
 * 只保留前 [capacity] 个样本，避免测量本身引入内存增长。
 */
class DrawLatencyMeter(private val capacity: Int = 4096) {

    private val samples = LongArray(capacity)

    var size: Int = 0
        private set

    fun add(millis: Long) {
        if (size < capacity) {
            samples[size] = millis
            size++
        }
    }

    fun reset() {
        size = 0
    }

    /** 形如 `n=128 p50=42ms p95=96ms max=210ms`；无样本时返回 null。 */
    fun summary(): String? {
        if (size == 0) return null
        val sorted = samples.copyOf(size).also { it.sort() }
        fun at(percent: Double): Long =
            sorted[((size - 1) * percent).toInt().coerceIn(0, size - 1)]
        return String.format(
            Locale.US,
            "n=%d p50=%dms p95=%dms p99=%dms max=%dms",
            size,
            at(0.50),
            at(0.95),
            at(0.99),
            sorted[size - 1],
        )
    }
}
