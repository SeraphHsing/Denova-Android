package com.denova.novara.core.workbench

/**
 * 工作台页签模型（P4-B / WB-002 / WB-003）。
 *
 * 口径来源：固定 Web 基线 `web/src/features/agent-chat/types.ts` 与 `tab-state.ts`。
 * - 一个页签只属于一个 `primary` / `secondary` 区（两区模型就够表达「一边对话、一边看改动」）；
 * - 项目身份是 `projectId` 而不是显示名，重命名或重新关联目录都不改变页签归属；
 * - 同一项目内「会话页签」按 `sessionId` 去重、「文件页签」每项目唯一。
 */
enum class WorkbenchGroup { PRIMARY, SECONDARY;
    fun opposite(): WorkbenchGroup = if (this == PRIMARY) SECONDARY else PRIMARY
}

sealed interface WorkbenchTabKind {
    /** 内置 Agent 会话页签。 */
    data class Agent(val sessionId: String) : WorkbenchTabKind

    /** 终端页签：`profileId` 为 `shell` 或已注册命令；`terminalSessionId` 首次 attach 成功后才有。 */
    data class Terminal(
        val profileId: String = "shell",
        val terminalSessionId: String? = null,
        val label: String = "",
    ) : WorkbenchTabKind

    /** 项目文件工作区页签（每项目唯一）。 */
    data object Files : WorkbenchTabKind
}

data class WorkbenchTab(
    val id: String,
    val projectId: String,
    val group: WorkbenchGroup,
    val kind: WorkbenchTabKind,
    /** 用户改写过的标题；为空时按会话标题 / 固定文案展示。 */
    val customTitle: String = "",
)

/**
 * 页签集合的纯逻辑（可 JVM 单测）。
 *
 * 所有函数都返回新列表，不修改入参；顺序即展示顺序（区内的插入顺序）。
 */
object WorkbenchTabs {

    fun ofGroup(tabs: List<WorkbenchTab>, group: WorkbenchGroup): List<WorkbenchTab> =
        tabs.filter { it.group == group }

    fun find(tabs: List<WorkbenchTab>, id: String): WorkbenchTab? = tabs.firstOrNull { it.id == id }

    /**
     * 打开页签：同身份的页签已存在时**不新建**，只把它移到本区末尾并返回（调用方负责激活）。
     * 身份：Agent = 项目 + 会话；Files = 项目；Terminal = 项目 + 终端会话（未 attach 时按页签 id）。
     */
    fun open(tabs: List<WorkbenchTab>, tab: WorkbenchTab): List<WorkbenchTab> {
        val existing = tabs.firstOrNull { sameIdentity(it, tab) }
        if (existing != null) {
            // 已存在但换了区：跟随目标区（对齐 Web 的「送到另一侧」语义）。
            val moved = if (existing.group != tab.group) existing.copy(group = tab.group) else existing
            return (tabs - existing) + moved
        }
        return tabs + tab
    }

    private fun sameIdentity(left: WorkbenchTab, right: WorkbenchTab): Boolean {
        if (left.projectId != right.projectId) return false
        return when {
            left.kind is WorkbenchTabKind.Agent && right.kind is WorkbenchTabKind.Agent ->
                (left.kind as WorkbenchTabKind.Agent).sessionId == (right.kind as WorkbenchTabKind.Agent).sessionId
            left.kind is WorkbenchTabKind.Files && right.kind is WorkbenchTabKind.Files -> true
            left.kind is WorkbenchTabKind.Terminal && right.kind is WorkbenchTabKind.Terminal -> left.id == right.id
            else -> false
        }
    }

    fun close(tabs: List<WorkbenchTab>, id: String): List<WorkbenchTab> = tabs.filterNot { it.id == id }

    /** 关闭后应激活的同区页签：右邻居 → 左邻居 → 该区首个；该区空则 null。 */
    fun activeAfterClose(tabs: List<WorkbenchTab>, closing: String): String? {
        val target = find(tabs, closing) ?: return null
        val group = ofGroup(tabs, target.group)
        val index = group.indexOfFirst { it.id == closing }
        val remaining = group.filterNot { it.id == closing }
        if (remaining.isEmpty()) return null
        return remaining.getOrNull(index)?.id ?: remaining.last().id
    }

    /** 把页签送到另一区（WB-003 主副切换）。 */
    fun moveToGroup(tabs: List<WorkbenchTab>, id: String, group: WorkbenchGroup): List<WorkbenchTab> =
        tabs.map { if (it.id == id) it.copy(group = group) else it }

    /** 区内重排：把 [id] 移动 [delta] 位；越界原样返回。 */
    fun move(tabs: List<WorkbenchTab>, id: String, delta: Int): List<WorkbenchTab> {
        val target = find(tabs, id) ?: return tabs
        val group = ofGroup(tabs, target.group).toMutableList()
        val from = group.indexOfFirst { it.id == id }
        if (from < 0) return tabs
        val to = (from + delta).coerceIn(0, group.lastIndex)
        if (to == from) return tabs
        val item = group.removeAt(from)
        group.add(to, item)
        // 只替换该区占用的槽位，另一区的页签位置保持不变。
        var next = 0
        return tabs.map { if (it.group == target.group) group[next++] else it }
    }

    /** 该区下一个可激活的页签（打开新页签或关闭后使用）。 */
    fun lastInGroup(tabs: List<WorkbenchTab>, group: WorkbenchGroup): String? =
        ofGroup(tabs, group).lastOrNull()?.id

    /** 展示标题：自定义 → 会话标题 → 类型文案键（由界面解析）。 */
    fun titleOf(tab: WorkbenchTab, sessionTitles: Map<String, String>): String {
        if (tab.customTitle.isNotBlank()) return tab.customTitle
        return when (val kind = tab.kind) {
            is WorkbenchTabKind.Agent -> sessionTitles[kind.sessionId].orEmpty()
            is WorkbenchTabKind.Terminal -> kind.label
            WorkbenchTabKind.Files -> ""
        }
    }

    /** 关闭某项目全部页签（项目被移除 / 归档时），返回剩余页签。 */
    fun closeProject(tabs: List<WorkbenchTab>, projectId: String): List<WorkbenchTab> =
        tabs.filterNot { it.projectId == projectId }
}
