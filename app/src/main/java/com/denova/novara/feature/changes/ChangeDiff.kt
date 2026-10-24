package com.denova.novara.feature.changes

import com.denova.novara.core.network.ChangeCommentAnchor
import com.denova.novara.core.network.WorkspaceChangeGroup
import com.denova.novara.core.network.WorkspaceChangeSet
import com.github.difflib.DiffUtils

/** 统一 diff 的一行；[beforeLine] / [afterLine] 为 1-based 行号，另一侧不存在时为 null。 */
data class DiffLine(
    val kind: DiffKind,
    val text: String,
    val beforeLine: Int? = null,
    val afterLine: Int? = null,
)

enum class DiffKind { CONTEXT, ADD, REMOVE }

data class DiffStats(val additions: Int, val deletions: Int)

/** 一个文件的审阅投影：diff 行、增删计数、审阅状态与截断标记。 */
data class ChangeFileDiff(
    /** 变更集 id：局部审阅与评论锚点都按它定位。 */
    val changeSetId: String,
    val path: String,
    val beforeExists: Boolean,
    val afterExists: Boolean,
    val reviewStatus: String,
    val lines: List<DiffLine>,
    val stats: DiffStats,
    /** 文件过大时只保留前若干行；界面需要提示「已截断」，不能假装展示了全部差异。 */
    val truncated: Boolean = false,
) {
    /** 新增 / 删除 / 改写（两侧都存在但内容不同）。 */
    val isNew: Boolean get() = !beforeExists && afterExists
    val isDeleted: Boolean get() = beforeExists && !afterExists

    /** 该变更集是否还等待审阅（据此决定是否给「仅此文件」按钮）。 */
    val pending: Boolean get() = reviewStatus == "pending"
}

/**
 * 统一 diff（WRI-016）：用服务端回填的 `before_content` / `after_content` 本地做行级差异，
 * 与服务端 `change_sets` 一一对应。纯函数，便于 JVM 单测；不做 hunk 折叠，保持「差异即所见」。
 */
object ChangeDiff {

    /** 单文件最多展示的行数，超出部分标记 [ChangeFileDiff.truncated]。 */
    const val MAX_LINES = 2000

    fun files(group: WorkspaceChangeGroup): List<ChangeFileDiff> = group.changeSets.map(::file)

    fun file(set: WorkspaceChangeSet): ChangeFileDiff {
        val before = if (set.beforeExists) set.beforeContent else ""
        val after = if (set.afterExists) set.afterContent else ""
        val all = unified(before, after)
        val lines = if (all.size > MAX_LINES) all.take(MAX_LINES) else all
        return ChangeFileDiff(
            changeSetId = set.id,
            path = set.path,
            beforeExists = set.beforeExists,
            afterExists = set.afterExists,
            reviewStatus = set.reviewStatus,
            lines = lines,
            stats = stats(lines),
            truncated = all.size > MAX_LINES,
        )
    }

    /**
     * 把一行 diff 换算成服务端校验的评论锚点（`utf8-bytes-v1`）。
     *
     * 规则来自后端 `validateCommentAnchor`：`side` + 该 side 的 revision + 内容内的字节区间，
     * 且 `content[start:end]` 必须**一字不差**等于 `quote`。空行没有可锚定的文本，返回 null，
     * 由界面退化成整组评论，避免伪造一个服务端会拒绝的锚点。
     */
    fun anchor(set: WorkspaceChangeSet, line: DiffLine): ChangeCommentAnchor? {
        val after = line.kind != DiffKind.REMOVE
        val content = if (after) set.afterContent else set.beforeContent
        val revision = if (after) set.revision else set.baseRevision
        val lineNo = (if (after) line.afterLine else line.beforeLine) ?: return null
        if (revision.isBlank()) return null
        val lines = splitLines(content)
        if (lineNo < 1 || lineNo > lines.size) return null
        val quote = lines[lineNo - 1]
        if (quote.isBlank()) return null
        var offset = 0
        for (i in 0 until lineNo - 1) {
            offset += lines[i].toByteArray(Charsets.UTF_8).size + 1
        }
        val start = offset
        val end = start + quote.toByteArray(Charsets.UTF_8).size
        return ChangeCommentAnchor(
            side = if (after) {
                ChangeCommentAnchor.SIDE_AFTER
            } else {
                ChangeCommentAnchor.SIDE_BEFORE
            },
            encoding = ChangeCommentAnchor.ENCODING_UTF8_BYTES,
            revision = revision,
            start = start,
            end = end,
            quote = quote,
        )
    }

    /** 行级统一 diff：相同行输出 CONTEXT，被替换的先 REMOVE 再 ADD。 */
    fun unified(before: String, after: String): List<DiffLine> {
        val b = splitLines(before)
        val a = splitLines(after)
        val deltas = DiffUtils.diff(b, a).deltas
        val out = ArrayList<DiffLine>(b.size + a.size)
        var bi = 0
        var ai = 0
        for (delta in deltas) {
            val start = delta.source.position.coerceIn(bi, b.size)
            while (bi < start) {
                out += DiffLine(DiffKind.CONTEXT, b[bi], bi + 1, ai + 1)
                bi++
                ai++
            }
            for (line in delta.source.lines) {
                out += DiffLine(DiffKind.REMOVE, line, bi + 1, null)
                bi++
            }
            for (line in delta.target.lines) {
                out += DiffLine(DiffKind.ADD, line, null, ai + 1)
                ai++
            }
        }
        while (bi < b.size) {
            out += DiffLine(DiffKind.CONTEXT, b[bi], bi + 1, ai + 1)
            bi++
            ai++
        }
        return out
    }

    fun stats(lines: List<DiffLine>): DiffStats = DiffStats(
        additions = lines.count { it.kind == DiffKind.ADD },
        deletions = lines.count { it.kind == DiffKind.REMOVE },
    )

    /** 按行切分；末尾换行不产生额外的空行，空串为 0 行。 */
    fun splitLines(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val parts = text.split("\n")
        return if (text.endsWith("\n")) parts.dropLast(1) else parts
    }
}
