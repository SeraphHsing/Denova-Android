package com.denova.novara.core.network

import java.net.URLDecoder
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * 统计页（trajectory）数据契约。
 *
 * 事实来源：后端 `internal/agents/run/trace_reader.go`（`RunTraceSummary` / `RunTrace` / `RunTraceRecord`）
 * 与 `internal/app/agent_session.go`（`GlobalAgentRunTraceSummary` / `GlobalAgentRunTraceCatalog`）。
 * 端点见 `internal/api/routes.go`：
 * - `GET /api/agent-runs?limit=&project_id=&run_id=`（全局目录，**开发者模式关闭时 404 `api.trajectory.disabled`**）
 * - `GET /api/projects/:id/agent-runs?limit=`
 * - `GET /api/projects/:id/agent-runs/:id`
 * - `GET /api/projects/:id/agent-runs/:id/export`（JSONL 全文）
 *
 * 字段全部给默认值：服务端大量字段是 `omitempty`，缺省即空；数值型缺省按 0 处理。
 */
@Serializable
data class AgentRunTraceRef(
    val id: String = "",
    @SerialName("session_id") val sessionId: String = "",
    @SerialName("agent_name") val agentName: String = "",
    @SerialName("parent_call_id") val parentCallId: String = "",
    /** 全局目录组装时补的子 Run 状态；单条引用接口不带。 */
    val status: String = "",
)

@Serializable
data class AgentRunSummary(
    val id: String = "",
    @SerialName("created_at") val createdAt: String = "",
    val path: String = "",
    val status: String = "",
    val reason: String = "",
    val events: Int = 0,
    @SerialName("context_parts") val contextParts: Int = 0,
    @SerialName("project_id") val projectId: String = "",
    @SerialName("project_name") val projectName: String = "",
    @SerialName("task_id") val taskId: String = "",
    @SerialName("agent_kind") val agentKind: String = "",
    @SerialName("agent_name") val agentName: String = "",
    @SerialName("parent_run_id") val parentRunId: String = "",
    @SerialName("child_runs") val childRuns: List<AgentRunTraceRef> = emptyList(),
    @SerialName("session_id") val sessionId: String = "",
    @SerialName("session_title") val sessionTitle: String = "",
    @SerialName("trajectory_uri") val trajectoryUri: String = "",
    @SerialName("story_id") val storyId: String = "",
    @SerialName("branch_id") val branchId: String = "",
    @SerialName("turn_id") val turnId: String = "",
    @SerialName("maintenance_task") val maintenanceTask: String = "",
    val phase: String = "",
    @SerialName("tool_calls") val toolCalls: Int = 0,
    @SerialName("tool_successes") val toolSuccesses: Int = 0,
    @SerialName("tool_blocked") val toolBlocked: Int = 0,
    @SerialName("tool_errors") val toolErrors: Int = 0,
    @SerialName("tool_truncated") val toolTruncated: Int = 0,
    @SerialName("invalid_tool_args") val invalidToolArgs: Int = 0,
    @SerialName("tool_domain_accepted") val toolDomainAccepted: Int = 0,
    @SerialName("tool_domain_rejected") val toolDomainRejected: Int = 0,
    @SerialName("tool_domain_pending") val toolDomainPending: Int = 0,
    @SerialName("tool_domain_diagnostics") val toolDomainDiagnostics: Int = 0,
    @SerialName("llm_calls") val llmCalls: Int = 0,
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("cached_prompt_tokens") val cachedPromptTokens: Int = 0,
    @SerialName("uncached_prompt_tokens") val uncachedPromptTokens: Int = 0,
    @SerialName("cache_hit_rate") val cacheHitRate: Double = 0.0,
    @SerialName("duration_ms") val durationMs: Long = 0L,
    val mutations: Int = 0,
    @SerialName("verification_status") val verificationStatus: String = "",
    val recoverable: Boolean = false,
    @SerialName("content_captured") val contentCaptured: Boolean = false,
) {
    /** 目录里稳定选中键：全局返回 `trajectory_uri`，项目端点为空白，回退 `project_id + id`。 */
    val selectionKey: String
        get() = trajectoryUri.ifBlank {
            if (projectId.isBlank()) id else "$projectId/$id"
        }
}

@Serializable
data class AgentRunIssue(
    @SerialName("project_id") val projectId: String = "",
    @SerialName("project_name") val projectName: String = "",
    val message: String = "",
)

/** `GET /api/agent-runs`：全局目录 + 读取失败的项目（`issues`）。 */
@Serializable
data class AgentRunCatalog(
    val runs: List<AgentRunSummary> = emptyList(),
    val issues: List<AgentRunIssue> = emptyList(),
)

/** `GET /api/projects/:id/agent-runs`：项目作用域目录。 */
@Serializable
data class AgentRunListResponse(
    val runs: List<AgentRunSummary> = emptyList(),
)

@Serializable
data class AgentRunRecord(
    val type: String = "",
    @SerialName("run_id") val runId: String = "",
    @SerialName("created_at") val createdAt: String = "",
    /** 记录载荷结构随 `type` 变化，按 `JsonObject` 保留原样，由分析层取字段。 */
    val data: JsonObject? = null,
)

@Serializable
data class AgentRunTrace(
    val summary: AgentRunSummary = AgentRunSummary(),
    val children: List<AgentRunSummary> = emptyList(),
    val records: List<AgentRunRecord> = emptyList(),
    val truncated: Boolean = false,
)

/** 导出结果：后端返回 `application/x-ndjson`，文件名取自 `Content-Disposition`（缺省 `<runId>.jsonl`）。 */
data class AgentRunExport(
    val runId: String,
    val filename: String,
    val bytes: ByteArray,
)

/**
 * 从 `Content-Disposition` 取附件文件名。
 *
 * 后端同时下发 `filename="x.jsonl"` 与 RFC 5987 的 `filename*=UTF-8''x.jsonl`。旧实现直接
 * `substringAfter("filename=")`，会把 `"; filename*=UTF-8''…` 一并当成文件名，导出的缓存文件
 * 因此带上整段参数（2026-11-01 真机日志 `file=run-….jsonl"; filename*=UTF-8''…`）。
 * 这里按 RFC 优先级取 `filename*`（percent-decoding 后），再做路径分隔符清洗，缺省回退。
 */
internal fun attachmentFilename(disposition: String, fallback: String): String {
    if (disposition.isBlank()) return fallback
    val extended = Regex("""filename\*\s*=\s*([^;]+)""", RegexOption.IGNORE_CASE)
        .find(disposition)?.groupValues?.get(1)?.trim()?.trim('"')
    val plain = Regex("""filename\s*=\s*("([^"]*)"|[^;]+)""", RegexOption.IGNORE_CASE)
        .find(disposition)
        ?.let { match -> match.groupValues[2].ifBlank { match.groupValues[1] } }
        ?.trim()
        ?.trim('"')
    val raw = extended ?: plain ?: return fallback
    // RFC 5987 形如 `UTF-8''run-….jsonl`；没有 charset 前缀时按原文使用。
    val encoded = if (raw.contains("''")) raw.substringAfter("''") else raw
    val decoded = runCatching { URLDecoder.decode(encoded, "UTF-8") }.getOrDefault(encoded)
    return decoded.substringAfterLast('/').substringAfterLast('\\').trim().ifBlank { fallback }
}
