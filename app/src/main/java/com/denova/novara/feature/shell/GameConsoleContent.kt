package com.denova.novara.feature.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.denova.novara.R
import com.denova.novara.core.game.LedgerActor
import com.denova.novara.core.game.LedgerField
import com.denova.novara.core.game.StoryLedger

/**
 * 游戏控制台（GAM-006）：只读状态台账。
 *
 * 角色 / 世界事实 / 本轮变更全部来自快照（服务端规范状态），
 * 因此这里没有编辑入口——修改一律通过剧情输入让服务端重算。
 */
@Composable
fun GameConsoleContent(
    ledger: StoryLedger,
    turnCount: Int,
    turnStart: Int,
    branchCount: Int,
    onRefresh: () -> Unit,
) {
    var tab by remember { mutableIntStateOf(0) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
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
                stringResource(
                    R.string.game_console_state_failed,
                    ledger.stateError.ifBlank { "—" },
                ),
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { tab = 0 }) { Text(stringResource(R.string.game_console_actors)) }
            TextButton(onClick = { tab = 1 }) { Text(stringResource(R.string.game_console_world)) }
        }

        if (ledger.isEmpty) {
            Text(
                stringResource(R.string.game_console_empty),
                color = MaterialTheme.colorScheme.outline,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(8.dp),
            )
            return@Column
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            if (tab == 0) {
                ledger.actors.forEach { actor ->
                    item(key = "actor-${actor.id}") { ActorBlock(actor) }
                }
                if (ledger.actors.isEmpty()) {
                    item { ConsoleNotice(stringResource(R.string.game_console_no_actors)) }
                }
            } else {
                ledger.world.forEach { field ->
                    item(key = "world-${field.name}") { FieldLine(field.name, field.value) }
                }
                if (ledger.world.isEmpty()) {
                    item { ConsoleNotice(stringResource(R.string.game_console_no_world)) }
                }
            }

            if (ledger.changes.isNotEmpty()) {
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
        }
    }
}

@Composable
private fun ActorBlock(actor: LedgerActor) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
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
