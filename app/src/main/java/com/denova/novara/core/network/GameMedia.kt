package com.denova.novara.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 互动故事配图（GAM-016）。
 *
 * 契约取自后端 `imageasset.InteractiveResult` 与 Web `InteractiveImage`
 * （`web/src/lib/api-client/types.ts`）：图片本身不是附件，URL 走
 * `GET /api/projects/:id/files/asset?path=<image_path>`（需会话 Cookie）。
 */
@Serializable
data class InteractiveImage(
    val schema: String = "",
    @SerialName("story_id") val storyId: String = "",
    @SerialName("branch_id") val branchId: String = "",
    @SerialName("turn_id") val turnId: String = "",
    @SerialName("image_path") val imagePath: String = "",
    @SerialName("meta_path") val metaPath: String = "",
    @SerialName("alt_text") val altText: String = "",
    @SerialName("profile_id") val profileId: String = "",
    val provider: String = "",
    val model: String = "",
    val size: String = "",
    val quality: String = "",
    @SerialName("output_format") val outputFormat: String = "",
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("revised_prompt") val revisedPrompt: String = "",
    @SerialName("mime_type") val mimeType: String = "",
    @SerialName("size_bytes") val sizeBytes: Long = 0,
)

/**
 * `POST /api/interactive/stories/:id/images/generate` 请求体。
 *
 * `source` / `force` 刻意用可空类型而非带默认值的非空类型：工程内的 `Json` 不开 `encodeDefaults`，
 * 默认值不会出现在请求体里——服务端把缺失的 `force` 当 `false`，会让「重新配图」被判成
 * `already_exists` 而不覆盖（2026-10-29 真机复现）。
 */
@Serializable
data class GenerateInteractiveImageRequest(
    /** 幂等键；同一 command_id 重放返回既有结果，不同请求体复用会 409 冲突。 */
    @SerialName("command_id") val commandId: String,
    @SerialName("branch_id") val branchId: String? = null,
    @SerialName("turn_id") val turnId: String,
    /** `manual` = 用户点击；`auto` = 回合结束自动生成（服务端按 `image_settings.mode` 判定）。 */
    val source: String? = null,
    /** 该轮已有图片时是否覆盖重生成（Web 手动路径固定 `true`）。 */
    val force: Boolean? = null,
) {
    companion object {
        const val SOURCE_MANUAL = "manual"
        const val SOURCE_AUTO = "auto"
    }
}

/** 图片生成结果：`skipped_reason` 取 `manual_mode` / `turn_not_found` / `interval` / `disabled` / `already_exists`。 */
@Serializable
data class GenerateInteractiveImageResult(
    val enabled: Boolean = false,
    val skipped: Boolean = false,
    @SerialName("skipped_reason") val skippedReason: String? = null,
    val image: InteractiveImage? = null,
)

/** `POST /api/interactive/stories/:id/context-compaction` 请求体。 */
@Serializable
data class ContextCompactionRequest(
    @SerialName("command_id") val commandId: String,
    @SerialName("branch_id") val branchId: String? = null,
)

/**
 * 上下文压缩结果（后端 `agentcompaction.Result`）。
 * CLI 引擎只填 `triggered` + `runtime_managed`；原生引擎额外给出 tokens / summary / revision。
 */
@Serializable
data class ContextCompactionResult(
    val triggered: Boolean = false,
    @SerialName("runtime_managed") val runtimeManaged: Boolean = false,
    @SerialName("skipped_reason") val skippedReason: String? = null,
    val revision: Long? = null,
    val summary: String? = null,
    @SerialName("tokens_before") val tokensBefore: Long? = null,
    @SerialName("tokens_after") val tokensAfter: Long? = null,
    @SerialName("source_message_count") val sourceMessageCount: Int? = null,
)

/** `DELETE .../context-compaction/active` 响应：`removed=false` 表示当前没有活动压缩点。 */
@Serializable
data class RemoveCompactionResult(val removed: Boolean = false)

/**
 * 附件上传体（GAM-016）：与 Web `ChatAttachmentUpload` 一致，
 * `data_url` 形如 `data:image/png;base64,....`，随 `/api/interactive/chat` 请求体内联发送。
 */
@Serializable
data class ChatAttachmentUpload(
    val name: String,
    @SerialName("media_type") val mediaType: String? = null,
    @SerialName("data_url") val dataUrl: String,
)

/** 轮次 / 输入回传的附件描述（不含 path 与 sha256，服务端会抹掉）。 */
@Serializable
data class ChatAttachmentDescriptor(
    val id: String = "",
    val name: String = "",
    @SerialName("media_type") val mediaType: String? = null,
    val size: Long = 0,
)
