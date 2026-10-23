package com.denova.novara.core.stream

import kotlinx.coroutines.flow.Flow

/**
 * 游戏流恢复的下一步决策（纯逻辑，不触发网络）。
 */
sealed interface GameRecoveryDecision {
    /** 有活动 task，用当前已确认游标挂接流。 */
    data class Attach(val taskId: String) : GameRecoveryDecision

    /** 流已失效 / 游标超预算，需从 canonical 历史整体重取（清游标后重挂）。 */
    data class Rehydrate(val taskId: String?) : GameRecoveryDecision

    /** 无活动 task，无需恢复。 */
    data object Settled : GameRecoveryDecision

    /** 流错误不可归类 / 尚未拿到投影，需重查 active。 */
    data object RetryActive : GameRecoveryDecision
}

/**
 * 根据 active 投影与流错误决定下一步。
 * 流错误优先；rehydrate/cursor 类错误一律走 [GameRecoveryDecision.Rehydrate]。
 */
fun decideRecovery(active: GameActiveView?, streamError: GameStreamError?): GameRecoveryDecision {
    if (streamError != null) {
        return when (streamError.code) {
            "agent_runtime.rehydrate_required",
            "agent_stream.cursor_expired",
            "agent_stream.invalid_cursor",
            -> GameRecoveryDecision.Rehydrate(active?.taskId)

            else -> GameRecoveryDecision.RetryActive
        }
    }
    if (active == null) return GameRecoveryDecision.RetryActive
    if (active.active && active.taskId != null) return GameRecoveryDecision.Attach(active.taskId)
    return GameRecoveryDecision.Settled
}

/** 游戏流 + 恢复的传输抽象；OkHttp 实现在 network 包，测试用 fake 注入。 */
interface GameStreamTransport {
    suspend fun active(storyId: String, branchId: String): GameActiveView

    fun stream(storyId: String, branchId: String, taskId: String, after: String?): Flow<GameStreamEvent>

    suspend fun recover(storyId: String, branchId: String, action: RecoveryAction): RecoveryReceipt
}
