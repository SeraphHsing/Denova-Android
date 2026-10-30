package com.denova.novara.core.stream

import com.denova.novara.R
import com.denova.novara.core.i18n.UiText

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Agent 提问交互（AGT-004）。
 *
 * 线上契约取自后端 `agentui` 的 `ask_pending` / `ask_resolved` 事件与 `pending_asks` 投影，
 * 以及 `POST /api/session/asks/:id/answer|cancel`：
 * - 流中以 `data-ask` 部件下发 [AgentAskInteraction]（`schema=ask.pending.v1`）；
 * - 页面恢复时 `GET /api/chat/active` 的 `pending_asks` 给出同样的结构（回答/取消后消失）；
 * - 答案体是 `[{question_id, selected_option_ids, custom_input}]`，取消体是 `{reason}`。
 */
@Serializable
data class AgentAskOption(
    val id: String = "",
    val label: String = "",
    val description: String? = null,
)

@Serializable
data class AgentAskQuestion(
    val id: String = "",
    val question: String = "",
    val options: List<AgentAskOption> = emptyList(),
    @SerialName("multi_select") val multiSelect: Boolean = false,
    @SerialName("recommended_option_id") val recommendedOptionId: String? = null,
)

/** 答案体：一个问题一条，单选给一个 id，多选给多个。 */
@Serializable
data class AgentAskAnswer(
    @SerialName("question_id") val questionId: String,
    @SerialName("selected_option_ids") val selectedOptionIds: List<String> = emptyList(),
    @SerialName("custom_input") val customInput: String? = null,
)

@Serializable
data class AgentAskSelectedOption(
    val id: String = "",
    val label: String = "",
)

@Serializable
data class AgentAskAnswerResult(
    @SerialName("question_id") val questionId: String = "",
    val question: String = "",
    @SerialName("selected_options") val selectedOptions: List<AgentAskSelectedOption> = emptyList(),
    @SerialName("custom_input") val customInput: String? = null,
)

/**
 * 工具副作用验证的宿主证据（后端 `agentinteraction.ToolEffectVerification`）。
 *
 * 只在「有副作用的工具已开始执行、但结果未落盘，随后运行恢复」时下发
 * （`agent/lifecycle/lifecycle_tool_facts.go` 的 `restoreEffectInteractions`），
 * 交互 id 固定为 `verify-<callId>`；`arguments` 是当时的原始调用参数。
 */
@Serializable
data class AgentToolEffectVerification(
    @SerialName("execution_id") val executionId: String = "",
    val tool: String = "",
    val arguments: JsonElement? = null,
)

@Serializable
data class AgentAskInteraction(
    val schema: String = "",
    val id: String = "",
    val kind: String? = null,
    @SerialName("tool_call_id") val toolCallId: String = "",
    @SerialName("task_id") val taskId: String? = null,
    @SerialName("agent_kind") val agentKind: String = "",
    val status: String = AgentAskLogic.STATUS_PENDING,
    val questions: List<AgentAskQuestion> = emptyList(),
    @SerialName("allow_other") val allowOther: Boolean = false,
    /** 工具审批类提问（`kind=tool_approval`）携带的决策信息；提问类为空。 */
    val approval: AgentToolApproval? = null,
    /** 工具副作用验证（普通提问形状 + 宿主证据）；其余交互为空。 */
    val verification: AgentToolEffectVerification? = null,
    val answers: List<AgentAskAnswerResult> = emptyList(),
    @SerialName("cancel_reason") val cancelReason: String? = null,
) {
    val isPending: Boolean get() = status == AgentAskLogic.STATUS_PENDING

    /** 是否为工具审批（`questions` 为空、决策在 [approval]）。 */
    val isToolApproval: Boolean get() = kind == AgentApprovalLogic.KIND_TOOL_APPROVAL && approval != null

    /** 是否为工具副作用验证（有宿主证据、`questions` 非空）。 */
    val isVerification: Boolean get() = verification != null
}

/** 回答 / 取消接口的返回：`status`、答案与取消原因。 */
@Serializable
data class AgentAskResolution(
    val schema: String = "",
    val id: String = "",
    val status: String = AgentAskLogic.STATUS_PENDING,
    val answers: List<AgentAskAnswerResult> = emptyList(),
    @SerialName("cancel_reason") val cancelReason: String? = null,
)

/**
 * 工具副作用验证（AGT-004 补验）：文案与证据展示的纯逻辑。
 *
 * 与 Web `AskInteractionCard` 对齐：有 `verification` 时标题/问题改用本地文案，
 * `allow_other` 强制关闭、推荐项不展示、选项 label 按 `executed`/`not_executed`/`unknown` 本地化；
 * 服务端只把前两者当作已核实，`unknown` 会保持 `status=pending`。
 */
object AgentVerificationLogic {

    const val QUESTION_ID = "effect"
    const val EXECUTED = "executed"
    const val NOT_EXECUTED = "not_executed"
    const val UNKNOWN = "unknown"

    /** Web 在验证场景下取消时使用的原因（区分普通提问的 `user_cancelled`）。 */
    const val CANCEL_REASON_TASK_ABORTED = "task_aborted"

    /** 已知三项映射到本地文案；其他值回落到服务端给的 label，不伪造。 */
    fun optionLabelRes(optionId: String): Int? = when (optionId.trim()) {
        EXECUTED -> R.string.verify_executed
        NOT_EXECUTED -> R.string.verify_not_executed
        UNKNOWN -> R.string.verify_unknown
        else -> null
    }

    fun cancelReason(ask: AgentAskInteraction): String =
        if (ask.isVerification) CANCEL_REASON_TASK_ABORTED else AgentAskLogic.CANCEL_REASON_USER

    /** 提交后仍待核实（服务端对 `unknown` / 取消 / 多答案返回 pending）。 */
    fun stillPending(resolution: AgentAskResolution): Boolean =
        resolution.status == AgentAskLogic.STATUS_PENDING

    /**
     * 宿主证据里的调用参数，缩进后用于卡片展示；超长时截断，避免一屏被参数占满。
     */
    fun argumentsText(verification: AgentToolEffectVerification?, limit: Int = 2000): String? {
        val element = verification?.arguments ?: return null
        val text = runCatching { PRETTY.encodeToString(JsonElement.serializer(), element) }.getOrNull() ?: return null
        return if (text.length <= limit) text else text.take(limit) + "…"
    }

    private val PRETTY = Json { prettyPrint = true }
}

/**
 * 提问交互的纯逻辑：选项切换、可否提交、答案体构造、已解决文案。
 *
 * 单选（`multi_select=false`）点选即替换；多选是切换。只有每个问题都选中至少一项才允许提交，
 * 避免发出空答案让服务端以 `invalid_ask_answer` 拒绝。
 */
object AgentAskLogic {

    const val STATUS_PENDING = "pending"
    const val STATUS_ANSWERED = "answered"
    const val STATUS_CANCELLED = "cancelled"

    /** 与 Web `cancelSessionAsk` 的默认值一致。 */
    const val CANCEL_REASON_USER = "user_cancelled"

    fun toggle(question: AgentAskQuestion, selected: List<String>, optionId: String): List<String> =
        if (question.multiSelect) {
            if (selected.contains(optionId)) selected.filterNot { it == optionId } else selected + optionId
        } else {
            if (selected == listOf(optionId)) emptyList() else listOf(optionId)
        }

    fun canSubmit(ask: AgentAskInteraction, selected: Map<String, List<String>>): Boolean =
        ask.isPending && ask.questions.isNotEmpty() &&
            ask.questions.all { !selected[it.id].isNullOrEmpty() }

    /** 只提交已作答的问题，顺序跟随提问顺序。 */
    fun answers(ask: AgentAskInteraction, selected: Map<String, List<String>>): List<AgentAskAnswer> =
        ask.questions.mapNotNull { question ->
            val ids = selected[question.id].orEmpty()
            if (ids.isEmpty()) null else AgentAskAnswer(questionId = question.id, selectedOptionIds = ids)
        }

    /**
     * 流里的 `ask_resolved` 部件可能只带状态、不带答案与取消原因，直接替换会把本地已有的
     * 回答详情抹掉。这里按字段合并：新值优先，缺失则沿用旧值。
     */
    fun merge(previous: AgentAskInteraction?, incoming: AgentAskInteraction): AgentAskInteraction {
        if (previous == null) return incoming
        return incoming.copy(
            answers = incoming.answers.ifEmpty { previous.answers },
            cancelReason = incoming.cancelReason ?: previous.cancelReason,
        )
    }

    /** 把接口返回的 resolution 落回本地交互：状态与答案/原因以服务端为准。 */
    fun applyResolution(ask: AgentAskInteraction, resolution: AgentAskResolution): AgentAskInteraction =
        ask.copy(
            status = resolution.status,
            answers = resolution.answers,
            cancelReason = resolution.cancelReason,
        )

    /** 已解决文案：回答列出选项名，取消显示原因。 */
    fun statusText(ask: AgentAskInteraction): UiText = when (ask.status) {
        STATUS_ANSWERED -> {
            val picked = ask.answers.flatMap { result ->
                result.selectedOptions.map { it.label.ifBlank { it.id } }
            }.filter { it.isNotBlank() }
            if (picked.isEmpty()) UiText.of(R.string.ask_status_answered) else UiText.of(R.string.ask_status_answered_items, picked.joinToString("、"))
        }

        STATUS_CANCELLED -> ask.cancelReason
            ?.takeIf { it.isNotBlank() }
            ?.let { UiText.of(R.string.ask_status_cancelled_reason, it) }
            ?: UiText.of(R.string.ask_status_cancelled)

        else -> UiText.of(R.string.ask_status_waiting)
    }

    /**
     * 回答 / 取消的错误码 → 文案（码取自后端 `writeAskResolutionError`）。
     * `ask_stale` 表示这个提问已经被别处回答或已过期，必须刷新投影而不是重试。
     */
    fun askError(code: String?, fallback: String? = null): UiText = when (code) {
        "agent_runtime.ask_stale" -> UiText.of(R.string.ask_err_stale)
        "agent_runtime.ask_conflict" -> UiText.of(R.string.ask_err_conflict)
        "agent_runtime.invalid_ask_answer" -> UiText.of(R.string.ask_err_invalid_answer)
        "agent_runtime.ask_not_found" -> UiText.of(R.string.ask_err_not_found)
        "agent_runtime.definition_mismatch" -> UiText.of(R.string.ask_err_definition_mismatch)
        "agent_runtime.no_workspace" -> UiText.of(R.string.ask_err_no_workspace)
        "agent_runtime.ask_failed" -> UiText.of(R.string.ask_err_failed)
        else -> fallback?.takeIf { it.isNotBlank() }?.let { UiText.raw(it) } ?: UiText.of(R.string.ask_err_submit_failed)
    }

    /** `ask_stale` 之后需要刷新投影（本地卡片已失效）。 */
    fun needsRefresh(code: String?): Boolean =
        code == "agent_runtime.ask_stale" || code == "agent_runtime.ask_not_found"
}
