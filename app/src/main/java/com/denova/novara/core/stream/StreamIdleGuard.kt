package com.denova.novara.core.stream

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 流空闲兜底阈值：超过则查一次任务状态。 */
const val STREAM_IDLE_THRESHOLD_MS = 60_000L

/** 空闲检查间隔。 */
const val STREAM_IDLE_CHECK_INTERVAL_MS = 15_000L

/**
 * 收集流并在空闲超过 [idleMillis] 时调用 [isTaskFinished]：
 * - 任务已结束 → 取消流并返回 true（避免服务端任务 error 后展示流不关闭、UI 永久停留在"运行中"）；
 * - 任务仍在跑 → 重置空闲计时继续等待（长生成期间无害）。
 */
suspend fun <T> Flow<T>.collectWithIdleGuard(
    isTaskFinished: suspend () -> Boolean,
    onEvent: suspend (T) -> Unit,
    idleMillis: Long = STREAM_IDLE_THRESHOLD_MS,
    checkIntervalMillis: Long = STREAM_IDLE_CHECK_INTERVAL_MS,
): Boolean = coroutineScope {
    var lastEventAt = System.currentTimeMillis()
    var terminated = false

    val collector = launch {
        this@collectWithIdleGuard.collect { value ->
            lastEventAt = System.currentTimeMillis()
            onEvent(value)
        }
    }
    val watchdog = launch {
        while (isActive) {
            delay(checkIntervalMillis)
            val idle = System.currentTimeMillis() - lastEventAt
            if (idle >= idleMillis) {
                if (isTaskFinished()) {
                    terminated = true
                    collector.cancel()
                    break
                }
                lastEventAt = System.currentTimeMillis()
            }
        }
    }

    collector.join()
    watchdog.cancel()
    terminated
}
