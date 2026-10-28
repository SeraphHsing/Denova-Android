package com.denova.novara.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 资料库（lore）契约：`/api/projects/:id/book/lore/…`（P4-C / LOR-001…004）。
 *
 * 字段与固定基线 `internal/book/lore/types.go` 一致。条目正文是 Markdown；
 * 固定字段只负责索引与展示。`index_memberships` 属于条目本身，索引编辑器只是它的另一个投影。
 */

@Serializable
data class LoreCategory(
    val id: String,
    val name: String = "",
)

/** `POST .../book/lore/categories` 的 `CategoryMutation`：op=create|rename|delete|move。 */
@Serializable
data class LoreCategoryMutation(
    val op: String,
    val id: String = "",
    val name: String = "",
    @SerialName("destination_id") val destinationId: String = "",
    val index: Int = 0,
)

@Serializable
data class LoreIndexMembership(
    @SerialName("group_id") val groupId: String,
    val detail: String = "",
)

@Serializable
data class LoreImage(
    val schema: String = "",
    @SerialName("image_path") val imagePath: String = "",
    @SerialName("image_url") val imageUrl: String = "",
    @SerialName("meta_path") val metaPath: String = "",
    @SerialName("alt_text") val altText: String = "",
    @SerialName("image_preset_id") val imagePresetId: String = "",
    @SerialName("profile_id") val profileId: String = "",
    val provider: String = "",
    val model: String = "",
    val size: String = "",
    val quality: String = "",
    @SerialName("output_format") val outputFormat: String = "",
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("revised_prompt") val revisedPrompt: String = "",
    @SerialName("mime_type") val mimeType: String = "",
    @SerialName("size_bytes") val sizeBytes: Int = 0,
)

@Serializable
data class LoreMaterialEntry(
    @SerialName("asset_id") val assetId: String,
    val name: String = "",
    val description: String = "",
)

@Serializable
data class LoreMaterials(
    val entries: List<LoreMaterialEntry> = emptyList(),
    @SerialName("cover_asset_id") val coverAssetId: String = "",
)

@Serializable
data class LoreAssetSource(
    val kind: String = "",
    @SerialName("meta_path") val metaPath: String = "",
    val url: String = "",
)

/** 解析后的材料（只读投影）：`resolved_materials[]` 与 `GET .../book/lore/assets`。 */
@Serializable
data class LoreMaterial(
    val id: String = "",
    val path: String = "",
    val url: String = "",
    @SerialName("original_name") val originalName: String = "",
    @SerialName("mime_type") val mimeType: String = "",
    @SerialName("size_bytes") val sizeBytes: Int = 0,
    @SerialName("created_at") val createdAt: String = "",
    val name: String = "",
    val description: String = "",
    val source: LoreAssetSource = LoreAssetSource(),
)

/** `POST .../items/:id/materials` 的 `MaterialMutation`：op=add|update|remove|cover。 */
@Serializable
data class LoreMaterialMutation(
    val op: String,
    @SerialName("asset_id") val assetId: String = "",
    val name: String = "",
    val description: String = "",
    val url: String = "",
    @SerialName("save_locally") val saveLocally: Boolean = false,
)

/** 写请求：`ItemInput`。省略 `index_memberships` 表示保留，显式空数组才清空。 */
@Serializable
data class LoreItemInput(
    val id: String = "",
    val enabled: Boolean? = null,
    val type: String = "",
    @SerialName("type_source") val typeSource: String = "",
    val name: String = "",
    val importance: String = "",
    val tags: List<String> = emptyList(),
    @SerialName("brief_description") val briefDescription: String = "",
    val keywords: List<String> = emptyList(),
    @SerialName("load_mode") val loadMode: String = "",
    @SerialName("index_memberships") val indexMemberships: List<LoreIndexMembership>? = null,
    val content: String = "",
    @SerialName("base_revision") val baseRevision: String = "",
)

@Serializable
data class LoreItemResponse(
    @SerialName("project_id") val projectId: String = "",
    val item: LoreItemSummary,
)

@Serializable
data class LoreIndexGroup(
    val id: String,
    val name: String = "",
    val purpose: String = "",
    @SerialName("body_markdown") val bodyMarkdown: String = "",
    @SerialName("default_detail") val defaultDetail: String = "",
)

@Serializable
data class LoreIndexGuide(
    @SerialName("intro_markdown") val introMarkdown: String = "",
    val groups: List<LoreIndexGroup> = emptyList(),
    @SerialName("automatic_details") val automaticDetails: Map<String, String> = emptyMap(),
    @SerialName("group_order") val groupOrder: List<String> = emptyList(),
    @SerialName("item_order") val itemOrder: Map<String, List<String>> = emptyMap(),
)

@Serializable
data class LoreIndexSnapshot(
    val guide: LoreIndexGuide = LoreIndexGuide(),
    val revision: String = "",
)

@Serializable
data class LoreIndexUpdate(
    val guide: LoreIndexGuide,
    @SerialName("base_revision") val baseRevision: String = "",
)

@Serializable
data class LoreIndexAutomaticGroup(
    val key: String = "",
    @SerialName("category_id") val categoryId: String = "",
    @SerialName("load_mode") val loadMode: String = "",
    @SerialName("default_detail") val defaultDetail: String = "",
    @SerialName("item_ids") val itemIds: List<String> = emptyList(),
)

@Serializable
data class LoreIndexPreview(
    val markdown: String = "",
    @SerialName("automatic_groups") val automaticGroups: List<LoreIndexAutomaticGroup> = emptyList(),
    @SerialName("custom_item_ids") val customItemIds: Map<String, List<String>> = emptyMap(),
    @SerialName("over_budget") val overBudget: Boolean = false,
    @SerialName("token_estimate") val tokenEstimate: Int = 0,
)

@Serializable
data class LoreClassificationRequest(
    @SerialName("item_ids") val itemIds: List<String> = emptyList(),
    val mode: String = "",
)

@Serializable
data class LoreClassificationItem(
    val id: String,
    val name: String = "",
    @SerialName("current_type") val currentType: String = "",
    @SerialName("current_type_source") val currentTypeSource: String = "",
    @SerialName("suggested_type") val suggestedType: String = "",
    val confidence: String = "",
    val reason: String = "",
    @SerialName("suggestion_source") val suggestionSource: String = "",
)

@Serializable
data class LoreClassificationPreview(
    val revision: String = "",
    val mode: String = "",
    val items: List<LoreClassificationItem> = emptyList(),
    val counts: Map<String, Int> = emptyMap(),
    val warning: String = "",
)

@Serializable
data class LoreTypeChange(
    val id: String,
    val type: String,
)

@Serializable
data class LoreClassificationApplyRequest(
    val revision: String,
    val changes: List<LoreTypeChange>,
)

@Serializable
data class LoreClassificationApplyResult(
    val revision: String = "",
    val items: List<LoreItemSummary> = emptyList(),
    val updated: List<LoreItemSummary> = emptyList(),
)

@Serializable
data class LoreSpeechRequest(
    val name: String,
    val text: String,
)

/**
 * 配图生成请求：`POST .../items/:id/image/generate`（`loreapp.ItemImageGenerateRequest`）。
 *
 * `mode=agent` 交给图像 Agent（可带 `instruction`）；其余走直接生成（`prompt` 覆盖预设）。
 * 与 Web `LoreMaterialGenerateDialog` 同一组字段。
 */
@Serializable
data class LoreImageGenerateRequest(
    /** 空字符串表示直接生成（服务端只有 `mode=agent` 才走图像 Agent）。 */
    val mode: String = "",
    val prompt: String = "",
    val instruction: String = "",
    @SerialName("image_preset_id") val imagePresetId: String = "",
)

/**
 * 资料库条目的固定取值（与 Web `types.ts` / 后端 `normalize.go` 同一组）。
 *
 * 重要度只有 `major | important | minor`：服务端把其它写法统一改成 `important`，
 * 因此客户端不能再用 high/medium/low（否则每次保存都被静默改写）。
 * 条目的 `type` 是**分类 id**，自定义分类也直接用它，不能退化成 `other`。
 */
object LoreOptions {
    val IMPORTANCE = listOf("major", "important", "minor")
    val LOAD_MODES = listOf("resident", "auto", "manual")
    const val DEFAULT_IMPORTANCE = "important"
    const val DEFAULT_LOAD_MODE = "auto"
    /** 有行为语义的固定分类：不可重命名、不可删除、不可作为迁移目标。 */
    const val CHARACTER = "character"
}
