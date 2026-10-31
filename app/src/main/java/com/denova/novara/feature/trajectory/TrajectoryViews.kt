package com.denova.novara.feature.trajectory

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import com.denova.novara.R
import com.denova.novara.core.network.AgentRunTrace
import com.denova.novara.ui.theme.NovaRadius
import com.denova.novara.ui.theme.NovaSize
import com.denova.novara.ui.theme.NovaSpacing
import com.denova.novara.ui.theme.NovaText
import com.denova.novara.ui.theme.NovaTheme
import com.denova.novara.ui.theme.NovaWeight
import kotlinx.serialization.json.Json

private val prettyJson = Json { prettyPrint = true }

/** 对话视图：按请求分组的模型可见记录（基线 `TrajectoryContentLedger` 的移动端形态）。 */
@Composable
fun TrajectoryRecordsView(
    content: TrajectoryContentAnalysis,
    modifier: Modifier = Modifier,
) {
    if (!content.available) {
        TrajectoryEmpty(
            title = stringResource(R.string.trajectory_records_unavailable),
            description = stringResource(R.string.trajectory_records_unavailable_hint),
            modifier = modifier,
        )
        return
    }
    var filter by remember { mutableStateOf(TrajectoryContentFilter.ALL) }
    val entriesByRequest = content.requests.map { request ->
        request to request.entries.filter { filter.matches(it) }
    }
    Column(modifier.fillMaxSize()) {
        FilterRow(selected = filter, onSelect = { filter = it })
        LazyColumn(Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(NovaSpacing.sm)) {
            entriesByRequest.forEach { (request, entries) ->
                if (entries.isEmpty()) return@forEach
                item(key = "request-${request.id}") {
                    Text(
                        text = stringResource(R.string.trajectory_request_index, request.index),
                        style = NovaText.micro.copy(fontWeight = NovaWeight.semibold),
                        color = NovaTheme.colors.textMuted,
                        modifier = Modifier.padding(
                            start = NovaSpacing.xs,
                            top = NovaSpacing.md,
                            bottom = NovaSpacing.xs,
                        ),
                    )
                }
                items(entries, key = { it.id }) { entry ->
                    ContentEntryRow(entry)
                }
            }
        }
    }
}

/** 时间线视图：三尺度投影 + 行式时间条 + 调用/事件账本。 */
@Composable
fun TrajectoryTimelineView(
    state: TrajectoryUiState,
    onModeChange: (TrajectoryTimelineMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val analysis = state.analysis
    val projection = remember(analysis, state.timelineMode) { projectTimeline(analysis, state.timelineMode) }
    Column(modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(NovaSize.trajectoryViewTabs)
                .padding(horizontal = NovaSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NovaSpacing.xs),
        ) {
            Text(
                stringResource(R.string.trajectory_timeline_scale),
                style = NovaText.nano,
                color = NovaTheme.colors.textFaint,
            )
            TimelineModeButton(
                label = stringResource(R.string.trajectory_timeline_actual),
                selected = state.timelineMode == TrajectoryTimelineMode.ACTUAL,
                onClick = { onModeChange(TrajectoryTimelineMode.ACTUAL) },
            )
            TimelineModeButton(
                label = stringResource(R.string.trajectory_timeline_duration),
                selected = state.timelineMode == TrajectoryTimelineMode.DURATION,
                onClick = { onModeChange(TrajectoryTimelineMode.DURATION) },
            )
            TimelineModeButton(
                label = stringResource(R.string.trajectory_timeline_sequence),
                selected = state.timelineMode == TrajectoryTimelineMode.SEQUENCE,
                onClick = { onModeChange(TrajectoryTimelineMode.SEQUENCE) },
            )
        }
        if (projection.spans.isEmpty()) {
            TrajectoryEmpty(
                title = stringResource(R.string.trajectory_timeline_empty),
                description = stringResource(R.string.trajectory_timeline_empty_hint),
                modifier = Modifier.weight(1f),
            )
            return@Column
        }
        LazyColumn(Modifier.weight(1f)) {
            item(key = "timeline-bars") {
                TimelineBars(projection)
            }
            item(key = "ledger-header") {
                Text(
                    text = stringResource(R.string.trajectory_ledger_title) + " · " +
                        stringResource(R.string.trajectory_ledger_events, analysis.events.size),
                    style = NovaText.micro.copy(fontWeight = NovaWeight.semibold),
                    color = NovaTheme.colors.textMuted,
                    modifier = Modifier.padding(
                        start = NovaSpacing.md,
                        top = NovaSpacing.md,
                        bottom = NovaSpacing.xs,
                    ),
                )
            }
            items(analysis.spans, key = { "span-${it.id}" }) { span ->
                LedgerSpanRow(span)
            }
            itemsIndexed(analysis.events, key = { _, event -> "event-${event.id}" }) { _, event ->
                LedgerEventRow(event)
            }
        }
    }
}

/** 原始视图：整份 trace 的格式化 JSON（对齐基线的 `raw` 视图，只读）。 */
@Composable
fun TrajectoryRawView(trace: AgentRunTrace?, modifier: Modifier = Modifier) {
    if (trace == null) {
        TrajectoryEmpty(
            title = stringResource(R.string.trajectory_select_run),
            description = stringResource(R.string.trajectory_select_run_hint),
            modifier = modifier,
        )
        return
    }
    val text = remember(trace) { prettyJson.encodeToString(AgentRunTrace.serializer(), trace) }
    LazyColumn(
        modifier.fillMaxSize().background(NovaTheme.colors.surface),
        contentPadding = PaddingValues(NovaSpacing.sm),
    ) {
        itemsIndexed(text.split('\n')) { _, line ->
            Text(
                text = line,
                style = NovaText.nano,
                fontFamily = FontFamily.Monospace,
                color = NovaTheme.colors.textMuted,
            )
        }
    }
}

private enum class TrajectoryContentFilter {
    ALL, SYSTEM, USER, CONTEXT, ASSISTANT, TOOL;

    fun matches(entry: TrajectoryContentEntry): Boolean = when (this) {
        ALL -> true
        SYSTEM -> entry.kind == TrajectoryContentKind.SYSTEM
        USER -> entry.kind == TrajectoryContentKind.USER
        CONTEXT -> entry.kind == TrajectoryContentKind.CONTEXT
        ASSISTANT -> entry.kind == TrajectoryContentKind.ASSISTANT
        TOOL -> entry.kind == TrajectoryContentKind.TOOL || entry.toolCalls.isNotEmpty()
    }
}

@Composable
private fun FilterRow(selected: TrajectoryContentFilter, onSelect: (TrajectoryContentFilter) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = NovaSpacing.sm, vertical = NovaSpacing.xs),
        horizontalArrangement = Arrangement.spacedBy(NovaSpacing.xs),
    ) {
        FilterChip(selected == TrajectoryContentFilter.ALL, stringResource(R.string.trajectory_filter_all)) {
            onSelect(TrajectoryContentFilter.ALL)
        }
        FilterChip(selected == TrajectoryContentFilter.SYSTEM, stringResource(R.string.trajectory_filter_system)) {
            onSelect(TrajectoryContentFilter.SYSTEM)
        }
        FilterChip(selected == TrajectoryContentFilter.USER, stringResource(R.string.trajectory_filter_user)) {
            onSelect(TrajectoryContentFilter.USER)
        }
        FilterChip(selected == TrajectoryContentFilter.CONTEXT, stringResource(R.string.trajectory_filter_context)) {
            onSelect(TrajectoryContentFilter.CONTEXT)
        }
        FilterChip(selected == TrajectoryContentFilter.ASSISTANT, stringResource(R.string.trajectory_filter_assistant)) {
            onSelect(TrajectoryContentFilter.ASSISTANT)
        }
        FilterChip(selected == TrajectoryContentFilter.TOOL, stringResource(R.string.trajectory_filter_tools)) {
            onSelect(TrajectoryContentFilter.TOOL)
        }
    }
}

@Composable
private fun FilterChip(selected: Boolean, label: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = NovaText.nano,
        color = if (selected) NovaTheme.colors.text else NovaTheme.colors.textFaint,
        modifier = Modifier
            .background(
                color = if (selected) NovaTheme.colors.active else Color.Transparent,
                shape = RoundedCornerShape(NovaRadius.xs),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = NovaSpacing.sm, vertical = NovaSpacing.xxs),
    )
}

@Composable
private fun TimelineModeButton(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        style = NovaText.nano,
        color = if (selected) NovaTheme.colors.text else NovaTheme.colors.textFaint,
        modifier = Modifier
            .background(
                color = if (selected) NovaTheme.colors.active else Color.Transparent,
                shape = RoundedCornerShape(NovaRadius.xs),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = NovaSpacing.sm, vertical = NovaSpacing.xxs),
    )
}

@Composable
private fun ContentEntryRow(entry: TrajectoryContentEntry) {
    // System Prompt 往往数千字，默认折叠；其余记录默认展开（与基线「易读」模式一致）。
    var expanded by remember(entry.id) { mutableStateOf(entry.kind != TrajectoryContentKind.SYSTEM) }
    val colors = NovaTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = NovaSpacing.xs)
            .background(colors.surface, RoundedCornerShape(NovaRadius.sm))
            .clickable { expanded = !expanded }
            .padding(horizontal = NovaSpacing.sm, vertical = NovaSpacing.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = entryLabel(entry),
                style = NovaText.nano.copy(fontWeight = NovaWeight.semibold),
                color = colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.trajectory_characters, entry.content.length),
                style = NovaText.nano,
                color = colors.textFaint,
            )
            Text(
                text = if (expanded) "−" else "+",
                style = NovaText.nano,
                color = colors.textFaint,
                modifier = Modifier.padding(start = NovaSpacing.xs),
            )
        }
        if (entry.reasoning.isNotBlank() && expanded) {
            Text(
                text = entry.reasoning,
                style = NovaText.compact,
                color = colors.textFaint,
                modifier = Modifier.padding(top = NovaSpacing.xs),
            )
        }
        if (entry.content.isNotBlank()) {
            Text(
                text = if (expanded) entry.content else entry.content.take(PREVIEW_CHARS) + "…",
                style = NovaText.compact,
                color = colors.text,
                modifier = Modifier.padding(top = NovaSpacing.xs),
            )
        } else if (!entry.hasBody) {
            Text(
                text = stringResource(R.string.trajectory_no_text),
                style = NovaText.compact,
                color = colors.textFaint,
                modifier = Modifier.padding(top = NovaSpacing.xs),
            )
        }
        if (expanded) {
            entry.toolCalls.forEach { call ->
                Column(Modifier.padding(top = NovaSpacing.xs)) {
                    Text(
                        text = stringResource(R.string.trajectory_tool_call) + " · " + call.name,
                        style = NovaText.nano.copy(fontWeight = NovaWeight.semibold),
                        color = colors.accent,
                    )
                    if (call.arguments.isNotBlank()) {
                        Text(
                            text = call.arguments,
                            style = NovaText.nano,
                            fontFamily = FontFamily.Monospace,
                            color = colors.textMuted,
                        )
                    }
                }
            }
            if (entry.toolResult.isNotBlank() || entry.toolError.isNotBlank()) {
                Text(
                    text = stringResource(R.string.trajectory_tool_result),
                    style = NovaText.nano.copy(fontWeight = NovaWeight.semibold),
                    color = if (entry.toolError.isBlank()) colors.textMuted else colors.danger,
                    modifier = Modifier.padding(top = NovaSpacing.xs),
                )
                Text(
                    text = entry.toolError.ifBlank { entry.toolResult },
                    style = NovaText.nano,
                    fontFamily = FontFamily.Monospace,
                    color = colors.textMuted,
                )
                if (entry.toolTruncated) {
                    Text(
                        text = stringResource(R.string.trajectory_tool_truncated),
                        style = NovaText.nano,
                        color = colors.warning,
                    )
                }
            }
        }
    }
}

@Composable
private fun entryLabel(entry: TrajectoryContentEntry): String = when {
    entry.kind == TrajectoryContentKind.SYSTEM && entry.label == "initial_system" ->
        stringResource(R.string.trajectory_label_initial_system)
    entry.kind == TrajectoryContentKind.SYSTEM && entry.label == "system_update" ->
        stringResource(R.string.trajectory_label_system_update)
    entry.kind == TrajectoryContentKind.CONTEXT && entry.label == "snapshot_changed" ->
        stringResource(R.string.trajectory_label_snapshot_changed)
    entry.kind == TrajectoryContentKind.USER -> stringResource(R.string.trajectory_label_user)
    entry.kind == TrajectoryContentKind.CONTEXT -> stringResource(R.string.trajectory_label_context)
    entry.kind == TrajectoryContentKind.TOOL -> stringResource(R.string.trajectory_label_tool) +
        entry.toolName.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
    entry.direction == "output" -> stringResource(R.string.trajectory_label_output) +
        " · " + stringResource(R.string.trajectory_request_index, entry.requestIndex)
    else -> stringResource(R.string.trajectory_label_assistant)
}

@Composable
private fun TimelineBars(projection: TimelineProjection) {
    val span = (projection.end - projection.start).coerceAtLeast(1.0)
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = NovaSpacing.sm, vertical = NovaSpacing.xs),
    ) {
        val width = maxWidth
        Column {
            projection.spans.take(MAX_TIMELINE_BARS).forEach { item ->
                val from = ((item.start - projection.start) / span).toFloat().coerceIn(0f, 1f)
                val to = ((item.end - projection.start) / span).toFloat().coerceIn(0f, 1f)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(NovaSize.trajectoryTimelineRow),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = item.label,
                        style = NovaText.nano,
                        color = NovaTheme.colors.textFaint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(width * LABEL_FRACTION),
                    )
                    Box(Modifier.weight(1f)) {
                        Box(
                            Modifier
                                .offset(x = width * (1f - LABEL_FRACTION) * from)
                                .width((width * (1f - LABEL_FRACTION) * (to - from)).coerceAtLeast(MIN_BAR_WIDTH))
                                .height(NovaSize.trajectoryTimelineBar)
                                .background(categoryColor(item.category), RoundedCornerShape(NovaRadius.micro)),
                        )
                    }
                    Text(
                        text = formatTrajectoryDuration(item.durationMs),
                        style = NovaText.nano,
                        color = NovaTheme.colors.textMuted,
                        modifier = Modifier.padding(start = NovaSpacing.xs),
                    )
                }
            }
        }
    }
}

@Composable
private fun LedgerSpanRow(span: TrajectorySpan) {
    val colors = NovaTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = NovaSpacing.md, vertical = NovaSpacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(NovaSize.statusDot)
                .height(NovaSize.statusDot)
                .background(categoryColor(span.category), RoundedCornerShape(NovaRadius.pill)),
        )
        Text(
            text = span.label,
            style = NovaText.nano,
            color = colors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = NovaSpacing.xs),
        )
        Text(
            text = formatTrajectoryDuration(span.durationMs),
            style = NovaText.nano,
            color = colors.textMuted,
        )
    }
}

@Composable
private fun LedgerEventRow(event: TrajectoryEventRecord) {
    val colors = NovaTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = NovaSpacing.md, vertical = NovaSpacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = event.label,
            style = NovaText.nano,
            color = colors.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = event.status,
            style = NovaText.nano,
            color = if (event.status.lowercase() in ERROR_STATUSES) colors.danger else colors.textFaint,
        )
    }
}

@Composable
private fun TrajectoryEmpty(title: String, description: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(horizontal = NovaSpacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, style = NovaText.body, color = NovaTheme.colors.text)
            Text(
                description,
                style = NovaText.micro,
                color = NovaTheme.colors.textFaint,
                modifier = Modifier.padding(top = NovaSpacing.xs),
            )
        }
    }
}

@Composable
private fun categoryColor(category: TrajectoryCategory): Color = when (category) {
    TrajectoryCategory.MODEL -> NovaTheme.colors.accent
    TrajectoryCategory.TOOL -> NovaTheme.colors.warning
    TrajectoryCategory.CONTEXT -> NovaTheme.colors.accentBlue
    TrajectoryCategory.VERIFICATION -> NovaTheme.colors.success
    TrajectoryCategory.RUN -> NovaTheme.colors.textMuted
    TrajectoryCategory.EVENT -> NovaTheme.colors.textFaint
}

private val ERROR_STATUSES = setOf("error", "failed", "blocked", "aborted")
private const val PREVIEW_CHARS = 240
private const val MAX_TIMELINE_BARS = 200
private const val LABEL_FRACTION = 0.34f
private val MIN_BAR_WIDTH = NovaSize.trajectoryTimelineBar
