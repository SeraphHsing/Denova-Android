package com.denova.novara.feature.automations

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.denova.novara.R
import com.denova.novara.core.automations.AutomationDraft
import com.denova.novara.core.automations.AutomationEditor
import com.denova.novara.core.i18n.UiText
import com.denova.novara.core.logging.DenovaLog
import com.denova.novara.core.network.AutomationInboxItem
import com.denova.novara.core.network.AutomationProjectGroup
import com.denova.novara.core.network.AutomationSchedule
import com.denova.novara.core.network.AutomationTask
import com.denova.novara.core.network.AutomationTemplate
import com.denova.novara.core.network.AutomationTrigger
import com.denova.novara.core.network.DenovaApiException
import com.denova.novara.core.network.DenovaClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 自动化页的两个窄屏标签（基线 `automations.mobile.task` / `automations.mobile.inbox`）。 */
enum class AutomationTab { TASKS, INBOX }

enum class InboxAction { READ, CONFIRM, DISMISS }

/** 自动化页面状态（P4-D / AUT-001…004）。 */
data class AutomationsUiState(
    val loading: Boolean = false,
    val error: UiText? = null,
    val notice: UiText? = null,
    val projectId: String = "",
    val groups: List<AutomationProjectGroup> = emptyList(),
    val tasks: List<AutomationTask> = emptyList(),
    val inbox: List<AutomationInboxItem> = emptyList(),
    val templates: List<AutomationTemplate> = emptyList(),
    val tab: AutomationTab = AutomationTab.TASKS,
    val selectedKey: String = "",
    /** 每个任务一份草稿：切标签 / 切任务 / 去 Agent 再回来都不丢编辑（AUT-004）。 */
    val drafts: Map<String, AutomationDraft> = emptyMap(),
    val fieldErrors: Map<String, UiText> = emptyMap(),
    val busy: Boolean = false,
    val conflict: Boolean = false,
) {
    val selected: AutomationTask? get() = tasks.firstOrNull { it.key == selectedKey }
    val draft: AutomationDraft? get() = drafts[selectedKey]
    val creating: Boolean get() = selectedKey == CREATE_KEY
    val unread: Int get() = AutomationEditor.unreadCount(inbox)
    val dirty: Boolean get() = draft?.isDirty(selected) ?: false

    companion object {
        const val CREATE_KEY = "::new"
    }
}

/**
 * 自动化：项目目录 / 任务编辑 / 触发器 / 立即运行 / 收件箱三动作（已读、确认、忽略）。
 *
 * 写入语义对齐基线：`PATCH` 必带 `base_revision`，409 时保留草稿并提示重新加载；
 * 运行前由客户端生成 `command_id` 幂等键，重复点击不会重复触发服务端副作用。
 */
class AutomationsViewModel(application: Application) : AndroidViewModel(application) {

    private var client: DenovaClient? = null
    private var templatesLoaded = false

    private val _state = MutableStateFlow(AutomationsUiState())
    val state: StateFlow<AutomationsUiState> = _state.asStateFlow()

    fun bindClient(client: DenovaClient, projectId: String) {
        // 宿主传进来的 projectID 可能为空（例如上次打开的项目已归档）：空值不能覆盖已在使用的
        // 作用域，否则「去 Agent 再回来」会把 0 个任务的空作用域顶掉当前项目与草稿（AUT-004）。
        val incoming = projectId.ifBlank { _state.value.projectId }
        val switched = this.client !== client || incoming != _state.value.projectId
        this.client = client
        if (switched) _state.value = _state.value.copy(projectId = incoming)
        if (switched || _state.value.tasks.isEmpty()) load(force = true)
        loadTemplates()
    }

    fun setProject(projectId: String) {
        if (projectId.isBlank() || _state.value.projectId == projectId) return
        _state.value = _state.value.copy(
            projectId = projectId,
            selectedKey = "",
            drafts = emptyMap(),
            error = null,
        )
        load(force = true)
    }

    fun load(force: Boolean = false) {
        val c = client ?: return
        if (_state.value.loading) return
        val projectId = _state.value.projectId
        if (projectId.isBlank() && !force) return
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            try {
                val catalog = c.automationCatalog()
                val group = catalog.projects.firstOrNull { it.projectId == projectId }
                val tasks = AutomationEditor.sorted(group?.tasks.orEmpty())
                val inbox = group?.inbox.orEmpty()
                val keepSelection = _state.value.selectedKey
                val stillThere = tasks.any { it.key == keepSelection } || keepSelection == AutomationsUiState.CREATE_KEY
                _state.value = _state.value.copy(
                    loading = false,
                    groups = catalog.projects,
                    tasks = tasks,
                    inbox = inbox,
                    selectedKey = if (stillThere) keepSelection else "",
                    drafts = if (stillThere) _state.value.drafts else emptyMap(),
                )
                DenovaLog.i(
                    "Automations",
                    "loaded project=$projectId tasks=${tasks.size} inbox=${inbox.size} " +
                        "projects=${catalog.projects.size}",
                )
            } catch (e: Exception) {
                DenovaLog.w("Automations", "load failed project=$projectId", e)
                _state.value = _state.value.copy(
                    loading = false,
                    error = failureText(e, R.string.automation_load_failed),
                )
            }
        }
    }

    private fun loadTemplates() {
        val c = client ?: return
        if (templatesLoaded) return
        viewModelScope.launch {
            runCatching { c.automationTemplates("zh-CN") }
                .onSuccess {
                    templatesLoaded = true
                    _state.value = _state.value.copy(templates = it)
                }
                .onFailure { DenovaLog.w("Automations", "templates failed", it) }
        }
    }

    fun selectTab(tab: AutomationTab) {
        _state.value = _state.value.copy(tab = tab)
    }

    fun select(key: String) {
        if (_state.value.selectedKey == key) return
        // 首次打开某任务时用服务端版本播种草稿；之后切走再切回沿用（并保留）已有草稿。
        val task = _state.value.tasks.firstOrNull { it.key == key }
        val drafts = if (task != null && !_state.value.drafts.containsKey(key)) {
            _state.value.drafts + (key to AutomationEditor.draftOf(task))
        } else {
            _state.value.drafts
        }
        _state.value = _state.value.copy(
            selectedKey = key,
            drafts = drafts,
            fieldErrors = emptyMap(),
            conflict = false,
        )
    }

    fun startCreate() {
        // 当前书籍尚未确定时（例如上次打开的项目已归档），退到目录里的第一个项目，
        // 避免落在「项目为空」的必填校验上；用户仍可在面板里改选。
        val projectId = _state.value.projectId.ifBlank {
            _state.value.groups.firstOrNull { it.projectId.isNotBlank() }?.projectId.orEmpty()
        }
        val draft = AutomationEditor.newDraft(
            projectId = projectId,
            template = _state.value.templates.firstOrNull(),
        )
        _state.value = _state.value.copy(
            selectedKey = AutomationsUiState.CREATE_KEY,
            drafts = _state.value.drafts + (AutomationsUiState.CREATE_KEY to draft),
            fieldErrors = emptyMap(),
            conflict = false,
            tab = AutomationTab.TASKS,
        )
    }

    fun cancelCreate() {
        _state.value = _state.value.copy(
            selectedKey = "",
            drafts = _state.value.drafts - AutomationsUiState.CREATE_KEY,
            fieldErrors = emptyMap(),
        )
    }

    private fun updateDraft(block: (AutomationDraft) -> AutomationDraft) {
        val key = _state.value.selectedKey
        val draft = _state.value.drafts[key] ?: return
        _state.value = _state.value.copy(
            drafts = _state.value.drafts + (key to block(draft)),
            fieldErrors = emptyMap(),
            conflict = false,
        )
    }

    fun applyTemplate(templateId: String) {
        val template = _state.value.templates.firstOrNull { it.id == templateId }
        updateDraft { draft ->
            if (template == null) {
                draft.copy(template = templateId)
            } else {
                val defaults = template.defaults
                draft.copy(
                    template = templateId,
                    name = draft.name.ifBlank { defaults.name },
                    prompt = defaults.prompt,
                    schedule = defaults.schedule,
                    sessionStrategy = defaults.sessionStrategy.ifBlank { draft.sessionStrategy },
                )
            }
        }
    }

    fun setName(value: String) = updateDraft { it.copy(name = value) }
    fun setPrompt(value: String) = updateDraft { it.copy(prompt = value) }
    fun setEnabled(value: Boolean) = updateDraft { it.copy(enabled = value) }
    fun setModelProfile(value: String) = updateDraft { it.copy(modelProfileId = value) }
    fun setSessionStrategy(value: String) = updateDraft { it.copy(sessionStrategy = value) }
    fun setProjectForCreate(projectId: String) = updateDraft { it.copy(projectId = projectId) }

    fun setScheduleKind(kind: String) = updateDraft {
        it.copy(schedule = it.schedule.copy(kind = kind))
    }

    fun setSchedule(schedule: AutomationSchedule) = updateDraft { it.copy(schedule = schedule) }

    fun addTrigger(type: String) = updateDraft { it.copy(triggers = it.triggers + AutomationEditor.newTrigger(type)) }

    fun removeTrigger(index: Int) = updateDraft {
        it.copy(triggers = it.triggers.filterIndexed { i, _ -> i != index })
    }

    fun retypeTrigger(index: Int, type: String) = updateDraft { draft ->
        draft.copy(
            triggers = draft.triggers.mapIndexed { i, trigger ->
                if (i == index) AutomationEditor.retype(trigger, type) else trigger
            },
        )
    }

    fun setTriggerNotify(index: Int, policy: String) = updateTrigger(index) { it.copy(notifyPolicy = policy) }

    fun setTriggerCondition(index: Int, text: String) =
        updateTrigger(index) { it.copy(semanticCondition = text) }

    fun setTriggerBatchSize(index: Int, value: Int) =
        updateTrigger(index) { it.copy(chapterBatchSize = value.coerceIn(1, 100)) }

    private fun updateTrigger(index: Int, block: (AutomationTrigger) -> AutomationTrigger) =
        updateDraft { draft ->
            draft.copy(
                triggers = draft.triggers.mapIndexed { i, trigger ->
                    if (i == index) block(trigger) else trigger
                },
            )
        }

    fun save() {
        val c = client ?: return
        val draft = _state.value.draft ?: return
        if (_state.value.busy) return
        val errors = AutomationEditor.validate(draft)
        if (errors.isNotEmpty()) {
            _state.value = _state.value.copy(fieldErrors = errors, error = UiText.of(R.string.automation_invalid))
            DenovaLog.w("Automations", "draft rejected locally fields=${errors.keys}")
            return
        }
        val creating = draft.creating
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null)
            try {
                val saved = if (creating) {
                    c.automationCreate(draft.toCreateRequest())
                } else {
                    c.automationUpdate(_state.value.selectedKey, draft.toUpdateRequest())
                }
                _state.value = _state.value.copy(
                    busy = false,
                    // 新建可能落在另一个项目上：把作用域切过去，列表才会显示刚建的任务。
                    projectId = if (creating && saved.projectId.isNotBlank()) {
                        saved.projectId
                    } else {
                        _state.value.projectId
                    },
                    notice = UiText.of(if (creating) R.string.automation_created else R.string.automation_saved),
                    conflict = false,
                    drafts = _state.value.drafts - _state.value.selectedKey,
                    selectedKey = saved.key,
                )
                DenovaLog.i(
                    "Automations",
                    "${if (creating) "created" else "saved"} key=${saved.key} revision=${saved.revision.take(8)}",
                )
                load(force = true)
            } catch (e: Exception) {
                val conflict = (e as? DenovaApiException)?.status == 409
                _state.value = _state.value.copy(
                    busy = false,
                    conflict = conflict,
                    error = if (conflict) UiText.of(R.string.automation_conflict) else failureText(
                        e,
                        R.string.automation_save_failed,
                    ),
                )
                DenovaLog.w("Automations", "save failed conflict=$conflict", e)
            }
        }
    }

    fun delete() {
        val c = client ?: return
        val key = _state.value.selectedKey
        if (key.isBlank() || key == AutomationsUiState.CREATE_KEY || _state.value.busy) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null)
            try {
                c.automationDelete(key)
                _state.value = _state.value.copy(
                    busy = false,
                    selectedKey = "",
                    drafts = _state.value.drafts - key,
                    notice = UiText.of(R.string.automation_deleted),
                )
                DenovaLog.i("Automations", "deleted key=$key")
                load(force = true)
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    busy = false,
                    error = failureText(e, R.string.automation_delete_failed),
                )
                DenovaLog.w("Automations", "delete failed", e)
            }
        }
    }

    /** 立即运行：客户端生成幂等 `command_id`；服务端返回 202 + run。 */
    fun runNow() {
        val c = client ?: return
        val key = _state.value.selectedKey
        if (key.isBlank() || key == AutomationsUiState.CREATE_KEY || _state.value.busy) return
        val commandId = AutomationEditor.newCommandId()
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null)
            try {
                val run = c.automationRun(key, commandId)
                val text = if (run.sessionId.isBlank()) {
                    UiText.of(R.string.automation_run_started_no_session, run.status)
                } else {
                    UiText.of(R.string.automation_run_started, run.status)
                }
                _state.value = _state.value.copy(busy = false, notice = text)
                DenovaLog.i(
                    "Automations",
                    "run key=$key cmd=$commandId status=${run.status} session=${run.sessionId}",
                )
                load(force = true)
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    busy = false,
                    error = failureText(e, R.string.automation_run_start_failed),
                )
                DenovaLog.w("Automations", "run failed key=$key", e)
            }
        }
    }

    /** 检查触发器：命中项并入收件箱并切到收件箱标签（对齐基线行为）。 */
    fun checkTriggers() {
        val c = client ?: return
        val key = _state.value.selectedKey
        if (key.isBlank() || key == AutomationsUiState.CREATE_KEY || _state.value.busy) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null)
            try {
                val items = c.automationCheck(key)
                _state.value = _state.value.copy(
                    busy = false,
                    tab = AutomationTab.INBOX,
                    notice = UiText.of(R.string.automation_check_done, items.size),
                )
                DenovaLog.i("Automations", "check key=$key items=${items.size}")
                load(force = true)
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    busy = false,
                    error = failureText(e, R.string.automation_check_failed),
                )
                DenovaLog.w("Automations", "check failed key=$key", e)
            }
        }
    }

    /** 收件箱三动作：已读 / 确认（可能顺带产生一次运行）/ 忽略。 */
    fun inboxAction(item: AutomationInboxItem, action: InboxAction) {
        val c = client ?: return
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null)
            try {
                val notice = when (action) {
                    InboxAction.READ -> {
                        c.automationInboxRead(item.id)
                        UiText.of(R.string.automation_inbox_read)
                    }

                    InboxAction.CONFIRM -> {
                        val result = c.automationInboxConfirm(item.id)
                        if (result.run == null) {
                            UiText.of(R.string.automation_inbox_confirmed)
                        } else {
                            UiText.of(R.string.automation_inbox_confirmed_run, result.run.status)
                        }
                    }

                    InboxAction.DISMISS -> {
                        c.automationInboxDismiss(item.id)
                        UiText.of(R.string.automation_inbox_dismissed)
                    }
                }
                _state.value = _state.value.copy(busy = false, notice = notice)
                DenovaLog.i("Automations", "inbox ${action.name.lowercase()} item=${item.id}")
                load(force = true)
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    busy = false,
                    error = failureText(e, R.string.automation_inbox_failed),
                )
                DenovaLog.w("Automations", "inbox action failed action=${action.name}", e)
            }
        }
    }

    private fun failureText(error: Exception, fallback: Int): UiText =
        (error as? DenovaApiException)?.message?.takeIf { it.isNotBlank() }?.let(UiText::raw)
            ?: UiText.of(fallback)
}
