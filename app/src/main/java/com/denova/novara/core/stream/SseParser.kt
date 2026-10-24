package com.denova.novara.core.stream

private val LINE_SPLIT = Regex("\\r\\n|\\r|\\n")

/**
 * 增量 SSE 解析器（纯 Kotlin，无 Android / 网络依赖）。对齐 Web `sse.ts`：
 * 分帧边界 `\n\n` / `\r\r` / `\r\n\r\n`、注释行（`:` 开头）、空行、字段值前导空格、
 * 多行 `data`、EOF 剩余数据。只有含非空 `event` 的块才产出 [SseFrame]。
 */
class SseParser {

    private val buffer = StringBuilder()

    /** 喂入一段文本，返回本段内已完整的全部帧。 */
    fun feed(chunk: String): List<SseFrame> {
        buffer.append(chunk)
        return drain(flushRemainder = false)
    }

    /** 流结束：把剩余缓冲作为最后一帧（若有）产出并清空。 */
    fun finish(): List<SseFrame> = drain(flushRemainder = true)

    fun reset() {
        buffer.clear()
    }

    private fun drain(flushRemainder: Boolean): List<SseFrame> {
        val frames = mutableListOf<SseFrame>()
        while (true) {
            val boundary = findBoundary(buffer.toString()) ?: break
            val block = buffer.substring(0, boundary.first)
            buffer.delete(0, boundary.first + boundary.second)
            parseBlock(block)?.let { frames.add(it) }
        }
        if (flushRemainder) {
            parseBlock(buffer.toString())?.let { frames.add(it) }
            buffer.clear()
        }
        return frames
    }

    private fun findBoundary(value: String): Pair<Int, Int>? {
        val lf = value.indexOf("\n\n")
        val cr = value.indexOf("\r\r")
        val crlf = value.indexOf("\r\n\r\n")
        return listOfNotNull(
            if (lf >= 0) lf to 2 else null,
            if (cr >= 0) cr to 2 else null,
            if (crlf >= 0) crlf to 4 else null,
        ).minByOrNull { it.first }
    }

    private fun parseBlock(block: String): SseFrame? {
        if (block.isBlank()) return null
        var id: String? = null
        var event: String? = null
        val data = mutableListOf<String>()
        for (line in block.split(LINE_SPLIT)) {
            if (line.isEmpty() || line.startsWith(":")) continue
            val sep = line.indexOf(':')
            val field = if (sep >= 0) line.substring(0, sep) else line
            var value = if (sep >= 0) line.substring(sep + 1) else ""
            if (value.startsWith(" ")) value = value.substring(1)
            when (field) {
                "id" -> id = value
                "event" -> event = value
                "data" -> data.add(value)
            }
        }
        // 保留"有 data 无 event"的帧（AI SDK UI message 流只有 data 行）；
        // 既无 event 又无 data 的空块/注释块才丢弃。
        if (event.isNullOrEmpty() && data.isEmpty()) return null
        return SseFrame(id, event, data.takeIf { it.isNotEmpty() }?.joinToString("\n"))
    }
}
