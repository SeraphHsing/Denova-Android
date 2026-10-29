package com.denova.novara.core.game

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.denova.novara.core.logging.DenovaLog
import com.denova.novara.core.network.ChatAttachmentUpload
import java.io.ByteArrayOutputStream
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 已选待发送的附件（GAM-016）。
 *
 * `dataUrl` 与 Web `ChatAttachmentUpload.data_url` 同形（`data:<mime>;base64,<payload>`），
 * 随 `/api/interactive/chat` 请求体内联发送；[previewUrl] 只用于界面缩略图，不上传。
 */
data class PendingAttachment(
    val name: String,
    val mediaType: String,
    val dataUrl: String,
    val sizeBytes: Long,
    val previewUrl: String? = null,
) {
    fun toUpload(): ChatAttachmentUpload = ChatAttachmentUpload(
        name = name,
        mediaType = mediaType,
        dataUrl = dataUrl,
    )
}

/** 附件被拒的原因（与 Web 上限一致，服务端同样会拒绝）。 */
enum class AttachmentReject { TOO_MANY, FILE_TOO_LARGE, TOTAL_TOO_LARGE, UNREADABLE }

/**
 * 附件上限：对齐后端 `internal/agents/attachment/store.go`
 * （10 个 / 单个 20MiB / 合计 50MiB）。这里按**解码后字节**计，
 * 与服务端校验口径一致（base64 膨胀不计入）。
 */
object GameAttachmentLimits {
    const val MAX_FILES = 10
    const val MAX_FILE_BYTES = 20L * 1024 * 1024
    const val MAX_TOTAL_BYTES = 50L * 1024 * 1024

    /** 返回拒绝原因；`null` 表示可以继续添加。 */
    fun reject(existingCount: Int, existingBytes: Long, incomingBytes: Long): AttachmentReject? = when {
        existingCount + 1 > MAX_FILES -> AttachmentReject.TOO_MANY
        incomingBytes > MAX_FILE_BYTES -> AttachmentReject.FILE_TOO_LARGE
        existingBytes + incomingBytes > MAX_TOTAL_BYTES -> AttachmentReject.TOTAL_TOO_LARGE
        else -> null
    }

    /** `data:<mime>;base64,<payload>`；mime 缺失时按二进制处理。 */
    fun dataUrl(mediaType: String, bytes: ByteArray): String =
        "data:${mediaType.ifBlank { DEFAULT_MEDIA_TYPE }};base64,${Base64.getEncoder().encodeToString(bytes)}"

    /**
     * 完全没有可发送内容（正文与附件都为空）：不发空回合。
     */
    fun isEmptyInput(message: String, attachmentCount: Int): Boolean =
        message.isBlank() && attachmentCount == 0

    /**
     * 只有附件、没有正文。
     *
     * 2026-10-29 实测：`POST /api/interactive/chat` 带 `attachments` 但 `message` 为空时，
     * 服务端在物化 canonical 输入阶段报 `player input is empty`（流内 error，不落轮次）。
     * 附件必须随正文发送，客户端先拦下以免发出注定失败的请求。
     */
    fun requiresText(message: String, attachmentCount: Int): Boolean =
        message.isBlank() && attachmentCount > 0

    const val DEFAULT_MEDIA_TYPE = "application/octet-stream"
}

/** 单次读取结果：成功给出可直接发送的附件，失败给出可展示的原因。 */
sealed interface GameAttachmentRead {
    data class Ok(val attachment: PendingAttachment) : GameAttachmentRead
    data class Rejected(val name: String, val reason: AttachmentReject) : GameAttachmentRead
}

/**
 * 从系统选择器返回的 Uri 读取附件内容（GAM-016）。
 *
 * 不做重编码：Web 基线同样直传原始字节，服务端按字节嗅探原生图片类型；
 * 客户端只做大小与数量校验，避免发出必然被服务端拒绝的请求。
 */
class GameAttachmentReader(private val context: Context) {

    suspend fun read(uri: Uri): GameAttachmentRead = withContext(Dispatchers.IO) {
        val name = displayName(uri)
        val mediaType = context.contentResolver.getType(uri).orEmpty()
        val bytes = try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val out = ByteArrayOutputStream()
                input.copyTo(out, DEFAULT_COPY_BUFFER)
                out.toByteArray()
            }
        } catch (e: Exception) {
            DenovaLog.w("Game", "read attachment failed uri=$uri", e)
            null
        }
        if (bytes == null || bytes.isEmpty()) {
            return@withContext GameAttachmentRead.Rejected(name, AttachmentReject.UNREADABLE)
        }
        GameAttachmentRead.Ok(
            PendingAttachment(
                name = name,
                mediaType = mediaType.ifBlank { GameAttachmentLimits.DEFAULT_MEDIA_TYPE },
                dataUrl = GameAttachmentLimits.dataUrl(mediaType, bytes),
                sizeBytes = bytes.size.toLong(),
                previewUrl = if (mediaType.startsWith("image/")) uri.toString() else null,
            ),
        )
    }

    private fun displayName(uri: Uri): String {
        val fromResolver = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
        }.getOrNull()
        val fallback = uri.lastPathSegment.orEmpty()
        return (fromResolver ?: fallback).ifBlank { DEFAULT_NAME }
    }

    private companion object {
        const val DEFAULT_COPY_BUFFER = 64 * 1024
        const val DEFAULT_NAME = "attachment"
    }
}
