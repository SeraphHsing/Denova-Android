package com.denova.novara.core.logging

/** 日志输出目的地；由 [LogSystem] 统一分发已脱敏的 [LogEntry]。 */
fun interface LogSink {
    fun write(entry: LogEntry)
}
