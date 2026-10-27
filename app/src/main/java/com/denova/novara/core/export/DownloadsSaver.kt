package com.denova.novara.core.export

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import com.denova.novara.core.logging.DenovaLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 把任意导出文件落到用户可见位置（P4-B 会话导出、P4-C 书籍导出共用）。
 *
 * Android 10+ 走 MediaStore 写入 `Download/Denova/`，不需要存储权限也不会被清理；
 * Android 8/9（minSdk 26）没有分区存储，退回到应用外部目录
 * `Android/data/<pkg>/files/exports` —— 用户能通过文件管理器找到，同样不需要权限。
 */
object DownloadsSaver {

    const val DIRECTORY = "Denova"

    sealed interface Result {
        /** [displayPath] 用于界面提示，例如 `Download/Denova/xxx.txt`。 */
        data class Saved(val displayPath: String, val uri: String) : Result

        data class Failed(val reason: String) : Result
    }

    suspend fun save(
        context: Context,
        fileName: String,
        bytes: ByteArray,
        mimeType: String = "application/octet-stream",
    ): Result = withContext(Dispatchers.IO) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveToMediaStore(context, fileName, bytes, mimeType)
            } else {
                saveToAppExternal(context, fileName, bytes)
            }
        }.onFailure {
            DenovaLog.w("Export", "save failed name=$fileName", it)
        }.getOrElse { error ->
            Result.Failed(error.message.orEmpty())
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveToMediaStore(
        context: Context,
        fileName: String,
        bytes: ByteArray,
        mimeType: String,
    ): Result {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$DIRECTORY")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val uri = resolver.insert(collection, values) ?: return Result.Failed("insert failed")
        return try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return Result.Failed("open stream failed")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            DenovaLog.i("Export", "saved name=$fileName bytes=${bytes.size} scope=downloads")
            Result.Saved("${Environment.DIRECTORY_DOWNLOADS}/$DIRECTORY/$fileName", uri.toString())
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    private fun saveToAppExternal(context: Context, fileName: String, bytes: ByteArray): Result {
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "exports")
        if (!dir.exists() && !dir.mkdirs()) return Result.Failed("mkdir failed")
        val file = File(dir, fileName)
        file.writeBytes(bytes)
        DenovaLog.i("Export", "saved name=$fileName bytes=${bytes.size} scope=app-external")
        return Result.Saved(file.absolutePath, file.toURI().toString())
    }
}
