package com.denova.novara.feature.trajectory

import com.denova.novara.core.network.AgentRunSummary
import java.time.Instant
import java.time.OffsetDateTime

/** 运行记录的两种组织方式（基线 `trajectory.runs.byRun` / `bySession`）。 */
enum class RunOrganization { RUN, SESSION }

data class SessionRunGroup(
    val key: String,
    val sessionId: String,
    val sessionTitle: String,
    val runs: List<AgentRunSummary>,
)

data class ProjectRunGroup(
    val key: String,
    val name: String,
    val runCount: Int,
    val sessions: List<SessionRunGroup>,
)

/** 会话分组键：`project_id + session_id`，没有 session 的归到 `no-session` 桶。 */
fun sessionGroupKey(run: AgentRunSummary): String {
    val sessionId = run.sessionId.trim()
    return if (sessionId.isEmpty()) "${run.projectId}\u0000no-session" else "${run.projectId}\u0000$sessionId"
}

/** 按项目 → 会话分组（对应基线 `groupRunsByProject`，组内按创建时间倒序）。 */
fun groupRunsByProject(runs: List<AgentRunSummary>): List<ProjectRunGroup> {
    val projects = LinkedHashMap<String, MutableProject>()
    val sorted = runs.sortedByDescending { parseTimestamp(it.createdAt) ?: 0L }
    for (run in sorted) {
        val project = projects.getOrPut(run.projectId) { MutableProject(run.projectId, run.projectName) }
        project.runCount += 1
        val key = sessionGroupKey(run)
        val session = project.sessions.getOrPut(key) {
            MutableSession(key, run.sessionId.trim(), run.sessionTitle.trim())
        }
        if (session.sessionTitle.isEmpty() && run.sessionTitle.trim().isNotEmpty()) {
            session.sessionTitle = run.sessionTitle.trim()
        }
        session.runs += run
    }
    return projects.values.map { project ->
        ProjectRunGroup(
            key = project.key,
            name = project.name.ifBlank { project.key },
            runCount = project.runCount,
            sessions = project.sessions.values.map {
                SessionRunGroup(
                    key = it.key,
                    sessionId = it.sessionId,
                    sessionTitle = it.sessionTitle,
                    runs = it.runs,
                )
            },
        )
    }
}

/** 可选 Agent 筛选项（去重 + 排序，空 `agent_kind` 不参与）。 */
fun agentOptions(runs: List<AgentRunSummary>): List<String> =
    runs.mapNotNull { it.agentKind.trim().takeIf { kind -> kind.isNotEmpty() } }
        .distinct()
        .sorted()

/** 按 Agent 过滤；`filter` 为空表示全部。 */
fun visibleRuns(runs: List<AgentRunSummary>, filter: String): List<AgentRunSummary> =
    if (filter.isBlank()) runs else runs.filter { it.agentKind == filter }

/** 列表项次标题（分组态显示 agent，平铺态显示项目名），与基线一致。 */
fun runSubtitle(run: AgentRunSummary, grouped: Boolean, fallback: String): String =
    if (grouped) run.agentName.ifBlank { run.agentKind.ifBlank { fallback } } else run.projectName

/** 状态点/文案归一：`success`/`completed` 视为完成，`running` 提示进行中，其余按失败色。 */
enum class RunStatusTone { SUCCESS, RUNNING, FAILURE }

fun runStatusTone(status: String): RunStatusTone = when (status.lowercase()) {
    "success", "completed" -> RunStatusTone.SUCCESS
    "running" -> RunStatusTone.RUNNING
    else -> RunStatusTone.FAILURE
}

/** `created_at`（RFC3339）→ `MM-dd HH:mm` 本地时间；无效时返回空串，让 UI 显示 `—`。 */
fun formatRunTimestamp(raw: String): String {
    val millis = parseTimestamp(raw) ?: return ""
    val zone = java.time.ZoneId.systemDefault()
    val time = Instant.ofEpochMilli(millis).atZone(zone)
    return String.format(
        java.util.Locale.ROOT,
        "%02d-%02d %02d:%02d",
        time.monthValue,
        time.dayOfMonth,
        time.hour,
        time.minute,
    )
}

private data class MutableProject(
    val key: String,
    val name: String,
    var runCount: Int = 0,
    val sessions: LinkedHashMap<String, MutableSession> = LinkedHashMap(),
)

private data class MutableSession(
    val key: String,
    val sessionId: String,
    var sessionTitle: String,
    val runs: MutableList<AgentRunSummary> = mutableListOf(),
)
