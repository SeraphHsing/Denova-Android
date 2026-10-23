package com.denova.novara.core.logging

/** 日志级别，按 [priority] 从低到高用于过滤。 */
enum class LogLevel(val priority: Int) {
    VERBOSE(0),
    DEBUG(1),
    INFO(2),
    WARN(3),
    ERROR(4);

    companion object {
        /** 解析级别名，未知或空时回退到 [INFO]。 */
        fun fromName(name: String?): LogLevel =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: INFO
    }
}
