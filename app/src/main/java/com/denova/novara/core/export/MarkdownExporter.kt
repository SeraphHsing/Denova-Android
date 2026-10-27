package com.denova.novara.core.export

import android.content.Context

/**
 * 把会话 Markdown 落到用户可见的位置（P4-B / WB-006）。
 *
 * 落盘细节（MediaStore / 应用外部目录的分支与权限）统一在 [DownloadsSaver]，
 * 这里只固定 Markdown 的文件类型。
 */
object MarkdownExporter {

    const val DIRECTORY = DownloadsSaver.DIRECTORY

    sealed interface Result {
        data class Saved(val displayPath: String, val uri: String) : Result

        data class Failed(val reason: String) : Result
    }

    suspend fun save(context: Context, fileName: String, content: String): Result =
        when (val result = DownloadsSaver.save(
            context = context,
            fileName = fileName,
            bytes = content.toByteArray(Charsets.UTF_8),
            mimeType = "text/markdown",
        )) {
            is DownloadsSaver.Result.Saved -> Result.Saved(result.displayPath, result.uri)
            is DownloadsSaver.Result.Failed -> Result.Failed(result.reason)
        }
}
