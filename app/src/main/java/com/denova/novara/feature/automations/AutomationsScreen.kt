package com.denova.novara.feature.automations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.denova.novara.R
import com.denova.novara.core.automations.AutomationDraft
import com.denova.novara.core.automations.AutomationEditor
import com.denova.novara.core.i18n.resolve
import com.denova.novara.core.network.AutomationInboxItem
import com.denova.novara.core.network.AutomationTask
import com.denova.novara.core.network.DenovaClient

/**
 * 自动化页（P4-D / AUT-001…004）。
 *
 * 结构对齐基线：任务 / 收件箱两个标签（窄屏不带侧栏），任务列表 → 编辑面板（基本区 + 触发条件
 * + 最近运行），顶部动作「新建 / 保存 / 删除 / 立即运行 / 检查触发器」。
 * 草稿按任务保存在 ViewModel 里，所以切标签、切任务、去写作 Agent 再回来都不会丢编辑。
 */
@Composable
fun AutomationsScreen(
    client: DenovaClient?,
    projectId: String,
    onOpenDrawer: () -> Unit,
    // 由 Activity 持有：草稿要跨页面存活（AUT-004「切列表编辑、去 Agent 再返回」不丢编辑），
    // 绑在导航目的地作用域上会在离开页面时被销毁。
    viewModel: AutomationsViewModel,
) {
    LaunchedEffect(client, projectId) { client?.let { viewModel.bindClient(it, projectId) } }
    val state by viewModel.state.collectAsState()
    AutomationsContent(state = state, viewModel = viewModel, onOpenDrawer = onOpenDrawer)
}

@Composable
private fun AutomationsContent(
    state: AutomationsUiState,
    viewModel: AutomationsViewModel,
    onOpenDrawer: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).imePadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onOpenDrawer) { Text("☰") }
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.automation_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.automation_subtitle, state.tasks.size, state.unread),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (state.loading || state.busy) CircularProgressIndicator(Modifier.padding(6.dp))
        }
        // 动作行单独一行并横向可滚：窄屏（320）下按钮不重叠。
        LazyRow(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item {
                TextButton(onClick = { viewModel.startCreate() }) {
                    Text(stringResource(R.string.automation_new))
                }
            }
            item {
                TextButton(
                    onClick = { viewModel.save() },
                    enabled = state.draft != null && state.dirty && !state.busy,
                ) { Text(stringResource(R.string.save)) }
            }
            item {
                TextButton(
                    onClick = { viewModel.runNow() },
                    enabled = state.selectedKey.isNotBlank() && !state.creating && !state.busy,
                ) { Text(stringResource(R.string.automation_run_now)) }
            }
            item {
                TextButton(
                    onClick = { viewModel.checkTriggers() },
                    enabled = state.selectedKey.isNotBlank() && !state.creating && !state.busy,
                ) { Text(stringResource(R.string.automation_check_triggers)) }
            }
            item {
                TextButton(
                    onClick = { confirmDelete = true },
                    enabled = state.selectedKey.isNotBlank() && !state.creating && !state.busy,
                ) { Text(stringResource(R.string.delete)) }
            }
        }
        // 项目作用域（目录按项目分组；切项目即切任务与收件箱）。
        if (state.groups.size > 1) {
            LazyRow(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(state.groups.filter { it.projectId.isNotBlank() }) { group ->
                    FilterChip(
                        selected = group.projectId == state.projectId,
                        onClick = { viewModel.setProject(group.projectId) },
                        label = { Text(group.projectId.takeLast(8)) },
                    )
                }
            }
        }
        state.error?.let {
            Text(
                it.resolve(),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }
        state.notice?.let {
            Text(
                it.resolve(),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }
        if (state.conflict) {
            Text(
                stringResource(R.string.automation_conflict_hint),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(
                selected = state.tab == AutomationTab.TASKS,
                onClick = { viewModel.selectTab(AutomationTab.TASKS) },
                label = { Text(stringResource(R.string.automation_tab_tasks)) },
            )
            FilterChip(
                selected = state.tab == AutomationTab.INBOX,
                onClick = { viewModel.selectTab(AutomationTab.INBOX) },
                label = {
                    Text(
                        if (state.unread > 0) {
                            stringResource(R.string.automation_tab_inbox_unread, state.unread)
                        } else {
                            stringResource(R.string.automation_tab_inbox)
                        },
                    )
                },
            )
        }
        HorizontalDivider()

        when (state.tab) {
            AutomationTab.INBOX -> InboxList(state, viewModel)
            AutomationTab.TASKS -> if (state.selectedKey.isNotBlank()) {
                EditorPanel(state, viewModel)
            } else {
                TaskList(state, viewModel)
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.automation_delete_title)) },
            text = { Text(stringResource(R.string.automation_delete_body, state.selected?.name.orEmpty())) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.delete()
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun TaskList(state: AutomationsUiState, viewModel: AutomationsViewModel) {
    if (state.tasks.isEmpty()) {
        Text(
            stringResource(R.string.automation_empty),
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodySmall,
        )
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(state.tasks, key = { it.key }) { task ->
            TaskRow(task) { viewModel.select(task.key) }
        }
    }
}

@Composable
private fun TaskRow(task: AutomationTask, onOpen: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(task.name.ifBlank { task.key }, style = MaterialTheme.typography.bodyMedium)
                Text(
                    AutomationEditor.statusText(task).resolve() + " · " +
                        AutomationEditor.scheduleText(task.schedule).resolve(),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            TextButton(onClick = onOpen) { Text(stringResource(R.string.automation_open)) }
        }
        task.lastRun?.takeIf { it.summary.isNotBlank() }?.let {
            Text(it.summary, style = MaterialTheme.typography.labelSmall)
        }
        HorizontalDivider(Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun InboxList(state: AutomationsUiState, viewModel: AutomationsViewModel) {
    if (state.inbox.isEmpty()) {
        Text(
            stringResource(R.string.automation_inbox_empty),
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodySmall,
        )
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(state.inbox, key = { it.id }) { item -> InboxRow(item, viewModel) }
    }
}

@Composable
private fun InboxRow(item: AutomationInboxItem, viewModel: AutomationsViewModel) {
    val unreadLabel = stringResource(R.string.automation_inbox_unread)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            item.title.ifBlank { item.id },
            style = MaterialTheme.typography.bodyMedium,
        )
        if (item.summary.isNotBlank()) {
            Text(item.summary, style = MaterialTheme.typography.labelSmall)
        }
        Text(
            if (item.readAt.isBlank()) "${item.status} · $unreadLabel" else item.status,
            style = MaterialTheme.typography.labelSmall,
        )
        if (item.actionError.isNotBlank()) {
            Text(item.actionError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(
                onClick = { viewModel.inboxAction(item, InboxAction.READ) },
                enabled = item.readAt.isBlank(),
            ) { Text(stringResource(R.string.automation_inbox_mark_read)) }
            // 基线的门控：确认只在 pending + action_policy=confirm 时出现；忽略只在 pending 时出现。
            if (item.status == "pending" && item.actionPolicy == "confirm") {
                TextButton(
                    onClick = { viewModel.inboxAction(item, InboxAction.CONFIRM) },
                    enabled = item.actionError.isBlank(),
                ) { Text(stringResource(R.string.automation_inbox_confirm)) }
            }
            if (item.status == "pending") {
                TextButton(
                    onClick = { viewModel.inboxAction(item, InboxAction.DISMISS) },
                ) { Text(stringResource(R.string.automation_inbox_dismiss)) }
            }
        }
        HorizontalDivider()
    }
}

@Composable
private fun EditorPanel(state: AutomationsUiState, viewModel: AutomationsViewModel) {
    val draft = state.draft ?: return
    LazyColumn(Modifier.fillMaxSize()) {
        if (draft.creating) {
            item("project") {
                SectionTitle(stringResource(R.string.automation_field_project))
                val options = state.groups.map { it.projectId }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(horizontal = 16.dp)) {
                    items(options) { id ->
                        FilterChip(
                            selected = draft.projectId == id,
                            onClick = { viewModel.setProjectForCreate(id) },
                            label = { Text(id.takeLast(8)) },
                        )
                    }
                }
                state.fieldErrors["project"]?.let { ErrorText(it.resolve()) }
            }
        }
        item("name") {
            LabeledField(
                label = stringResource(R.string.automation_field_name),
                value = draft.name,
                onChange = viewModel::setName,
            )
        }
        item("enabled") {
            SectionTitle(stringResource(R.string.automation_field_enabled))
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                FilterChip(
                    selected = draft.enabled,
                    onClick = { viewModel.setEnabled(true) },
                    label = { Text(stringResource(R.string.settings_on)) },
                )
                FilterChip(
                    selected = !draft.enabled,
                    onClick = { viewModel.setEnabled(false) },
                    label = { Text(stringResource(R.string.settings_off)) },
                )
            }
        }
        if (state.templates.isNotEmpty()) {
            item("template") {
                SectionTitle(stringResource(R.string.automation_field_template))
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    items(state.templates) { template ->
                        FilterChip(
                            selected = draft.template == template.id,
                            onClick = { viewModel.applyTemplate(template.id) },
                            label = { Text(template.id) },
                        )
                    }
                }
            }
        }
        item("session") {
            SectionTitle(stringResource(R.string.automation_field_session))
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                AutomationEditor.SESSION_STRATEGIES.forEach { strategy ->
                    FilterChip(
                        selected = draft.sessionStrategy == strategy,
                        onClick = { viewModel.setSessionStrategy(strategy) },
                        label = {
                            Text(
                                stringResource(
                                    if (strategy == "per_task") {
                                        R.string.automation_session_per_task
                                    } else {
                                        R.string.automation_session_per_run
                                    },
                                ),
                            )
                        },
                    )
                }
            }
        }
        item("model") {
            LabeledField(
                label = stringResource(R.string.automation_field_model),
                value = draft.modelProfileId,
                onChange = viewModel::setModelProfile,
            )
        }
        item("prompt") {
            OutlinedTextField(
                value = draft.prompt,
                onValueChange = viewModel::setPrompt,
                label = { Text(stringResource(R.string.automation_field_prompt)) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                minLines = 3,
                maxLines = 8,
            )
        }
        item("schedule") { ScheduleEditor(draft, state, viewModel) }
        item("triggers") { TriggersEditor(draft, state, viewModel) }
        item("runs") { RecentRuns(state.selected) }
        item("footer") { Text("", Modifier.padding(16.dp)) }
    }
}

@Composable
private fun ScheduleEditor(
    draft: AutomationDraft,
    state: AutomationsUiState,
    viewModel: AutomationsViewModel,
) {
    val schedule = draft.schedule
    SectionTitle(stringResource(R.string.automation_field_schedule))
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(horizontal = 16.dp),
    ) {
        items(AutomationEditor.SCHEDULE_KINDS) { kind ->
            FilterChip(
                selected = schedule.kind == kind,
                onClick = { viewModel.setScheduleKind(kind) },
                label = { Text(kind) },
            )
        }
    }
    if (schedule.kind != "manual") {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            NumberField(stringResource(R.string.automation_field_hour), schedule.hour, 0, 23) {
                viewModel.setSchedule(schedule.copy(hour = it))
            }
            NumberField(stringResource(R.string.automation_field_minute), schedule.minute, 0, 59) {
                viewModel.setSchedule(schedule.copy(minute = it))
            }
            if (schedule.kind == "weekly") {
                NumberField(stringResource(R.string.automation_field_weekday), schedule.weekday ?: 1, 0, 6) {
                    viewModel.setSchedule(schedule.copy(weekday = it))
                }
            }
            if (schedule.kind == "monthly") {
                NumberField(stringResource(R.string.automation_field_day), schedule.dayOfMonth ?: 1, 1, 31) {
                    viewModel.setSchedule(schedule.copy(dayOfMonth = it))
                }
            }
            if (schedule.kind == "every_hours") {
                NumberField(stringResource(R.string.automation_field_every_hours), schedule.everyHours ?: 1, 1, 168) {
                    viewModel.setSchedule(schedule.copy(everyHours = it))
                }
            }
        }
    }
    Text(
        stringResource(
            R.string.automation_schedule_preview,
            AutomationEditor.scheduleText(schedule).resolve(),
            AutomationEditor.cronOf(schedule).ifBlank { "-" },
        ),
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
    state.fieldErrors["schedule"]?.let { ErrorText(it.resolve()) }
}

@Composable
private fun TriggersEditor(
    draft: AutomationDraft,
    state: AutomationsUiState,
    viewModel: AutomationsViewModel,
) {
    SectionTitle(stringResource(R.string.automation_field_triggers))
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(horizontal = 16.dp),
    ) {
        items(AutomationEditor.TRIGGER_TYPES) { type ->
            FilterChip(
                selected = false,
                onClick = { viewModel.addTrigger(type) },
                label = { Text("+ $type") },
            )
        }
    }
    draft.triggers.forEachIndexed { index, trigger ->
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.automation_trigger_index, index + 1),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { viewModel.removeTrigger(index) }) {
                    Text(stringResource(R.string.delete))
                }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(AutomationEditor.TRIGGER_TYPES) { type ->
                    FilterChip(
                        selected = trigger.type == type,
                        onClick = { viewModel.retypeTrigger(index, type) },
                        label = { Text(type) },
                    )
                }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(AutomationEditor.NOTIFY_POLICIES) { policy ->
                    FilterChip(
                        selected = trigger.notifyPolicy == policy,
                        onClick = { viewModel.setTriggerNotify(index, policy) },
                        label = {
                            Text(
                                stringResource(
                                    if (policy == "silent") {
                                        R.string.automation_notify_silent
                                    } else {
                                        R.string.automation_notify_inbox
                                    },
                                ),
                            )
                        },
                    )
                }
            }
            when (trigger.type) {
                "schedule" -> Text(
                    stringResource(
                        R.string.automation_trigger_schedule,
                        AutomationEditor.scheduleText(trigger.schedule).resolve(),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                )

                "semantic" -> OutlinedTextField(
                    value = trigger.semanticCondition,
                    onValueChange = { viewModel.setTriggerCondition(index, it) },
                    label = { Text(stringResource(R.string.automation_trigger_condition)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                else -> NumberField(
                    stringResource(R.string.automation_trigger_batch_size),
                    trigger.chapterBatchSize ?: 5,
                    1,
                    100,
                ) { viewModel.setTriggerBatchSize(index, it) }
            }
            state.fieldErrors["trigger-$index"]?.let { ErrorText(it.resolve()) }
        }
    }
    if (draft.triggers.isEmpty()) {
        Text(
            stringResource(R.string.automation_trigger_empty),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

@Composable
private fun RecentRuns(task: AutomationTask?) {
    SectionTitle(stringResource(R.string.automation_recent_runs))
    val runs = task?.recentRuns?.take(5).orEmpty()
    if (runs.isEmpty()) {
        Text(
            stringResource(R.string.automation_runs_empty),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        return
    }
    runs.forEach { run ->
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            Text(
                AutomationEditor.runStatusText(run.status).resolve() + " · " + run.startedAt,
                style = MaterialTheme.typography.labelSmall,
            )
            if (run.outputPath.isNotBlank()) {
                Text(run.outputPath, style = MaterialTheme.typography.labelSmall)
            }
            if (run.summary.isNotBlank()) {
                Text(run.summary, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun ErrorText(text: String) {
    Text(
        text,
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}

@Composable
private fun LabeledField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        singleLine = true,
    )
}

@Composable
private fun NumberField(
    label: String,
    value: Int,
    minimum: Int,
    maximum: Int,
    onChange: (Int) -> Unit,
) {
    OutlinedTextField(
        value = value.toString(),
        onValueChange = { text ->
            val parsed = text.filter { it.isDigit() }.toIntOrNull() ?: return@OutlinedTextField
            onChange(parsed.coerceIn(minimum, maximum))
        },
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}
