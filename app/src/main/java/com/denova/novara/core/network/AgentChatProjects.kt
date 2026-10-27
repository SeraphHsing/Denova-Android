package com.denova.novara.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 工作台（P4-B / WB-001 / WB-002）的项目与会话契约。
 *
 * 口径来源：固定 Web 基线 `web/src/features/agent-chat/api.ts` 与后端
 * `internal/app/agentchat/contracts.go`（`Project` / `Session` / `HistoryItem`）。
 * 这些接口**不隐式切换全局写作工作区**：`current` 只是「后端当前打开的项目」标记。
 */
@Serializable
data class AgentChatSession(
    val id: String = "",
    @SerialName("custom_agent_id") val customAgentId: String? = null,
    val title: String = "",
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
    @SerialName("message_count") val messageCount: Int = 0,
    /** AgentChat 没有单例 active 会话；`running` 才是该会话的执行状态。 */
    val running: Boolean = false,
    val active: Boolean = false,
)

/** `type`：`book` / `general` / `agents`；`status`：`available` / `missing` / `archived`。 */
@Serializable
data class AgentChatProject(
    val id: String = "",
    val type: String = "general",
    val path: String = "",
    val name: String = "",
    val status: String = "available",
    /** 后端当前打开的项目（不代表客户端选中的会话）。 */
    val current: Boolean = false,
    /** 截断前的会话总数。 */
    val total: Int = 0,
    val sessions: List<AgentChatSession> = emptyList(),
    /** 单个项目读取失败时的原因；不能因为一个坏项目清空整棵树。 */
    val error: String? = null,
)

@Serializable
data class AgentChatProjectsResponse(val projects: List<AgentChatProject> = emptyList())

/** `GET /api/agent-chat/activity`：只在任务状态变化时刷新的轻量心跳。 */
@Serializable
data class AgentChatActivityBinding(
    @SerialName("project_id") val projectId: String = "",
    @SerialName("session_id") val sessionId: String = "",
)

@Serializable
data class AgentChatActivityResponse(val bindings: List<AgentChatActivityBinding> = emptyList())

@Serializable
data class AgentChatHistoryItem(
    @SerialName("project_id") val projectId: String = "",
    @SerialName("project_name") val projectName: String = "",
    val session: AgentChatSession = AgentChatSession(),
    val excerpt: String = "",
)

@Serializable
data class AgentChatHistoryPage(
    val items: List<AgentChatHistoryItem> = emptyList(),
    val total: Int = 0,
    val offset: Int = 0,
    @SerialName("has_more") val hasMore: Boolean = false,
)

/** 宿主目录选择结果（`POST /api/host/dialogs/directory`）。 */
@Serializable
data class HostDirectorySelection(
    val path: String = "",
    val canceled: Boolean = false,
)

/** `POST /api/agent-chat/projects`：登记一个已存在的绝对路径。 */
@Serializable
data class AddAgentChatProjectRequest(val path: String)

/** `POST /api/agent-chat/projects/directory`：在宿主上新建 General 项目目录。 */
@Serializable
data class CreateAgentChatDirectoryRequest(
    val name: String,
    @SerialName("parent_directory") val parentDirectory: String? = null,
)

/** `PATCH /api/agent-chat/projects/:id`：一次只能改名称或路径之一。 */
@Serializable
data class UpdateAgentChatProjectRequest(
    val name: String? = null,
    val path: String? = null,
)

@Serializable
data class ReorderAgentChatProjectsRequest(@SerialName("project_ids") val projectIds: List<String>)

@Serializable
data class CreateAgentChatSessionRequest(
    val title: String = "",
    @SerialName("custom_agent_id") val customAgentId: String? = null,
)

@Serializable
data class RenameAgentChatSessionRequest(
    @SerialName("session_id") val sessionId: String,
    val title: String,
)

@Serializable
data class DeleteAgentChatSessionRequest(@SerialName("session_id") val sessionId: String)

@Serializable
data class HostDirectoryRequest(@SerialName("initial_path") val initialPath: String = "")
