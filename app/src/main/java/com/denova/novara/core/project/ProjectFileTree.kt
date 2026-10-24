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

/** 展开/折叠后的一行（深度优先扁平化，用于渲染）。 */
data class ProjectFileRow(
    val node: ProjectFileNode,
    val depth: Int,
    val expanded: Boolean,
)

/**
 * `/files/resolve` 目录缓存 → 可渲染文件树。纯逻辑，JVM 可测。
 * 对齐 Web `features/project-explorer/model.ts`：服务端顺序保留、目录展开后递归子目录。
 */
object ProjectFileTree {

    private const val MAX_DEPTH = 64

    /** 从递归解析的目录缓存构建根树；根路径为 `""`。 */
    fun build(directories: Map<String, ProjectDirectoryPage>): List<ProjectFileNode> {
        val root = directories[""] ?: return emptyList()
        return root.entries.mapNotNull { entry -> buildNode(entry, directories, 1) }
    }

    /** 深度优先展开可见行：目录自身始终可见，展开时才递归其子节点。 */
    fun visibleRows(nodes: List<ProjectFileNode>, expandedPaths: Set<String>): List<ProjectFileRow> {
        val rows = mutableListOf<ProjectFileRow>()
        fun visit(items: List<ProjectFileNode>, depth: Int) {
            for (node in items) {
                val expanded = node.path in expandedPaths
                rows.add(ProjectFileRow(node, depth, expanded))
                if (node.isDir && expanded) visit(node.children, depth + 1)
            }
        }
        visit(nodes, 0)
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
