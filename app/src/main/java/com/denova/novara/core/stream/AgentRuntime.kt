package com.denova.novara.core.stream

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 一次 Agent 运行的身份（`active.last_operation`），用于确认停止后的最终结果。 */
@Serializable
data class AgentOperationView(
    @SerialName("operation_id") val operationId: String = "",
    @SerialName("command_id") val commandId: String = "",
    val status: String? = null,
    val reason: String? = null,
)

/** `GET /api/chat/active`（写作 Agent）的活动投影。 */
@Serializable
data class AgentActiveView(
    val active: Boolean = false,
    val status: String? = null,
    @SerialName("task_id") val taskId: String? = null,
    @SerialName("stream_cursor") val streamCursor: Long? = null,
    @SerialName("pending_interruption_id") val pendingInterruptionId: String? = null,
    @SerialName("runtime_recoverable") val runtimeRecoverable: Boolean = false,
    @SerialName("recovery_paused") val recoveryPaused: Boolean = false,
    @SerialName("stream_attached") val streamAttached: Boolean = false,
    val phase: String? = null,
    /**
     * 当前操作的权威身份（`active_operation_id`）。暂停 / 停止必须把它原样作为
     * `target_operation_id` 回传，服务端据此校验目标是否已变化（AGT-005）。
     */
    @SerialName("active_operation_id") val activeOperationId: String = "",
    /** Agent Runtime journal 游标；不可用作 SSE `after`。 */
    val cursor: Long? = null,
    @SerialName("last_operation") val lastOperation: AgentOperationView? = null,
    @SerialName("recovery_actions") val recoveryActions: List<RecoveryAction> = emptyList(),
)

/** 恢复请求体（`POST /api/chat/recovery`）。 */
@Serializable
data class AgentRecoveryRequest(
    val action: RecoveryAction,
    @SerialName("session_id") val sessionId: String,
)

/** Agent 流恢复的下一步决策（纯逻辑）。 */
sealed interface AgentRecoveryDecision {
    /** 用 `after`（来自 active 的 stream_cursor）挂接流。 */
    data class Attach(val taskId: String, val after: String?) : AgentRecoveryDecision

    /** 投影超预算 / 游标失效，需从 canonical 历史整体重取。 */
    data object Rehydrate : AgentRecoveryDecision

    /** 无活动任务或已暂停（需用户 resume/abort），不观察。 */
    data object Settled : AgentRecoveryDecision

    /** 结果不可归类，需重查 active。 */
    data object RetryActive : AgentRecoveryDecision
}

/** 根据 active 投影与流错误决定下一步。 */
fun decideAgentRecovery(active: AgentActiveView?, streamError: GameStreamError?): AgentRecoveryDecision {
    if (streamError != null) {
        return when (streamError.code) {
            "agent_runtime.rehydrate_required",
            "agent_stream.cursor_expired",
            "agent_stream.invalid_cursor",
            "agent_stream.rehydrate_required",
            -> AgentRecoveryDecision.Rehydrate

            else -> AgentRecoveryDecision.RetryActive
        }
    }
    if (active == null) return AgentRecoveryDecision.RetryActive
    // suspended 需要先 resume/abort，不自动挂接（与 Web 语义一致）。
    if (active.phase == "suspended") return AgentRecoveryDecision.Settled
    if (active.active && active.taskId != null) {
        return AgentRecoveryDecision.Attach(active.taskId, active.streamCursor?.toString())
    }
    return AgentRecoveryDecision.Settled
}

/** Agent 流 + 恢复的传输抽象；OkHttp 实现在 network 包，测试用 fake 注入。 */
interface AgentStreamTransport {
    suspend fun agentActive(sessionId: String): AgentActiveView

    fun agentStream(taskId: String, after: String?): Flow<AgentUiFrame>

    suspend fun agentRecover(sessionId: String, action: RecoveryAction): RecoveryReceipt
}
