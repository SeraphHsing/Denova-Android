package com.denova.novara.core.stream

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 恢复动作种类（`POST .../chat/recovery` 的 `action.kind`）。 */
enum class RecoveryActionKind { ATTACH, RESUME, ABORT }

/** 一次服务端投影的恢复动作（也是 recovery 请求体 `action` 的形状）。 */
@Serializable
data class RecoveryAction(
    @SerialName("action_id") val actionId: String? = null,
    val kind: String,
    @SerialName("command_id") val commandId: String,
    @SerialName("operation_id") val operationId: String,
)

/** 恢复请求体（`POST /api/interactive/chat/recovery`）。 */
@Serializable
data class RecoveryRequest(
    val action: RecoveryAction,
    @SerialName("story_id") val storyId: String,
    @SerialName("branch_id") val branchId: String,
)

/** 恢复回执（`POST .../chat/recovery` 的 202 响应）。 */
@Serializable
data class RecoveryReceipt(
    @SerialName("task_id") val taskId: String? = null,
    val status: String? = null,
    @SerialName("stream_cursor") val streamCursor: Long? = null,
    val cursor: Long? = null,
    @SerialName("recovery_action") val recoveryAction: RecoveryAction? = null,
)

/**
 * `GET /api/interactive/chat/active` 的活动运行时投影。
 * 含恢复相关的 runtime projection 字段（`runtime_recoverable`/`recovery_actions` 等）。
 */
@Serializable
data class GameActiveView(
    val active: Boolean = false,
    val status: String? = null,
    @SerialName("task_id") val taskId: String? = null,
    @SerialName("command_id") val commandId: String? = null,
    @SerialName("stream_cursor") val streamCursor: Long? = null,
    @SerialName("story_id") val storyId: String? = null,
    @SerialName("branch_id") val branchId: String? = null,
    val message: String? = null,
    @SerialName("pending_interruption_id") val pendingInterruptionId: String? = null,
    /**
     * 仍待回答的提问（GAM-011）。流不会在重连时重放 `ask_pending`，页面恢复只能靠这个投影，
     * 否则「同一待处理事项持续存在」不成立。
     */
    @SerialName("pending_ask") val pendingAsk: AgentAskInteraction? = null,
    @SerialName("regenerate_from_turn_id") val regenerateFromTurnId: String? = null,
    @SerialName("runtime_recoverable") val runtimeRecoverable: Boolean = false,
    @SerialName("recovery_paused") val recoveryPaused: Boolean = false,
    @SerialName("stream_attached") val streamAttached: Boolean = false,
    /** 目标操作 id：`suspend` / `abort` 命令的必填 `target_operation_id` 来源（GAM-012）。 */
    @SerialName("active_operation_id") val activeOperationId: String? = null,
    val phase: String? = null,
    @SerialName("recovery_actions") val recoveryActions: List<RecoveryAction> = emptyList(),
)

/** 流 / 恢复相关的服务端错误。code 为服务端错误键，status 为 HTTP 状态码。 */
class GameStreamError(
    val status: Int,
    val code: String?,
    message: String?,
    val earliestCursor: Long? = null,
    val latestCursor: Long? = null,
) : Exception(message ?: "HTTP $status")

@Serializable
private data class GameStreamErrorBody(
    val error: String? = null,
    val code: String? = null,
    @SerialName("earliest_cursor") val earliestCursor: Long? = null,
    @SerialName("latest_cursor") val latestCursor: Long? = null,
)

/** 把服务端错误响应解析为 [GameStreamError]；body 非法时保留原始文本。 */
fun parseGameStreamError(status: Int, body: String, json: Json): GameStreamError {
    val parsed = runCatching { json.decodeFromString(GameStreamErrorBody.serializer(), body) }.getOrNull()
    return GameStreamError(
        status = status,
        code = parsed?.code,
        message = parsed?.error ?: body.ifBlank { null },
        earliestCursor = parsed?.earliestCursor,
        latestCursor = parsed?.latestCursor,
    )
}
