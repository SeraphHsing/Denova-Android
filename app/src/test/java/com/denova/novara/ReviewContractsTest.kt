package com.denova.novara

import com.denova.novara.core.network.ChangeCommentAnchor
import com.denova.novara.core.network.ChangeCommentRequest
import com.denova.novara.core.network.ReviewChangeRequest
import com.denova.novara.core.network.VersionHistoryResponse
import com.denova.novara.core.network.VersionStatus
import com.denova.novara.core.network.WorkspaceChangeSet
import com.denova.novara.feature.changes.ChangeDiff
import com.denova.novara.feature.changes.DiffKind
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** WRI-016 补充契约：局部审阅（change_set_id）、评论锚点（utf8-bytes-v1）、版本入口。 */
class ReviewContractsTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    private fun set(
        before: String = "第一段\n第二段\n",
        after: String = "第一段\n第二段\n新增一行\n",
    ) = WorkspaceChangeSet(
        id = "cs1",
        path = "a.md",
        baseRevision = "r0",
        revision = "r1",
        beforeContent = before,
        afterContent = after,
    )

    @Test
    fun `review request omits change set for whole group`() {
        val body = Json.encodeToString(ReviewChangeRequest.serializer(), ReviewChangeRequest("accept"))
        assertTrue(body.contains("\"decision\":\"accept\""))
        assertFalse(body.contains("change_set_id"))
    }

    @Test
    fun `review request targets one change set when provided`() {
        val body = Json.encodeToString(
            ReviewChangeRequest.serializer(),
            ReviewChangeRequest("reject", "cs1"),
        )
        assertTrue(body.contains("\"change_set_id\":\"cs1\""))
        assertTrue(body.contains("\"decision\":\"reject\""))
    }

    @Test
    fun `anchor of added line points at the after side bytes`() {
        val diff = ChangeDiff.file(set())
        val added = diff.lines.single { it.kind == DiffKind.ADD }
        val anchor = ChangeDiff.anchor(set(), added)!!
        assertEquals("after", anchor.side)
        assertEquals("r1", anchor.revision)
        assertEquals("新增一行", anchor.quote)
        // 前两行各 3 个汉字（9 字节）+ 换行，第三行起点 = 20
        assertEquals(20, anchor.start)
        assertEquals(20 + 12, anchor.end)
        // 服务端会逐字节比对 content[start:end] 与 quote
        val content = set().afterContent
        assertEquals(anchor.quote, content.toByteArray().copyOfRange(anchor.start, anchor.end).toString(Charsets.UTF_8))
    }

    @Test
    fun `anchor of removed line points at the before side bytes`() {
        val removedSet = set(before = "保留\n要删掉\n", after = "保留\n")
        val diff = ChangeDiff.file(removedSet)
        val removed = diff.lines.single { it.kind == DiffKind.REMOVE }
        val anchor = ChangeDiff.anchor(removedSet, removed)!!
        assertEquals("before", anchor.side)
        assertEquals("r0", anchor.revision)
        assertEquals("要删掉", anchor.quote)
    }

    @Test
    fun `blank line and missing revision produce no anchor`() {
        val blankSet = set(before = "a\n\n", after = "a\n\n")
        val blankLine = ChangeDiff.file(blankSet).lines.first { it.text.isBlank() }
        assertNull(ChangeDiff.anchor(blankSet, blankLine))

        val noRevision = set().copy(revision = "")
        val added = ChangeDiff.file(set()).lines.single { it.kind == DiffKind.ADD }
        assertNull(ChangeDiff.anchor(noRevision, added))
    }

    @Test
    fun `comment request serialises anchor and change set`() {
        val request = ChangeCommentRequest(
            groupId = "g1",
            changeSetId = "cs1",
            body = "这句保留",
            anchor = ChangeCommentAnchor(side = "after", encoding = "utf8-bytes-v1", revision = "r1", start = 0, end = 9, quote = "第一段"),
        )
        val body = json.encodeToString(ChangeCommentRequest.serializer(), request)
        assertTrue(body.contains("\"group_id\":\"g1\""))
        assertTrue(body.contains("\"change_set_id\":\"cs1\""))
        assertTrue(body.contains("\"side\":\"after\""))
        assertTrue(body.contains("\"encoding\":\"utf8-bytes-v1\""))
        assertTrue(body.contains("\"quote\":\"第一段\""))
    }

    @Test
    fun `comment request without anchor stays group level`() {
        val body = json.encodeToString(
            ChangeCommentRequest.serializer(),
            ChangeCommentRequest(groupId = "g1", body = "整组意见"),
        )
        assertFalse(body.contains("anchor"))
        assertFalse(body.contains("change_set_id"))
    }

    @Test
    fun `version status and history decode`() {
        val status = json.decodeFromString(
            VersionStatus.serializer(),
            """{"has_versions":true,"clean":false,"changes":[{"path":"a.md","status":"modified"}],
                "latest":{"id":"v1","message":"手动保存","created_at":"2026-10-25T01:00:00Z","source":"manual",
                          "file_count":3,"total_bytes":120,"changed_paths":["a.md"]},
                "auto":{"timed_enabled":true,"timed_interval_minutes":15,"retention":10,"last_auto_at":"2026-10-25T00:00:00Z"}}""",
        )
        assertTrue(status.hasVersions)
        assertFalse(status.clean)
        assertEquals("v1", status.latest?.id)
        assertEquals(3, status.latest?.fileCount)
        assertTrue(status.auto.timedEnabled)
        assertEquals(15, status.auto.timedIntervalMinutes)

        val history = json.decodeFromString(
            VersionHistoryResponse.serializer(),
            """{"versions":[{"id":"v2","message":"auto","created_at":"t","source":"auto","file_count":1,"total_bytes":10}]}""",
        )
        assertEquals(1, history.versions.size)
        assertEquals("auto", history.versions.single().source)
    }

    @Test
    fun `version status tolerates missing latest and auto`() {
        val status = json.decodeFromString(VersionStatus.serializer(), """{"has_versions":false,"clean":true}""")
        assertFalse(status.hasVersions)
        assertTrue(status.clean)
        assertNull(status.latest)
        assertFalse(status.auto.timedEnabled)
    }

    @Test
    fun `comment with anchor decodes from group payload`() {
        val comment = json.decodeFromString(
            com.denova.novara.core.network.WorkspaceChangeComment.serializer(),
            """{"id":"c1","group_id":"g1","change_set_id":"cs1","body":"这里","anchor":
                {"side":"after","encoding":"utf8-bytes-v1","revision":"r1","start":0,"end":9,"quote":"第一段"}}""",
        )
        assertEquals("after", comment.anchor?.side)
        assertEquals("cs1", comment.changeSetId)
    }
}
