package com.denova.novara.feature.editor

/**
 * 编辑器标签栏状态（WRI-003）。
 *
 * 标签身份 = 文件路径，与 Web `tabKey = file:<path>` 对齐：同一项目内路径唯一，
 * 显示名取路径末段（Web `tabLabel`）。项目（书籍）维度由 [projectId] 承接，
 * 每个项目各自一份标签桶，切换项目不共享。
 */
data class EditorTabsState(
    val projectId: String = "",
    val paths: List<String> = emptyList(),
    val activePath: String = "",
) {
    val activeIndex: Int get() = paths.indexOf(activePath)

    val isEmpty: Boolean get() = paths.isEmpty()

    fun isActive(path: String): Boolean = path == activePath
}

/**
 * 标签列表的纯变换：打开、关闭后的相邻激活、重排。
 *
 * 只做不可变列表运算，不触碰 IO，便于 JVM 单测；持久化与页面跳转由 `EditorViewModel` 负责。
 * 语义逐条对齐 Web 基线 `TabController.tsx` / `App.tsx#handleCloseTab`。
 */
object EditorTabs {

    /** 打开文件：已存在则保持原位（不重复、不挪位），否则追加到末尾。 */
    fun open(paths: List<String>, path: String): List<String> =
        if (path.isBlank() || paths.contains(path)) paths else paths + path

    /** 移除标签，保持其余顺序。 */
    fun remove(paths: List<String>, path: String): List<String> = paths.filterNot { it == path }

    /**
     * 关闭标签后应激活哪一个：优先原位置的右邻居，其次左邻居，最后第一个；没有剩余标签返回 null。
     *
     * 对应 Web `handleCloseTab` 的 `next[idx] ?? next[idx - 1] ?? next[0]`。
     */
    fun nextActiveAfterClose(paths: List<String>, closed: String): String? {
        val index = paths.indexOf(closed)
        val remaining = remove(paths, closed)
        if (remaining.isEmpty()) return null
        if (index < 0) return remaining.first()
        return remaining.getOrNull(index) ?: remaining.getOrNull(index - 1) ?: remaining.first()
    }

    /**
     * 把 [from] 位置的标签移动到 [to]（摘下再插入，与 Web `reorderTabs` 一致）。
     * 下标越界或原地移动时返回原列表，调用方可据此跳过持久化。
     */
    fun move(paths: List<String>, from: Int, to: Int): List<String> {
        if (from !in paths.indices || to !in paths.indices || from == to) return paths
        val mutable = paths.toMutableList()
        val moved = mutable.removeAt(from)
        mutable.add(to, moved)
        return mutable
    }

    /** 标签显示名：路径末段（`chapters/001.md` → `001.md`），与 Web 一致。 */
    fun label(path: String): String = path.substringAfterLast('/').ifBlank { path }
}
