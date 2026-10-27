package com.denova.novara.feature.shell

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.denova.novara.R
import com.denova.novara.core.game.LedgerActor
import com.denova.novara.core.game.LedgerField
import com.denova.novara.core.game.StoryLedger
import com.denova.novara.core.i18n.resolve
import com.denova.novara.core.network.BranchPlan
import com.denova.novara.core.network.BranchSummary
import com.denova.novara.core.stream.TurnAnchor
import com.denova.novara.core.i18n.UiText

/**
 * 游戏控制台（GAM-006 / GAM-007 / GAM-008 / GAM-009）：只读状态台账 + 轮次跳转 + 分支 + 分支方案。
 *
 * 台账数据只认快照（服务端规范状态），因此没有就地编辑入口——改状态一律通过剧情输入。
 * 分支方案是带 revision 的文档，保存冲突（409）按服务端文案提示，不自动重试。
 */
@Composable
fun GameConsoleContent(
    ledger: StoryLedger,
    turnAnchors: List<TurnAnchor>,
    branchId: String,
    branches: List<BranchSummary>,
    branchPlan: BranchPlan?,
    branchBusy: Boolean,
    branchError: UiText?,
    turnCount: Int,
    turnStart: Int,
    branchCount: Int,
    historyHasMore: Boolean,
    loadingEarlier: Boolean,
    onRefresh: () -> Unit,
    onLoadEarlier: () -> Unit,
    onSelectTurn: (String) -> Unit,
    onForkTurn: (String) -> Unit,
    onSwitchBranch: (String) -> Unit,
    onDeleteBranch: (String) -> Unit,
    onSavePlan: (String) -> Unit,
) {
    var tab by remember { mutableIntStateOf(0) }
    // 删除分支不可撤销：先确认再发请求。
    var pendingDelete by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.game_console_overview, turnCount, branchCount, turnStart),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRefresh) { Text(stringResource(R.string.common_refresh)) }
        }

        when (ledger.stateStatus) {
            "pending" -> ConsoleNotice(stringResource(R.string.game_console_state_pending))
            "failed" -> ConsoleNotice(
                stringResource(R.string.game_console_state_failed, ledger.stateError.ifBlank { "—" }),
            )
        }

        branchError?.let {
            Text(
                it.resolve(),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ConsoleTab(R.string.game_console_actors, tab == 0) { tab = 0 }
            ConsoleTab(R.string.game_console_world, tab == 1) { tab = 1 }
            ConsoleTab(R.string.game_console_turns, tab == 2) { tab = 2 }
            ConsoleTab(R.string.game_console_branches, tab == 3) { tab = 3 }
            ConsoleTab(R.string.game_console_plan, tab == 4) { tab = 4 }
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            when (tab) {
                0 -> {
                    if (ledger.actors.isEmpty()) {
                        item { ConsoleNotice(stringResource(R.string.game_console_no_actors)) }
                    }
                    items(ledger.actors, key = { "actor-${it.id}" }) { actor -> ActorBlock(actor) }
                    changesSection(ledger)
                }

                1 -> {
                    if (ledger.world.isEmpty()) {
                        item { ConsoleNotice(stringResource(R.string.game_console_no_world)) }
                    }
                    items(ledger.world, key = { "world-${it.name}" }) { field ->
                        FieldLine(field.name, field.value)
                    }
                    changesSection(ledger)
                }

                2 -> {
                    item {
                        TextButton(
                            onClick = onLoadEarlier,
                            enabled = historyHasMore && !loadingEarlier,
                        ) {
                            Text(
                                if (loadingEarlier) {
                                    stringResource(R.string.game_console_loading_earlier)
                                } else {
                                    stringResource(R.string.game_console_load_earlier)
                                },
                            )
                        }
                    }
                    if (turnAnchors.isEmpty()) {
                        item { ConsoleNotice(stringResource(R.string.game_console_no_turns)) }
                    }
                    // 新轮次在前，便于直接跳到最近几轮。
                    items(turnAnchors.asReversed(), key = { "turn-${it.turnId}" }) { anchor ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 4.dp),
                            ) {
                                Text(
                                    stringResource(R.string.game_console_turn_number, anchor.number),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                                Text(anchor.label, style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = { onSelectTurn(anchor.turnId) }) {
                                Text(stringResource(R.string.game_console_turn_jump))
                            }
                            TextButton(onClick = { onForkTurn(anchor.turnId) }, enabled = !branchBusy) {
                                Text(stringResource(R.string.game_console_branch_fork))
                            }
                        }
                    }
                }

                3 -> {
                    item {
                        Text(
                            stringResource(R.string.game_console_current_branch, branchId.ifBlank { "main" }),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }
                    if (branches.isEmpty()) {
                        item { ConsoleNotice(stringResource(R.string.game_console_branch_empty)) }
                    }
                    itemsIndexed(branches, key = { _, b -> "branch-${b.id}" }) { _, branch ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 4.dp),
                            ) {
                                Text(
                                    branch.id,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (branch.id == branchId) FontWeight.SemiBold else FontWeight.Normal,
                                )
                                val headLabel = stringResource(
                                    R.string.game_console_branch_head,
                                    branch.head.take(8),
                                )
                                val subtitle = when {
                                    branch.title.isBlank() && branch.head.isBlank() -> ""
                                    branch.head.isBlank() -> branch.title
                                    branch.title.isBlank() -> headLabel
                                    else -> "${branch.title} · $headLabel"
                                }
                                if (subtitle.isNotEmpty()) {
                                    Text(
                                        subtitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                }
                            }
                            if (branch.id == branchId) {
                                Text(
                                    stringResource(R.string.game_console_branch_is_current),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            } else {
                                TextButton(onClick = { onSwitchBranch(branch.id) }, enabled = !branchBusy) {
                                    Text(stringResource(R.string.game_console_branch_switch))
                                }
                                TextButton(
                                    onClick = { pendingDelete = branch.id },
                                    enabled = !branchBusy,
                                ) {
                                    Text(stringResource(R.string.game_console_branch_delete))
                                }
                            }
                        }
                    }
                }

                else -> {
                    item { BranchPlanSection(branchPlan, branchBusy, onSavePlan) }
                }
            }
        }
    }

    pendingDelete?.let { branchId ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.game_console_branch_delete)) },
            text = { Text(stringResource(R.string.game_console_branch_delete_confirm, branchId)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    onDeleteBranch(branchId)
                }) { Text(stringResource(R.string.game_console_branch_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

@Composable
private fun ConsoleTab(labelRes: Int, selected: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(
            stringResource(labelRes),
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 分支方案：只读展示 + 行内编辑；保存时带快照 revision，冲突由 ViewModel 提示。 */
@Composable
private fun BranchPlanSection(plan: BranchPlan?, busy: Boolean, onSave: (String) -> Unit) {
    var editing by remember(plan?.revision) { mutableStateOf(false) }
    var draft by remember(plan?.markdown) { mutableStateOf(plan?.markdown.orEmpty()) }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (plan == null) {
                    stringResource(R.string.game_console_plan_empty)
                } else {
                    stringResource(R.string.game_console_plan_revision, plan.revision.ifBlank { "—" })
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.weight(1f),
            )
            if (!editing) {
                TextButton(onClick = {
                    draft = plan?.markdown.orEmpty()
                    editing = true
                }) { Text(stringResource(R.string.game_console_plan_edit)) }
            } else {
                TextButton(onClick = { editing = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
                TextButton(
                    onClick = { onSave(draft) },
                    enabled = !busy && draft.isNotBlank(),
                ) { Text(stringResource(R.string.game_console_plan_save)) }
            }
        }
        if (editing) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = 8,
                maxLines = 20,
                label = { Text(stringResource(R.string.game_console_plan_markdown)) },
            )
        } else {
            val markdown = plan?.markdown.orEmpty()
            if (markdown.isBlank()) {
                ConsoleNotice(stringResource(R.string.game_console_plan_empty))
            } else {
                Text(markdown, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ActorBlock(actor: LedgerActor) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                actor.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            if (actor.role.isNotBlank()) {
                Text(
                    " · ${actor.role}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
        actor.fields.forEach { field -> FieldLine(field.name, field.value, indent = true) }
        if (actor.fields.isEmpty()) {
            Text(
                stringResource(R.string.game_console_no_fields),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun FieldLine(name: String, value: String, indent: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth().padding(start = if (indent) 8.dp else 0.dp, top = 1.dp)) {
        Text(
            name,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.padding(horizontal = 4.dp))
        Text(
            value.ifBlank { "—" },
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(2f, fill = false),
        )
    }
}

@Composable
private fun ConsoleNotice(text: String) {
    Text(
        text,
        color = MaterialTheme.colorScheme.outline,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
    )
}

/** 本轮变更摘要（角色 tab 与世界 tab 共用）。 */
private fun androidx.compose.foundation.lazy.LazyListScope.changesSection(ledger: StoryLedger) {
    if (ledger.changes.isEmpty()) return
    item(key = "changes-title") {
        Text(
            stringResource(R.string.game_console_changes),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
        )
    }
    ledger.changes.forEachIndexed { index, change ->
        item(key = "change-$index") {
            Text(
                "· $change",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 1.dp),
            )
        }
    }
}
