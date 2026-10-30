package com.denova.novara.core.model

/**
 * 连接身份：规范化后的 scheme + host + port + basePath。
 * 这是客户端本地切换服务器时的隔离 key（Cookie / 缓存 / 草稿 / 游标按此隔离），
 * 不用于传输字段；传输仍用服务端现有接口。
 */
data class ServerKey(
    val scheme: String,
    val host: String,
    val port: Int,
    val basePath: String,
) {
    val origin: String get() = "$scheme://$host:$port"
    val baseUrl: String get() = origin + basePath

    companion object {
        /**
         * 解析用户输入的服务器地址。缺 scheme 时默认 http；缺端口时按 scheme 补 80/443；
         * 末尾斜杠与 "/" 路径被规整掉。无法解析返回 null。
         */
        fun parse(raw: String): ServerKey? {
            val input = raw.trim()
            if (input.isEmpty()) return null
            val withScheme = if (input.contains("://")) input else "http://$input"
            return try {
                val uri = java.net.URI(withScheme)
                val scheme = uri.scheme?.lowercase() ?: return null
                if (scheme != "http" && scheme != "https") return null
                // host 归一成小写：`MyHost` 与 `myhost` 是同一台服务器，
                // 不归一的话草稿作用域与 Cookie jar 会各存一份（表现为「同一台服务器还要重新登录」）。
                val host = uri.host?.lowercase() ?: return null
                if (host.isEmpty()) return null
                val port = if (uri.port == -1) (if (scheme == "https") 443 else 80) else uri.port
                var path = uri.rawPath.orEmpty()
                while (path.endsWith("/")) path = path.dropLast(1)
                ServerKey(scheme, host, port, path)
            } catch (_: Exception) {
                null
            }
        }
    }
}
