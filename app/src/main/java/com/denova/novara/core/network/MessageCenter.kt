package com.denova.novara.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 消息中心契约（P4-D / MSG-001…003），对齐固定基线 `85ec97c`：
 *
 * - `GET /api/messages` → `{items:[Message], unread_count}`（**无分页、无服务端过滤参数**，
 *   过滤在客户端做；locale 走 `X-Denova-Locale` 头）
 * - `POST /api/messages/:id/read` → 该条消息（幂等，已读直接返回）
 * - `POST /api/messages/read-all` → `{items, unread_count:0}`
 * - 基线**没有**详情、删除、单条 dismiss 端点：详情就是列表项的 `body`（Markdown）。
 *
 * 类型由服务端生成：`changelog`（产品更新）/ `automation`（自动化运行）/ `automation_action`
 * （自动化待办，`action_required=true`）。未知类型原样显示。
 */
@Serializable
data class ProductMessage(
    val id: String = "",
    val type: String = "",
    val title: String = "",
    val summary: String = "",
    val body: String = "",
    @SerialName("published_at") val publishedAt: String = "",
    @SerialName("read_at") val readAt: String = "",
    @SerialName("task_id") val taskId: String = "",
    @SerialName("run_id") val runId: String = "",
    @SerialName("inbox_id") val inboxId: String = "",
    @SerialName("project_id") val projectId: String = "",
    val workspace: String = "",
    val status: String = "",
    @SerialName("action_required") val actionRequired: Boolean = false,
) {
    val unread: Boolean get() = readAt.isBlank()

    /** 能跳回自动化：基线只在有 `task_id` 且宿主提供跳转能力时显示入口。 */
    val canOpenAutomation: Boolean get() = taskId.isNotBlank()
}

@Serializable
data class ProductMessageList(
    val items: List<ProductMessage> = emptyList(),
    @SerialName("unread_count") val unreadCount: Int = 0,
)

/** `GET /api/activity/summary`：抽屉角标只需要消息未读数。 */
@Serializable
data class ActivitySummary(
    @SerialName("message_unread_count") val messageUnreadCount: Int = 0,
    @SerialName("automation_inbox_unread_count") val automationInboxUnreadCount: Int = 0,
    @SerialName("automation_running_count") val automationRunningCount: Int = 0,
)
