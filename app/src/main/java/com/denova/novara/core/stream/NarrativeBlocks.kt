package com.denova.novara.core.stream

/**
 * 增量叙事分块缓冲。
 *
 * 流式叙事若用 `String +=` 累积，每个 chunk 都要复制整段文本（O(n²)）；
 * 再塞进单个 `Text` 又会每帧整段重新测量布局。这里把文本切成有上限的块：
 * - 追加只改最后一块（复制量 ≤ [maxBlockChars]）；
 * - UI 用惰性列表只渲染可见块，新增内容不触发整段重排。
 *
 * 切块优先落在换行处，保证段落观感；仅在单行超长时才硬切。
 */
class NarrativeBuilder(private val maxBlockChars: Int = 400) {

    private val blocks = mutableListOf<String>()

    /** 当前块的不可变快照（供 StateFlow 发布）。 */
    val snapshot: List<String> get() = blocks.toList()

    val isEmpty: Boolean get() = blocks.isEmpty()

    /** 用 canonical 基线重置。 */
    fun reset(baseline: String) {
        blocks.clear()
        if (baseline.isNotEmpty()) append(baseline)
    }

    /** 追加文本；超过 [maxBlockChars] 自动封块。 */
    fun append(text: String) {
        if (text.isEmpty()) return
        var rest = text
        while (rest.isNotEmpty()) {
            if (blocks.isEmpty() || blocks.last().length >= maxBlockChars) {
                blocks.add("")
            }
            val lastIndex = blocks.lastIndex
            val capacity = maxBlockChars - blocks[lastIndex].length
            if (capacity <= 0) {
                blocks.add("")
                continue
            }
            val take = chunkLength(rest, capacity)
            blocks[lastIndex] = blocks[lastIndex] + rest.substring(0, take)
            rest = rest.substring(take)
        }
    }

    /** 本次可切走的长度：优先在换行处断开，否则硬切到容量上限。 */
    private fun chunkLength(rest: String, capacity: Int): Int {
        if (rest.length <= capacity) return rest.length
        val window = rest.substring(0, capacity)
        val breakAt = window.lastIndexOf('\n')
        return if (breakAt > 0) breakAt + 1 else capacity
    }
}
