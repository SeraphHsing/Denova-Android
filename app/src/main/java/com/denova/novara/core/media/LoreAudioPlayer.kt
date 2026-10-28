package com.denova.novara.core.media

import android.content.Context
import android.media.MediaPlayer
import com.denova.novara.core.logging.DenovaLog
import java.io.File

/**
 * 资料库语音材料的本机试听（LOR-002「预览」）。
 *
 * 语音材料是受保护的 `assets/lore` 下的 mp3，MediaPlayer 拿不到会话 Cookie，
 * 因此先用带会话的 OkHttp 下载到 cacheDir，再从本地文件播放。
 * 只保留最后一个文件（按 asset id 命名，切换材料时先停旧播放器）。
 */
class LoreAudioPlayer(
    private val context: Context,
    private val onFinished: (String) -> Unit,
) {
    private var player: MediaPlayer? = null
    private var file: File? = null

    var playingId: String? = null
        private set

    /** 播放给定字节；替换正在播放的内容。失败返回 false（不抛给调用方）。 */
    fun play(assetId: String, bytes: ByteArray, mimeType: String): Boolean {
        stop()
        val suffix = when {
            mimeType.contains("mpeg", true) || mimeType.contains("mp3", true) -> ".mp3"
            mimeType.contains("wav", true) -> ".wav"
            mimeType.contains("ogg", true) -> ".ogg"
            else -> ".audio"
        }
        return runCatching {
            val target = File(context.cacheDir, "lore-audio-${assetId.takeLast(24)}$suffix")
            target.writeBytes(bytes)
            file = target
            val mp = MediaPlayer()
            mp.setDataSource(target.absolutePath)
            mp.setOnCompletionListener {
                stop()
                onFinished(assetId)
            }
            mp.setOnErrorListener { _, what, extra ->
                DenovaLog.w("Lore", "audio playback error what=$what extra=$extra")
                stop()
                onFinished(assetId)
                true
            }
            mp.prepare()
            mp.start()
            player = mp
            playingId = assetId
            DenovaLog.i("Lore", "audio play asset=$assetId bytes=${bytes.size} duration=${mp.duration}ms")
            true
        }.getOrElse { e ->
            DenovaLog.w("Lore", "audio play failed asset=$assetId", e)
            stop()
            false
        }
    }

    fun stop() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        playingId = null
        file?.delete()
        file = null
    }

    fun release() = stop()
}
