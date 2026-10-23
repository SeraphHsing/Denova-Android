package com.denova.novara.core.logging

import android.util.Log

/** 写入系统 logcat；仅在 Android 运行时可用，勿在 JVM 单测中实例化。 */
class LogcatSink : LogSink {
    override fun write(entry: LogEntry) {
        val priority = when (entry.level) {
            LogLevel.VERBOSE -> Log.VERBOSE
            LogLevel.DEBUG -> Log.DEBUG
            LogLevel.INFO -> Log.INFO
            LogLevel.WARN -> Log.WARN
            LogLevel.ERROR -> Log.ERROR
        }
        Log.println(priority, entry.tag, entry.message)
    }
}
