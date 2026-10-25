package com.denova.novara

import com.denova.novara.core.save.AutosaveConflictCodec
import com.denova.novara.core.save.AutosaveConflictRecord
import com.denova.novara.core.save.ConflictArchive
import com.denova.novara.core.save.ConflictSnapshot
import com.denova.novara.core.save.ConflictStorage
import com.denova.novara.core.save.FallbackConflictArchive
import com.denova.novara.core.save.LocalConflictArchive
import com.denova.novara.core.save.PreservedConflict
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * WRI-013 冲突材料归档：请求体必须满足后端 `autosave.Input` 的必填校验
 * （resource / scope / id / strategy 非空、四份 value 是合法 JSON、conflict_paths 至少一条）。
 */
class ConflictArchiveTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun record() = AutosaveConflictRecord(
        scope = "project-efb2",
        id = "notes/idea.md",
        base = ConflictSnapshot("r1", "base"),
        local = ConflictSnapshot("r1", "local"),
        external = ConflictSnapshot("r2", "external"),
        merged = ConflictSnapshot("r2", "<<<<<<< 本地修改\nlocal\n=======\nexternal\n>>>>>>> 服务器"),
    )

    @Test
    fun `encoded record keeps defaulted fields required by the server`() {
        val text = AutosaveConflictCodec.encode(record())

        // 默认值字段被省略时后端会判 400，因此编解码器必须显式写出它们。
        assertTrue(text.contains("\"resource\":\"workspace_file\""))
        assertTrue(text.contains("\"strategy\":\"merge_non_overlap_prefer_local\""))
        assertTrue(text.contains("\"conflict_paths\":[[]]"))
        // value 是 JSON 字符串（Go 侧按 json.RawMessage 校验）。
        assertTrue(text.contains("\"value\":\"base\""))
        assertTrue(text.contains("\"base\":{\"revision\":\"r1\",\"value\":\"base\"}"))
    }

    @Test
    fun `encode then decode round trips every snapshot`() {
        val back = AutosaveConflictCodec.decode(AutosaveConflictCodec.encode(record()))

        assertEquals("workspace_file", back.resource)
        assertEquals("project-efb2", back.scope)
        assertEquals("notes/idea.md", back.id)
        assertEquals("r1", back.base.revision)
        assertEquals("local", back.local.value)
        assertEquals("r2", back.external.revision)
        assertEquals(listOf(emptyList<String>()), back.conflictPaths)
    }

    @Test
    fun `local archive writes a recoverable copy named after the returned id`() = runTest {
        val dir = File(temp.root, "conflicts")
        val saved = LocalConflictArchive(dir).preserve(record())

        assertEquals(ConflictStorage.LOCAL, saved.storage)
        assertTrue(saved.id.startsWith("autosave-conflict-"))
        val file = File(saved.path)
        assertTrue(file.exists())
        assertEquals(saved.id, file.nameWithoutExtension)
        // 落盘内容本身仍是可解析的冲突记录，供事后回放。
        val stored = AutosaveConflictCodec.decode(file.readText())
        assertEquals("notes/idea.md", stored.id)
        assertEquals("external", stored.external.value)
    }

    @Test
    fun `fallback archive uses the local copy when the server journal fails`() = runTest {
        val failing = object : ConflictArchive {
            override suspend fun preserve(record: AutosaveConflictRecord): PreservedConflict =
                throw IllegalStateException("journal unavailable")
        }
        val archive = FallbackConflictArchive(failing, LocalConflictArchive(File(temp.root, "conflicts")))

        val saved = archive.preserve(record())

        assertEquals(ConflictStorage.LOCAL, saved.storage)
        assertTrue(File(saved.path).exists())
    }

    @Test
    fun `fallback archive propagates cancellation instead of masking it`() = runTest {
        val cancelling = object : ConflictArchive {
            override suspend fun preserve(record: AutosaveConflictRecord): PreservedConflict =
                throw CancellationException("scope cancelled")
        }
        val archive = FallbackConflictArchive(cancelling, LocalConflictArchive(File(temp.root, "conflicts")))

        try {
            archive.preserve(record())
            fail("cancellation must propagate")
        } catch (expected: CancellationException) {
            assertTrue(expected.message!!.contains("scope cancelled"))
        }
    }
}
