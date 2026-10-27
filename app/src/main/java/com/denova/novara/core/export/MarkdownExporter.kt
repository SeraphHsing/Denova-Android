package com.denova.novara.core.export

import android.content.ContentValues
import android.content.Context
import androidx.annotation.RequiresApi
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.denova.novara.core.logging.DenovaLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 把会话 Markdown 落到用户可见的位置（P4-B / WB-006）。
 *
 * Android 10+ 走 MediaStore 写入 `Download/Denova/`，不需要存储权限也不会被清理；
 * Android 8/9（minSdk 26）没有分区存储，退回到应用外部目录 `Android/data/<pkg>/files/exports`
 * ——那里用户能通过文件管理器找到，且同样不需要权限。
 */
object MarkdownExporter {

    const val DIRECTORY = "Denova"

    sealed interface Result {
        /** [displayPath] 用于界面提示，例如 `Download/Denova/xxx.md`。 */
        data class Saved(val displayPath: String, val uri: String) : Result

        data class Failed(val reason: String) : Result
    }

    suspend fun save(context: Context, fileName: String, content: String): Result =
        withContext(Dispatchers.IO) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    saveToMediaStore(context, fileName, content)
                } else {
                    saveToAppExternal(context, fileName, content)
                }
            }.onFailure {
                DenovaLog.w("Export", "save failed name=$fileName", it)
            }.getOrElse { error ->
                Result.Failed(error.message.orEmpty())
            }
        }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveToMediaStore(context: Context, fileName: String, content: String): Result {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "text/markdown")
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$DIRECTORY")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return Result.Failed("insert failed")
        return try {
            resolver.openOutputStream(uri)?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
                ?: return Result.Failed("open stream failed")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            DenovaLog.i("Export", "saved name=$fileName bytes=${content.length} scope=downloads")
            Result.Saved("${Environment.DIRECTORY_DOWNLOADS}/$DIRECTORY/$fileName", uri.toString())
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    private fun saveToAppExternal(context: Context, fileName: String, content: String): Result {
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "exports")
        if (!dir.exists() && !dir.mkdirs()) return Result.Failed("mkdir failed")
        val file = File(dir, fileName)
        file.writeText(content, Charsets.UTF_8)
        DenovaLog.i("Export", "saved name=$fileName bytes=${content.length} scope=app-external")
        return Result.Saved(file.absolutePath, file.toURI().toString())
    }
}
