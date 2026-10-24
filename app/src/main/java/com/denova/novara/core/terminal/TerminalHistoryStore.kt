package com.denova.novara.core.terminal

import com.denova.novara.core.logging.DenovaLog
import java.io.File

/**
 * 终端历史快照的落盘（按 server origin + 项目隔离）。
 *
 * 写文件是同步小写入（≤64KB，只在会话结束/被释放时发生），不需要额外调度；
 * 内容属于用户正文，只写应用私有目录，不写日志。
 */
class TerminalHistoryStore(private val dir: File) {

    fun save(serverOrigin: String, projectId: String, text: String) {
        if (text.isBlank()) return
        runCatching {
            if (!dir.isDirectory) dir.mkdirs()
            fileFor(serverOrigin, projectId).writeText(text)
        }.onFailure { DenovaLog.w("TerminalHistory", "save failed", it) }
    }

    fun load(serverOrigin: String, projectId: String): String? {
        val file = fileFor(serverOrigin, projectId)
        if (!file.isFile) return null
        return runCatching { file.readText() }
            .onFailure { DenovaLog.w("TerminalHistory", "load failed", it) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    private fun fileFor(serverOrigin: String, projectId: String): File =
        File(dir, sanitize(serverOrigin) + "_" + sanitize(projectId) + ".txt")

    private fun sanitize(raw: String): String =
        raw.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
}
