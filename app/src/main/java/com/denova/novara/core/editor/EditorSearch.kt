package com.denova.novara.core.editor

/** 一次命中：正文字符区间（左闭右开）。 */
data class SearchMatch(val start: Int, val end: Int)

/**
 * 编辑器内查找 / 替换（WRI-015），对齐 Web `editorDecorations.ts` 的编辑器内实现：
 *
 * - 字面量：`toLowerCase` 比较，**大小写不敏感**；
 * - 正则：`new RegExp(q, 'gi')`，同样**不敏感**（注意与后端 `/workspace/search` 的 RE2 大小写敏感不同，
 *   这是基线自身的不一致，编辑器内以基线行为为准）；
 * - 全部替换一次性完成（从后往前），因此整体只产生一次编辑 → 一次撤销即可回退。
 */
object EditorSearch {

    /** 空查询不产生命中；正则可匹配空串时同样视为无效（Web 服务端直接 400）。 */
    fun findAll(text: String, query: String, regex: Boolean = false): List<SearchMatch> {
        val q = query.trim()
        if (q.isEmpty() || text.isEmpty()) return emptyList()
        val found = mutableListOf<SearchMatch>()
        if (regex) {
            val compiled = runCatching { Regex(q, RegexOption.IGNORE_CASE) }.getOrNull() ?: return emptyList()
            for (m in compiled.findAll(text)) {
                if (m.range.isEmpty()) return emptyList()
                found += SearchMatch(m.range.first, m.range.last + 1)
            }
            return found
        }
        var from = 0
        while (from <= text.length - q.length) {
            val at = text.indexOf(q, from, ignoreCase = true)
            if (at < 0) break
            found += SearchMatch(at, at + q.length)
            from = at + q.length
        }
        return found
    }

    /**
     * 改光标/当前命中项：用于「上一个 / 下一个」。
     * [current] 为-1 表示尚未定位；越界时按环绕处理（与 Web 查找条一致）。
     */
    fun step(count: Int, current: Int, forward: Boolean): Int {
        if (count <= 0) return -1
        if (current < 0 || current >= count) return if (forward) 0 else count - 1
        return if (forward) (current + 1) % count else (current - 1 + count) % count
    }

    /** 光标位置所在的命中序号；不在任何命中内返回-1。 */
    fun indexOfCaret(matches: List<SearchMatch>, caret: Int): Int =
        matches.indexOfFirst { caret in it.start until it.end }

    /** 替换单个命中：只动这一处，返回新正文。 */
    fun replaceOne(text: String, match: SearchMatch, replacement: String): String {
        if (match.start < 0 || match.end > text.length || match.start > match.end) return text
        return text.substring(0, match.start) + replacement + text.substring(match.end)
    }

    /**
     * 全部替换（从后往前，保证前面的偏移不失效）。返回新正文与替换处数；
     * 正则可带 `$1` / `${name}` 分组引用（Kotlin `Regex.replace` 语义）。
     */
    fun replaceAll(
        text: String,
        query: String,
        replacement: String,
        regex: Boolean = false,
    ): Pair<String, Int> {
        val matches = findAll(text, query, regex)
        if (matches.isEmpty()) return text to 0
        if (regex) {
            val compiled = runCatching { Regex(query.trim(), RegexOption.IGNORE_CASE) }.getOrNull()
                ?: return text to 0
            val replaced = compiled.replace(text, replacement)
            return replaced to matches.size
        }
        var out = text
        for (m in matches.asReversed()) {
            out = out.substring(0, m.start) + replacement + out.substring(m.end)
        }
        return out to matches.size
    }
}
