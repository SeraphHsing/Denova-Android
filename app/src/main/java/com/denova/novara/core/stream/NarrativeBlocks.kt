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
 *
 * A4 起额外记录**轮次 → 首块索引**（[blockIndexFor]），用于「跳转指定轮次」的滚动定位：
 * 每轮开始处强制换块，避免一轮跨块导致定位到半截文本。
 */
class NarrativeBuilder(private val maxBlockChars: Int = 400) {

    private val blocks = mutableListOf<String>()

    /** 轮次 id → 该轮首块索引（只记录有正文的轮次）。 */
    private val turnStarts = mutableMapOf<String, Int>()
    private var currentTurn: String = ""

    /** 当前块的不可变快照（供 StateFlow 发布）。 */
    val snapshot: List<String> get() = blocks.toList()

    val isEmpty: Boolean get() = blocks.isEmpty()

    /** 用 canonical 基线重置（无轮次信息，仅用于纯文本场景与测试）。 */
    fun reset(baseline: String) {
        blocks.clear()
        turnStarts.clear()
        currentTurn = ""
        if (baseline.isNotEmpty()) append(baseline)
    }

    /**
     * 用按轮次分好的正文重建：`(turnId, 该轮正文)` 从旧到新。
     * 空正文的轮次不建块，也不进 [blockIndexFor]（跳转无意义）。
     */
    fun resetTurns(turns: List<Pair<String, String>>) {
        blocks.clear()
        turnStarts.clear()
        currentTurn = ""
        turns.forEach { (turnId, text) ->
            if (text.isBlank()) return@forEach
            beginTurn(turnId)
            append(text)
        }
    }

    /** 标记新一轮的开始：强制从新块起排，并记下首块索引。 */
    fun beginTurn(turnId: String) {
        if (turnId.isBlank() || turnId == currentTurn) return
        currentTurn = turnId
        if (blocks.isNotEmpty() && blocks.last().isNotEmpty()) blocks.add("")
        turnStarts[turnId] = if (blocks.isEmpty()) 0 else blocks.lastIndex
    }

    /** 轮次首块索引；未记录（无正文或未加载）返回 null。 */
    fun blockIndexFor(turnId: String): Int? = turnStarts[turnId]

    /** 当前所有轮次锚点（按加入顺序）。 */
    val turnAnchors: Map<String, Int> get() = turnStarts.toMap()

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
