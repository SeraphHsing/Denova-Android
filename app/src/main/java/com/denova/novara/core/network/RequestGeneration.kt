package com.denova.novara.core.network

import java.util.concurrent.atomic.AtomicLong

/**
 * 请求世代（AGENTS §4「迟到响应只有在请求作用域及世代仍有效时才能更新当前状态」）。
 *
 * 每次 logout、切换服务器或重新登录时调用 [retire] 递增世代；在途请求发出前用
 * [snapshot] 记录世代，响应返回后用 [isCurrent] 判断是否已过期——过期响应必须丢弃，
 * 尤其不能把旧会话的迟到 401 落到新登录上（AUTH-004）。
 */
class RequestGeneration {

    private val current = AtomicLong(0L)

    /** 使所有在途请求作废；返回新的世代值。 */
    fun retire(): Long = current.incrementAndGet()

    /** 请求发出时的世代快照。 */
    fun snapshot(): Long = current.get()

    /** 快照是否仍是当前世代。 */
    fun isCurrent(snapshot: Long): Boolean = snapshot == current.get()
}
