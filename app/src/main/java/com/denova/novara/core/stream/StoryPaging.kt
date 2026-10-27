package com.denova.novara.core.stream

/** 单轮在叙事面板里的可读文本（`> 玩家输入` + 叙事），与历史基线同口径。 */
object StoryTurnText {

    fun of(turn: TurnEvent): String = buildString {
        // 跳过内部提示（如 `[Source: story opening configuration...]`），不展示给用户。
        val user = turn.user?.takeIf { it.isNotBlank() && !it.trimStart().startsWith("[Source:") }
        if (user != null) append("> ").append(user.trim())
        if (!turn.narrative.isNullOrBlank()) {
            if (isNotEmpty()) append("\n\n")
            append(turn.narrative.trim())
        }
    }
}

/** 轮次锚点：用于「跳转指定轮次」。 */
data class TurnAnchor(
    val turnId: String,
    /** 轮次序号（1 基，含已翻页的更早轮次）。 */
    val number: Int,
    /** 展示用摘要（玩家输入首段，空则用叙事首段）。 */
    val label: String,
    /** 该轮在叙事块列表里的首块索引。 */
    val blockIndex: Int,
)

object StoryPaging {

    private const val LABEL_CHARS = 28

    /**
     * 把更早的一页前置合并到现有轮次：按 id 去重，保持「旧 → 新」顺序。
     * 服务端分页游标可能重叠（`before` 为包含边界），去重避免重复渲染。
     */
    fun mergeOlder(older: List<TurnEvent>, current: List<TurnEvent>): List<TurnEvent> {
        val existing = current.map { it.id }.filter { it.isNotBlank() }.toSet()
        return older.filter { it.id.isBlank() || it.id !in existing } + current
    }

    /**
     * 生成轮次锚点：只保留有正文的轮次（跳转到空轮次没有意义）。
     * [blockIndexOf] 由叙事分块缓冲提供，未命中则跳过该轮。
     */
    fun anchors(
        turns: List<TurnEvent>,
        firstNumber: Int,
        blockIndexOf: (String) -> Int?,
    ): List<TurnAnchor> = turns.mapIndexedNotNull { index, turn ->
        val blockIndex = blockIndexOf(turn.id) ?: return@mapIndexedNotNull null
        TurnAnchor(
            turnId = turn.id,
            number = firstNumber + index,
            label = label(turn),
            blockIndex = blockIndex,
        )
    }

    fun label(turn: TurnEvent): String {
        val user = turn.user?.takeIf { it.isNotBlank() && !it.trimStart().startsWith("[Source:") }
        val text = user?.trim()
            ?: turn.narrative?.trim().orEmpty()
        return if (text.length > LABEL_CHARS) text.take(LABEL_CHARS) + "…" else text
    }
}
