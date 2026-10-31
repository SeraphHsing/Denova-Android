package com.denova.novara.feature.trajectory

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import com.denova.novara.R
import com.denova.novara.core.i18n.resolve
import com.denova.novara.core.network.AgentRunSummary
import com.denova.novara.ui.components.NovaIconButton
import com.denova.novara.ui.components.NovaTopBar
import com.denova.novara.ui.theme.NovaRadius
import com.denova.novara.ui.theme.NovaSize
import com.denova.novara.ui.theme.NovaSpacing
import com.denova.novara.ui.theme.NovaText
import com.denova.novara.ui.theme.NovaTheme
import com.denova.novara.ui.theme.NovaWeight

/**
 * 统计页（TRJ-001…003）。
 *
 * 移动端形态对齐 Web 基线：主区显示选中 Run 的概览 + 视图，运行记录是左侧滑出面板
 * （`ResourceWorkspace` 的 `trajectory-runs` pane，宽 `min(88vw, 360px)`）。
 * 触屏差异（登记 09）：基线时间线用滚轮缩放 + 拖拽选区，Android 用尺度切换 + 行式时间条。
 */
@Composable
fun TrajectoryScreen(
    viewModel: TrajectoryViewModel,
    onOpenDrawer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    var showRuns by remember { mutableStateOf(false) }
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        // 首次进入由 bindClient 触发加载；这里只清理上一次的导出提示。
        viewModel.clearMessages()
    }

    Box(modifier.fillMaxSize().background(NovaTheme.colors.bg)) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars),
        ) {
            NovaTopBar(
                title = stringResource(R.string.trajectory_title),
                subtitle = stringResource(R.string.trajectory_subtitle),
                navigationIcon = Icons.Filled.Menu,
                navigationContentDescription = stringResource(R.string.drawer_title),
                onNavigationClick = onOpenDrawer,
                actions = {
                    NovaIconButton(
                        icon = Icons.Filled.List,
                        contentDescription = stringResource(R.string.trajectory_runs_title),
                        onClick = { showRuns = true },
                    )
                    NovaIconButton(
                        icon = Icons.Filled.Refresh,
                        contentDescription = stringResource(R.string.trajectory_refresh),
                        onClick = { viewModel.refresh() },
                    )
                    NovaIconButton(
                        icon = Icons.Filled.Share,
                        contentDescription = stringResource(R.string.trajectory_export),
                        enabled = state.selectedRun != null && !state.exporting,
                        onClick = { viewModel.exportSelected() },
                    )
                },
            )
            state.exportMessage?.let { MessageBanner(it.resolve(), NovaTheme.colors.textMuted) }
            state.exportError?.let { MessageBanner(it.resolve(), NovaTheme.colors.danger) }
            if (state.issues.isNotEmpty()) {
                MessageBanner(
                    stringResource(R.string.trajectory_partial_projects, state.issues.size),
                    NovaTheme.colors.warning,
                )
            }
            TrajectoryBody(
                state = state,
                onRetry = { viewModel.refresh() },
                onTimelineMode = viewModel::setTimelineMode,
                onView = viewModel::setView,
                onOpenRuns = { showRuns = true },
                onCopyRunId = { copyRunId(context, it) },
                onSelectRun = viewModel::selectRun,
                modifier = Modifier.weight(1f),
            )
        }
        if (showRuns) {
            RunsPanel(
                state = state,
                onSelect = { key ->
                    viewModel.selectRun(key)
                    showRuns = false
                },
                onOrganization = viewModel::setOrganization,
                onToggleSession = viewModel::toggleSession,
                onClose = { showRuns = false },
            )
        }
    }
}

@Composable
private fun TrajectoryBody(
    state: TrajectoryUiState,
    onRetry: () -> Unit,
    onTimelineMode: (TrajectoryTimelineMode) -> Unit,
    onView: (TrajectoryView) -> Unit,
    onOpenRuns: () -> Unit,
    onCopyRunId: (String) -> Unit,
    onSelectRun: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        state.disabled -> StateMessage(
            title = stringResource(R.string.trajectory_disabled),
            description = stringResource(R.string.trajectory_disabled_hint),
            modifier = modifier,
        )
        state.error != null && state.runs.isEmpty() -> StateMessage(
            title = stringResource(R.string.trajectory_load_failed),
            description = state.error.resolve(),
            action = stringResource(R.string.trajectory_retry),
            onAction = onRetry,
            modifier = modifier,
        )
        state.loadingRuns && state.runs.isEmpty() -> StateMessage(
            title = stringResource(R.string.trajectory_loading),
            description = stringResource(R.string.trajectory_loading_hint),
            modifier = modifier,
        )
        state.runs.isEmpty() -> StateMessage(
            title = stringResource(R.string.trajectory_empty),
            description = stringResource(R.string.trajectory_empty_hint),
            action = stringResource(R.string.trajectory_runs_title),
            onAction = onOpenRuns,
            modifier = modifier,
        )
        else -> {
            val run = state.selectedRun
            Column(modifier.fillMaxSize()) {
                if (run != null) {
                    RunHeader(
                        run = run,
                        trace = state.trace,
                        analysis = state.analysis,
                        onCopyRunId = { onCopyRunId(run.id) },
                        onOpenParent = { onSelectRun(run.parentRunId) },
                        parentAvailable = state.runs.any { it.id == run.parentRunId },
                    )
                }
                ViewTabs(
                    selected = state.view,
                    // 视图切换只改渲染分支，不重新拉数据（与基线一致）。
                    onSelect = onView,
                )
                if (state.trace?.truncated == true) {
                    MessageBanner(stringResource(R.string.trajectory_truncated), NovaTheme.colors.warning)
                }
                val contentModifier = Modifier.weight(1f)
                when (state.view) {
                    TrajectoryView.RECORDS -> {
                        if (state.loadingTrace) {
                            StateMessage(
                                title = stringResource(R.string.trajectory_loading),
                                description = stringResource(R.string.trajectory_loading_trace_hint),
                                modifier = contentModifier,
                            )
                        } else if (state.trace == null) {
                            StateMessage(
                                title = stringResource(R.string.trajectory_select_run),
                                description = state.error?.resolve()
                                    ?: stringResource(R.string.trajectory_select_run_hint),
                                modifier = contentModifier,
                            )
                        } else {
                            TrajectoryRecordsView(state.content, contentModifier)
                        }
                    }
                    TrajectoryView.TIMELINE -> TrajectoryTimelineView(state, onTimelineMode, contentModifier)
                    TrajectoryView.RAW -> TrajectoryRawView(state.trace, contentModifier)
                }
            }
        }
    }
}

@Composable
private fun ViewTabs(selected: TrajectoryView, onSelect: (TrajectoryView) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(NovaSize.trajectoryViewTabs)
            .padding(horizontal = NovaSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ViewTab(stringResource(R.string.trajectory_view_records), selected == TrajectoryView.RECORDS) {
            onSelect(TrajectoryView.RECORDS)
        }
        ViewTab(stringResource(R.string.trajectory_view_timeline), selected == TrajectoryView.TIMELINE) {
            onSelect(TrajectoryView.TIMELINE)
        }
        ViewTab(stringResource(R.string.trajectory_view_raw), selected == TrajectoryView.RAW) {
            onSelect(TrajectoryView.RAW)
        }
    }
}

@Composable
private fun ViewTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxHeight()
            .background(if (selected) NovaTheme.colors.active else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = NovaSpacing.md),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = NovaText.nano,
            color = if (selected) NovaTheme.colors.text else NovaTheme.colors.textFaint,
        )
    }
}

@Composable
private fun RunHeader(
    run: AgentRunSummary,
    trace: com.denova.novara.core.network.AgentRunTrace?,
    analysis: TrajectoryAnalysis,
    onCopyRunId: () -> Unit,
    onOpenParent: () -> Unit,
    parentAvailable: Boolean,
) {
    val colors = NovaTheme.colors
    var childrenOpen by remember(run.id) { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = NovaSpacing.md, vertical = NovaSpacing.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = run.id,
                style = NovaText.micro,
                fontFamily = FontFamily.Monospace,
                color = colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            StatusBadge(run.status)
            Text(
                text = stringResource(R.string.trajectory_copy_run_id),
                style = NovaText.nano,
                color = colors.accent,
                modifier = Modifier
                    .padding(start = NovaSpacing.sm)
                    .clickable(onClick = onCopyRunId),
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = NovaSpacing.xxs)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(NovaSpacing.sm),
        ) {
            MetaText(run.agentKind.ifBlank { stringResource(R.string.trajectory_agent_run) })
            MetaText(formatRunTimestamp(run.createdAt).ifBlank { "—" })
            MetaText(
                stringResource(
                    R.string.trajectory_calls,
                    run.llmCalls,
                    run.toolCalls,
                ),
            )
            MetaText(
                if (run.contentCaptured) stringResource(R.string.trajectory_content_full)
                else stringResource(R.string.trajectory_metadata_only),
            )
        }
        if (parentAvailable) {
            Text(
                text = "‹ " + stringResource(R.string.trajectory_parent_run),
                style = NovaText.nano,
                color = colors.accent,
                modifier = Modifier
                    .padding(top = NovaSpacing.xs)
                    .clickable(onClick = onOpenParent),
            )
        }
        val children = trace?.children.orEmpty().ifEmpty { emptyList() }
        if (children.isNotEmpty()) {
            Text(
                text = stringResource(R.string.trajectory_children_title, children.size),
                style = NovaText.nano.copy(fontWeight = NovaWeight.semibold),
                color = colors.textMuted,
                modifier = Modifier
                    .padding(top = NovaSpacing.xs)
                    .clickable { childrenOpen = !childrenOpen },
            )
            if (childrenOpen) {
                children.forEach { child ->
                    Text(
                        text = (child.agentName.ifBlank { child.agentKind.ifBlank { child.id } }) +
                            " · " + child.status,
                        style = NovaText.nano,
                        color = colors.textFaint,
                        modifier = Modifier.padding(start = NovaSpacing.md, top = NovaSpacing.xxs),
                    )
                }
            }
        }
        MetricStrip(analysis.metrics)
    }
}

@Composable
private fun MetricStrip(metrics: TrajectoryMetrics) {
    val colors = NovaTheme.colors
    val items = listOf(
        stringResource(R.string.trajectory_metric_total) to formatTrajectoryDuration(metrics.totalMs),
        stringResource(R.string.trajectory_metric_model) to
            "${metrics.modelCalls} · ${formatTrajectoryDuration(metrics.modelMs)}",
        stringResource(R.string.trajectory_metric_tools) to
            "${metrics.toolCalls} · ${formatTrajectoryDuration(metrics.toolMs)}",
        stringResource(R.string.trajectory_metric_ttft) to
            "P50 ${formatTrajectoryDuration(metrics.p50TtftMs)} · P95 ${formatTrajectoryDuration(metrics.p95TtftMs)}",
        stringResource(R.string.trajectory_metric_throughput) to
            formatTrajectoryThroughput(metrics.averageThroughput),
        stringResource(R.string.trajectory_metric_idle) to formatTrajectoryDuration(metrics.idleMs),
        stringResource(R.string.trajectory_metric_cache) to
            if (metrics.promptTokens > 0) {
                String.format(java.util.Locale.ROOT, "%.1f%%", metrics.cacheHitRate * 100)
            } else {
                "—"
            },
    )
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = NovaSpacing.xs)
            .background(colors.surface, RoundedCornerShape(NovaRadius.sm))
            .horizontalScroll(rememberScrollState()),
    ) {
        items.forEach { (label, value) ->
            Row(
                Modifier
                    .height(NovaSize.trajectoryMetricStrip)
                    .padding(horizontal = NovaSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NovaSpacing.xs),
            ) {
                Text(label, style = NovaText.nano, color = colors.textFaint)
                Text(
                    value,
                    style = NovaText.nano,
                    fontFamily = FontFamily.Monospace,
                    color = colors.text,
                )
            }
        }
    }
}

@Composable
private fun StatusBadge(status: String) {
    val colors = NovaTheme.colors
    val tone = runStatusTone(status)
    val color = when (tone) {
        RunStatusTone.SUCCESS -> colors.success
        RunStatusTone.RUNNING -> colors.warning
        RunStatusTone.FAILURE -> colors.danger
    }
    val label = when (tone) {
        RunStatusTone.SUCCESS -> stringResource(R.string.trajectory_status_success)
        RunStatusTone.RUNNING -> stringResource(R.string.trajectory_status_running)
        RunStatusTone.FAILURE -> stringResource(R.string.trajectory_status_failure)
    }
    Text(
        text = label,
        style = NovaText.nano,
        color = color,
        modifier = Modifier.padding(start = NovaSpacing.xs),
    )
}

@Composable
private fun MetaText(text: String) {
    Text(text, style = NovaText.nano, color = NovaTheme.colors.textFaint, maxLines = 1)
}

@Composable
private fun MessageBanner(text: String, color: Color) {
    Text(
        text = text,
        style = NovaText.nano,
        color = color,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = NovaSpacing.md, vertical = NovaSpacing.xxs),
    )
}

/** 运行记录面板：覆盖在主区之上，左侧对齐、宽度 `min(88% 可用宽, 360dp)`（基线 `w-[min(88vw,360px)]`）。 */
@Composable
private fun RunsPanel(
    state: TrajectoryUiState,
    onSelect: (String) -> Unit,
    onOrganization: (RunOrganization) -> Unit,
    onToggleSession: (String) -> Unit,
    onClose: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val panelWidth = minOf(maxWidth * PANEL_WIDTH_FRACTION, NovaSize.trajectoryPanelMax)
        Row(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .width(panelWidth)
                    .fillMaxHeight()
                    .background(NovaTheme.colors.surface),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(NovaSize.trajectoryListHeader)
                        .padding(horizontal = NovaSpacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.trajectory_runs_title) + " " + state.filteredRuns.size,
                        style = NovaText.nano.copy(fontWeight = NovaWeight.semibold),
                        color = NovaTheme.colors.textMuted,
                        modifier = Modifier.weight(1f),
                    )
                    OrganizeButton(
                        label = stringResource(R.string.trajectory_by_run),
                        selected = state.organization == RunOrganization.RUN,
                    ) { onOrganization(RunOrganization.RUN) }
                    OrganizeButton(
                        label = stringResource(R.string.trajectory_by_session),
                        selected = state.organization == RunOrganization.SESSION,
                    ) { onOrganization(RunOrganization.SESSION) }
                }
                LazyColumn(Modifier.weight(1f)) {
                    if (state.organization == RunOrganization.RUN) {
                        items(state.filteredRuns, key = { it.selectionKey }) { run ->
                            RunRow(
                                run = run,
                                grouped = false,
                                selected = run.selectionKey == state.selectedKey,
                                onClick = { onSelect(run.selectionKey) },
                            )
                        }
                    } else {
                        state.projectGroups.forEach { project ->
                            item(key = "project-${project.key}") {
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = NovaSpacing.md, vertical = NovaSpacing.xs),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        project.name,
                                        style = NovaText.micro.copy(fontWeight = NovaWeight.semibold),
                                        color = NovaTheme.colors.text,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        stringResource(R.string.trajectory_session_count, project.runCount),
                                        style = NovaText.nano,
                                        color = NovaTheme.colors.textFaint,
                                    )
                                }
                            }
                            project.sessions.forEach { session ->
                                val expanded = session.key in state.expandedSessions
                                item(key = "session-${session.key}") {
                                    SessionRow(session, expanded) { onToggleSession(session.key) }
                                }
                                if (expanded) {
                                    items(session.runs, key = { "run-${it.selectionKey}" }) { run ->
                                        RunRow(
                                            run = run,
                                            grouped = true,
                                            selected = run.selectionKey == state.selectedKey,
                                            indented = true,
                                            onClick = { onSelect(run.selectionKey) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(NovaTheme.colors.scrim)
                    .clickable(onClick = onClose),
            )
        }
    }
}

@Composable
private fun OrganizeButton(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        style = NovaText.nano,
        color = if (selected) NovaTheme.colors.text else NovaTheme.colors.textFaint,
        modifier = Modifier
            .background(
                if (selected) NovaTheme.colors.active else Color.Transparent,
                RoundedCornerShape(NovaRadius.xs),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = NovaSpacing.xs, vertical = NovaSpacing.xxs),
    )
}

@Composable
private fun SessionRow(session: SessionRunGroup, expanded: Boolean, onToggle: () -> Unit) {
    val colors = NovaTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = NovaSpacing.md, vertical = NovaSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Icon(
            imageVector = if (expanded) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = colors.textFaint,
            modifier = Modifier.size(NovaSize.iconChevron),
        )
        Text(
            text = session.sessionTitle.ifBlank {
                session.sessionId.ifBlank { stringResource(R.string.trajectory_no_session) }
            },
            style = NovaText.nano,
            color = colors.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = NovaSpacing.xs),
        )
        Text(
            stringResource(R.string.trajectory_session_count, session.runs.size),
            style = NovaText.nano,
            color = colors.textFaint,
        )
    }
}

@Composable
private fun RunRow(
    run: AgentRunSummary,
    grouped: Boolean,
    selected: Boolean,
    indented: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = NovaTheme.colors
    val fallback = stringResource(R.string.trajectory_agent_run)
    val title = if (grouped) {
        run.agentName.ifBlank { run.agentKind.ifBlank { fallback } }
    } else {
        run.projectName.ifBlank { run.id }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = if (indented) NovaSpacing.lg else NovaSpacing.sm, end = NovaSpacing.sm)
            .background(
                if (selected) colors.active else Color.Transparent,
                RoundedCornerShape(NovaRadius.sm),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = NovaSpacing.xs, vertical = NovaSpacing.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = NovaText.micro.copy(
                    fontWeight = if (selected) NovaWeight.semibold else NovaWeight.medium,
                ),
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            StatusBadge(run.status)
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = NovaSpacing.xxs),
            horizontalArrangement = Arrangement.spacedBy(NovaSpacing.sm),
        ) {
            if (!grouped) {
                Text(
                    text = run.agentKind.ifBlank { fallback },
                    style = NovaText.nano,
                    color = colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            MetaText(formatRunTimestamp(run.createdAt).ifBlank { "—" })
            MetaText(formatTrajectoryDuration(run.durationMs))
            MetaText(stringResource(R.string.trajectory_calls, run.llmCalls, run.toolCalls))
        }
    }
}

@Composable
private fun StateMessage(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .widthIn(max = NovaSize.trajectoryPanelMax)
                .padding(horizontal = NovaSpacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, style = NovaText.body, color = NovaTheme.colors.text)
            Text(
                description,
                style = NovaText.micro,
                color = NovaTheme.colors.textFaint,
                modifier = Modifier.padding(top = NovaSpacing.xs),
            )
            if (action != null && onAction != null) {
                Text(
                    text = action,
                    style = NovaText.nano,
                    color = NovaTheme.colors.accent,
                    modifier = Modifier
                        .padding(top = NovaSpacing.sm)
                        .clickable(onClick = onAction),
                )
            }
        }
    }
}

private fun copyRunId(context: Context, runId: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    manager.setPrimaryClip(ClipData.newPlainText("run_id", runId))
}

private const val PANEL_WIDTH_FRACTION = 0.88f
