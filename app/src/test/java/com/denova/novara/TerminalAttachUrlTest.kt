package com.denova.novara

import com.denova.novara.core.model.ServerKey
import com.denova.novara.core.network.DenovaClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * attach URL 的真机崩溃回归（2026-10-24，小米 14 Pro）。
 *
 * 之前把 scheme 手工改成 `ws` / `wss`，OkHttp 的 `HttpUrl.Builder.scheme` 直接抛
 * `IllegalArgumentException: unexpected scheme: ws`，应用在主线程 FATAL。
 * `newWebSocket()` 自己会按 ws(s) 升级，URL 必须保持 http(s)。
 */
class TerminalAttachUrlTest {

    private fun client(scheme: String, port: Int) = DenovaClient(ServerKey(scheme, "192.168.2.102", port, ""))

    @Test
    fun `plain http server keeps http scheme and carries the token`() {
        val url = client("http", 18081).terminalAttachUrl("term-1", "tok-abc")

        assertEquals("http", url.scheme)
        assertEquals("192.168.2.102", url.host)
        assertEquals(18081, url.port)
        assertEquals("/api/terminal/sessions/term-1/attach", url.encodedPath)
        assertEquals("tok-abc", url.queryParameter("token"))
    }

    @Test
    fun `https server keeps https so newWebSocket can upgrade to wss`() {
        val url = client("https", 443).terminalAttachUrl("term-2", "tok-def")

        assertEquals("https", url.scheme)
        assertTrue(url.encodedQuery.orEmpty().contains("token=tok-def"))
    }

    @Test
    fun `token is percent encoded`() {
        val url = client("http", 18081).terminalAttachUrl("term-3", "a b&c")

        // 原样拼接会截断 query，服务端会拿不到完整 token → 403 tokenInvalid
        assertEquals("a b&c", url.queryParameter("token"))
        assertTrue(url.encodedQuery.orEmpty().contains("a%20b%26c"))
    }
}
