package com.denova.novara

import com.denova.novara.core.persistence.GameDraftStore
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 按故事隔离的输入草稿（GAM-002）。
 *
 * 覆盖：不同故事互不覆盖、切回读到原内容、发送后清除、空文本删文件、key 清洗防路径穿越、
 * 超长截断。用临时目录构造，不依赖 Android Context。
 */
class GameDraftStoreTest {

    private lateinit var root: File
    private lateinit var store: GameDraftStore

    @Before
    fun setUp() {
        root = File(System.getProperty("java.io.tmpdir"), "game-drafts-${System.nanoTime()}")
        store = GameDraftStore(root)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `drafts are isolated per story`() = runBlocking {
        store.save("story-a", "写给 A 的开头")
        store.save("story-b", "写给 B 的开头")

        assertEquals("写给 A 的开头", store.load("story-a"))
        assertEquals("写给 B 的开头", store.load("story-b"))
    }

    @Test
    fun `switching back restores the exact text`() = runBlocking {
        store.save("story-a", "第一行\n第二行")
        // 模拟切到别的故事再切回来：中间没有任何写入。
        assertEquals("", store.load("story-c"))
        assertEquals("第一行\n第二行", store.load("story-a"))
    }

    @Test
    fun `clear removes the draft so the next load is empty`() = runBlocking {
        store.save("story-a", "待发送")
        store.clear("story-a")

        assertEquals("", store.load("story-a"))
        assertFalse(File(root, "${GameDraftStore.draftKey("story-a")}.txt").exists())
    }

    @Test
    fun `blank text deletes the file instead of storing whitespace`() = runBlocking {
        store.save("story-a", "x")
        store.save("story-a", "   ")

        assertEquals("", store.load("story-a"))
    }

    @Test
    fun `draft keys are sanitized against path traversal`() {
        assertEquals("project_1_story_2", GameDraftStore.draftKey("project/1?story=2"))
        assertEquals("unknown", GameDraftStore.draftKey("   "))

        // 关键点：清洗后不会出现路径分隔符，文件一定落在 root 之下。
        val key = GameDraftStore.draftKey("../../etc/passwd")
        assertFalse(key.contains('/'))
        assertFalse(key.contains('\\'))
    }

    @Test
    fun `oversized drafts are truncated`() = runBlocking {
        val long = "字".repeat(GameDraftStore.MAX_LENGTH + 500)
        store.save("story-a", long)

        assertEquals(GameDraftStore.MAX_LENGTH, store.load("story-a").length)
    }

    @Test
    fun `unknown story id never reads another story`() {
        assertEquals("", store.load(""))
        assertEquals("", store.load("not-saved-yet"))
        assertTrue(root.listFiles().isNullOrEmpty() || root.listFiles()!!.isEmpty())
    }
}
