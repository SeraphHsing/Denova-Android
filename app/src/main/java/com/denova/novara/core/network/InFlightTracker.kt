package com.denova.novara.core.network

import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap

/**
 * 在途请求计数（G1「退出时等待进行中读取」）。
 *
 * OkHttp 的 [okhttp3.EventListener] 在每个 call 开始时 [begin]、结束时 [end]，
 * 用 call 自身作为 token，保证 `callEnd` / `callFailed` 只减一次。
 *
 * 退出登录时先用 [awaitIdle] 等在途请求收尾再清会话，避免把服务端回包丢掉或
 * 让迟到响应落到新会话上。
 */
class InFlightTracker {

    private val calls = ConcurrentHashMap.newKeySet<Any>()

    /** 当前在途数；长连接（SSE / WebSocket）会一直计入，直到连接关闭。 */
    val count: Int get() = calls.size

    fun begin(token: Any) {
        calls.add(token)
    }

    fun end(token: Any) {
        calls.remove(token)
    }

    /** 等到没有在途请求；超时返回 false（调用方仍需继续退出流程）。 */
    suspend fun awaitIdle(timeoutMillis: Long, pollMillis: Long = 20): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (calls.isNotEmpty()) {
            if (System.currentTimeMillis() >= deadline) return false
            delay(pollMillis)
        }
        return true
    }
}
