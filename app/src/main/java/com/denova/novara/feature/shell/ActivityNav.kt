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

    /** Web `DEFAULT_ACTIVITY_ORDER`（settings 不在其中，单独一组）。 */
    val DEFAULT_ORDER: List<String> = listOf(
        "editor", "game", "agent", "workbench", "lore", "teller",
        "versions", "books", "skills", "agents", "automations", "market",
    )

    val SPECS: List<ActivitySpec> = listOf(
        ActivitySpec("editor", R.string.dest_editor, ActivityGroup.CREATION, Icons.Filled.Edit, implemented = true),
        ActivitySpec("game", R.string.dest_game, ActivityGroup.CREATION, Icons.Filled.PlayArrow, implemented = true),
        ActivitySpec("agent", R.string.dest_agent, ActivityGroup.TOOLS, Icons.Filled.Send, implemented = true),
        ActivitySpec("workbench", R.string.dest_workbench, ActivityGroup.TOOLS, Icons.Filled.Home, implemented = true),
        ActivitySpec("lore", R.string.dest_lore, ActivityGroup.TOOLS, Icons.Filled.Star, implemented = true),
        ActivitySpec("teller", R.string.dest_teller, ActivityGroup.TOOLS, Icons.Filled.Notifications, implemented = false),
        ActivitySpec("versions", R.string.dest_versions, ActivityGroup.TOOLS, Icons.Filled.Refresh, implemented = false),
        ActivitySpec("books", R.string.dest_books, ActivityGroup.TOOLS, Icons.Filled.List, implemented = true),
        ActivitySpec("skills", R.string.dest_skills, ActivityGroup.TOOLS, Icons.Filled.Build, implemented = false),
        ActivitySpec("agents", R.string.dest_agents, ActivityGroup.TOOLS, Icons.Filled.Person, implemented = false),
        ActivitySpec("automations", R.string.dest_automations, ActivityGroup.TOOLS, Icons.Filled.DateRange, implemented = false),
        ActivitySpec("market", R.string.dest_market, ActivityGroup.TOOLS, Icons.Filled.ShoppingCart, implemented = false),
        ActivitySpec("settings", R.string.dest_settings, ActivityGroup.SETTINGS, Icons.Filled.Settings, implemented = false),
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

    /** 分组展示：创作 → 工具 → 设置；组内保持顺序表顺序。 */
    fun grouped(items: List<ActivitySpec>): List<Pair<ActivityGroup, List<ActivitySpec>>> {
        val order = listOf(ActivityGroup.CREATION, ActivityGroup.TOOLS, ActivityGroup.SETTINGS)
        return order.map { group -> group to items.filter { it.group == group } }
            .filter { (_, list) -> list.isNotEmpty() }
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
