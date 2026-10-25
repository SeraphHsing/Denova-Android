package com.denova.novara.core.project

import com.denova.novara.core.network.ProjectDirectoryPage
import com.denova.novara.core.network.ProjectFileEntry

/** 项目目录树节点（UI 用，保留服务端顺序）。 */
data class ProjectFileNode(
    val path: String,
    val name: String,
    val isDir: Boolean,
    val children: List<ProjectFileNode> = emptyList(),
)

/**
 * 展开/折叠后的一行（深度优先扁平化，用于渲染）。
 * [loadMore] 为 true 时是「加载更多」占位行（合成路径，不是真实文件），点击走续页而不是打开文件。
 */
data class ProjectFileRow(
    val node: ProjectFileNode,
    val depth: Int,
    val expanded: Boolean,
    val loadMore: Boolean = false,
)

/**
 * `/files/resolve` 目录缓存 → 可渲染文件树。纯逻辑，JVM 可测。
 * 对齐 Web `features/project-explorer/model.ts`：服务端顺序保留、目录展开后递归子目录，
 * 且 `children_state=partial` 的目录在子项末尾补一行「加载更多」。
 */
object ProjectFileTree {

    private const val MAX_DEPTH = 64

    /** 「加载更多」合成行的路径后缀；只用于 LazyColumn 的 key 与点击分发，不代表真实路径。 */
    const val LOAD_MORE_SUFFIX = "\u0000more"

    /**
     * 合并一次 resolve 结果（对齐 Web `mergeProjectDirectories`）。
     *
     * [appendPath] 非空表示这是一次续页：只有同一目录且 `revision` 未变时才把新条目追加到已有页面后面，
     * 否则整页替换——revision 变了说明目录被改过，游标已失效，追加会造成重复或错位。
     */
    fun merge(
        current: Map<String, ProjectDirectoryPage>,
        pages: List<ProjectDirectoryPage>,
        appendPath: String? = null,
    ): Map<String, ProjectDirectoryPage> {
        if (pages.isEmpty()) return current
        val next = current.toMutableMap()
        for (page in pages) {
            val previous = next[page.path]
            next[page.path] = if (page.path == appendPath && previous != null && previous.revision == page.revision) {
                page.copy(entries = appendEntries(previous.entries, page.entries))
            } else {
                page
            }
        }
        return next
    }

    /** 还有下一页的目录路径（界面据此显示「加载更多」）。 */
    fun pendingMore(directories: Map<String, ProjectDirectoryPage>): Set<String> =
        directories.values.filter { it.hasMore }.mapTo(mutableSetOf()) { it.path }

    /** 从递归解析的目录缓存构建根树；根路径为 `""`。 */
    fun build(directories: Map<String, ProjectDirectoryPage>): List<ProjectFileNode> {
        val root = directories[""] ?: return emptyList()
        return root.entries.mapNotNull { entry -> buildNode(entry, directories, 1) }
    }

    /**
     * 深度优先展开可见行：目录自身始终可见，展开时才递归其子节点。
     * [pendingMorePaths] 中的目录在子项之后追加一行「加载更多」。
     */
    fun visibleRows(
        nodes: List<ProjectFileNode>,
        expandedPaths: Set<String>,
        pendingMorePaths: Set<String> = emptySet(),
    ): List<ProjectFileRow> {
        val rows = mutableListOf<ProjectFileRow>()
        fun visit(items: List<ProjectFileNode>, depth: Int) {
            for (node in items) {
                val expanded = node.path in expandedPaths
                rows.add(ProjectFileRow(node, depth, expanded))
                if (!node.isDir || !expanded) continue
                visit(node.children, depth + 1)
                if (node.path in pendingMorePaths) {
                    rows.add(
                        ProjectFileRow(
                            node = ProjectFileNode(path = node.path + LOAD_MORE_SUFFIX, name = "", isDir = false),
                            depth = depth + 1,
                            expanded = false,
                            loadMore = true,
                        ),
                    )
                }
            }
        }
        visit(nodes, 0)
        // 根目录自己也可能被截断（预算先命中根页）：根没有对应行，续页入口补在整棵树的末尾。
        if ("" in pendingMorePaths) {
            rows.add(
                ProjectFileRow(
                    node = ProjectFileNode(path = LOAD_MORE_SUFFIX, name = "", isDir = false),
                    depth = 0,
                    expanded = false,
                    loadMore = true,
                ),
            )
        }
        return rows
    }

    /** 所有目录路径（用于默认展开）。 */
    fun directoryPaths(nodes: List<ProjectFileNode>): Set<String> {
        val out = mutableSetOf<String>()
        fun visit(items: List<ProjectFileNode>) {
            for (node in items) {
                if (node.isDir) {
                    out.add(node.path)
                    visit(node.children)
                }
            }
        }
        visit(nodes)
        return out
    }

    /** 续页追加：按 path 去重，保持已有顺序在前（对齐 Web `mergeEntries`）。 */
    private fun appendEntries(
        existing: List<ProjectFileEntry>,
        incoming: List<ProjectFileEntry>,
    ): List<ProjectFileEntry> {
        val seen = existing.mapTo(mutableSetOf()) { it.path }
        return existing + incoming.filter { seen.add(it.path) }
    }

    private fun buildNode(
        entry: ProjectFileEntry,
        directories: Map<String, ProjectDirectoryPage>,
        depth: Int,
    ): ProjectFileNode? {
        if (depth > MAX_DEPTH) return null
        val children = if (entry.isDir && !entry.symlink) {
            directories[entry.path]?.entries?.mapNotNull { buildNode(it, directories, depth + 1) } ?: emptyList()
        } else {
            emptyList()
        }
        return ProjectFileNode(path = entry.path, name = entry.name, isDir = entry.isDir, children = children)
    }
}
