package com.denova.novara.core.persistence

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 按故事隔离的玩家输入草稿（GAM-002）。
 *
 * 基线里每个故事各自保留「还没发出去」的输入；Android 侧原本把输入放在 Composable 的
 * `remember { mutableStateOf("") }` 里，切换故事或离开页面就丢。这里按 `storyId` 落一份草稿：
 *
 * - key 由 storyId 派生并清洗（只保留 `[A-Za-z0-9._-]`），避免把 id 当路径用造成穿越；
 * - 先写 `.tmp` 再 rename，进程被杀不会留下半截内容；
 * - 单条上限 [MAX_LENGTH] 字符（输入框本身不会超，这里是防御性截断）；
 * - 文本为空时删文件，不在磁盘堆空草稿。
 *
 * 存 `filesDir` 而不是 Room：草稿是纯文本、单值、随故事覆盖，不需要查询与事务。
 */
class GameDraftStore(private val root: File) {

    /** 读取该故事的草稿；没有草稿或读取失败返回空串（读取失败不能阻断进入页面）。 */
    fun load(storyId: String): String {
        if (storyId.isBlank()) return ""
        val file = fileFor(storyId)
        if (!file.isFile) return ""
        return runCatching { file.readText() }.getOrDefault("")
    }

    /** 覆盖写入该故事的草稿；空文本等价于清除。 */
    suspend fun save(storyId: String, text: String) {
        if (storyId.isBlank()) return
        withContext(Dispatchers.IO) {
            val file = fileFor(storyId)
            if (text.isBlank()) {
                runCatching { file.delete() }
                return@withContext
            }
            runCatching {
                root.mkdirs()
                val tmp = File(root, "${file.name}.tmp")
                tmp.writeText(text.take(MAX_LENGTH))
                if (!tmp.renameTo(file)) {
                    file.writeText(text.take(MAX_LENGTH))
                    tmp.delete()
                }
            }
        }
    }

    /** 发送成功后清除草稿（成功才清，失败要保留供重试）。 */
    suspend fun clear(storyId: String) = save(storyId, "")

    private fun fileFor(storyId: String): File = File(root, "${draftKey(storyId)}.txt")

    companion object {
        const val MAX_LENGTH: Int = 4_000

        /** storyId → 文件名：只留安全字符，空串兜底 `unknown`（避免生成 `.txt` 隐藏文件）。 */
        fun draftKey(storyId: String): String =
            storyId.trim()
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
                .ifBlank { "unknown" }
    }
}
