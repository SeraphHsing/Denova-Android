package com.denova.novara

import com.denova.novara.core.stream.NarrativeBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NarrativeBuilderTest {

    @Test
    fun `short text stays in one block`() {
        val builder = NarrativeBuilder(maxBlockChars = 100)
        builder.append("短文本")
        assertEquals(listOf("短文本"), builder.snapshot)
    }

    @Test
    fun `empty append is ignored`() {
        val builder = NarrativeBuilder(maxBlockChars = 100)
        builder.append("")
        assertTrue(builder.isEmpty)
    }

    @Test
    fun `long text splits and preserves content`() {
        val builder = NarrativeBuilder(maxBlockChars = 10)
        val text = "abcdefghijklmnopqrstuvwxyz"
        builder.append(text)
        assertTrue(builder.snapshot.size > 1)
        assertEquals(text, builder.snapshot.joinToString(""))
        assertTrue(builder.snapshot.all { it.length <= 10 })
    }

    @Test
    fun `splits prefer newline boundary`() {
        val builder = NarrativeBuilder(maxBlockChars = 10)
        builder.append("abcdef\nghijklmn")
        assertEquals("abcdef\nghijklmn", builder.snapshot.joinToString(""))
        assertTrue(builder.snapshot.all { it.length <= 10 })
        // 首块在换行处（下标 7）切出，而不是把第一行硬切成 "abcdefghij"
        assertTrue(builder.snapshot.first().startsWith("abcdef\n"))
    }

    @Test
    fun `incremental appends match concatenation`() {
        val builder = NarrativeBuilder(maxBlockChars = 8)
        val parts = listOf("你好", "世界", "\n\n", "新的", "一段")
        parts.forEach { builder.append(it) }
        assertEquals(parts.joinToString(""), builder.snapshot.joinToString(""))
    }

    @Test
    fun `reset replaces previous content`() {
        val builder = NarrativeBuilder(maxBlockChars = 100)
        builder.append("旧内容")
        builder.reset("基线")
        assertEquals(listOf("基线"), builder.snapshot)
    }

    @Test
    fun `snapshot is a copy`() {
        val builder = NarrativeBuilder(maxBlockChars = 100)
        builder.append("a")
        val first = builder.snapshot
        builder.append("b")
        assertEquals(listOf("a"), first)
        assertEquals(listOf("ab"), builder.snapshot)
    }
}
