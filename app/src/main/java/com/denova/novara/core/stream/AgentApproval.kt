package com.denova.novara.core.stream

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 工具调用审批（AGT-003）。
 *
 * 线上契约来自后端 `chat.buildAskEvent`：`kind=tool_approval` 的提问 **questions 为空**，
 * 决策信息在 `approval` 里，答案沿用同一个 answer 端点，形状固定为
 * `answers:[{question_id:"tool-approval", selected_option_ids:[<option>]}]`，
 * option ∈ `allow-once` / `allow-workspace` / `deny`（与 Web `ToolApprovalCard` 一致）。
 *
 * `allow-workspace` 只在 `can_remember=true` 且 `rule_display_pattern` 非空时提供：
 * 服务端会把这条审批转成一条工作区规则，之后同样的调用不再打断用户。
 */
@Serializable
data class AgentToolApproval(
    val mode: String = "",
    @SerialName("tool_name") val toolName: String = "",
    val command: String? = null,
    val details: String? = null,
    val cwd: String? = null,
    val risk: String = "",
    @SerialName("rule_id") val ruleId: String = "",
    @SerialName("args_hash") val argsHash: String = "",
    @SerialName("can_remember") val canRemember: Boolean = false,
    @SerialName("rule_matcher_version") val ruleMatcherVersion: Int = 0,
    @SerialName("rule_match_key") val ruleMatchKey: String = "",
    @SerialName("rule_display_pattern") val ruleDisplayPattern: String = "",
)

/** 审批动作；`wireId` 是提交给服务端的稳定标识，不可本地改写。 */
enum class ApprovalOption(val wireId: String) {
    DENY("deny"),
    ALLOW_WORKSPACE("allow-workspace"),
    ALLOW_ONCE("allow-once"),
    ;

    companion object {
        fun fromWire(id: String?): ApprovalOption? = entries.firstOrNull { it.wireId == id }
    }
}

/** 审批结果态（界面据此显示徽标，文案在资源里）。 */
enum class ApprovalState { WAITING, ALLOWED, ALLOWED_WORKSPACE, DENIED, CANCELLED }

/** 审批原因（`rule_id`）枚举；未识别的一律 GENERIC，界面不会伪造具体原因。 */
enum class ApprovalReason {
    WORKSPACE_UNAVAILABLE,
    INVALID_WORKSPACE_BOUNDARY,
    BASH_PARSE_FAILED,
    BASH_DYNAMIC_SYNTAX,
    BASH_UNLISTED_COMMAND,
    BASH_UNCLASSIFIED,
    PWSH_DYNAMIC_SYNTAX,
    PWSH_PARSE_FAILED,
    PWSH_UNLISTED_COMMAND,
    BROWSER_ARGUMENTS_UNKNOWN,
    BROWSER_REMOTE_MUTATION,
    BROWSER_COMMAND_UNKNOWN,
    BROWSER_ACTION_UNKNOWN,
    BROWSER_NETWORK_ACCESS,
    EXTERNAL_FILESYSTEM_READ,
    GENERIC,
}

enum class ApprovalRisk { LOW, MEDIUM, HIGH, CRITICAL, UNKNOWN }

enum class ApprovalMode { ASK, WRITE, FULL_ACCESS, UNKNOWN }

/** 审批卡片的纯逻辑：可选作用域、答案体、状态与文案分类。 */
object AgentApprovalLogic {

    /** 服务端约定的答案问题 ID（questions 为空，仍必须回这个问题 ID）。 */
    const val QUESTION_ID = "tool-approval"

    const val KIND_TOOL_APPROVAL = "tool_approval"

    /** 只有服务端明确允许记忆且给出可展示的作用域时，才提供「始终允许」。 */
    fun canAlwaysAllow(approval: AgentToolApproval?): Boolean =
        approval?.canRemember == true && !approval.ruleDisplayPattern.isBlank()

    /** 按钮顺序对齐 Web：拒绝 → 始终允许（可选）→ 允许本次。 */
    fun options(approval: AgentToolApproval?): List<ApprovalOption> = buildList {
        add(ApprovalOption.DENY)
        if (canAlwaysAllow(approval)) add(ApprovalOption.ALLOW_WORKSPACE)
        add(ApprovalOption.ALLOW_ONCE)
    }

    fun answer(option: ApprovalOption): AgentAskAnswer =
        AgentAskAnswer(questionId = QUESTION_ID, selectedOptionIds = listOf(option.wireId))

    /**
     * 已解决态：以 **用户选了什么** 为先，再看服务端状态。
     *
     * 服务端把「拒绝」落成 `status=cancelled` 但保留 `answers=[deny]`（真机读回如此），
     * 若先看 status 会把用户明确的拒绝显示成「已取消」。真正的取消（用户点取消）没有 answers，
     * 因此这里以选中项优先、无 answers 时才回落到 cancelled。
     */
    fun state(status: String, selectedOptionId: String?): ApprovalState = when {
        status == AgentAskLogic.STATUS_PENDING -> ApprovalState.WAITING
        selectedOptionId == ApprovalOption.ALLOW_WORKSPACE.wireId -> ApprovalState.ALLOWED_WORKSPACE
        selectedOptionId == ApprovalOption.ALLOW_ONCE.wireId -> ApprovalState.ALLOWED
        selectedOptionId == ApprovalOption.DENY.wireId -> ApprovalState.DENIED
        status == AgentAskLogic.STATUS_CANCELLED -> ApprovalState.CANCELLED
        else -> ApprovalState.DENIED
    }

    fun reason(ruleId: String?): ApprovalReason = when (ruleId?.trim()) {
        "workspace_unavailable" -> ApprovalReason.WORKSPACE_UNAVAILABLE
        "invalid_workspace_boundary" -> ApprovalReason.INVALID_WORKSPACE_BOUNDARY
        "bash_parse_failed" -> ApprovalReason.BASH_PARSE_FAILED
        "bash_dynamic_syntax" -> ApprovalReason.BASH_DYNAMIC_SYNTAX
        "bash_unlisted_command" -> ApprovalReason.BASH_UNLISTED_COMMAND
        "bash_unclassified" -> ApprovalReason.BASH_UNCLASSIFIED
        "pwsh_dynamic_syntax" -> ApprovalReason.PWSH_DYNAMIC_SYNTAX
        "pwsh_parse_failed" -> ApprovalReason.PWSH_PARSE_FAILED
        "pwsh_unlisted_command" -> ApprovalReason.PWSH_UNLISTED_COMMAND
        "browser_arguments_unknown" -> ApprovalReason.BROWSER_ARGUMENTS_UNKNOWN
        "browser_remote_mutation" -> ApprovalReason.BROWSER_REMOTE_MUTATION
        "browser_command_unknown" -> ApprovalReason.BROWSER_COMMAND_UNKNOWN
        "browser_action_unknown" -> ApprovalReason.BROWSER_ACTION_UNKNOWN
        "browser_network_access" -> ApprovalReason.BROWSER_NETWORK_ACCESS
        "external_filesystem_read" -> ApprovalReason.EXTERNAL_FILESYSTEM_READ
        else -> ApprovalReason.GENERIC
    }

    fun risk(value: String?): ApprovalRisk = when (value?.trim()?.lowercase()) {
        "low" -> ApprovalRisk.LOW
        "medium" -> ApprovalRisk.MEDIUM
        "high" -> ApprovalRisk.HIGH
        "critical" -> ApprovalRisk.CRITICAL
        else -> ApprovalRisk.UNKNOWN
    }

    fun mode(value: String?): ApprovalMode = when (value?.trim()?.lowercase()) {
        "ask" -> ApprovalMode.ASK
        "write" -> ApprovalMode.WRITE
        "full_access" -> ApprovalMode.FULL_ACCESS
        else -> ApprovalMode.UNKNOWN
    }

    /** 命令优先展示，没有命令时退到 details（Web 同序）。 */
    fun body(approval: AgentToolApproval?): String? =
        approval?.command?.takeIf { it.isNotBlank() }
            ?: approval?.details?.takeIf { it.isNotBlank() }
}
