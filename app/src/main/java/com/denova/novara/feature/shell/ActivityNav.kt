package com.denova.novara.feature.shell

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Star
import androidx.compose.ui.graphics.vector.ImageVector
import com.denova.novara.R

/** 导航分组：对齐 Web `workspace-mobile-layout.tsx` 的 creation / tools / settings。 */
enum class ActivityGroup { CREATION, TOOLS, SETTINGS }

/** 一级目的一项（顺序由 [ActivityNav.sort] 决定，分组只影响展示）。 */
data class ActivitySpec(
    val id: String,
    val labelRes: Int,
    val group: ActivityGroup,
    val icon: ImageVector,
    val implemented: Boolean,
    /**
     * 条件入口：基线里只在特定开关下出现（当前仅 `trajectory`，由服务端 `labs.developer_mode` 控制）。
     * 抽屉按 [ActivityNav.visibleSpecs] 过滤，业务开关不写进导航表。
     */
    val conditional: Boolean = false,
)

/**
 * 一级导航的顺序与分组（NAV-001 / NAV-004 / NAV-005）。
 *
 * 口径来源 `web/src/components/workbench/workbench-activity-order.ts`：
 * - 默认顺序 `writing, story, agentchat, trajectory, lore, teller, versions, books, skills, agents, automations, market`；
 * - 用户保存的顺序优先；新增条目按「默认顺序里的前一个已存在项之后」插入（[insertMissing]）；
 * - 分组 `creation = writing/story`、`tools = 其余`、`settings` 单条且**不带分组标题**；
 * - 手机端**不应用** hiddenActivityIDs 过滤，始终显示完整入口。
 */
object ActivityNav {

    /**
     * Web `DEFAULT_ACTIVITY_ORDER`（settings 不在其中，单独一组）。
     *
     * `trajectory` 插在 `agent` 之后，位置与基线 `writing, story, agentchat, trajectory, lore…` 一致；
     * Android 多出的 `workbench`（基线里 workbench 是宿主容器、不是抽屉条目）紧随其后，
     * 按 SD-02 登记为允许差异（见 07）。
     */
    val DEFAULT_ORDER: List<String> = listOf(
        "editor", "game", "agent", "trajectory", "workbench", "lore", "teller",
        "versions", "books", "skills", "agents", "automations", "market",
    )

    val SPECS: List<ActivitySpec> = listOf(
        ActivitySpec("editor", R.string.dest_editor, ActivityGroup.CREATION, Icons.Filled.Edit, implemented = true),
        ActivitySpec("game", R.string.dest_game, ActivityGroup.CREATION, Icons.Filled.PlayArrow, implemented = true),
        ActivitySpec("agent", R.string.dest_agent, ActivityGroup.TOOLS, Icons.Filled.Send, implemented = true),
        ActivitySpec(
            "trajectory",
            R.string.dest_trajectory,
            ActivityGroup.TOOLS,
            Icons.Filled.Search,
            implemented = true,
            conditional = true,
        ),
        ActivitySpec("workbench", R.string.dest_workbench, ActivityGroup.TOOLS, Icons.Filled.Home, implemented = true),
        ActivitySpec("lore", R.string.dest_lore, ActivityGroup.TOOLS, Icons.Filled.Star, implemented = true),
        ActivitySpec("teller", R.string.dest_teller, ActivityGroup.TOOLS, Icons.Filled.Notifications, implemented = true),
        ActivitySpec("versions", R.string.dest_versions, ActivityGroup.TOOLS, Icons.Filled.Refresh, implemented = true),
        ActivitySpec("books", R.string.dest_books, ActivityGroup.TOOLS, Icons.Filled.List, implemented = true),
        ActivitySpec("skills", R.string.dest_skills, ActivityGroup.TOOLS, Icons.Filled.Build, implemented = true),
        ActivitySpec("agents", R.string.dest_agents, ActivityGroup.TOOLS, Icons.Filled.Person, implemented = true),
        ActivitySpec("automations", R.string.dest_automations, ActivityGroup.TOOLS, Icons.Filled.DateRange, implemented = true),
        ActivitySpec("market", R.string.dest_market, ActivityGroup.TOOLS, Icons.Filled.ShoppingCart, implemented = true),
        ActivitySpec("settings", R.string.dest_settings, ActivityGroup.SETTINGS, Icons.Filled.Settings, implemented = true),
    )

    /**
     * 把保存过的顺序补全为完整顺序：缺的条目插到「默认顺序里它前面那个已存在项」之后，
     * 找不到前项就插到后项之前，都没有则追加到末尾（对齐基线 `insertMissingActivityItems`）。
     */
    fun insertMissing(stored: List<String>, default: List<String> = DEFAULT_ORDER): List<String> {
        val result = stored.toMutableList()
        for (id in default) {
            if (id in result) continue
            val index = default.indexOf(id)
            val preceding = default.subList(0, index).reversed().firstOrNull { it in result }
            if (preceding != null) {
                result.add(result.indexOf(preceding) + 1, id)
                continue
            }
            val following = default.drop(index + 1).firstOrNull { it in result }
            if (following != null) result.add(result.indexOf(following), id) else result.add(id)
        }
        return result
    }

    /** 按顺序表排好、并补上表里没有的条目（追加在末尾，保持可见，遵循 NAV-005）。 */
    fun sort(specs: List<ActivitySpec> = SPECS, order: List<String> = DEFAULT_ORDER): List<ActivitySpec> {
        val effective = insertMissing(order)
        val index = effective.withIndex().associate { (i, id) -> id to i }
        return specs.sortedBy { index[it.id] ?: (effective.size + specs.indexOf(it)) }
    }

    /**
     * 条件入口过滤：`developerMode=false` 时去掉只在开发者模式出现的条目（基线 `trajectory`）。
     *
     * 过滤放在界面层而不是 [SPECS] 里，保证路由表稳定（深链/恢复时仍能解析到该 route）。
     */
    fun visibleSpecs(specs: List<ActivitySpec>, developerMode: Boolean): List<ActivitySpec> =
        specs.filter { !it.conditional || developerMode }

    /** 分组展示：创作 → 工具 → 设置；组内保持顺序表顺序。 */
    fun grouped(items: List<ActivitySpec>): List<Pair<ActivityGroup, List<ActivitySpec>>> {
        val order = listOf(ActivityGroup.CREATION, ActivityGroup.TOOLS, ActivityGroup.SETTINGS)
        return order.map { group -> group to items.filter { it.group == group } }
            .filter { (_, list) -> list.isNotEmpty() }
    }

    /**
     * 抽屉搜索（NAV-006）的单条匹配：本地化标签做大小写不敏感子串匹配，空搜索视为全部命中。
     *
     * 只做纯判定，标签由界面用 `stringResource` 求值后传入（在 `filter` 的 inline lambda 里调用），
     * 这样既能在组合作用域取资源，又能被 JVM 覆盖 trim / 大小写 / 空词边界。
     */
    fun matches(query: String, label: String): Boolean {
        val keyword = query.trim()
        return keyword.isEmpty() || label.contains(keyword, ignoreCase = true)
    }

    /** 在当前顺序里把某个条目移动 [delta] 位；越界或条目不存在时原样返回。 */
    fun move(order: List<String>, id: String, delta: Int): List<String> {
        val current = insertMissing(order)
        val from = current.indexOf(id)
        if (from < 0) return current
        val to = (from + delta).coerceIn(0, current.lastIndex)
        if (to == from) return current
        val result = current.toMutableList()
        result.removeAt(from)
        result.add(to, id)
        return result
    }

    /** settings 之外的条目 ID（持久化时不需要把设置页写进顺序表）。 */
    fun reorderableIDs(): List<String> = DEFAULT_ORDER
}
