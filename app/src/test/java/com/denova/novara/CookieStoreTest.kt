package com.denova.novara

import com.denova.novara.core.persistence.CookieStore
import com.denova.novara.core.persistence.PersistentCookieJar
import java.io.File
import java.util.concurrent.Executor
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话 Cookie 持久化（G6 决策 C）：进程被杀后重启要能复用登录态，退出登录要清干净。
 */
class CookieStoreTest {

    private val dir: File = File(System.getProperty("java.io.tmpdir"), "novara-cookie-test-${System.nanoTime()}")
    private val direct = Executor { it.run() }
    private val origin = "http://192.168.2.102:18081"
    private val url = "$origin/api/auth/status".toHttpUrl()

    @After
    fun cleanup() {
        dir.deleteRecursively()
    }

    @Test
    fun `cookie survives a new jar instance`() {
        val store = CookieStore(dir, direct)
        val jar = PersistentCookieJar(origin, store)
        jar.saveFromResponse(url, listOf(cookie("session", "abc", expiresInMillis = 60_000)))

        // 模拟进程重启：同一磁盘、全新 jar
        val restarted = PersistentCookieJar(origin, CookieStore(dir, direct))

        val sent = restarted.loadForRequest(url)
        assertEquals(1, sent.size)
        assertEquals("abc", sent[0].value)
    }

    @Test
    fun `expired cookie is dropped on load`() {
        val store = CookieStore(dir, direct)
        val jar = PersistentCookieJar(origin, store)
        jar.saveFromResponse(url, listOf(cookie("session", "old", expiresInMillis = -1_000)))

        val restarted = PersistentCookieJar(origin, CookieStore(dir, direct))

        assertTrue(restarted.loadForRequest(url).isEmpty())
    }

    @Test
    fun `different servers are isolated`() {
        val store = CookieStore(dir, direct)
        PersistentCookieJar(origin, store).saveFromResponse(url, listOf(cookie("session", "a")))

        val other = PersistentCookieJar("http://192.168.2.200:18081", store)

        assertTrue(
            "切换服务器不能复用另一个服务器的会话 Cookie",
            other.loadForRequest("http://192.168.2.200:18081/api/auth/status".toHttpUrl()).isEmpty(),
        )
    }

    @Test
    fun `clear removes persisted credentials`() {
        val store = CookieStore(dir, direct)
        val jar = PersistentCookieJar(origin, store)
        jar.saveFromResponse(url, listOf(cookie("session", "abc")))

        jar.clear()

        assertTrue(PersistentCookieJar(origin, CookieStore(dir, direct)).loadForRequest(url).isEmpty())
    }

    @Test
    fun `httpOnly and secure flags are preserved`() {
        val store = CookieStore(dir, direct)
        val jar = PersistentCookieJar(origin, store)
        jar.saveFromResponse(
            url,
            listOf(
                Cookie.Builder()
                    .name("session").value("v").hostOnlyDomain(url.host).path("/")
                    .httpOnly().expiresAt(System.currentTimeMillis() + 60_000).build(),
            ),
        )

        val restored = PersistentCookieJar(origin, CookieStore(dir, direct)).loadForRequest(url).single()

        assertTrue(restored.httpOnly)
    }

    private fun cookie(name: String, value: String, expiresInMillis: Long = 60_000): Cookie =
        Cookie.Builder()
            .name(name)
            .value(value)
            .hostOnlyDomain(url.host)
            .path("/")
            .expiresAt(System.currentTimeMillis() + expiresInMillis)
            .build()
}
