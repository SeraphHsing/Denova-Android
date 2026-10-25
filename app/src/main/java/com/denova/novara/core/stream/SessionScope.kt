package com.denova.novara.core.stream

/**
 * Agent 会话作用域（AGT-011）：每次切换会话推进一代，回包只有「世代与会话都还是当前」时才允许落到界面。
 *
 * 只靠取消协程不够：取消在下一个挂起点才生效，已经拿到响应、正在写状态的代码仍会执行，
 * 于是 A 会话迟到的 canonical 页 / 流帧 / active 投影会把 B 会话的正文和按钮状态改掉。
 */
class SessionScope {

    private var generation = 0L

    var sessionId: String = ""
        private set

    /** 进入某个会话，返回本次世代号；旧世代的回包一律作废。 */
    fun enter(sessionId: String): Long {
        this.sessionId = sessionId
        generation += 1
        return generation
    }

    /** 当前世代号（只读探测 / 调用方已有会话时复用）。 */
    fun currentGeneration(): Long = generation

    fun isCurrent(generation: Long, sessionId: String): Boolean =
        generation == this.generation && this.sessionId == sessionId
}
