package com.denova.novara.feature.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.denova.novara.R
import com.denova.novara.core.network.WorkspaceChangeComment
import com.denova.novara.core.network.WorkspaceChangeGroupSummary
import com.denova.novara.core.network.WorkspaceChangeSet
import com.denova.novara.core.project.ProjectFileRow
import com.denova.novara.feature.changes.ChangeDiff
import com.denova.novara.feature.changes.ChangeFileDiff
import com.denova.novara.feature.changes.ChangesUiState
import com.denova.novara.feature.changes.DiffKind
import com.denova.novara.feature.changes.DiffLine
import com.denova.novara.feature.project.ProjectDirectoryUiState
import com.denova.novara.feature.versions.VersionsUiState

/**
 * 右项目 / 控制台抽屉（01 §3）：从右侧滑出，内容按当前页面提供——
 * 写作页为项目目录，游戏页为游戏控制台。数据源待接（文件树 API / 游戏状态）。
 */
@Composable
fun RightDrawer(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.4f))
                .clickable(onClick = onDismiss),
        )
        AnimatedVisibility(
            visible = true,
            enter = slideInHorizontally(initialOffsetX = { it }),
            exit = slideOutHorizontally(targetOffsetX = { it }),
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(320.dp),
                tonalElevation = 3.dp,
            ) {
                Column(Modifier.fillMaxSize().padding(16.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    content()
                }
            }
        }
    }
}

/**
 * 项目目录（WRI-002）：`/files/resolve` 递归文件树。点击目录展开/折叠，点击文件由回调交给编辑器打开。
 */
@Composable
fun ProjectDirectoryContent(
    state: ProjectDirectoryUiState,
    onToggle: (String) -> Unit,
    onOpenFile: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.project_directory), style = MaterialTheme.typography.labelLarge)
            TextButton(onClick = onRefresh, enabled = !state.loading) {
                Text(stringResource(R.string.refresh))
            }
        }
        val muted = MaterialTheme.colorScheme.outline
        when {
            state.projectId.isBlank() -> Text(
                stringResource(R.string.project_directory_hint),
                color = muted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            state.loading && state.rows.isEmpty() -> Text(
                stringResource(R.string.loading),
                color = muted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            state.error != null -> Text(
                state.error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            state.rows.isEmpty() -> Text(
                stringResource(R.string.project_directory_empty),
                color = muted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(state.rows, key = { it.node.path }) { row ->
                    ProjectFileRowItem(
                        row = row,
                        selected = row.node.path == state.selectedPath,
                        onToggle = onToggle,
                        onOpenFile = onOpenFile,
                    )
                }
            }
        }
    }
}

/**
 * 变更审阅（P3 链路）：列出 `/changes/groups`，支持接受 / 拒绝 / 撤销 / 重做。
 */
@Composable
fun ChangesContent(
    state: ChangesUiState,
    onReview: (groupId: String, decision: String, changeSetId: String?) -> Unit,
    onUndo: (String) -> Unit,
    onRedo: (String) -> Unit,
    onRefresh: () -> Unit,
    onOpen: (String) -> Unit = {},
    onCloseDetail: () -> Unit = {},
    onSelectLine: (WorkspaceChangeSet, DiffLine) -> Unit = { _, _ -> },
    onGroupComment: () -> Unit = {},
    onDraftChange: (String) -> Unit = {},
    onSubmitComment: () -> Unit = {},
    onCancelComment: () -> Unit = {},
    onEditComment: (WorkspaceChangeComment) -> Unit = {},
    onDeleteComment: (String) -> Unit = {},
) {
    val detail = state.detail
    if (detail != null) {
        ChangeGroupDetail(
            state = state,
            onClose = onCloseDetail,
            onReview = onReview,
            onUndo = onUndo,
            onRedo = onRedo,
            onSelectLine = onSelectLine,
            onGroupComment = onGroupComment,
            onDraftChange = onDraftChange,
            onSubmitComment = onSubmitComment,
            onCancelComment = onCancelComment,
            onEditComment = onEditComment,
            onDeleteComment = onDeleteComment,
        )
        return
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.changes_title), style = MaterialTheme.typography.labelLarge)
            TextButton(onClick = onRefresh, enabled = !state.loading) {
                Text(stringResource(R.string.refresh))
            }
        }
        val muted = MaterialTheme.colorScheme.outline
        when {
            state.projectId.isBlank() -> Text(
                stringResource(R.string.changes_hint), color = muted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            state.loading && state.groups.isEmpty() -> Text(
                stringResource(R.string.loading), color = muted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            state.error != null -> Text(
                state.error, color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            state.groups.isEmpty() -> Text(
                stringResource(R.string.changes_empty), color = muted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(state.groups, key = { it.id }) { group ->
                    ChangeGroupItem(
                        group = group,
                        busy = state.busyGroupId == group.id,
                        onReview = { gid, decision -> onReview(gid, decision, null) },
                        onUndo = onUndo,
                        onRedo = onRedo,
                        onOpen = onOpen,
                    )
                }
            }
        }
    }
}

/**
 * 版本入口（WRI-016 的「版本」部分）：只读 `GET /versions/status` + `GET /versions`。
 * 创建 / diff / 恢复属 VER-001、VER-002，这里不做写操作，也不放会误导的按钮。
 */
@Composable
fun VersionsContent(state: VersionsUiState, onRefresh: () -> Unit) {
    val muted = MaterialTheme.colorScheme.outline
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.versions_title), style = MaterialTheme.typography.labelLarge)
            TextButton(onClick = onRefresh, enabled = !state.loading) {
                Text(stringResource(R.string.refresh))
            }
        }
        when {
            state.projectId.isBlank() -> Text(
                stringResource(R.string.versions_hint), color = muted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            state.loading && state.status == null -> Text(
                stringResource(R.string.loading), color = muted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            state.error != null -> Text(
                state.error, color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            else -> {
                val status = state.status
                if (status != null) {
                    Text(
                        if (status.clean) stringResource(R.string.versions_clean) else
                            stringResource(R.string.versions_dirty, status.changes.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (status.clean) muted else MaterialTheme.colorScheme.error,
                    )
                    status.latest?.let {
                        // message 自身已带「自动版本：…」这类来源前缀，不再二次加标签。
                        Text(
                            it.message.ifBlank { it.id.take(12) },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text(
                        if (status.auto.timedEnabled) {
                            stringResource(R.string.versions_auto_timed, status.auto.timedIntervalMinutes)
                        } else {
                            stringResource(R.string.versions_auto_off)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = muted,
                    )
                }
                if (state.versions.isEmpty()) {
                    Text(
                        stringResource(R.string.versions_none), color = muted,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(state.versions, key = { it.id }) { entry ->
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                            ) {
                                Text(
                                    entry.message.ifBlank { entry.id.take(12) },
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    entry.createdAt + " · " + entry.source + " · " +
                                        stringResource(R.string.versions_files, entry.fileCount, entry.totalBytes),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = muted,
                                )
                            }
                        }
                        item(key = "readonly-hint") {
                            Text(
                                stringResource(R.string.versions_readonly_hint),
                                style = MaterialTheme.typography.labelSmall,
                                color = muted,
                                modifier = Modifier.padding(vertical = 8.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 变更详情（WRI-016）：文件级统一 diff + 评论 + 接受 / 拒绝 / 撤销 / 重做。
 * 差异全部来自服务端回填的 before/after 正文，本地只做行级对比，不推测「确认范围」。
 */
@Composable
private fun ChangeGroupDetail(
    state: ChangesUiState,
    onClose: () -> Unit,
    onReview: (String, String, String?) -> Unit,
    onUndo: (String) -> Unit,
    onRedo: (String) -> Unit,
    onSelectLine: (WorkspaceChangeSet, DiffLine) -> Unit,
    onGroupComment: () -> Unit,
    onDraftChange: (String) -> Unit,
    onSubmitComment: () -> Unit,
    onCancelComment: () -> Unit,
    onEditComment: (WorkspaceChangeComment) -> Unit,
    onDeleteComment: (String) -> Unit,
) {
    val group = state.detail ?: return
    val busy = state.busyGroupId == group.id
    val diffs = remember(group.changeSets) { ChangeDiff.files(group) }
    val muted = MaterialTheme.colorScheme.outline
    val composerOpen = state.commentTarget != null || state.editingCommentId != null

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onClose) { Text("‹ " + stringResource(R.string.changes_back)) }
            Text(group.id.take(12), style = MaterialTheme.typography.labelMedium)
            Text(
                changesStatusText(group.reviewStatus) + " · " + changesApplyText(group.applyState),
                style = MaterialTheme.typography.labelSmall,
                color = muted,
            )
        }
        Text(
            stringResource(R.string.changes_files, group.changeSets.map { it.path }.distinct().size) +
                " · " + stringResource(R.string.changes_pending_edit_count, group.pendingEditCount) +
                " · " + stringResource(R.string.changes_comments, group.comments.size),
            style = MaterialTheme.typography.labelSmall,
            color = muted,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (group.reviewStatus == "pending" || group.pendingEditCount > 0) {
                TextButton(onClick = { onReview(group.id, "accept", null) }, enabled = !busy) {
                    Text(stringResource(R.string.changes_accept))
                }
                TextButton(onClick = { onReview(group.id, "reject", null) }, enabled = !busy) {
                    Text(stringResource(R.string.changes_reject))
                }
            }
            if (group.canUndo) {
                TextButton(onClick = { onUndo(group.id) }, enabled = !busy) {
                    Text(stringResource(R.string.changes_undo))
                }
            }
            if (group.canRedo) {
                TextButton(onClick = { onRedo(group.id) }, enabled = !busy) {
                    Text(stringResource(R.string.changes_redo))
                }
            }
            TextButton(onClick = onGroupComment, enabled = !busy) {
                Text(stringResource(R.string.changes_add_group_comment))
            }
        }
        state.detailError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        if (composerOpen) {
            CommentComposer(
                state = state,
                onChange = onDraftChange,
                onSubmit = onSubmitComment,
                onCancel = onCancelComment,
            )
        }
        LazyColumn(Modifier.fillMaxSize()) {
            // 同一路径可能出现多个变更集（例如拒绝后生成的逆向变更），key 必须带上外层序号，
            // 否则 Compose 会因重复 key 直接崩溃（真机验证时踩到）。
            diffs.forEachIndexed { fileIndex, diff ->
                val set = group.changeSets.firstOrNull { it.id == diff.changeSetId }
                item(key = "h-$fileIndex") {
                    ChangeDiffHeader(
                        diff = diff,
                        busy = busy,
                        onAcceptFile = {
                            onReview(group.id, "accept", diff.changeSetId)
                        },
                        onRejectFile = {
                            onReview(group.id, "reject", diff.changeSetId)
                        },
                    )
                }
                itemsIndexed(diff.lines, key = { lineIndex, _ -> "d-$fileIndex-$lineIndex" }) { index, line ->
                    DiffLineRow(
                        line = line,
                        index = index,
                        // 空行没有可锚定文本（服务端会拒绝），点它不进入行内评论。
                        clickable = set != null && line.text.isNotBlank(),
                        onClick = { if (set != null) onSelectLine(set, line) },
                    )
                }
                if (diff.truncated) {
                    item(key = "t-$fileIndex") {
                        Text(
                            stringResource(R.string.changes_truncated, ChangeDiff.MAX_LINES),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            item(key = "comments") {
                Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(R.string.changes_comments, group.comments.size),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    if (group.comments.none { !it.deleted }) {
                        Text(
                            stringResource(R.string.changes_no_comments),
                            style = MaterialTheme.typography.bodySmall,
                            color = muted,
                        )
                    } else {
                        group.comments.filterNot { it.deleted }.forEach { comment ->
                            CommentRow(
                                comment = comment,
                                onEdit = { onEditComment(comment) },
                                onDelete = { onDeleteComment(comment.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 评论编辑器：行内目标显示行号与原文，编辑既有评论时显示「正在编辑」。 */
@Composable
private fun CommentComposer(
    state: ChangesUiState,
    onChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val title = when {
            state.editingCommentId != null -> stringResource(R.string.changes_comment_edit_title)
            state.commentTarget?.anchor != null ->
                stringResource(R.string.changes_comment_target, state.commentTarget.lineLabel)
            else -> stringResource(R.string.changes_add_group_comment)
        }
        Text(title, style = MaterialTheme.typography.labelMedium)
        state.commentTarget?.anchor?.let {
            Text(it.quote, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
        OutlinedTextField(
            value = state.commentDraft,
            onValueChange = onChange,
            placeholder = { Text(stringResource(R.string.changes_comment_hint)) },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        state.commentError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onSubmit, enabled = !state.commentSubmitting && state.commentDraft.isNotBlank()) {
                Text(stringResource(R.string.changes_comment_save))
            }
            TextButton(onClick = onCancel, enabled = !state.commentSubmitting) {
                Text(stringResource(R.string.changes_comment_cancel))
            }
        }
    }
}

@Composable
private fun CommentRow(
    comment: WorkspaceChangeComment,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val author = comment.author?.takeIf { it.isNotBlank() }
            Text(
                if (author != null) "$author：${comment.body}" else comment.body,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            if (comment.anchor != null) {
                Text(
                    stringResource(R.string.changes_comment_anchored),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
        Row {
            TextButton(onClick = onEdit) { Text(stringResource(R.string.changes_comment_edit)) }
            TextButton(onClick = onDelete) { Text(stringResource(R.string.changes_comment_delete)) }
        }
    }
}

@Composable
private fun ChangeDiffHeader(
    diff: ChangeFileDiff,
    busy: Boolean,
    onAcceptFile: () -> Unit,
    onRejectFile: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .padding(6.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                buildString {
                    append(diff.path)
                    if (diff.isNew) append(" (" + stringResource(R.string.changes_new_file) + ")")
                    if (diff.isDeleted) append(" (" + stringResource(R.string.changes_deleted_file) + ")")
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(changesStatusText(diff.reviewStatus), style = MaterialTheme.typography.labelSmall)
                Text(
                    "+${diff.stats.additions} -${diff.stats.deletions}",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        if (diff.pending) {
            // 局部审阅：只裁决这一个变更集，服务端 ReviewRequest.change_set_id。
            Row {
                TextButton(onClick = onAcceptFile, enabled = !busy) {
                    Text(stringResource(R.string.changes_accept_file))
                }
                TextButton(onClick = onRejectFile, enabled = !busy) {
                    Text(stringResource(R.string.changes_reject_file))
                }
            }
        }
    }
}

/** 单行 diff：`+` / `-` 前缀 + 行号，颜色区分新增与删除；可点击则为行内评论的锚点来源。 */
@Composable
private fun DiffLineRow(
    line: DiffLine,
    index: Int,
    clickable: Boolean,
    onClick: () -> Unit,
) {
    val color = when (line.kind) {
        DiffKind.ADD -> Color(0xFF1B5E20)
        DiffKind.REMOVE -> Color(0xFFB71C1C)
        DiffKind.CONTEXT -> MaterialTheme.colorScheme.onSurface
    }
    val bg = when (line.kind) {
        DiffKind.ADD -> Color(0x1A4CAF50)
        DiffKind.REMOVE -> Color(0x1AF44336)
        DiffKind.CONTEXT -> Color.Transparent
    }
    val prefix = when (line.kind) {
        DiffKind.ADD -> "+"
        DiffKind.REMOVE -> "-"
        DiffKind.CONTEXT -> " "
    }
    val number = (line.beforeLine ?: line.afterLine ?: (index + 1)).toString().padStart(3)
    Text(
        text = "$number $prefix${line.text}",
        style = MaterialTheme.typography.labelSmall,
        color = color,
        maxLines = 3,
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .let { if (clickable) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 4.dp, vertical = 1.dp),
    )
}

@Composable
private fun changesStatusText(status: String): String = stringResource(
    when (status) {
        "accepted" -> R.string.changes_status_accepted
        "rejected" -> R.string.changes_status_rejected
        "mixed" -> R.string.changes_status_mixed
        else -> R.string.changes_status_pending
    },
)

@Composable
private fun changesApplyText(applyState: String): String = stringResource(
    when (applyState) {
        "applied" -> R.string.changes_apply_applied
        "reverted" -> R.string.changes_apply_reverted
        "conflicted" -> R.string.changes_apply_conflicted
        else -> R.string.changes_apply_prepared
    },
)

@Composable
private fun ChangeGroupItem(
    group: WorkspaceChangeGroupSummary,
    busy: Boolean,
    onReview: (String, String) -> Unit,
    onUndo: (String) -> Unit,
    onRedo: (String) -> Unit,
    onOpen: (String) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .clickable { onOpen(group.id) }
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            "${group.id.take(12)} · ${group.reviewStatus}/${group.applyState}",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onOpen(group.id) }, enabled = !busy) {
                Text(stringResource(R.string.changes_open))
            }
            if (group.commentCount > 0) {
                Text(
                    stringResource(R.string.changes_comments, group.commentCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
        if (group.paths.isNotEmpty()) {
            Text(
                group.paths.joinToString(", "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 2,
            )
        }
        Text(
            stringResource(R.string.changes_pending_edits, group.pendingEditCount, group.changeSetCount),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (group.reviewStatus == "pending") {
                TextButton(onClick = { onReview(group.id, "accept") }, enabled = !busy) {
                    Text(stringResource(R.string.changes_accept))
                }
                TextButton(onClick = { onReview(group.id, "reject") }, enabled = !busy) {
                    Text(stringResource(R.string.changes_reject))
                }
            }
            if (group.canUndo) {
                TextButton(onClick = { onUndo(group.id) }, enabled = !busy) {
                    Text(stringResource(R.string.changes_undo))
                }
            }
            if (group.canRedo) {
                TextButton(onClick = { onRedo(group.id) }, enabled = !busy) {
                    Text(stringResource(R.string.changes_redo))
                }
            }
        }
    }
}

@Composable
private fun ProjectFileRowItem(
    row: ProjectFileRow,
    selected: Boolean,
    onToggle: (String) -> Unit,
    onOpenFile: (String) -> Unit,
) {
    val node = row.node
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .clickable { if (node.isDir) onToggle(node.path) else onOpenFile(node.path) }
            .padding(start = (12 + row.depth * 16).dp, top = 6.dp, bottom = 6.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (node.isDir) {
            Text(
                if (row.expanded) "▾" else "▸",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(node.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
        } else {
            Text("·", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            Text(node.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
        }
    }
}

/** 游戏控制台占位（游戏页）。会话 / 分支状态后续接 GameViewModel。 */
@Composable
fun GameConsoleContent() {
    Column(Modifier.fillMaxWidth()) {
        Text(
            "游戏控制台（待接会话 / 分支状态）",
            color = MaterialTheme.colorScheme.outline,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
