package com.denova.novara.feature.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.denova.novara.R
import com.denova.novara.core.network.BookRecord
import com.denova.novara.core.onboarding.OnboardingLogic
import com.denova.novara.core.onboarding.OnboardingStep
import com.denova.novara.ui.theme.NovaRadius
import com.denova.novara.ui.theme.NovaSize
import com.denova.novara.ui.theme.NovaSpacing
import com.denova.novara.ui.theme.NovaText
import com.denova.novara.ui.theme.NovaTheme

/** 一级目的地（顺序与实现状态都来自 [ActivityNav]，避免两处各写一份）。 */
data class AppDestination(
    val route: String,
    val labelRes: Int,
    val implemented: Boolean,
)

/** 按默认顺序展开的目的地表（供导航路由复用）。 */
val appDestinations: List<AppDestination> = ActivityNav.sort().map {
    AppDestination(it.id, it.labelRes, it.implemented)
}

private fun destinationOf(id: String): AppDestination? = appDestinations.firstOrNull { it.route == id }

/** 左导航抽屉内容。 */
@Composable
fun AppDrawer(
    currentRoute: String?,
    darkTheme: Boolean,
    onToggleDarkTheme: () -> Unit,
    language: String,
    onSelectLanguage: (String) -> Unit,
    books: List<BookRecord>,
    currentProjectId: String?,
    onSelectBook: (BookRecord) -> Unit,
    /** debug 构建的日志入口；为 null 时不显示。放在抽屉里而不是悬浮按钮，避免遮挡底部操作。 */
    onOpenDebug: (() -> Unit)? = null,
    /** 消息中心入口（MSG-001…003）与未读角标；为 null 时不显示。 */
    onOpenMessages: (() -> Unit)? = null,
    unreadMessages: Int = 0,
    onNavigate: (AppDestination) -> Unit,
    /** 新手引导当前步（WRI-001）；为 null 时不显示引导卡。 */
    onboardingStep: OnboardingStep? = null,
    onSkipOnboarding: () -> Unit = {},
    onCreateBook: () -> Unit = {},
    onFillFirstPrompt: () -> Unit = {},
    /** 已按用户保存顺序排好的入口（NAV-001/004）；默认用基线顺序。 */
    activityItems: List<ActivitySpec> = ActivityNav.sort(),
    onMoveActivity: (id: String, delta: Int) -> Unit = { _, _ -> },
    onResetActivityOrder: () -> Unit = {},
    /** 标题栏左侧返回箭头（基线 `[data-mobile-drawer-header]` 的关闭动作）；为 null 时不显示。 */
    onClose: (() -> Unit)? = null,
) {
    // 排序模式与搜索词都是抽屉内的临时界面状态，不参与业务状态。
    var reorderMode by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    // 选中结果后先收键盘再导航：否则键盘会盖住目标页首屏，且焦点留在已关闭的抽屉里（NAV-006）。
    val focusManager = LocalFocusManager.current
    // 过滤与分组在组合作用域里算：`filter` 是 inline，lambda 内可以调 `stringResource`；
    // 匹配规则在 ActivityNav.matches 里（纯函数，JVM 覆盖 trim / 大小写 / 空词）。
    val visible = activityItems.filter { ActivityNav.matches(query, stringResource(it.labelRes)) }
    val grouped = ActivityNav.grouped(visible)
    // 标题固定，其余内容全部放进同一个 LazyColumn。
    // 之前是「固定底栏 + 中间 Column(weight + verticalScroll)」：横屏时可视高度只剩百来像素，
    // 中间的滚动列被压到几乎为零，导航分组整块不渲染（2026-10-27 真机 + 语义树确认）。
    // 换成单个 LazyColumn 后任意高度都能滚动到每一项，也不再出现「渲染出来却点不到」的裁剪问题。
    // 底部也要避开系统导航/手势区：只加 statusBars 时，抽屉最后几条（Skills / Agents）
    // 会落到手势条下方，语义树里能查到但点了没反应（2026-10-28 真机确认）。
    // 手势导航下 navigationBars 常报 0，真正吃掉点击的是 systemGestures，取两者较大值；
    // 另外给列表底部留出额外空间，滚到底时最后一项停在上方可点区域，而不是贴着屏幕底边。
    val bottomInset = maxOf(
        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
        WindowInsets.systemGestures.asPaddingValues().calculateBottomPadding(),
    )
    val listBottomPadding = if (bottomInset > 240.dp) bottomInset else 240.dp
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            // NAV-006：搜索框聚焦时键盘会盖住结果列表（edge-to-edge 下 adjustResize 不缩窗口），
            // 按 IME insets 让内容上移，结果行始终可点。
            .imePadding(),
    ) {
        // VG-002：标题栏固定 56dp、左右 12dp、17sp/600，基线 `[data-mobile-drawer-header]` 无分隔线。
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = NovaSize.drawerHeader)
                .padding(horizontal = NovaSize.drawerHeaderPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onClose != null) {
                Box(
                    Modifier
                        .size(NovaSize.touchTarget)
                        .clip(RoundedCornerShape(NovaRadius.field))
                        .clickable(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = stringResource(R.string.drawer_close),
                        tint = NovaTheme.colors.text,
                        modifier = Modifier.size(NovaSize.iconAction),
                    )
                }
            }
            Text(
                stringResource(R.string.drawer_title),
                style = NovaText.drawerTitle,
                color = NovaTheme.colors.text,
                modifier = Modifier.padding(start = if (onClose != null) NovaSpacing.sm else 0.dp),
            )
        }
        // NAV-004 / NAV-006 工具区固定在标题栏下方：
        // 搜索框原本在列表中部，键盘弹出后结果整块被盖住（真机确认），固定到顶部后结果仍在可视区。
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.drawer_nav_section), style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = { reorderMode = !reorderMode }) {
                Text(stringResource(if (reorderMode) R.string.drawer_sort_done else R.string.drawer_sort))
            }
            if (reorderMode) {
                TextButton(onClick = onResetActivityOrder) { Text(stringResource(R.string.drawer_sort_reset)) }
            }
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            label = { Text(stringResource(R.string.drawer_search_actions)) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        // 搜索态只留结果：书籍区与引导卡会让结果被挤出键盘可视区。
        val searching = query.isNotBlank()
        // 搜索词变化后回到列表顶部：搜索时列表项变少，保留旧滚动位置会停在中间，
        // 清空搜索后看起来像「书籍区和创作组消失」（真机确认）。
        val listState = rememberLazyListState()
        LaunchedEffect(query) { listState.scrollToItem(0) }
        LazyColumn(
            Modifier.weight(1f),
            state = listState,
            contentPadding = PaddingValues(bottom = listBottomPadding),
        ) {
            // 新手引导（WRI-001）：三步核心，完成或跳过就不再出现。
            if (!searching && onboardingStep != null) {
                item {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(stringResource(R.string.onboarding_progress, OnboardingLogic.progressLabel(onboardingStep)), style = MaterialTheme.typography.labelLarge)
                            TextButton(onClick = onSkipOnboarding) { Text(stringResource(R.string.onboarding_skip)) }
                        }
                        Text(stringResource(OnboardingLogic.stepTitle(onboardingStep)), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            stringResource(OnboardingLogic.stepBody(onboardingStep)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                        when (onboardingStep) {
                            OnboardingStep.BOOK -> TextButton(onClick = onCreateBook) { Text(stringResource(R.string.onboarding_go_create)) }
                            OnboardingStep.AGENT -> TextButton(onClick = onFillFirstPrompt) { Text(stringResource(R.string.onboarding_fill_example)) }
                            OnboardingStep.MODEL -> Text(stringResource(R.string.onboarding_model_hint), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    HorizontalDivider()
                }
            }

            if (!searching) {
                // 当前书籍切换（P3 链路起点）：选择后作为编辑器 / 项目目录的工作区。
                item {
                    Box(Modifier.fillMaxWidth().padding(horizontal = NovaSpacing.lg, vertical = NovaSpacing.xs)) {
                        SectionLabel(stringResource(R.string.current_book))
                    }
                }
                if (books.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.book_empty),
                            color = MaterialTheme.colorScheme.outline,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                } else {
                    items(books.size) { index ->
                        val book = books[index]
                        val selected = book.projectId == currentProjectId
                        TextButton(onClick = { onSelectBook(book) }, modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    book.name,
                                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                )
                                if (selected) Text("✓", color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
                // 建书入口常驻：引导卡只在新手期出现，跳过后仍要能新建书籍（对齐 Web 书籍管理入口）。
                item {
                    TextButton(onClick = onCreateBook, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.drawer_new_book), style = MaterialTheme.typography.bodyMedium)
                    }
                    HorizontalDivider()
                }
            }

            // 一级导航（VG-002…004，取值见 docs/evidence/web-baseline/2026-10-30/metrics.json）：
            // 创作组是两列 58dp 入口卡（选中用 primary 反色），工具/设置组是整组卡片，行高 52dp、
            // 组圆角 18dp、行间 1px 分隔线左缩进 48dp、尾部 chevron 14dp。
            val openDestination: (AppDestination) -> Unit = { destination ->
                focusManager.clearFocus()
                // 命令面板语义：选中即清空搜索词，避免下次打开仍被过滤（NAV-006）。
                query = ""
                onNavigate(destination)
            }
            grouped.forEach { (group, groupSpecs) ->
                val entries = groupSpecs.mapNotNull { spec ->
                    destinationOf(spec.id)?.let { destination -> spec to destination }
                }
                if (entries.isEmpty()) return@forEach
                if (group == ActivityGroup.CREATION) {
                    item(key = "group-creation") {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = NovaSpacing.lg, vertical = NovaSpacing.xs),
                        ) {
                            SectionLabel(stringResource(R.string.group_creation))
                            Row(horizontalArrangement = Arrangement.spacedBy(NovaSpacing.sm + NovaSpacing.xxs)) {
                                entries.forEach { (spec, destination) ->
                                    NavEntryCard(
                                        label = stringResource(spec.labelRes),
                                        icon = spec.icon,
                                        selected = spec.id == currentRoute,
                                        enabled = spec.implemented,
                                        modifier = Modifier.weight(1f),
                                        onClick = { openDestination(destination) },
                                    )
                                }
                            }
                        }
                    }
                } else {
                    item(key = "group-${group.name}") {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = NovaSpacing.lg, vertical = NovaSpacing.xs),
                        ) {
                            // 设置组按基线不带分组标题。
                            if (group != ActivityGroup.SETTINGS) {
                                SectionLabel(stringResource(R.string.group_tools))
                            }
                            Surface(
                                shape = RoundedCornerShape(NovaRadius.group),
                                color = NovaTheme.colors.surface,
                            ) {
                                Column {
                                    entries.forEachIndexed { index, entry ->
                                        val spec = entry.first
                                        val destination = entry.second
                                        NavListItem(
                                            label = stringResource(spec.labelRes),
                                            icon = spec.icon,
                                            selected = spec.id == currentRoute,
                                            enabled = spec.implemented,
                                            onClick = { openDestination(destination) },
                                        ) {
                                            if (reorderMode && group != ActivityGroup.SETTINGS) {
                                                TextButton(onClick = { onMoveActivity(spec.id, -1) }) { Text("↑") }
                                                TextButton(onClick = { onMoveActivity(spec.id, 1) }) { Text("↓") }
                                            } else {
                                                NavRowTrailing(spec = spec, selected = spec.id == currentRoute)
                                            }
                                        }
                                        if (index != entries.lastIndex) {
                                            HorizontalDivider(
                                                modifier = Modifier.padding(start = 48.dp),
                                                thickness = 1.dp,
                                                color = NovaTheme.colors.borderSoft,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (visible.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.drawer_no_match),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }

            item {
                HorizontalDivider()
                TextButton(onClick = onToggleDarkTheme, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.dark_mode), modifier = Modifier.weight(1f))
                    Switch(checked = darkTheme, onCheckedChange = null)
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.language), modifier = Modifier.weight(1f))
                    LanguageOption("中文", "zh", language, onSelectLanguage)
                    LanguageOption("English", "en", language, onSelectLanguage)
                }
                if (onOpenMessages != null) {
                    HorizontalDivider()
                    TextButton(onClick = onOpenMessages, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            if (unreadMessages > 0) {
                                stringResource(R.string.drawer_messages_unread, unreadMessages)
                            } else {
                                stringResource(R.string.drawer_messages)
                            },
                        )
                    }
                }
                if (onOpenDebug != null) {
                    HorizontalDivider()
                    TextButton(onClick = onOpenDebug, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.drawer_debug))
                    }
                }
            }
        }
    }
}

/** 分组标签：12sp/500、左缩进 12dp、下边距 8dp（基线 `.nova-mobile-section-label`）。 */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = NovaText.sectionLabel,
        color = NovaTheme.colors.textMuted,
        modifier = Modifier.padding(start = NovaSpacing.md, bottom = NovaSpacing.sm),
    )
}

/** 创作组入口卡：58dp 高、18dp 圆角、选中时 primary 反色（基线 `[data-mobile-nav-group="creation"]`）。 */
@Composable
private fun NavEntryCard(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = NovaTheme.colors
    val container = if (selected) colors.primary else colors.surface
    val content = when {
        !enabled -> colors.textFaint
        selected -> colors.onPrimary
        else -> colors.text
    }
    Row(
        modifier
            .height(NovaSize.navEntryRow)
            .clip(RoundedCornerShape(NovaRadius.group))
            .background(container)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NovaSpacing.sm + NovaSpacing.xxs),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(NovaSize.iconAction),
        )
        Text(
            label,
            style = NovaText.navEntryLabel,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 工具组行：52dp 高、图标容器 22×24、左内边距 14dp（基线 `.nova-mobile-nav-row`）。 */
@Composable
private fun NavListItem(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    trailing: @Composable () -> Unit,
) {
    val colors = NovaTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = NovaSize.navRow)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NovaSpacing.md),
    ) {
        Box(
            Modifier
                .width(NovaSize.iconNavBoxWidth)
                .height(NovaSize.iconNavBoxHeight),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (selected) colors.text else colors.textMuted,
                modifier = Modifier.size(NovaSize.iconAction),
            )
        }
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = NovaText.navLabel,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (enabled) colors.text else colors.textFaint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        trailing()
    }
}

/** 行尾状态：未实现标「未实现」，当前项打勾，其余显示 14dp chevron。 */
@Composable
private fun NavRowTrailing(spec: ActivitySpec, selected: Boolean) {
    when {
        // 未实现：只标 WIP，不给可点击的视觉暗示（不给 ›，也没有跳转）。
        !spec.implemented -> Text(
            stringResource(R.string.dest_placeholder),
            style = NovaText.sectionLabel,
            color = NovaTheme.colors.textFaint,
        )
        selected -> Icon(
            Icons.Filled.Check,
            contentDescription = stringResource(R.string.dest_current),
            tint = NovaTheme.colors.text,
            modifier = Modifier.size(NovaSize.iconAction),
        )
        else -> Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = NovaTheme.colors.textFaint,
            modifier = Modifier.size(NovaSize.iconChevron),
        )
    }
}

@Composable
private fun LanguageOption(
    label: String,
    tag: String,
    current: String,
    onSelect: (String) -> Unit,
) {
    val selected = current == tag || (current.isBlank() && tag == "en")
    TextButton(onClick = { onSelect(tag) }) {
        Text(
            label,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** 未实现目的地的占位页。 */
@Composable
fun PlaceholderScreen(labelRes: Int, onOpenDrawer: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)) {
            IconButton(onClick = onOpenDrawer) { Text("☰") }
            Text(stringResource(labelRes), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(8.dp))
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.not_implemented, stringResource(labelRes)),
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}
