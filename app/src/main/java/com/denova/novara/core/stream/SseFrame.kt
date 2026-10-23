package com.denova.novara.core.stream

/** 一条解析后的 SSE 帧。`id` 即游标；`data` 多行已用 `\n` 连接。 */
data class SseFrame(
    val id: String?,
    val event: String?,
    val data: String?,
)
