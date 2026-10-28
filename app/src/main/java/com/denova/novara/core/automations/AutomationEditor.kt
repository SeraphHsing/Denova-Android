package com.denova.novara.core.automations

import com.denova.novara.R
import com.denova.novara.core.i18n.UiText
import com.denova.novara.core.network.AutomationCreateRequest
import com.denova.novara.core.network.AutomationInboxItem
import com.denova.novara.core.network.AutomationSchedule
import com.denova.novara.core.network.AutomationTarget
import com.denova.novara.core.network.AutomationTask
import com.denova.novara.core.network.AutomationTemplate
import com.denova.novara.core.network.AutomationTrigger
import com.denova.novara.core.network.AutomationUpdateRequest
import java.util.UUID

/**
 * 自动化的纯逻辑（P4-D / AUT-001…004）：
 * 草稿往返、必填校验、触发条件默认值与规范化、调度摘要、运行状态判定。
 *
 * 事实来源：基线 `web/src/lib/api-client/automations.ts`、
 * `web/src/features/automations/{AutomationConfigPanel,AutomationTriggerEditor}.tsx`、
 * 后端 `internal/automation/{types,schedule}.go`。关键口径：
 * - 触发器类型白名单 `workspace:[schedule,chapter_batch,semantic]`、`user:[schedule]`；
 * - `notify_policy` 默认 `schedule → silent`、其它 `→ inbox`；
 * - `chapter_batch_size` 默认 5（1–100）；
 * - `cron` 由服务端按 kind 生成，客户端只读；
 * - `default_action_policy` 服务端强制 `auto_run`；
 * - 必填只有「创建时的目标项目」与「运行时的 `command_id`」。
 */
object AutomationEditor {

    const val TEMPLATE_CUSTOM = "custom_prompt"
    val TEMPLATES = listOf("custom_prompt", "continue_writing", "review", "memory_consolidation")
    val TRIGGER_TYPES = listOf("schedule", "chapter_batch", "semantic")
    val NOTIFY_POLICIES = listOf("inbox", "silent")
    val SCHEDULE_KINDS = listOf("manual", "daily", "weekly", "monthly", "every_hours")
    val SESSION_STRATEGIES = listOf("per_run", "per_task")

    private val RUNNING_STATUS = setOf("pending", "accepted", "queued", "running", "suspended")
    private val TERMINAL_STATUS = setOf("success", "failed", "aborted")

    /** 新建面板的草稿：目标项目必填，其余取服务端模板默认值。 */
    fun newDraft(projectId: String = "", template: AutomationTemplate? = null): AutomationDraft {
        val defaults = template?.defaults
        return AutomationDraft(
            taskKey = "",
            projectId = projectId,
            name = defaults?.name.orEmpty(),
            template = defaults?.template?.takeIf { it.isNotBlank() } ?: TEMPLATE_CUSTOM,
            prompt = defaults?.prompt.orEmpty(),
            modelProfileId = defaults?.modelProfileId.orEmpty(),
            sessionStrategy = defaults?.sessionStrategy?.takeIf { it.isNotBlank() } ?: "per_run",
            enabled = defaults?.enabled ?: true,
            schedule = defaults?.schedule ?: AutomationSchedule(),
            triggers = emptyList(),
            baseRevision = "",
            creating = true,
        )
    }

    fun draftOf(task: AutomationTask): AutomationDraft = AutomationDraft(
        taskKey = task.key,
        projectId = task.projectId,
        name = task.name,
        template = task.template.ifBlank { TEMPLATE_CUSTOM },
        prompt = task.prompt,
        modelProfileId = task.modelProfileId,
        sessionStrategy = task.sessionStrategy.ifBlank { "per_run" },
        enabled = task.enabled,
        schedule = task.schedule,
        triggers = task.triggers,
        baseRevision = task.revision,
        creating = false,
    )

    fun isRunning(task: AutomationTask): Boolean =
        task.lastRun?.status?.let { it in RUNNING_STATUS } ?: task.recentRuns.any { it.status in RUNNING_STATUS }

    /** 列表排序：运行中优先、其次启用、最后按名称（对齐基线摘要优先级）。 */
    fun sorted(tasks: List<AutomationTask>): List<AutomationTask> =
        tasks.sortedWith(
            compareByDescending<AutomationTask> { isRunning(it) }
                .thenByDescending { it.enabled }
                .thenBy { it.name.lowercase() },
        )

    /** 列表摘要：运行中 > 启用 > 停用。 */
    fun statusText(task: AutomationTask): UiText = when {
        isRunning(task) -> UiText.of(R.string.automation_status_running)
        task.enabled -> UiText.of(R.string.automation_status_enabled)
        else -> UiText.of(R.string.automation_status_disabled)
    }

    fun runStatusText(status: String): UiText = when (status) {
        "pending" -> UiText.of(R.string.automation_run_pending)
        "accepted" -> UiText.of(R.string.automation_run_accepted)
        "queued" -> UiText.of(R.string.automation_run_queued)
        "running" -> UiText.of(R.string.automation_run_running)
        "suspended" -> UiText.of(R.string.automation_run_suspended)
        "success" -> UiText.of(R.string.automation_run_success)
        "failed" -> UiText.of(R.string.automation_run_failed)
        "aborted" -> UiText.of(R.string.automation_run_aborted)
        else -> UiText.raw(status)
    }

    /** 调度摘要（时区按服务器本地时区，基线不显示时区）。 */
    fun scheduleText(schedule: AutomationSchedule?): UiText {
        val s = schedule ?: return UiText.of(R.string.automation_schedule_manual)
        val clock = "%02d:%02d".format(s.hour, s.minute)
        return when (s.kind) {
            "daily" -> UiText.of(R.string.automation_schedule_daily, clock)
            "weekly" -> UiText.of(
                R.string.automation_schedule_weekly,
                weekdayName(s.weekday ?: 1),
                clock,
            )

            "monthly" -> UiText.of(
                R.string.automation_schedule_monthly,
                s.dayOfMonth ?: 1,
                clock,
            )

            "every_hours" -> UiText.of(
                R.string.automation_schedule_every_hours,
                s.everyHours ?: 1,
                s.minute,
            )

            else -> UiText.of(R.string.automation_schedule_manual, clock)
        }
    }

    private fun weekdayName(value: Int): String = when (value) {
        0 -> "Sun"
        1 -> "Mon"
        2 -> "Tue"
        3 -> "Wed"
        4 -> "Thu"
        5 -> "Fri"
        else -> "Sat"
    }

    /** 新触发器：类型决定默认通知策略（`schedule → silent`、其它 `inbox`）。 */
    fun newTrigger(type: String): AutomationTrigger = AutomationTrigger(
        id = "trg-" + UUID.randomUUID().toString().take(12),
        type = type,
        enabled = true,
        notifyPolicy = if (type == "schedule") "silent" else "inbox",
        schedule = if (type == "schedule") AutomationSchedule(kind = "daily", hour = 3, minute = 0) else null,
        chapterBatchSize = if (type == "chapter_batch") 5 else null,
    )

    /**
     * 切类型时清掉不适用的字段（对齐基线 `normalizeDraftTrigger`）：
     * 语义触发不接受 schedule，批量触发不接受 semantic_condition。
     */
    fun retype(trigger: AutomationTrigger, type: String): AutomationTrigger = trigger.copy(
        type = type,
        notifyPolicy = if (type == "schedule") "silent" else "inbox",
        schedule = if (type == "schedule") {
            trigger.schedule ?: AutomationSchedule(kind = "daily", hour = 3, minute = 0)
        } else {
            null
        },
        semanticCondition = if (type == "semantic") trigger.semanticCondition else "",
        chapterBatchSize = if (type == "chapter_batch") trigger.chapterBatchSize ?: 5 else null,
    )

    fun validate(draft: AutomationDraft): Map<String, UiText> {
        val errors = LinkedHashMap<String, UiText>()
        if (draft.creating && draft.projectId.isBlank()) {
            errors["project"] = UiText.of(R.string.automation_error_project_required)
        }
        val schedule = draft.schedule
        if (schedule.kind == "every_hours" && (schedule.everyHours ?: 1) !in 1..168) {
            errors["schedule"] = UiText.of(R.string.automation_error_every_hours)
        }
        if (schedule.hour !in 0..23 || schedule.minute !in 0..59) {
            errors["schedule"] = UiText.of(R.string.automation_error_clock)
        }
        draft.triggers.forEachIndexed { index, trigger ->
            if (trigger.type == "chapter_batch") {
                val size = trigger.chapterBatchSize ?: 5
                if (size !in 1..100) {
                    errors["trigger-$index"] = UiText.of(R.string.automation_error_batch_size)
                }
            }
            if (trigger.type == "semantic" && trigger.semanticCondition.isBlank()) {
                errors["trigger-$index"] = UiText.of(R.string.automation_error_condition)
            }
        }
        return errors
    }

    fun canSubmit(draft: AutomationDraft): Boolean = validate(draft).isEmpty()

    /**
     * 服务端按 kind 生成 cron；这里复刻同一规则用于本地预览（`schedule.go`）。
     */
    fun cronOf(schedule: AutomationSchedule): String = when (schedule.kind) {
        "daily" -> "${schedule.minute} ${schedule.hour} * * *"
        "weekly" -> "${schedule.minute} ${schedule.hour} * * ${schedule.weekday ?: 1}"
        "monthly" -> "${schedule.minute} ${schedule.hour} ${schedule.dayOfMonth ?: 1} * *"
        "every_hours" -> "${schedule.minute} */${(schedule.everyHours ?: 1).coerceIn(1, 168)} * * *"
        else -> ""
    }

    /** 运行一次需要客户端生成的幂等键。 */
    fun newCommandId(): String = "automation-" + UUID.randomUUID().toString()

    fun unreadCount(items: List<AutomationInboxItem>): Int =
        items.count { it.readAt.isBlank() && it.status == "pending" }

    fun isTerminal(status: String): Boolean = status in TERMINAL_STATUS
}

/** 编辑面板的草稿（创建与编辑共用一个结构）。 */
data class AutomationDraft(
    val taskKey: String,
    val projectId: String,
    val name: String,
    val template: String,
    val prompt: String,
    val modelProfileId: String,
    val sessionStrategy: String,
    val enabled: Boolean,
    val schedule: AutomationSchedule,
    val triggers: List<AutomationTrigger>,
    val baseRevision: String,
    val creating: Boolean = false,
) {
    /** 与给定任务是否有差异（用于「未保存」提示，避免无改动也发 PATCH）。 */
    fun isDirty(base: AutomationTask?): Boolean {
        if (base == null) return true
        return name != base.name ||
            template != base.template.ifBlank { AutomationEditor.TEMPLATE_CUSTOM } ||
            prompt != base.prompt ||
            modelProfileId != base.modelProfileId ||
            sessionStrategy != base.sessionStrategy.ifBlank { "per_run" } ||
            enabled != base.enabled ||
            schedule != base.schedule ||
            triggers != base.triggers
    }

    fun toCreateRequest(): AutomationCreateRequest = AutomationCreateRequest(
        scope = "workspace",
        target = AutomationTarget(kind = "project", projectId = projectId),
        enabled = enabled,
        name = name.trim(),
        template = template,
        prompt = prompt,
        modelProfileId = modelProfileId,
        schedule = schedule,
        triggers = triggers,
        defaultActionPolicy = "auto_run",
        sessionStrategy = sessionStrategy,
    )

    fun toUpdateRequest(): AutomationUpdateRequest = AutomationUpdateRequest(
        enabled = enabled,
        prompt = prompt,
        modelProfileId = modelProfileId,
        sessionStrategy = sessionStrategy,
        baseRevision = baseRevision,
        name = name.trim().takeIf { it.isNotBlank() },
        template = template.takeIf { it.isNotBlank() },
        schedule = schedule,
        triggers = triggers,
    )
}
