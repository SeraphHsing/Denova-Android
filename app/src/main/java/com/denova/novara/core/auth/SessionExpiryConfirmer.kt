package com.denova.novara.core.auth

/**
 * 会话失效（401）的二次确认（AUTH-004）。
 *
 * 为什么需要：401 回调来自「任意请求」，可能是登录完成之前就发出的旧请求——登录成功后
 * `resetUnauthorizedFlag()` 会把一次性标志复位，旧请求的迟到 401 会把刚建立的会话顶掉，
 * 界面又回到登录页。用户看到的是「明明登录成功却掉回登录页」。
 *
 * 处理口径（对齐 AGENTS §4「写入结果不明时先读服务端 canonical 状态」）：
 * 收到 401 不立刻降级，先读一次 `status`；只有服务端明确返回「未认证」才算失效。
 * - `false`：确认失效 → 回到登录页并提示会话过期；
 * - `true`：会话仍有效（迟到 401）→ 保持当前登录态；
 * - `null`（探测失败 / 网络异常）→ 保守保持当前状态，下一次真实请求的 401 会再次触发确认。
 *
 * 纯逻辑，探测动作由调用方注入，便于 JVM 覆盖三种分支。
 */
class SessionExpiryConfirmer(
    private val probeAuthenticated: suspend () -> Boolean?,
) {
    /** @return true 表示确认会话已失效，调用方应降级到登录页。 */
    suspend fun isExpired(): Boolean = probeAuthenticated() == false
}
