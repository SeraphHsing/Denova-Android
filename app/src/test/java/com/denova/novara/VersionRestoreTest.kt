package com.denova.novara

import com.denova.novara.core.network.VersionCommandResult
import com.denova.novara.core.network.VersionDiff
import com.denova.novara.core.network.VersionRestorePlan
import com.denova.novara.core.network.VersionRestoreResult
import com.denova.novara.feature.versions.VersionsUiState
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** VER-001 / VER-002 的契约解析与「恢复范围」判定。 */
class VersionRestoreTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    @Test
    fun `create result carries version and status`() {
        val raw = """
            {"message":"已创建版本","version":{"id":"abc123","message":"第三章",
             "created_at":"2026-10-25T15:00:00Z","source":"manual","file_count":12,"total_bytes":2048},
             "status":{"has_versions":true,"clean":true,"changes":[],"auto":{"timed_enabled":true,
             "timed_interval_minutes":10,"retention":100}}}
        """.trimIndent()
        val result = json.decodeFromString(VersionCommandResult.serializer(), raw)
        assertEquals("abc123", result.version?.id)
        assertEquals(12, result.version?.fileCount)
        assertTrue(result.status?.clean == true)
        assertTrue(result.status?.auto?.timedEnabled == true)
    }

    @Test
    fun `diff lists every change and per-file content`() {
        val raw = """
            {"files_complete":true,"comparison":"workspace",
             "version":{"id":"abc123","message":"before","created_at":"","source":"manual"},
             "changes":[{"path":"chapters/03.md","status":"modified"},
                        {"path":"chapters/04.md","status":"deleted"}],
             "files":[{"path":"chapters/03.md","status":"modified","original":"a","modified":"b","text":true}]}
        """.trimIndent()
        val diff = json.decodeFromString(VersionDiff.serializer(), raw)
        assertEquals(2, diff.changes.size)
        assertEquals("chapters/04.md", diff.changes[1].path)
        assertTrue(diff.files.single().text)
        assertEquals("b", diff.files.single().modified)
        assertNull(diff.baseVersion)
    }

    @Test
    fun `restore plan exposes scope backup and warnings`() {
        val raw = """
            {"target":{"id":"abc123","message":"before","created_at":"","source":"manual"},
             "scope":"paths","paths":["chapters/03.md"],
             "changes":[{"path":"chapters/03.md","status":"modified","text":true}],
             "will_create_backup":true,"current_dirty":true,
             "backup_message":"恢复前备份","warnings":["工作区有未保存改动"]}
        """.trimIndent()
        val plan = json.decodeFromString(VersionRestorePlan.serializer(), raw)
        assertEquals("paths", plan.scope)
        assertEquals(listOf("chapters/03.md"), plan.paths)
        assertTrue(plan.willCreateBackup)
        assertTrue(plan.currentDirty)
        assertEquals(1, plan.warnings.size)
    }

    @Test
    fun `restore result carries restored paths and backup version`() {
        val raw = """
            {"message":"已恢复 1 个文件","target":{"id":"abc123","message":"before","created_at":"","source":"manual"},
             "backup_version":{"id":"bak999","message":"备份","created_at":"","source":"rollback_backup"},
             "restored_paths":["chapters/03.md"],"scope":"paths",
             "status":{"has_versions":true,"clean":true,"changes":[]}}
        """.trimIndent()
        val result = json.decodeFromString(VersionRestoreResult.serializer(), raw)
        assertEquals(listOf("chapters/03.md"), result.restoredPaths)
        assertEquals("bak999", result.backupVersion?.id)
        assertTrue(result.status?.clean == true)
    }

    @Test
    fun `all paths selected maps to workspace scope only when every path is checked`() {
        val diff = json.decodeFromString(
            VersionDiff.serializer(),
            """{"changes":[{"path":"a.md","status":"modified"},{"path":"b.md","status":"modified"}]}""",
        )
        val all = VersionsUiState(diff = diff, selectedPaths = setOf("a.md", "b.md"))
        assertTrue(all.allPathsSelected)

        val partial = all.copy(selectedPaths = setOf("a.md"))
        assertFalse(partial.allPathsSelected)

        val none = all.copy(selectedPaths = emptySet())
        assertFalse(none.allPathsSelected)

        // 没有 diff（还没加载）时不能误判成「整本范围」。
        assertFalse(VersionsUiState().allPathsSelected)
    }
}
