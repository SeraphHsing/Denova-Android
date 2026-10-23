package com.denova.novara.core.logging

/** 一条已完成脱敏、准备写入 sink 的日志记录。 */
data class LogEntry(
    val timestamp: Long,
    val level: LogLevel,
    val tag: String,
    val message: String,
)
