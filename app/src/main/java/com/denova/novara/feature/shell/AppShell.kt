package com.denova.novara.feature.shell

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.denova.novara.R
import com.denova.novara.core.network.BookRecord
import com.denova.novara.core.onboarding.OnboardingLogic
import com.denova.novara.core.onboarding.OnboardingStep

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
) {
    // 排序模式与搜索词都是抽屉内的临时界面状态，不参与业务状态。
    var reorderMode by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    // 过滤与分组要在组合作用域里算（stringResource 是 @Composable，不能写在 LazyListScope 内）。
    val visible = activityItems.filter { spec ->
        query.isBlank() || stringResource(spec.labelRes).contains(query.trim(), ignoreCase = true)
    }
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
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Text(
            stringResource(R.string.drawer_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(16.dp),
        )
        HorizontalDivider()
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = listBottomPadding),
        ) {
            // 新手引导（WRI-001）：三步核心，完成或跳过就不再出现。
            if (onboardingStep != null) {
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

            // 当前书籍切换（P3 链路起点）：选择后作为编辑器 / 项目目录的工作区。
            item {
                Text(
                    stringResource(R.string.current_book),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
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

            // 一级导航：分组（创作 / 工具 / 设置）+ 图标 + 尾部状态（当前 ✓ / 其余 ›）。
            // 排序与搜索都是抽屉内的临时状态，不写业务状态（NAV-004 / NAV-006）。
            item {
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
            }

            grouped.forEach { (group, specs) ->
                // 设置组按基线不带分组标题。
                if (group != ActivityGroup.SETTINGS) {
                    item(key = "group-${group.name}") {
                        Text(
                            when (group) {
                                ActivityGroup.CREATION -> stringResource(R.string.group_creation)
                                else -> stringResource(R.string.group_tools)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                    }
                }
                items(specs.size, key = { index -> "nav-${specs[index].id}" }) { index ->
                    val spec = specs[index]
                    val destination = destinationOf(spec.id)
                    val selected = spec.id == currentRoute
                    if (destination == null) return@items
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = { onNavigate(destination) },
                            enabled = spec.implemented,
                            modifier = Modifier.weight(1f),
                        ) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Icon(
                                    spec.icon,
                                    contentDescription = null,
                                    tint = if (selected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                                Text(
                                    stringResource(spec.labelRes),
                                    modifier = Modifier.weight(1f),
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                )
                                when {
                                    // 未实现：只标 WIP，不给可点击的视觉暗示（不给 ›，也没有跳转）。
                                    !spec.implemented -> Text(
                                        stringResource(R.string.dest_placeholder),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                    selected -> Icon(
                                        Icons.Filled.Check,
                                        contentDescription = stringResource(R.string.dest_current),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                    else -> Icon(
                                        Icons.Filled.KeyboardArrowRight,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.outline,
                                    )
                                }
                            }
                        }
                        if (reorderMode && group != ActivityGroup.SETTINGS) {
                            TextButton(onClick = { onMoveActivity(spec.id, -1) }) { Text("↑") }
                            TextButton(onClick = { onMoveActivity(spec.id, 1) }) { Text("↓") }
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
