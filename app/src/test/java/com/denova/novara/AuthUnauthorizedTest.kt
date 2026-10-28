package com.denova.novara

import com.denova.novara.core.network.DenovaClient
import com.denova.novara.core.network.DenovaApiException
import com.denova.novara.core.model.ServerKey
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress

/**
 * 会话失效（401）上报（P4-C 回归 / G1-d 迟到 401 的可见性补强）。
 *
 * 服务端返回 401 时客户端必须通知连接层回到登录页，而不是只把服务端文案抛给界面；
 * 登录请求自身的 401（密码错误）不算会话失效。
 */
class AuthUnauthorizedTest {

    private lateinit var server: HttpServer
    private var status = 401

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/auth/status") { exchange ->
            val body = if (status == 401) {
                """{"code":"api.access.passwordRequired","error":"请先输入 Denova 远程访问用户名和密码"}"""
                    .toByteArray()
            } else {
                """{"authenticated":true,"local":false}""".toByteArray()
            }
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/api/auth/login") { exchange ->
            val body = """{"code":"api.access.loginFailed","error":"用户名或密码错误"}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(401, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
    }

    @After
    fun stop() {
        server.stop(0)
    }

    private fun client(): DenovaClient {
        val key = ServerKey.parse("http://127.0.0.1:${server.address.port}")!!
        return DenovaClient(key)
    }

    @Test
    fun `401 notifies the connection layer once`() = runBlocking {
        var count = 0
        val c = client()
        c.onUnauthorized = { count++ }

        runCatching { c.status() }
        runCatching { c.status() }

        assertEquals(1, count)
    }

    @Test
    fun `login 401 is not treated as an expired session`() = runBlocking {
        var count = 0
        val c = client()
        c.onUnauthorized = { count++ }

        val failure = runCatching { c.suppressingUnauthorized { c.login("u", "p") } }.exceptionOrNull()

        assertTrue(failure is DenovaApiException)
        assertEquals(401, (failure as DenovaApiException).status)
        assertEquals(0, count)
    }

    @Test
    fun `successful requests do not notify`() = runBlocking {
        status = 200
        var count = 0
        val c = client()
        c.onUnauthorized = { count++ }

        c.status()

        assertEquals(0, count)
    }
}
