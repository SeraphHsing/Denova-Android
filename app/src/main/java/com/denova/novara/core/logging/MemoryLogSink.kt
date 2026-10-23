package com.denova.novara.core.logging

import java.util.ArrayDeque

/** 内存环形缓冲，供运行时 Debug 面板读取最近日志；线程安全。 */
class MemoryLogSink(private val capacity: Int = 500) : LogSink {

    private val buffer = ArrayDeque<LogEntry>()
    private val lock = Any()

    override fun write(entry: LogEntry) {
        synchronized(lock) {
            buffer.addLast(entry)
            while (buffer.size > capacity) buffer.removeFirst()
        }
    }

    /** 最新在前，返回当前快照副本。 */
    fun snapshot(): List<LogEntry> = synchronized(lock) { buffer.toList().asReversed() }

    fun clear() = synchronized(lock) { buffer.clear() }
}
