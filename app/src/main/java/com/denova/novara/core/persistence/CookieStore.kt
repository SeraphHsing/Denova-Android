package com.denova.novara.core.persistence

import com.denova.novara.core.logging.DenovaLog
import java.io.File
import java.util.concurrent.Executors
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 登录会话 Cookie 的本地持久化（按服务器 origin 隔离）。
 *
 * 背景（G6 决策 C）：Android 回前台可以在秒级重挂终端，但**进程被杀**后 Cookie 只在内存，
 * 用户必须重新登录，期间 PTY 一直处于 `attached=0`，Web 端挂载工作台会把它当孤儿回收。
 * 持久化会话 Cookie 后，重启应用即可直接复用登录态并立即重挂，把该窗口从分钟级压到秒级。
 *
 * 约束（AGENTS §5）：文件写在应用私有目录，不写日志、不进仓库；服务器切换按 origin 隔离，
 * 退出登录时清空自己的记录。
 */
class CookieStore(
    private val dir: File,
    /** 落盘调度：生产用单线程，测试传直通执行器即可同步断言。 */
    private val io: java.util.concurrent.Executor =
        Executors.newSingleThreadExecutor { r -> Thread(r, "cookie-store") },
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    private data class StoredCookie(
        val name: String,
        val value: String,
        val domain: String,
        val path: String,
        @SerialName("expires_at") val expiresAt: Long,
        @SerialName("host_only") val hostOnly: Boolean,
        val secure: Boolean,
        @SerialName("http_only") val httpOnly: Boolean,
    )

    fun load(origin: String): List<Cookie> {
        val file = fileFor(origin)
        if (!file.isFile) return emptyList()
        return runCatching {
            val list = json.decodeFromString(ListSerializer, file.readText())
            list.mapNotNull { it.toCookie() }
        }.getOrElse {
            DenovaLog.w("CookieStore", "load failed; ignore", it)
            emptyList()
        }
    }

    fun save(origin: String, cookies: List<Cookie>) {
        val snapshot = cookies.map { it.toStored() }
        io.execute {
            runCatching {
                ensureDir()
                fileFor(origin).writeText(json.encodeToString(ListSerializer, snapshot))
            }.onFailure { DenovaLog.w("CookieStore", "save failed", it) }
        }
    }

    fun clear(origin: String) {
        io.execute {
            runCatching { fileFor(origin).delete() }
                .onFailure { DenovaLog.w("CookieStore", "clear failed", it) }
        }
    }

    private fun ensureDir() {
        if (!dir.isDirectory && !dir.mkdirs()) {
            DenovaLog.w("CookieStore", "cannot create dir")
        }
    }

    private fun fileFor(origin: String): File =
        File(dir, origin.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".json")

    private fun Cookie.toStored(): StoredCookie = StoredCookie(
        name = name,
        value = value,
        domain = domain,
        path = path,
        expiresAt = expiresAt,
        hostOnly = hostOnly,
        secure = secure,
        httpOnly = httpOnly,
    )

    private fun StoredCookie.toCookie(): Cookie? {
        if (expiresAt in 1..System.currentTimeMillis()) return null
        val builder = Cookie.Builder().name(name).value(value).path(path)
        if (hostOnly) builder.hostOnlyDomain(domain) else builder.domain(domain)
        if (expiresAt > 0) builder.expiresAt(expiresAt)
        if (secure) builder.secure()
        if (httpOnly) builder.httpOnly()
        return runCatching { builder.build() }.getOrNull()
    }

    private companion object {
        val ListSerializer = kotlinx.serialization.builtins.ListSerializer(StoredCookie.serializer())
    }
}

/**
 * 带持久化的 CookieJar：读写内存视图，写穿透到 [CookieStore]。
 *
 * 保持与旧 [okhttp3.CookieJar] 相同的可见语义（同 name/domain/path 覆盖），只是多了落盘。
 */
class PersistentCookieJar(
    private val origin: String,
    private val store: CookieStore,
) : CookieJar {

    private val host: String? = origin.toHttpUrlOrNull()?.host

    private val cookies = LinkedHashMap<String, Cookie>()

    init {
        store.load(origin).forEach { cookies[keyOf(it)] = it }
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        synchronized(this) {
            cookies.forEach { this.cookies[keyOf(it)] = it }
            runCatching { store.save(origin, this.cookies.values.toList()) }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        if (host == null || url.host != host) return emptyList()
        val now = System.currentTimeMillis()
        return synchronized(this) {
            cookies.values.filter { (it.expiresAt == Long.MAX_VALUE || it.expiresAt > now) && it.matches(url) }
        }
    }

    /** 退出登录：清掉本地会话凭证，避免残留。 */
    fun clear() {
        synchronized(this) {
            cookies.clear()
            runCatching { store.clear(origin) }
        }
    }

    private fun keyOf(cookie: Cookie): String = "${cookie.domain}|${cookie.path}|${cookie.name}"
}
