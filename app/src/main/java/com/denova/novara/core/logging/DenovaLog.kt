package com.denova.novara.core.logging

/**
 * 静态日志门面。默认挂 [MemoryLogSink]（供运行时 Debug 面板读取）；Android 端由
 * [MainActivity] 额外挂 [LogcatSink]。日志经 [Redactor] 脱敏后统一英文记录，
 * 不落密码 / Cookie / 配对与 attach token / API key / 私人正文（见 AGENTS.md §5）。
 */
object DenovaLog {

    private val system = LogSystem()

    /** 内存环形缓冲，Debug 面板直接读取。 */
    val memory = MemoryLogSink()

    init {
        system.addSink(memory)
    }

    fun setThreshold(level: LogLevel) = system.setThreshold(level)

    fun addSink(sink: LogSink) = system.addSink(sink)

    fun removeSink(sink: LogSink) = system.removeSink(sink)

    fun v(tag: String, message: String) = system.log(LogLevel.VERBOSE, tag, message)

    fun d(tag: String, message: String) = system.log(LogLevel.DEBUG, tag, message)

    fun i(tag: String, message: String) = system.log(LogLevel.INFO, tag, message)

    fun w(tag: String, message: String) = system.log(LogLevel.WARN, tag, message)

    fun w(tag: String, message: String, throwable: Throwable) = system.log(LogLevel.WARN, tag, message, throwable)

    fun e(tag: String, message: String) = system.log(LogLevel.ERROR, tag, message)

    fun e(tag: String, message: String, throwable: Throwable) = system.log(LogLevel.ERROR, tag, message, throwable)
}
