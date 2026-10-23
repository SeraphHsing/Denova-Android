package com.denova.novara

import com.denova.novara.core.model.ServerKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerKeyTest {

    @Test
    fun `parses host and port without scheme`() {
        val key = ServerKey.parse("192.168.2.102:18081")
        assertEquals("http", key?.scheme)
        assertEquals("192.168.2.102", key?.host)
        assertEquals(18081, key?.port)
        assertEquals("", key?.basePath)
    }

    @Test
    fun `keeps explicit scheme and default port`() {
        val http = ServerKey.parse("http://example.com")!!
        assertEquals("http", http.scheme)
        assertEquals(80, http.port)

        val https = ServerKey.parse("https://example.com")!!
        assertEquals("https", https.scheme)
        assertEquals(443, https.port)
    }

    @Test
    fun `normalizes trailing slash and base path`() {
        assertEquals("", ServerKey.parse("http://example.com/")!!.basePath)
        assertEquals("/base", ServerKey.parse("http://example.com/base/")!!.basePath)
        assertEquals("/a/b", ServerKey.parse("http://example.com/a/b")!!.basePath)
    }

    @Test
    fun `rejects empty or invalid input`() {
        assertNull(ServerKey.parse(""))
        assertNull(ServerKey.parse("   "))
        assertNull(ServerKey.parse("http://"))
        assertNull(ServerKey.parse("ftp://example.com"))
    }

    @Test
    fun `builds base url`() {
        val key = ServerKey.parse("192.168.2.102:18081")!!
        assertEquals("http://192.168.2.102:18081", key.baseUrl)
        assertEquals("http://192.168.2.102:18081", key.origin)
    }
}
