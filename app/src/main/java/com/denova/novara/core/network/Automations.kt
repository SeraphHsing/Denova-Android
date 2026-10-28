package com.denova.novara.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 自动化契约（P4-D / AUT-001…004），对齐固定基线 `85ec97c`：
 *
 * - `GET /api/automations/catalog` → `{projects:[{project_id,tasks,inbox,error?}]}`（逐项目）
 * - `GET /api/automations?project_id=` → `{tasks:[Task]}`
 * - `POST /api/automations` body = TaskDefinition → `Task`
 * - `PATCH /api/automations/:id` body = 可编辑字段 + **`base_revision`（必填）** → `Task`
 * - `DELETE /api/automations/:id` → `{message}`（服务端按归档 tombstone 处理）
 * - `POST /api/automations/:id/check` → `{items:[InboxItem]}`
 * - `POST /api/automations/:id/run` body `{command_id}` → **202** `{run}`
 * - `GET /api/automations/inbox?project_id=` → `{items}`
 * - `POST /api/automations/inbox/:id/{read,confirm,dismiss}` → Item / `{item,run?}` / Item
 *
 * 基线没有「详情 / 运行记录列表 / 复制 / 启停专用端点 / webhook 触发器」：运行记录只能读
 * `task.recent_runs`（服务端保留最近 20 条）。
 */
@Serializable
data class AutomationTarget(
    val kind: String = "",
    @SerialName("project_id") val projectId: String = "",
    val workspace: String = "",
)

@Serializable
data class AutomationSchedule(
    val kind: String = "manual",
    @SerialName("every_hours") val everyHours: Int? = null,
    val weekday: Int? = null,
    @SerialName("day_of_month") val dayOfMonth: Int? = null,
    val hour: Int = 0,
    val minute: Int = 0,
    /** 服务端按 kind 生成，客户端只读展示（基线没有 cron 输入框）。 */
    val cron: String = "",
)

@Serializable
data class AutomationTrigger(
    val id: String = "",
    val type: String = "schedule",
    val enabled: Boolean = true,
    val name: String = "",
    @SerialName("action_policy") val actionPolicy: String = "",
    @SerialName("notify_policy") val notifyPolicy: String = "",
    val schedule: AutomationSchedule? = null,
    @SerialName("semantic_condition") val semanticCondition: String = "",
    @SerialName("chapter_batch_size") val chapterBatchSize: Int? = null,
)

@Serializable
data class AutomationRunRecord(
    val id: String = "",
    @SerialName("task_id") val taskId: String = "",
    @SerialName("session_id") val sessionId: String = "",
    val status: String = "",
    val trigger: String = "",
    val summary: String = "",
    val error: String = "",
    @SerialName("started_at") val startedAt: String = "",
    @SerialName("finished_at") val finishedAt: String = "",
    @SerialName("output_path") val outputPath: String = "",
)

@Serializable
data class AutomationTriggerEvidence(
    val source: String = "",
    val title: String = "",
    val ref: String = "",
    val snippet: String = "",
)

@Serializable
data class AutomationInboxItem(
    val id: String = "",
    @SerialName("task_id") val taskId: String = "",
    @SerialName("trigger_id") val triggerId: String = "",
    val purpose: String = "",
    val status: String = "",
    /** `auto_run` / `confirm` / `notify_only`（服务端 `EffectiveActionPolicy` 目前恒为 auto_run）。 */
    @SerialName("action_policy") val actionPolicy: String = "",
    val title: String = "",
    val summary: String = "",
    @SerialName("action_error") val actionError: String = "",
    val evidence: List<AutomationTriggerEvidence> = emptyList(),
    @SerialName("run_id") val runId: String = "",
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("read_at") val readAt: String = "",
)

@Serializable
data class AutomationTask(
    val id: String = "",
    @SerialName("catalog_id") val catalogId: String = "",
    val revision: String = "",
    val scope: String = "",
    val target: AutomationTarget = AutomationTarget(),
    val enabled: Boolean = false,
    val name: String = "",
    val template: String = "",
    val prompt: String = "",
    @SerialName("model_profile_id") val modelProfileId: String = "",
    val schedule: AutomationSchedule = AutomationSchedule(),
    val triggers: List<AutomationTrigger> = emptyList(),
    @SerialName("default_action_policy") val defaultActionPolicy: String = "",
    @SerialName("session_strategy") val sessionStrategy: String = "per_run",
    @SerialName("last_run") val lastRun: AutomationRunRecord? = null,
    @SerialName("recent_runs") val recentRuns: List<AutomationRunRecord> = emptyList(),
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
) {
    /** 客户端身份键：`catalog_id || id`（对齐基线 `automation-catalog.ts`）。 */
    val key: String get() = catalogId.ifBlank { id }

    val projectId: String get() = target.projectId
}

@Serializable
data class AutomationProjectGroup(
    @SerialName("project_id") val projectId: String = "",
    val tasks: List<AutomationTask> = emptyList(),
    val inbox: List<AutomationInboxItem> = emptyList(),
    val error: String = "",
)

@Serializable
data class AutomationCatalog(val projects: List<AutomationProjectGroup> = emptyList())

@Serializable
data class AutomationTasksResponse(val tasks: List<AutomationTask> = emptyList())

@Serializable
data class AutomationInboxResponse(val items: List<AutomationInboxItem> = emptyList())

@Serializable
data class AutomationCheckResponse(val items: List<AutomationInboxItem> = emptyList())

@Serializable
data class AutomationRunResponse(val run: AutomationRunRecord = AutomationRunRecord())

@Serializable
data class AutomationConfirmResponse(
    val item: AutomationInboxItem = AutomationInboxItem(),
    val run: AutomationRunRecord? = null,
)

/** `POST /api/automations`：新建时目标项目必填（服务端拒绝 `scope=user`）。 */
@Serializable
data class AutomationCreateRequest(
    val scope: String = "workspace",
    val target: AutomationTarget,
    val enabled: Boolean = true,
    val name: String = "",
    val template: String = "custom_prompt",
    val prompt: String = "",
    @SerialName("model_profile_id") val modelProfileId: String = "",
    val schedule: AutomationSchedule = AutomationSchedule(),
    val triggers: List<AutomationTrigger> = emptyList(),
    @SerialName("default_action_policy") val defaultActionPolicy: String = "auto_run",
    @SerialName("session_strategy") val sessionStrategy: String = "per_run",
)

/** `PATCH /api/automations/:id`：服务端要求 `base_revision`，缺省返回 `base_revision_required`。 */
@Serializable
data class AutomationUpdateRequest(
    val enabled: Boolean,
    val prompt: String,
    @SerialName("model_profile_id") val modelProfileId: String = "",
    @SerialName("session_strategy") val sessionStrategy: String = "per_run",
    @SerialName("base_revision") val baseRevision: String,
    val name: String? = null,
    val template: String? = null,
    val schedule: AutomationSchedule? = null,
    val triggers: List<AutomationTrigger>? = null,
)

/** `POST /api/automations/:id/run`：`command_id` 是幂等键，重复提交返回 409。 */
@Serializable
data class AutomationRunRequest(
    @SerialName("command_id") val commandId: String,
)

/** `GET /api/automations/templates?locale=`：创建面板的模板下拉。 */
@Serializable
data class AutomationTemplateDefaults(
    val enabled: Boolean = true,
    val name: String = "",
    val template: String = "",
    val prompt: String = "",
    @SerialName("model_profile_id") val modelProfileId: String = "",
    val schedule: AutomationSchedule = AutomationSchedule(),
    @SerialName("session_strategy") val sessionStrategy: String = "per_run",
)

@Serializable
data class AutomationTemplate(
    val id: String = "",
    val description: String = "",
    @SerialName("target_kinds") val targetKinds: List<String> = emptyList(),
    val defaults: AutomationTemplateDefaults = AutomationTemplateDefaults(),
)

@Serializable
data class AutomationTemplatesResponse(val templates: List<AutomationTemplate> = emptyList())
