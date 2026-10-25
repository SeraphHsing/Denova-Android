package com.denova.novara.core.save

import com.denova.novara.core.logging.DenovaLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * 冲突材料归档（WRI-013）：把三方合并用到的四份正文（base / local / external / merged）落盘，
 * 让「保留本地」不会成为不可追溯的覆盖。
 *
 * 契约对齐后端 `internal/workspace/autosave`（`POST /api/autosave-conflicts`）与
 * Web `lib/api-client/autosave-conflicts.ts`：`value` 是 JSON 字符串（后端按 `json.RawMessage`
 * 校验），`conflict_paths` 至少含一个路径数组。
 */
@Serializable
data class ConflictSnapshot(
    val revision: String = "",
    /** 正文按 JSON 字符串编码（与 Go `json.RawMessage` + Web 的 `value` 口径一致）。 */
    val value: String = "",
)

/** `POST /api/autosave-conflicts` 的请求体（字段名与后端 `autosave.Input` 一一对应）。 */
@Serializable
data class AutosaveConflictRecord(
    val resource: String = RESOURCE_WORKSPACE_FILE,
    val scope: String = "",
    val id: String = "",
    val base: ConflictSnapshot = ConflictSnapshot(),
    val local: ConflictSnapshot = ConflictSnapshot(),
    val external: ConflictSnapshot = ConflictSnapshot(),
    val merged: ConflictSnapshot = ConflictSnapshot(),
    val strategy: String = STRATEGY_MERGE_PREFER_LOCAL,
    @SerialName("conflict_paths") val conflictPaths: List<List<String>> = listOf(emptyList()),
) {
    companion object {
        const val RESOURCE_WORKSPACE_FILE = "workspace_file"

        /** 与 Web `preserveEditorConflict` 传入的 strategy 一致，服务端原样记录。 */
        const val STRATEGY_MERGE_PREFER_LOCAL = "merge_non_overlap_prefer_local"
    }
}

/** 服务端回执：记录 id 与落盘路径。 */
@Serializable
data class PreservedConflictPayload(
    val id: String = "",
    val path: String = "",
)

/** 冲突材料存放位置：服务端日志是权威副本，本地兜底覆盖「服务端不可达」的情况。 */
enum class ConflictStorage { SERVER, LOCAL }

data class PreservedConflict(
    val id: String,
    val path: String,
    val storage: ConflictStorage,
)

/**
 * 冲突材料落盘编解码。
 *
 * 独立用一个 `encodeDefaults = true` 的 `Json`：默认配置会省略与默认值相同的字段，
 * 而 `resource` / `strategy` / `conflict_paths` 恰好都有默认值，省略后服务端会判 400
 * （`resource is required`）。
 */
object AutosaveConflictCodec {
    private val json = Json { encodeDefaults = true }

    fun encode(record: AutosaveConflictRecord): String =
        json.encodeToString(AutosaveConflictRecord.serializer(), record)

    fun decode(text: String): AutosaveConflictRecord =
        json.decodeFromString(AutosaveConflictRecord.serializer(), text)
}

/** 归档端口；注入 fake 即可单测调用顺序与失败兜底。 */
interface ConflictArchive {
    suspend fun preserve(record: AutosaveConflictRecord): PreservedConflict
}

/**
 * 本地兜底归档（对应 Web 的 `localStorage` 分支）：服务端日志写不进去时，
 * 仍把完整冲突材料留在设备上，界面也能拿到一个可回指的 id。
 */
class LocalConflictArchive(private val directory: File) : ConflictArchive {

    override suspend fun preserve(record: AutosaveConflictRecord): PreservedConflict =
        withContext(Dispatchers.IO) {
            directory.mkdirs()
            val id = "autosave-conflict-" + UUID.randomUUID().toString().replace("-", "")
            val file = File(directory, "$id.json")
            file.writeText(AutosaveConflictCodec.encode(record))
            PreservedConflict(id, file.absolutePath, ConflictStorage.LOCAL)
        }
}

/** 优先进服务端日志，失败落本地兜底；取消必须继续向上传播，不能伪装成归档成功。 */
class FallbackConflictArchive(
    private val remote: ConflictArchive,
    private val local: ConflictArchive,
) : ConflictArchive {

    override suspend fun preserve(record: AutosaveConflictRecord): PreservedConflict =
        try {
            remote.preserve(record)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DenovaLog.w("Editor", "conflict journal unavailable; kept local recovery copy", e)
            local.preserve(record)
        }
}
