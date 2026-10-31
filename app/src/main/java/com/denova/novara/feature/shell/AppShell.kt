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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.denova.novara.R
import com.denova.novara.core.network.BookRecord
import com.denova.novara.core.onboarding.OnboardingLogic
import com.denova.novara.ui.components.NovaSearchField
import com.denova.novara.core.onboarding.OnboardingStep
import com.denova.novara.ui.theme.NovaRadius
import com.denova.novara.ui.theme.NovaSize
import com.denova.novara.ui.theme.NovaSpacing
import com.denova.novara.ui.theme.NovaText
import com.denova.novara.ui.theme.NovaTheme
import com.denova.novara.ui.components.NovaTopBar
import androidx.compose.material.icons.filled.Menu

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
    // 下拉卡、命令面板与排序模式都是抽屉内的临时界面状态，不参与业务状态。
    var bookMenuOpen by remember { mutableStateOf(false) }
    var commandOpen by remember { mutableStateOf(false) }
    var commandQuery by remember { mutableStateOf("") }
    var reorderMode by remember { mutableStateOf(false) }
    // 导航分组始终展示全部入口；过滤只发生在命令面板里（基线 `command.openButton`）。
    val grouped = ActivityNav.grouped(activityItems)
    // 选中结果后先收键盘再导航：否则键盘会盖住目标页首屏，且焦点留在已关闭的面板里（NAV-006）。
    val focusManager = LocalFocusManager.current
    // 抽屉与命令面板共用同一个「打开目的地」出口，保证选中即收起面板并清搜索词。
    val openDestination: (AppDestination) -> Unit = { destination ->
        focusManager.clearFocus()
        commandQuery = ""
        commandOpen = false
        bookMenuOpen = false
        onNavigate(destination)
    }
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
    val listBottomPadding = if (bottomInset > NovaSize.drawerBottomInset) bottomInset else NovaSize.drawerBottomInset
    Box(Modifier.fillMaxSize()) {
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
        // VG-011 / VG-012：抽屉顶部只有「当前作品」下拉卡与「命令 / 消息」两列快捷卡。
        // 基线 `workspace-mobile-layout.tsx` 的 navigationTools 正是这三块，移动端抽屉没有搜索框与排序按钮；
        // 搜索与排序迁到「命令面板」（桌面侧栏 customization 面板在移动端的等价入口，已登记 09）。
        val listState = rememberLazyListState()
        LazyColumn(
            Modifier.weight(1f),
            state = listState,
            contentPadding = PaddingValues(bottom = listBottomPadding),
        ) {
            item(key = "book-context") {
                BookContextBlock(
                    books = books,
                    currentProjectId = currentProjectId,
                    booksOpen = bookMenuOpen,
                    onToggleOpen = { bookMenuOpen = !bookMenuOpen },
                    onSelectBook = onSelectBook,
                    onCreateBook = onCreateBook,
                    onOpenCommand = { commandOpen = true },
                    onOpenMessages = onOpenMessages,
                    unreadMessages = unreadMessages,
                )
            }
            // 新手引导（WRI-001）：三步核心，完成或跳过就不再出现。
            if (onboardingStep != null) {
                item {
                    Column(Modifier.fillMaxWidth().padding(horizontal = NovaSpacing.lg, vertical = NovaSpacing.sm)) {
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

            // 书籍切换已改为顶部下拉卡（VG-011），此处不再重复列表。

            // 一级导航（VG-002…004，取值见 docs/evidence/web-baseline/2026-10-30/metrics.json）：
            // 创作组是两列 58dp 入口卡（选中用 primary 反色），工具/设置组是整组卡片，行高 52dp、
            // 组圆角 18dp、行间 1px 分隔线左缩进 48dp、尾部 chevron 14dp。
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
                                            NavRowTrailing(spec = spec, selected = spec.id == currentRoute)
                                        }
                                        if (index != entries.lastIndex) {
                                            HorizontalDivider(
                                                modifier = Modifier.padding(start = NovaSize.dividerInset),
                                                thickness = NovaSize.hairline,
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
            item {
                HorizontalDivider()
                TextButton(onClick = onToggleDarkTheme, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.dark_mode), modifier = Modifier.weight(1f))
                    Switch(checked = darkTheme, onCheckedChange = null)
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = NovaSpacing.lg, vertical = NovaSpacing.sm),
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
        if (commandOpen) {
            CommandPalette(
                items = activityItems,
                currentRoute = currentRoute,
                query = commandQuery,
                onQueryChange = { commandQuery = it },
                onNavigate = openDestination,
                reorderMode = reorderMode,
                onToggleReorder = { reorderMode = !reorderMode },
                onMove = onMoveActivity,
                onReset = onResetActivityOrder,
                onClose = {
                    commandQuery = ""
                    commandOpen = false
                    reorderMode = false
                },
            )
        }
    }
}

/**
 * 「当前作品」下拉卡 + 「命令 / 消息」两列快捷卡（VG-011 / VG-012）。
 *
 * 基线 `WorkbenchShell.tsx` 的 `mobileNavigationTools`：section 标签「当前作品」→
 * `BookSwitcher compact`（h-8、max-w-34vw、11px、名称 + chevron）→ 两列 44px 快捷卡
 * （命令 = 打开命令面板，消息 = 消息中心 + 未读角标）。
 *
 * 移动端差异（登记 09）：基线用 `DropdownMenu` 浮层，抽屉内改为内联展开，避免浮层被抽屉裁剪。
 */
@Composable
private fun BookContextBlock(
    books: List<BookRecord>,
    currentProjectId: String?,
    booksOpen: Boolean,
    onToggleOpen: () -> Unit,
    onSelectBook: (BookRecord) -> Unit,
    onCreateBook: () -> Unit,
    onOpenCommand: () -> Unit,
    onOpenMessages: (() -> Unit)?,
    unreadMessages: Int,
) {
    val colors = NovaTheme.colors
    val current = books.firstOrNull { it.projectId == currentProjectId }
    Column(Modifier.fillMaxWidth().padding(horizontal = NovaSpacing.lg, vertical = NovaSpacing.sm)) {
        SectionLabel(stringResource(R.string.drawer_current_work))
        Row(
            Modifier
                .fillMaxWidth(CONTEXT_TRIGGER_WIDTH_FRACTION)
                .height(NovaSize.contextTrigger)
                .background(colors.surface, RoundedCornerShape(NovaRadius.field))
                .clickable(onClick = onToggleOpen)
                .padding(horizontal = NovaSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = current?.name ?: stringResource(R.string.book_empty),
                style = NovaText.micro.copy(fontWeight = FontWeight.Medium),
                color = if (current != null) colors.text else colors.textFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (booksOpen) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = stringResource(R.string.drawer_book_switch_title),
                tint = colors.textFaint,
                modifier = Modifier.size(NovaSize.iconChevron),
            )
        }
        if (booksOpen) {
            Surface(
                shape = RoundedCornerShape(NovaRadius.card),
                color = colors.surface2,
                modifier = Modifier.padding(top = NovaSpacing.xs),
            ) {
                Column {
                    if (books.isEmpty()) {
                        Text(
                            text = stringResource(R.string.drawer_book_empty_shelf),
                            style = NovaText.micro,
                            color = colors.textFaint,
                            modifier = Modifier.padding(horizontal = NovaSpacing.md, vertical = NovaSpacing.md),
                        )
                    }
                    books.forEach { book ->
                        val selected = book.projectId == currentProjectId
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = NovaSize.bookMenuRow)
                                .clickable {
                                    onToggleOpen()
                                    if (!selected) onSelectBook(book)
                                }
                                .padding(horizontal = NovaSpacing.md, vertical = NovaSpacing.xs),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = book.name,
                                style = NovaText.micro.copy(
                                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                                ),
                                color = colors.text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (selected) {
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = null,
                                    tint = colors.textMuted,
                                    modifier = Modifier.size(NovaSize.iconSm),
                                )
                            }
                        }
                        HorizontalDivider(thickness = NovaSize.hairline, color = colors.borderSoft)
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = NovaSize.touchTarget)
                            .clickable {
                                onToggleOpen()
                                onCreateBook()
                            }
                            .padding(horizontal = NovaSpacing.md),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.drawer_book_manage),
                            style = NovaText.micro,
                            color = colors.textMuted,
                        )
                    }
                }
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = NovaSpacing.sm),
            horizontalArrangement = Arrangement.spacedBy(NovaSpacing.sm),
        ) {
            QuickActionCard(
                label = stringResource(R.string.drawer_quick_command),
                icon = Icons.Filled.Search,
                modifier = Modifier.weight(1f),
                onClick = onOpenCommand,
            )
            QuickActionCard(
                label = stringResource(R.string.drawer_quick_messages),
                icon = Icons.Filled.Notifications,
                badge = unreadMessages,
                modifier = Modifier.weight(1f),
                onClick = { onOpenMessages?.invoke() },
                enabled = onOpenMessages != null,
            )
        }
    }
}

/** 快捷动作卡：44dp 高、surface 背景、10dp 圆角，图标 20dp + 文案居中（基线两列 `grid-cols-2`）。 */
@Composable
private fun QuickActionCard(
    label: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    badge: Int = 0,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = NovaTheme.colors
    Row(
        modifier
            .height(NovaSize.touchTarget)
            .background(colors.surface, RoundedCornerShape(NovaRadius.field))
            .clickable(enabled = enabled, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) colors.textMuted else colors.textFaint,
            modifier = Modifier.size(NovaSize.iconAction),
        )
        Text(
            text = if (badge > 0) "$label · $badge" else label,
            style = NovaText.micro,
            color = if (enabled) colors.text else colors.textFaint,
            maxLines = 1,
            modifier = Modifier.padding(start = NovaSpacing.xs),
        )
    }
}

/**
 * 命令面板（VG-012）：抽屉里的搜索与排序入口。
 *
 * 基线 `command.ts`：标题「命令面板」、占位「搜索命令...」、空态「未找到匹配命令」；
 * 移动抽屉本身不带搜索框与排序按钮，二者都收进这里（桌面侧栏的 customization 面板在移动端的等价物）。
 */
@Composable
private fun CommandPalette(
    items: List<ActivitySpec>,
    currentRoute: String?,
    query: String,
    onQueryChange: (String) -> Unit,
    onNavigate: (AppDestination) -> Unit,
    reorderMode: Boolean,
    onToggleReorder: () -> Unit,
    onMove: (String, Int) -> Unit,
    onReset: () -> Unit,
    onClose: () -> Unit,
) {
    val colors = NovaTheme.colors
    val matched = items.filter { ActivityNav.matches(query, stringResource(it.labelRes)) }
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(colors.scrim)
                .clickable(onClick = onClose),
        )
        Surface(
            shape = RoundedCornerShape(NovaRadius.card),
            color = colors.surface2,
            modifier = Modifier
                .align(Alignment.Center)
                .windowInsetsPadding(WindowInsets.statusBars)
                .imePadding()
                .padding(horizontal = NovaSpacing.sm)
                .widthIn(max = NovaSize.commandPanel)
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
            Column(Modifier.padding(NovaSpacing.md)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.drawer_command_title),
                        style = NovaText.compact.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.text,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = if (reorderMode) {
                            stringResource(R.string.drawer_sort_done)
                        } else {
                            stringResource(R.string.drawer_sort)
                        },
                        style = NovaText.micro,
                        color = colors.textMuted,
                        modifier = Modifier
                            .clickable(onClick = onToggleReorder)
                            .padding(horizontal = NovaSpacing.xs),
                    )
                    Text(
                        text = "✕",
                        style = NovaText.micro,
                        color = colors.textFaint,
                        modifier = Modifier
                            .clickable(onClick = onClose)
                            .padding(horizontal = NovaSpacing.xs),
                    )
                }
                NovaSearchField(
                    value = query,
                    onValueChange = onQueryChange,
                    placeholder = stringResource(R.string.drawer_command_placeholder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = NovaSpacing.sm),
                )
                if (reorderMode) {
                    Text(
                        text = stringResource(R.string.drawer_sort_reset),
                        style = NovaText.micro,
                        color = colors.accent,
                        modifier = Modifier
                            .clickable(onClick = onReset)
                            .padding(vertical = NovaSpacing.sm),
                    )
                }
                LazyColumn(Modifier.heightIn(max = NovaSize.panelMaxHeight)) {
                    if (matched.isEmpty()) {
                        item(key = "command-empty") {
                            Text(
                                text = stringResource(R.string.drawer_command_empty),
                                style = NovaText.micro,
                                color = colors.textFaint,
                                modifier = Modifier.padding(vertical = NovaSpacing.lg),
                            )
                        }
                    }
                    items(matched, key = { it.id }) { spec ->
                        val destination = destinationOf(spec.id)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = NovaSize.navRow)
                                .clickable(enabled = destination != null) {
                                    destination?.let(onNavigate)
                                },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = spec.icon,
                                contentDescription = null,
                                tint = if (spec.id == currentRoute) colors.text else colors.textMuted,
                                modifier = Modifier.size(NovaSize.iconAction),
                            )
                            Text(
                                text = stringResource(spec.labelRes),
                                style = NovaText.body,
                                color = if (spec.id == currentRoute) colors.text else colors.textMuted,
                                maxLines = 1,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = NovaSpacing.sm),
                            )
                            if (reorderMode) {
                                Text(
                                    text = "↑",
                                    style = NovaText.compact,
                                    color = colors.textMuted,
                                    modifier = Modifier
                                        .clickable { onMove(spec.id, -1) }
                                        .padding(horizontal = NovaSpacing.sm),
                                )
                                Text(
                                    text = "↓",
                                    style = NovaText.compact,
                                    color = colors.textMuted,
                                    modifier = Modifier
                                        .clickable { onMove(spec.id, 1) }
                                        .padding(horizontal = NovaSpacing.sm),
                                )
                            }
                        }
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
            .padding(horizontal = NovaSize.navRowPadding),
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
            .padding(horizontal = NovaSize.navRowPadding),
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
        NovaTopBar(
            title = stringResource(labelRes),
            navigationIcon = Icons.Filled.Menu,
            navigationContentDescription = stringResource(R.string.drawer_open),
            onNavigationClick = onOpenDrawer,
        )
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.not_implemented, stringResource(labelRes)),
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/** 基线 WorkbenchContextSwitcherTrigger 的 compact 宽度上限：max-w-[34vw]。 */
private const val CONTEXT_TRIGGER_WIDTH_FRACTION = 0.34f
