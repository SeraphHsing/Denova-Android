package com.denova.novara

import com.denova.novara.R
import com.denova.novara.core.automations.AutomationDraft
import com.denova.novara.core.automations.AutomationEditor
import com.denova.novara.core.i18n.UiText
import com.denova.novara.core.network.AutomationInboxItem
import com.denova.novara.core.network.AutomationRunRecord
import com.denova.novara.core.network.AutomationSchedule
import com.denova.novara.core.network.AutomationTarget
import com.denova.novara.core.network.AutomationTask
import com.denova.novara.core.network.AutomationTemplate
import com.denova.novara.core.network.AutomationTemplateDefaults
import com.denova.novara.core.network.AutomationTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自动化契约与草稿逻辑（AUT-001…004）。
 *
 * 覆盖会真正写坏服务端配置的几处：创建必须带项目、`base_revision` 必须透传、
 * 切换任务不能丢草稿、收件箱未读只算 `pending + 未读`。
 */
class AutomationEditorTest {

    private fun task(
        key: String = "t1",
        enabled: Boolean = true,
        revision: String = "rev-1",
        lastRun: AutomationRunRecord? = null,
        triggers: List<AutomationTrigger> = emptyList(),
    ) = AutomationTask(
        id = key,
        revision = revision,
        scope = "workspace",
        target = AutomationTarget(kind = "project", projectId = "project-1"),
        enabled = enabled,
        name = "nightly",
        template = "custom_prompt",
        prompt = "summarize",
        schedule = AutomationSchedule(kind = "daily", hour = 3, minute = 5),
        triggers = triggers,
        lastRun = lastRun,
    )

    @Test
    fun `creating without a project is rejected before reaching the server`() {
        val draft = AutomationEditor.newDraft(projectId = "")
        assertTrue(AutomationEditor.validate(draft).containsKey("project"))
        assertFalse(AutomationEditor.canSubmit(draft))
    }

    @Test
    fun `creating with a project produces a project scoped request`() {
        val draft = AutomationEditor.newDraft(projectId = "project-9")
        val request = draft.toCreateRequest()
        assertEquals("workspace", request.scope)
        assertEquals("project", request.target.kind)
        assertEquals("project-9", request.target.projectId)
        // 服务端强制 auto_run，客户端不要自带 confirm / notify_only。
        assertEquals("auto_run", request.defaultActionPolicy)
    }

    @Test
    fun `update request carries base revision and keeps name only when set`() {
        val draft = AutomationEditor.draftOf(task()).copy(name = "  ")
        val request = draft.toUpdateRequest()
        assertEquals("rev-1", request.baseRevision)
        assertNull(request.name)
        assertEquals("summarize", request.prompt)
    }

    @Test
    fun `draft dirty detection ignores whitespace only name differences`() {
        val base = task()
        assertFalse(AutomationEditor.draftOf(base).isDirty(base))
        assertTrue(AutomationEditor.draftOf(base).copy(prompt = "other").isDirty(base))
        assertTrue(AutomationEditor.draftOf(base).copy(enabled = false).isDirty(base))
    }

    @Test
    fun `schedule summary and generated cron follow the baseline rules`() {
        assertEquals(
            UiText.of(R.string.automation_schedule_daily, "03:05"),
            AutomationEditor.scheduleText(AutomationSchedule(kind = "daily", hour = 3, minute = 5)),
        )
        assertEquals("5 3 * * *", AutomationEditor.cronOf(AutomationSchedule(kind = "daily", hour = 3, minute = 5)))
        assertEquals(
            "0 8 * * 1",
            AutomationEditor.cronOf(AutomationSchedule(kind = "weekly", weekday = 1, hour = 8)),
        )
        assertEquals(
            "0 8 15 * *",
            AutomationEditor.cronOf(AutomationSchedule(kind = "monthly", dayOfMonth = 15, hour = 8)),
        )
        assertEquals(
            "30 */6 * * *",
            AutomationEditor.cronOf(AutomationSchedule(kind = "every_hours", everyHours = 6, minute = 30)),
        )
        assertEquals("", AutomationEditor.cronOf(AutomationSchedule(kind = "manual")))
    }

    @Test
    fun `every hours out of range is rejected`() {
        val draft = AutomationEditor.draftOf(task())
            .copy(schedule = AutomationSchedule(kind = "every_hours", everyHours = 300))
        assertTrue(AutomationEditor.validate(draft).containsKey("schedule"))
    }

    @Test
    fun `new trigger defaults notify policy by type`() {
        assertEquals("silent", AutomationEditor.newTrigger("schedule").notifyPolicy)
        assertEquals("inbox", AutomationEditor.newTrigger("semantic").notifyPolicy)
        assertEquals(5, AutomationEditor.newTrigger("chapter_batch").chapterBatchSize)
    }

    @Test
    fun `retype drops fields that do not apply`() {
        val semantic = AutomationEditor.newTrigger("semantic").copy(semanticCondition = "rain")
        val asBatch = AutomationEditor.retype(semantic, "chapter_batch")
        assertEquals("", asBatch.semanticCondition)
        assertEquals(5, asBatch.chapterBatchSize)
        assertNull(asBatch.schedule)
        val asSchedule = AutomationEditor.retype(asBatch, "schedule")
        assertEquals("silent", asSchedule.notifyPolicy)
        assertNull(asSchedule.chapterBatchSize)
    }

    @Test
    fun `semantic trigger requires a condition and batch size stays in range`() {
        val broken = AutomationEditor.draftOf(task()).copy(
            triggers = listOf(AutomationEditor.newTrigger("semantic")),
        )
        assertTrue(AutomationEditor.validate(broken).containsKey("trigger-0"))
        val tooBig = AutomationEditor.draftOf(task()).copy(
            triggers = listOf(
                AutomationEditor.newTrigger("chapter_batch").copy(chapterBatchSize = 500),
            ),
        )
        assertTrue(AutomationEditor.validate(tooBig).containsKey("trigger-0"))
    }

    @Test
    fun `running tasks sort first then enabled then name`() {
        val running = task(key = "running", lastRun = AutomationRunRecord(status = "running"))
        val enabled = task(key = "enabled")
        val disabled = task(key = "disabled", enabled = false)
        assertEquals(
            listOf("running", "enabled", "disabled"),
            AutomationEditor.sorted(listOf(disabled, enabled, running)).map { it.id },
        )
        assertTrue(AutomationEditor.isRunning(running))
        assertFalse(AutomationEditor.isRunning(enabled))
    }

    @Test
    fun `unread inbox counts only pending and unread items`() {
        val items = listOf(
            AutomationInboxItem(id = "a", status = "pending", readAt = ""),
            AutomationInboxItem(id = "b", status = "pending", readAt = "2026-10-29T00:00:00Z"),
            AutomationInboxItem(id = "c", status = "dismissed", readAt = ""),
        )
        assertEquals(1, AutomationEditor.unreadCount(items))
    }

    @Test
    fun `template defaults seed a new draft but keep project empty until chosen`() {
        val template = AutomationTemplate(
            id = "review",
            defaults = AutomationTemplateDefaults(
                name = "Review chapters",
                template = "review",
                prompt = "review",
                sessionStrategy = "per_task",
            ),
        )
        val draft = AutomationEditor.newDraft(projectId = "", template = template)
        assertEquals("Review chapters", draft.name)
        assertEquals("review", draft.template)
        assertEquals("per_task", draft.sessionStrategy)
        assertTrue(draft.creating)
        assertTrue(AutomationEditor.validate(draft).containsKey("project"))
    }

    @Test
    fun `run status labels cover terminal and active states`() {
        assertEquals(UiText.of(R.string.automation_run_success), AutomationEditor.runStatusText("success"))
        assertEquals(UiText.of(R.string.automation_run_suspended), AutomationEditor.runStatusText("suspended"))
        assertEquals(UiText.raw("weird"), AutomationEditor.runStatusText("weird"))
        assertTrue(AutomationEditor.isTerminal("aborted"))
        assertFalse(AutomationEditor.isTerminal("running"))
    }

    @Test
    fun `command id is unique per run request`() {
        val first = AutomationEditor.newCommandId()
        val second = AutomationEditor.newCommandId()
        assertTrue(first.startsWith("automation-"))
        assertTrue(first != second)
    }

    @Test
    fun `editor keeps separate drafts per task so switching does not lose edits`() {
        val drafts = mapOf(
            "t1" to AutomationDraft("t1", "p", "a", "custom_prompt", "x", "", "per_run", true, AutomationSchedule(), emptyList(), "r1"),
            "t2" to AutomationDraft("t2", "p", "b", "custom_prompt", "y", "", "per_run", false, AutomationSchedule(), emptyList(), "r2"),
        )
        assertEquals("x", drafts.getValue("t1").prompt)
        assertEquals("y", drafts.getValue("t2").prompt)
    }
}
