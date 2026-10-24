package com.denova.novara

import com.denova.novara.core.network.DenovaApiException
import com.denova.novara.core.network.ProjectFileDocument
import com.denova.novara.core.network.SaveFileResponse
import com.denova.novara.core.persistence.Draft
import com.denova.novara.core.persistence.DraftStorage
import com.denova.novara.core.save.FileRemote
import com.denova.novara.core.save.FileSaveCoordinator
import com.denova.novara.core.save.SaveOutcome
import com.denova.novara.feature.editor.EditorOpen
import com.denova.novara.feature.editor.EditorPerf
import com.denova.novara.feature.editor.EditorSession
import com.denova.novara.feature.editor.EditorUiState
import com.denova.novara.feature.editor.OpenDecision
import com.denova.novara.feature.editor.ReceiptPolicy
import com.denova.novara.feature.editor.SaveStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EditorSessionTest {

    private class FakeRemote : FileRemote {
        var serverContent = ""
        var serverRevision = "r0"
        var conflictOnFirstSave = false
        private var saveCount = 0

        override suspend fun readFile(projectId: String, path: String) =
            ProjectFileDocument(path, serverContent, serverRevision)

        override suspend fun saveFile(
            projectId: String,
            path: String,
            content: String,
            baseRevision: String,
        ): SaveFileResponse {
            saveCount++
            if (conflictOnFirstSave && saveCount == 1) {
                throw DenovaApiException(409, "api.workspace.fileRevisionConflict", "conflict")
            }
            serverContent = content
            serverRevision = "r$saveCount"
            return SaveFileResponse(projectId, path, serverRevision, true, "saved")
        }

        fun saveCount() = saveCount
    }

    private class FakeDrafts : DraftStorage {
        val map = mutableMapOf<String, Draft>()
        override suspend fun saveDraft(fileKey: String, draft: Draft) { map[fileKey] = draft }
        override suspend fun loadDraft(fileKey: String): Draft? = map[fileKey]
        override suspend fun clearDraft(fileKey: String) { map.remove(fileKey) }
    }

    @Before
    fun setUp() {
        EditorPerf.reset()
    }

    // ---- EditorOpen.decide（进程重建恢复判定） ----

    @Test
    fun `decide without draft uses server content`() {
        val d = EditorOpen.decide("server", "r1", null)
        assertEquals("server", (d as OpenDecision.Server).content)
        assertEquals("r1", d.revision)
    }

    @Test
    fun `decide with stale draft equal to server uses server`() {
        val d = EditorOpen.decide("same", "r1", Draft("same", "r0", "base"))
        assertTrue(d is OpenDecision.Server)
    }

    @Test
    fun `decide with uncommitted draft restores draft`() {
        val d = EditorOpen.decide("server", "r1", Draft("draft", "r0", "base"))
        val r = d as OpenDecision.RestoredDraft
        assertEquals("draft", r.content)
        assertEquals("r0", r.baseRevision)
        assertEquals("base", r.baseContent)
    }

    // ---- open 草稿恢复 ----

    @Test
    fun `open without draft uses server`() = runTest {
        val drafts = FakeDrafts()
        val session = EditorSession(FileSaveCoordinator(FakeRemote(), drafts), drafts, this)
        session.open("p", "f.md", "hello", "r0")
        assertEquals("hello", session.state.value.content)
        assertEquals("r0", session.state.value.baseRevision)
        assertEquals(SaveStatus.IDLE, session.state.value.saveStatus)
        assertNull(session.state.value.message)
    }

    @Test
    fun `open with uncommitted draft restores it and keeps draft`() = runTest {
        val drafts = FakeDrafts().apply { map["p\u0000f.md"] = Draft("draft", "r0", "base") }
        val session = EditorSession(FileSaveCoordinator(FakeRemote(), drafts), drafts, this)
        session.open("p", "f.md", "server", "r1")
        assertEquals("draft", session.state.value.content)
        assertEquals("r0", session.state.value.baseRevision)
        assertEquals("已恢复未保存的编辑", session.state.value.message)
        assertNotNull(drafts.map["p\u0000f.md"]) // 草稿保留，下次仍可恢复
    }

    @Test
    fun `open with stale draft equal to server clears draft`() = runTest {
        val drafts = FakeDrafts().apply { map["p\u0000f.md"] = Draft("same", "r0", "base") }
        val session = EditorSession(FileSaveCoordinator(FakeRemote(), drafts), drafts, this)
        session.open("p", "f.md", "same", "r1")
        assertEquals("same", session.state.value.content)
        assertNull(drafts.map["p\u0000f.md"]) // 残留草稿已清
    }

    // ---- 正常保存 ----

    @Test
    fun `edit debounce saves and clears draft`() = runTest {
        val remote = FakeRemote()
        val drafts = FakeDrafts()
        val session = EditorSession(FileSaveCoordinator(remote, drafts), drafts, this)
        session.open("p", "f.md", "base", "r0")
        session.edit("base edited")
        advanceUntilIdle()
        assertEquals(SaveStatus.SAVED, session.state.value.saveStatus)
        assertEquals("base edited", remote.serverContent)
        assertTrue(drafts.map.isEmpty())
    }

    // ---- 冲突暂停自动保存 ----

    @Test
    fun `conflict pauses autosave and resolve saves`() = runTest {
        val remote = FakeRemote().apply {
            conflictOnFirstSave = true
            serverContent = "标题\n段落一\n段落二服务器\n段落三\n结尾"
            serverRevision = "r2"
        }
        val drafts = FakeDrafts()
        val session = EditorSession(FileSaveCoordinator(remote, drafts), drafts, this)
        val base = "标题\n段落一\n段落二\n段落三\n结尾"
        val ours = "标题\n段落一\n段落二本地\n段落三\n结尾"
        session.open("p", "f.md", base, "r0")
        session.edit(ours)
        advanceUntilIdle()
        assertEquals(SaveStatus.CONFLICT, session.state.value.saveStatus)
        assertNotNull(session.state.value.conflictText)
        val savesAfterConflict = remote.saveCount()

        // 冲突后继续编辑：不触发自动保存（暂停）
        session.edit(ours + " 继续编辑")
        advanceUntilIdle()
        assertEquals(SaveStatus.CONFLICT, session.state.value.saveStatus)
        assertEquals(savesAfterConflict, remote.saveCount())

        // 手动解决后才保存
        session.resolveConflict()
        advanceUntilIdle()
        assertTrue(remote.saveCount() > savesAfterConflict)
    }

    // ---- ReceiptPolicy.applySaved（迟到回包核心） ----

    @Test
    fun `applySaved keeps dirty when content changed during save`() {
        val current = EditorUiState(
            content = "v1 edited", baseRevision = "r0", baseContent = "base",
            loaded = true, saveStatus = SaveStatus.SAVING,
        )
        val r = ReceiptPolicy.applySaved("v1", "r1", current)
        assertEquals(SaveStatus.IDLE, r.state.saveStatus)
        assertEquals("v1 edited", r.state.content) // 当前编辑不被旧回执覆盖
        assertEquals("r1", r.state.baseRevision)
        assertEquals("v1", r.state.baseContent) // base 是已确认的快照
        assertNull(r.state.message)
        assertTrue(r.reschedule)
    }

    @Test
    fun `applySaved marks saved when no concurrent edit`() {
        val current = EditorUiState(
            content = "v1", baseRevision = "r0", baseContent = "base",
            loaded = true, saveStatus = SaveStatus.SAVING,
        )
        val r = ReceiptPolicy.applySaved("v1", "r1", current)
        assertEquals(SaveStatus.SAVED, r.state.saveStatus)
        assertEquals("v1", r.state.content)
        assertFalse(r.reschedule)
    }

    // ---- ReceiptPolicy.applyMerged ----

    @Test
    fun `applyMerged adopts merged text when no concurrent edit`() {
        val current = EditorUiState(
            content = "v1", baseRevision = "r0", baseContent = "base",
            loaded = true, saveStatus = SaveStatus.SAVING,
        )
        val outcome = SaveOutcome.MergedAndSaved("r1", "merged text")
        val r = ReceiptPolicy.applyMerged("v1", outcome, current)
        assertEquals(SaveStatus.MERGED, r.state.saveStatus)
        assertEquals("merged text", r.state.content)
        assertEquals(1, r.state.contentEpoch)
        assertFalse(r.reschedule)
    }

    @Test
    fun `applyMerged re-applies concurrent edit without losing remote change`() {
        val snapshot = "标题\n段落二\n段落三\n结尾"
        val current = EditorUiState(
            content = "标题\n段落二本地\n段落三\n结尾", baseRevision = "r0",
            baseContent = snapshot, loaded = true, saveStatus = SaveStatus.SAVING,
        )
        val mergedText = "标题\n段落二\n段落三服务器\n结尾" // 服务器改段落三，与期间编辑不重叠
        val r = ReceiptPolicy.applyMerged(snapshot, SaveOutcome.MergedAndSaved("r1", mergedText), current)
        assertTrue(r.reschedule)
        assertEquals(SaveStatus.IDLE, r.state.saveStatus)
        assertTrue(r.state.content.contains("段落二本地"))
        assertTrue(r.state.content.contains("段落三服务器"))
    }

    @Test
    fun `applyMerged re-applies concurrent edit to conflict when overlapping`() {
        val snapshot = "标题\n段落二\n结尾"
        val current = EditorUiState(
            content = "标题\n段落二本地\n结尾", baseRevision = "r0",
            baseContent = snapshot, loaded = true, saveStatus = SaveStatus.SAVING,
        )
        val mergedText = "标题\n段落二服务器\n结尾" // 服务器改同一行 → 重叠冲突
        val r = ReceiptPolicy.applyMerged(snapshot, SaveOutcome.MergedAndSaved("r1", mergedText), current)
        assertFalse(r.reschedule)
        assertEquals(SaveStatus.CONFLICT, r.state.saveStatus)
        assertNotNull(r.state.conflictText)
        assertTrue(r.state.content.contains("<<<<<<< 本地修改"))
    }
}
