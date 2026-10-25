package com.denova.novara.feature.agent

import com.denova.novara.core.network.AgentCommandInput
import com.denova.novara.core.network.AgentCommandRequest
import com.denova.novara.core.network.TextSelectionRef
import com.denova.novara.core.stream.AgentActiveView

/**
 * 暂停 / 停止命令的纯逻辑（AGT-005）。
 *
 * 两条硬约束（对齐 Web `useAgentChat.suspend/stop` 与后端 `HandleChatCommand`）：
 * 1. `target_operation_id` 必须取当前 active 投影里的 `active_operation_id`，不能自己编造或复用旧值；
 *    取不到就不发命令（后端会以 `agent_runtime.invalid_command` 拒绝空目标）。
 * 2. 停止带 `reason=user_requested`，服务端据此把结果标记为用户中止。
 *
 * 错误码到界面的映射也在这里，避免把「目标已变化」误报成普通失败。
 */
object AgentCommands {

    const val TYPE_ABORT = "abort"
    const val TYPE_SUSPEND = "suspend"
    const val TYPE_FOLLOW_UP = "follow_up"

    /** 与 Web 一致的停止原因。 */
    const val ABORT_REASON_USER = "user_requested"

    /** 当前可控制的操作身份：active 投影里的 `active_operation_id`（去空白）。 */
    fun activeOperationId(view: AgentActiveView?): String = view?.activeOperationId?.trim().orEmpty()

    /** 暂停 / 停止按钮是否可用：只有拿到明确的当前操作身份才允许下发命令。 */
    fun canControl(view: AgentActiveView?): Boolean = activeOperationId(view).isNotEmpty()

    /**
     * 输入是否应作为追加（`follow_up`）提交而不是开新一轮（AGT-006）。
     *
     * 对齐 Web `useAgentChat` 的判定：运行中或服务端已暂停时，输入进入该操作的队列，
     * 由它在下一个轮次边界执行；此时再发 `POST /api/chat` 会与正在跑的操作抢同一个会话。
     * 判据只用服务端投影里的 `active_operation_id`——客户端不能凭自己的流状态猜。
     */
    fun shouldFollowUp(operationId: String?): Boolean = !operationId.isNullOrBlank()

    /** 追加命令：正文与引用随 `input` 下发，目标是用户当前看到的那个操作。 */
    fun followUp(
        commandId: String,
        sessionId: String,
        operationId: String,
        message: String,
        references: List<String> = emptyList(),
        selections: List<TextSelectionRef> = emptyList(),
    ): AgentCommandRequest = AgentCommandRequest(
        sessionId = sessionId,
        type = TYPE_FOLLOW_UP,
        commandId = commandId,
        targetOperationId = operationId,
        input = AgentCommandInput(
            message = message.trim(),
            references = references,
            selections = selections,
        ),
    )

    /** 暂停命令：不携带 reason，语义是让当前操作进入 suspended。 */
    fun suspend(commandId: String, sessionId: String, operationId: String): AgentCommandRequest =
        AgentCommandRequest(
            sessionId = sessionId,
            type = TYPE_SUSPEND,
            commandId = commandId,
            targetOperationId = operationId,
        )

    /** 停止命令：带 `user_requested`，服务端据此给出用户中止的结果。 */
    fun abort(commandId: String, sessionId: String, operationId: String): AgentCommandRequest =
        AgentCommandRequest(
            sessionId = sessionId,
            type = TYPE_ABORT,
            commandId = commandId,
            targetOperationId = operationId,
            reason = ABORT_REASON_USER,
        )

    /**
     * 命令错误码 → 中文提示。
     * `invalid_phase` 与 `target_operation_mismatch` 必须分开：前者是「已经没有在跑的操作」，
     * 后者是「你要停的那个操作已经换代」，处理方式不同（后者应刷新 active 再决定）。
     */
    fun errorMessage(code: String?, fallback: String? = null): String = when (code) {
        "agent_runtime.invalid_phase" -> "当前没有运行中的 Agent"
        "agent_runtime.target_operation_mismatch" -> "目标运行已变化，已刷新状态后重试"
        "agent_runtime.capability_unsupported" -> "当前模型不支持该操作"
        "agent_runtime.invalid_command" -> "命令参数无效"
        "agent_runtime.queue_conflict" -> "队列操作与当前状态冲突"
        "agent_runtime.busy" -> "Agent 正忙，请稍后重试"
        "agent_runtime.commit_won" -> "Agent 输出已进入规范提交，本次控制未生效"
        "agent_runtime.context_changed" -> "运行上下文已变化（工作区或项目被切换）"
        "agent_runtime.no_workspace" -> "尚未选择工作区"
        "agent_runtime.failed" -> "操作失败，请重试"
        else -> fallback?.takeIf { it.isNotBlank() } ?: "操作失败"
    }

    /** 命令失败是否属于「目标已失效」：需要刷新 active，而不是原样重发。 */
    fun isTargetGone(code: String?): Boolean =
        code == "agent_runtime.invalid_phase" || code == "agent_runtime.target_operation_mismatch"
}
