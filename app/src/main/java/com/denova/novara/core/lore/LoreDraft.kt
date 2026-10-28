package com.denova.novara.core.lore

import com.denova.novara.core.network.LoreItemInput
import com.denova.novara.core.network.LoreItemSummary

/**
 * 资料库条目的编辑草稿（P4-C / LOR-001 / LOR-004）。
 *
 * 表单与条目分离，草稿留在 ViewModel 里：切到 Agent 或别的页面再回来，未保存的正文、
 * 标签、启用状态都还在（Web 侧同样把表单当本地状态）。保存时才组装 [LoreItemInput]，
 * 并把服务端 `base_revision` 一起带上，冲突交给 409 提示，不静默覆盖。
 */
@kotlinx.serialization.Serializable
data class LoreDraft(
    val id: String = "",
    val categoryId: String = "world",
    val enabled: Boolean = true,
    val type: String = "world",
    val name: String = "",
    val importance: String = com.denova.novara.core.network.LoreOptions.DEFAULT_IMPORTANCE,
    val tagsText: String = "",
    val keywordsText: String = "",
    val briefDescription: String = "",
    val loadMode: String = "auto",
    val content: String = "",
    val isNew: Boolean = true,
) {
    fun toInput(baseRevision: String = ""): LoreItemInput = LoreItemInput(
        id = id,
        enabled = enabled,
        type = type,
        name = name.trim(),
        importance = importance,
        tags = parseList(tagsText),
        briefDescription = briefDescription,
        keywords = parseList(keywordsText),
        loadMode = loadMode,
        content = content,
        baseRevision = baseRevision,
    )

    fun canSave(): Boolean = name.isNotBlank()

    /**
     * 表单是否与这条服务端条目一致（没有未保存编辑）。
     *
     * 列表开关、分类应用、材料回包都会重新读取条目；只有确实没改动时才用服务端版本重建表单，
     * 否则会把用户正在输入的正文覆盖掉（LOR-001 / LOR-004）。
     */
    fun matches(item: LoreItemSummary): Boolean =
        id == item.id &&
            enabled == item.enabled &&
            type == item.type &&
            name == item.name &&
            importance == item.importance.ifBlank { com.denova.novara.core.network.LoreOptions.DEFAULT_IMPORTANCE } &&
            tagsText == item.tags.joinToString("、") &&
            keywordsText == item.keywords.joinToString("、") &&
            briefDescription == item.briefDescription &&
            loadMode == item.loadMode.ifBlank { com.denova.novara.core.network.LoreOptions.DEFAULT_LOAD_MODE } &&
            content == item.content

    companion object {
        /** 新建条目的默认草稿（放在当前分类下）。 */
        fun create(categoryId: String): LoreDraft = LoreDraft(
            categoryId = categoryId,
            type = typeForCategory(categoryId),
            isNew = true,
        )

        fun from(item: LoreItemSummary, categoryId: String): LoreDraft = LoreDraft(
            id = item.id,
            categoryId = categoryId.ifBlank { item.type },
            enabled = item.enabled,
            type = item.type,
            name = item.name,
            importance = item.importance.ifBlank { com.denova.novara.core.network.LoreOptions.DEFAULT_IMPORTANCE },
            tagsText = item.tags.joinToString("、"),
            keywordsText = item.keywords.joinToString("、"),
            briefDescription = item.briefDescription,
            loadMode = item.loadMode.ifBlank { com.denova.novara.core.network.LoreOptions.DEFAULT_LOAD_MODE },
            content = item.content,
            isNew = false,
        )

        /**
         * 条目 `type` 就是分类 id（后端 `validateCollection` 要求 type ∈ 分类表）。
         * 自定义分类同样直接使用其 id；不会再退化成 `other`（那会让保存被拒）。
         */
        fun typeForCategory(categoryId: String): String = categoryId.trim()

        /** 把分类从 [from] 移到 [to]（服务端 `op=move` 的 `index` 语义）；越界返回原表。 */
        fun moveCategory(
            categories: List<com.denova.novara.core.network.LoreCategory>,
            id: String,
            to: Int,
        ): List<com.denova.novara.core.network.LoreCategory> {
            val from = categories.indexOfFirst { it.id == id }
            if (from < 0 || to < 0 || to >= categories.size || from == to) return categories
            val next = categories.toMutableList()
            val moved = next.removeAt(from)
            next.add(to, moved)
            return next
        }

        /** 标签 / 关键词输入：中英文逗号、顿号、换行都算分隔符，去空去重保序。 */
        fun parseList(raw: String): List<String> = raw
            .split(',', '，', '、', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }
}
