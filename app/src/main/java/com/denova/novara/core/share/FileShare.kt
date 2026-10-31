package com.denova.novara.core.share

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * 导出文件的落盘与分享（统计导出与 SYS-001 共用同一条通道）。
 *
 * 约束：
 * - 只写 `cacheDir/<folder>/`，文件名做白名单清洗（服务端 `Content-Disposition` 不可信）；
 * - 分享走 `FileProvider`（`${applicationId}.files`），只授予读权限，不写外部存储；
 * - 同名文件覆盖，避免缓存无限增长。
 */
object FileShare {

    private val unsafeChars = Regex("[^A-Za-z0-9._-]")

    fun authority(context: Context): String = "${context.packageName}.files"

    /** 写入缓存目录并返回文件句柄；`filename` 里的路径分隔符与非 ASCII 一律替换为 `_`。 */
    fun writeToCache(context: Context, folder: String, filename: String, bytes: ByteArray): File {
        val safeFolder = folder.replace(unsafeChars, "_").ifBlank { "exports" }
        val safeName = filename.replace(unsafeChars, "_").ifBlank { "export.bin" }
        val dir = File(context.cacheDir, safeFolder)
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, safeName)
        file.writeBytes(bytes)
        return file
    }

    /** 分享已有文件（`ACTION_SEND` + 读权限）；MIME 用后端返回的类型，缺省按二进制处理。 */
    fun share(context: Context, file: File, mimeType: String = "application/octet-stream") {
        val uri = FileProvider.getUriForFile(context, authority(context), file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, null).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    /** 清理缓存目录里的历史导出（进入页面或导出成功后调用，避免长期占用）。 */
    fun clearCache(context: Context, folder: String) {
        val dir = File(context.cacheDir, folder.replace(unsafeChars, "_"))
        if (!dir.isDirectory) return
        dir.listFiles()?.forEach { it.delete() }
    }
}
