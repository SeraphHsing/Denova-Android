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
    val importance: String = "medium",
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

    companion object {
        /** 新建条目的默认草稿（放在当前分类下）。 */
        fun create(categoryId: String): LoreDraft = LoreDraft(
            categoryId = categoryId,
            type = typeForCategory(categoryId),
            isNew = true,
        )

        fun from(item: LoreItemSummary, categoryId: String): LoreDraft = LoreDraft(
            id = item.id,
            categoryId = categoryId,
            enabled = item.enabled,
            type = item.type,
            name = item.name,
            importance = item.importance.ifBlank { "medium" },
            tagsText = item.tags.joinToString("、"),
            keywordsText = item.keywords.joinToString("、"),
            briefDescription = item.briefDescription,
            loadMode = item.loadMode.ifBlank { "auto" },
            content = item.content,
            isNew = false,
        )

        /** 分类 id 与条目类型同名；自定义分类退回 `other`，避免把角色行为挂到未知分类上。 */
        fun typeForCategory(categoryId: String): String = when (categoryId) {
            "character", "location", "faction", "item", "world" -> categoryId
            else -> "other"
        }

        /** 标签 / 关键词输入：中英文逗号、顿号、换行都算分隔符，去空去重保序。 */
        fun parseList(raw: String): List<String> = raw
            .split(',', '，', '、', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }
}
