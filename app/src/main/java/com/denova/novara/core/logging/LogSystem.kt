package com.denova.novara.core.logging

import java.util.concurrent.CopyOnWriteArrayList

/** 级别过滤 + 脱敏 + 分发到多个 [LogSink]。 */
class LogSystem(
    private val sinks: MutableList<LogSink> = CopyOnWriteArrayList(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Volatile
    private var threshold: LogLevel = LogLevel.INFO

    fun setThreshold(level: LogLevel) {
        threshold = level
    }

    fun addSink(sink: LogSink) {
        sinks.add(sink)
    }

    fun removeSink(sink: LogSink) {
        sinks.remove(sink)
    }

    fun log(level: LogLevel, tag: String, message: String, throwable: Throwable? = null) {
        if (level.priority < threshold.priority) return
        val composed = if (throwable != null) "$message\n${throwable.stackTraceToString()}" else message
        val sanitized = Redactor.redact(composed)
        val entry = LogEntry(clock(), level, tag, sanitized)
        sinks.forEach { it.write(entry) }
    }
}
