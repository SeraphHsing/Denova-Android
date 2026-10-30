package com.denova.novara

import com.denova.novara.R
import com.denova.novara.core.i18n.UiText
import com.denova.novara.core.network.DenovaApiException
import com.denova.novara.core.network.ProjectFileDocument
import com.denova.novara.core.network.SaveFileResponse
import com.denova.novara.core.persistence.Draft
import com.denova.novara.core.persistence.DraftKey
import com.denova.novara.core.persistence.DraftStorage
import com.denova.novara.core.save.AutosaveConflictRecord
import com.denova.novara.core.save.ConflictArchive
import com.denova.novara.core.save.ConflictStorage
import com.denova.novara.core.save.PreservedConflict
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
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
        val savedBaseRevisions = mutableListOf<String>()

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
            savedBaseRevisions += baseRevision
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

    /** 可挂起的远端：保存进入后停在 [gate]，用于构造「回执迟到」的时序。 */
    private class GatedRemote : FileRemote {
        private val gate = CompletableDeferred<Unit>()
        val saveStarted = CompletableDeferred<Unit>()
        var saveCount = 0
        val savedPaths = mutableListOf<String>()

        fun release() = gate.complete(Unit)

        override suspend fun readFile(projectId: String, path: String) =
            ProjectFileDocument(path, "server", "r9")

        override suspend fun saveFile(
            projectId: String,
            path: String,
            content: String,
            baseRevision: String,
        ): SaveFileResponse {
            if (!saveStarted.isCompleted) saveStarted.complete(Unit)
            gate.await()
            saveCount++
            savedPaths += path
            return SaveFileResponse(projectId, path, "rn$saveCount", true, "saved")
        }
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
        assertEquals(UiText.of(R.string.editor_restored_draft), session.state.value.message)
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
        session.keepLocalVersion()
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

    // ---- flush（切换书籍 / 导航前保存草稿） ----

    @Test
    fun `flush without edits returns true and skips save`() = runTest {
        val remote = FakeRemote()
        val drafts = FakeDrafts()
        val session = EditorSession(FileSaveCoordinator(remote, drafts), drafts, this)
        session.open("p", "f.md", "base", "r0")
        assertTrue(session.flush())
        assertEquals(0, remote.saveCount())
    }

    @Test
    fun `flush with edits saves and returns true`() = runTest {
        val remote = FakeRemote()
        val drafts = FakeDrafts()
        val session = EditorSession(FileSaveCoordinator(remote, drafts), drafts, this)
        session.open("p", "f.md", "base", "r0")
        session.edit("base edited")
        assertTrue(session.flush())
        assertEquals("base edited", remote.serverContent)
        assertEquals(SaveStatus.SAVED, session.state.value.saveStatus)
    }

    @Test
    fun `flush during conflict returns false and does not resave`() = runTest {
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
        val savesBefore = remote.saveCount()
        assertFalse(session.flush())
        assertEquals(savesBefore, remote.saveCount())
    }

    // ---- G3-d 切文件：取消在途保存 + 回执作用域校验 ----

    @Test
    fun `open new file cancels in-flight save`() = runTest {
        val remote = GatedRemote()
        val drafts = FakeDrafts()
        val session = EditorSession(FileSaveCoordinator(remote, drafts), drafts, this, debounceMillis = 50)
        session.open("p", "a.md", "A0", "rA0")
        session.edit("A1")
        advanceTimeBy(60) // 防抖到期，保存进入远端并挂起
        remote.saveStarted.await()

        session.open("p", "b.md", "B0", "rB0") // 切文件：取消在途保存
        remote.release()
        advanceUntilIdle()

        assertEquals("b.md", session.state.value.filePath)
        assertEquals("B0", session.state.value.content)
        assertEquals("rB0", session.state.value.baseRevision)
        assertEquals(SaveStatus.IDLE, session.state.value.saveStatus)
        assertNull(session.state.value.message) // 取消不伪装成「保存失败」
        assertEquals(0, remote.saveCount) // 被取消的保存在 gate 处终止
    }

    @Test
    fun `late receipt from previous file is discarded`() = runTest {
        val remote = GatedRemote()
        val drafts = FakeDrafts()
        val session = EditorSession(FileSaveCoordinator(remote, drafts), drafts, this, debounceMillis = 50)
        session.open("p", "a.md", "A0", "rA0")
        session.edit("A1")

        // flush 直接在本协程里保存，不受 open() 取消 saveJob 影响 → 只能靠作用域校验拦下回执。
        var flushResult: Boolean? = null
        val flushJob = launch { flushResult = session.flush() }
        remote.saveStarted.await()

        session.open("p", "b.md", "B0", "rB0")
        remote.release()
        flushJob.join()
        advanceUntilIdle()

        assertEquals(1, remote.saveCount) // 服务器确实写成功
        assertEquals(false, flushResult) // 但调用方不能把旧文件结果当成功
        assertEquals("b.md", session.state.value.filePath)
        assertEquals("B0", session.state.value.content) // 新文件正文未被旧回执覆盖
        assertEquals("rB0", session.state.value.baseRevision) // revision 归属未被污染
        assertEquals(SaveStatus.IDLE, session.state.value.saveStatus)
        assertNull(session.state.value.message)
    }

    @Test
    fun `save after switching file applies to new file`() = runTest {
        val remote = GatedRemote().apply { release() }
        val drafts = FakeDrafts()
        val session = EditorSession(FileSaveCoordinator(remote, drafts), drafts, this, debounceMillis = 50)
        session.open("p", "a.md", "A0", "rA0")
        session.open("p", "b.md", "B0", "rB0")
        session.edit("B1")
        advanceUntilIdle()

        assertEquals(SaveStatus.SAVED, session.state.value.saveStatus)
        assertEquals("rn1", session.state.value.baseRevision)
        assertEquals(listOf("b.md"), remote.savedPaths) // 只写新文件，不带旧文件残留
    }

    @Test
    fun `reopening same file bumps content epoch so the editor rebuilds its text`() = runTest {
        val remote = FakeRemote()
        val drafts = FakeDrafts()
        val session = EditorSession(FileSaveCoordinator(remote, drafts), drafts, this)
        session.open("p", "f.md", "old", "r0")
        val firstEpoch = session.state.value.contentEpoch
        remote.serverContent = "new from server"
        remote.serverRevision = "r9"
        session.open("p", "f.md", "new from server", "r9")
        assertEquals("new from server", session.state.value.content)
        assertTrue(session.state.value.contentEpoch > firstEpoch) // 界面据此重置输入框
    }

    // ---- WRI-011 手动保存入口 ----

    @Test
    fun `manual save bypasses debounce and does not save twice`() = runTest {
        val remote = FakeRemote()
        val drafts = FakeDrafts()
        val session = EditorSession(FileSaveCoordinator(remote, drafts), drafts, this)
        session.open("p", "f.md", "base", "r0")
        session.edit("base edited")
        session.saveNow()
        advanceTimeBy(10) // 远小于 800ms 防抖
        assertEquals(1, remote.saveCount())
        assertEquals("base edited", remote.serverContent)
        assertEquals(SaveStatus.SAVED, session.state.value.saveStatus)

        advanceUntilIdle() // 防抖计时器这段时间本会到期
        assertEquals(1, remote.saveCount()) // 没有重复 PUT
    }

    @Test
    fun `manual save on clean file does not write`() = runTest {
        val remote = FakeRemote()
        val drafts = FakeDrafts()
        val session = EditorSession(FileSaveCoordinator(remote, drafts), drafts, this)
        session.open("p", "f.md", "base", "r0")
        session.saveNow()
        advanceUntilIdle()
        assertEquals(0, remote.saveCount())
        assertEquals(SaveStatus.SAVED, session.state.value.saveStatus) // 仍给出「已保存」反馈
    }

    @Test
    fun `edits during in-flight save stay dirty and the latest text is saved`() = runTest {
        val remote = GatedRemote()
        val drafts = FakeDrafts()
        val session = EditorSession(FileSaveCoordinator(remote, drafts), drafts, this, debounceMillis = 50)
        session.open("p", "a.md", "A0", "rA0")
        session.edit("A1")
        session.saveNow()
        remote.saveStarted.await()

        // 保存仍在途时继续输入：不得中断在途请求，回执只确认快照 A1
        session.edit("A12")
        remote.release()
        advanceUntilIdle()

        assertEquals(2, remote.saveCount) // 回执后重新排一次，把最新内容写掉
        assertEquals("A12", session.state.value.content)
        assertEquals("rn2", session.state.value.baseRevision)
        assertEquals(SaveStatus.SAVED, session.state.value.saveStatus)
    }

    @Test
    fun `conflict rebases onto server content so resolved save writes`() = runTest {
        val remote = FakeRemote().apply {
            conflictOnFirstSave = true
            serverContent = "标题\n段落一\n段落二服务器\n段落三\n结尾"
            serverRevision = "r2"
        }
        val drafts = FakeDrafts()
        val session = EditorSession(FileSaveCoordinator(remote, drafts), drafts, this)
        session.open("p", "f.md", "标题\n段落一\n段落二\n段落三\n结尾", "r0")
        session.edit("标题\n段落一\n段落二本地\n段落三\n结尾")
        advanceUntilIdle()

        assertEquals(SaveStatus.CONFLICT, session.state.value.saveStatus)
        assertTrue(session.state.value.content.contains("<<<<<<<"))
        // base 必须是服务器内容，不能用带标记的合并结果冒充（否则解决后保存会被当成无改动跳过）
        assertEquals("标题\n段落一\n段落二服务器\n段落三\n结尾", session.state.value.baseContent)
        assertEquals("r2", session.state.value.baseRevision)

        session.edit("标题\n段落一\n双方合并结果\n段落三\n结尾")
        session.keepLocalVersion()
        advanceUntilIdle()

        assertEquals("标题\n段落一\n双方合并结果\n段落三\n结尾", remote.serverContent)
        assertEquals(SaveStatus.SAVED, session.state.value.saveStatus)
    }

    @Test
    fun `manual save during conflict waits for resolution`() = runTest {
        val remote = FakeRemote().apply {
            conflictOnFirstSave = true
            serverContent = "标题\n段落一\n段落二服务器\n段落三\n结尾"
            serverRevision = "r2"
        }
        val drafts = FakeDrafts()
        val session = EditorSession(FileSaveCoordinator(remote, drafts), drafts, this)
        session.open("p", "f.md", "标题\n段落一\n段落二\n段落三\n结尾", "r0")
        session.edit("标题\n段落一\n段落二本地\n段落三\n结尾")
        advanceUntilIdle()
        assertEquals(SaveStatus.CONFLICT, session.state.value.saveStatus)
        val savesAfterConflict = remote.saveCount()

        // 冲突态下编辑只改内容，不自动保存
        session.edit("标题\n段落一\n段落二本地已解决\n段落三\n结尾")
        advanceUntilIdle()
        assertEquals(savesAfterConflict, remote.saveCount())
        session.saveNow()
        advanceUntilIdle()
        assertEquals(savesAfterConflict, remote.saveCount()) // 冲突未解决不落盘

        session.keepLocalVersion()
        advanceUntilIdle()
        assertTrue(remote.saveCount() > savesAfterConflict)
        assertEquals("标题\n段落一\n段落二本地已解决\n段落三\n结尾", remote.serverContent)
    }

    // ---- WRI-008 输入法组合态 ----

    @Test
    fun `editing during IME composition does not autosave`() = runTest {
        val remote = FakeRemote()
        val drafts = FakeDrafts()
        val session = EditorSession(FileSaveCoordinator(remote, drafts), drafts, this, debounceMillis = 50)
        session.open("p", "f.md", "base", "r0")

        // 拼音候选未落定：只更新本地正文，不排自动保存，避免把半成品写进服务端。
        session.edit("base 拼", composing = true)
        advanceTimeBy(500)
        assertEquals(0, remote.saveCount())
        assertEquals("base 拼", session.state.value.content)

        // 组合结束的第一次回调恢复自动保存。
        session.edit("base 拼音", composing = false)
        advanceUntilIdle()
        assertEquals(1, remote.saveCount())
        assertEquals("base 拼音", remote.serverContent)
    }

    // ---- WRI-013 冲突材料归档 + 保留本地 / 加载外部 ----

    /** 记录归档调用的 fake：断言四份正文与作用域都进了冲突材料。 */
    private class RecordingArchive : ConflictArchive {
        val records = mutableListOf<AutosaveConflictRecord>()
        var fail = false

        override suspend fun preserve(record: AutosaveConflictRecord): PreservedConflict {
            if (fail) throw IllegalStateException("journal unavailable")
            records += record
            return PreservedConflict("autosave-conflict-test", "/data/conflicts/autosave-conflict-test.json", ConflictStorage.SERVER)
        }
    }

    private suspend fun conflictSession(
        remote: FakeRemote,
        drafts: FakeDrafts,
        archive: ConflictArchive?,
        scope: kotlinx.coroutines.CoroutineScope,
    ): EditorSession {
        val session = EditorSession(FileSaveCoordinator(remote, drafts, archive), drafts, scope)
        session.open("p", "f.md", "标题\n段落一\n段落二\n段落三\n结尾", "r0")
        session.edit("标题\n段落一\n段落二本地\n段落三\n结尾")
        return session
    }

    @Test
    fun `conflict archives all three sides and exposes recovery id`() = runTest {
        val remote = FakeRemote().apply {
            conflictOnFirstSave = true
            serverContent = "标题\n段落一\n段落二服务器\n段落三\n结尾"
            serverRevision = "r2"
        }
        val archive = RecordingArchive()
        val session = conflictSession(remote, FakeDrafts(), archive, this)
        advanceUntilIdle()

        val conflict = session.state.value.conflict
        assertNotNull(conflict)
        assertEquals("autosave-conflict-test", conflict?.recoveryId)

        val record = archive.records.single()
        assertEquals("workspace_file", record.resource)
        assertEquals("p", record.scope)
        assertEquals("f.md", record.id)
        assertEquals("merge_non_overlap_prefer_local", record.strategy)
        assertEquals("r0", record.base.revision)
        assertEquals("标题\n段落一\n段落二\n段落三\n结尾", record.base.value)
        assertEquals("标题\n段落一\n段落二本地\n段落三\n结尾", record.local.value)
        assertEquals("r2", record.external.revision)
        assertEquals("标题\n段落一\n段落二服务器\n段落三\n结尾", record.external.value)
        assertTrue(record.merged.value.contains("<<<<<<<"))
        assertEquals(listOf(emptyList<String>()), record.conflictPaths)
    }

    @Test
    fun `archive failure still leaves the conflict choice available`() = runTest {
        val remote = FakeRemote().apply {
            conflictOnFirstSave = true
            serverContent = "标题\n段落一\n段落二服务器\n段落三\n结尾"
            serverRevision = "r2"
        }
        val archive = RecordingArchive().apply { fail = true }
        val session = conflictSession(remote, FakeDrafts(), archive, this)
        advanceUntilIdle()

        assertEquals(SaveStatus.CONFLICT, session.state.value.saveStatus)
        assertNotNull(session.state.value.conflict)
        assertNull(session.state.value.conflict?.recoveryId)
    }

    @Test
    fun `keep local writes merged text against the server revision`() = runTest {
        val remote = FakeRemote().apply {
            conflictOnFirstSave = true
            serverContent = "标题\n段落一\n段落二服务器\n段落三\n结尾"
            serverRevision = "r2"
        }
        val session = conflictSession(remote, FakeDrafts(), RecordingArchive(), this)
        advanceUntilIdle()

        session.keepLocalVersion()
        advanceUntilIdle()

        assertNull(session.state.value.conflict)
        assertEquals(SaveStatus.SAVED, session.state.value.saveStatus)
        // 冲突后第一次真正落盘的 PUT 必须带服务器 revision，不能拿旧 base 盲覆盖。
        assertEquals("r2", remote.savedBaseRevisions.first())
        assertEquals("r2", remote.serverRevision)
        assertEquals(2, remote.saveCount())
    }

    @Test
    fun `load external replaces the body and never pushes the local edit`() = runTest {
        val remote = FakeRemote().apply {
            conflictOnFirstSave = true
            serverContent = "标题\n段落一\n段落二服务器\n段落三\n结尾"
            serverRevision = "r2"
        }
        val drafts = FakeDrafts()
        val session = conflictSession(remote, drafts, RecordingArchive(), this)
        advanceUntilIdle()
        val savesAfterConflict = remote.saveCount()

        session.loadExternalVersion()
        advanceUntilIdle()

        assertEquals("标题\n段落一\n段落二服务器\n段落三\n结尾", session.state.value.content)
        assertEquals("标题\n段落一\n段落二服务器\n段落三\n结尾", session.state.value.baseContent)
        assertEquals("r2", session.state.value.baseRevision)
        assertEquals(SaveStatus.IDLE, session.state.value.saveStatus)
        assertNull(session.state.value.conflict)
        // 丢弃本地改动：不发 PUT，草稿也清掉，避免下次打开又被当成未提交编辑恢复。
        assertEquals(savesAfterConflict, remote.saveCount())
        assertNull(drafts.loadDraft("p\u0000f.md"))

        // 之后继续编辑仍走正常自动保存。
        session.edit("标题\n段落一\n段落二服务器改\n段落三\n结尾")
        advanceUntilIdle()
        assertTrue(remote.saveCount() > savesAfterConflict)
    }

    // ---- 撤销 / 重做（WRI-014） ----

    @Test
    fun `undo restores body and caret, redo brings the edit back`() = runTest {
        val drafts = FakeDrafts()
        var clock = 0L
        val remote = FakeRemote()
        val session = EditorSession(FileSaveCoordinator(remote, drafts), drafts, this, now = { clock })
        session.open("p", "f.md", "abc", "r0")
        // 打开即基线：没有可撤销的历史。
        assertFalse(session.state.value.undoAvailable)

        clock += 5_000
        session.edit("abcd", caret = 3)
        clock += 5_000
        session.edit("abcde", caret = 4)
        assertEquals("abcde", session.state.value.content)
        assertTrue(session.state.value.undoAvailable)

        clock += 5_000
        // 两次编辑之间超过合并窗口 → 每一步独立可撤销。
        assertTrue(session.undo())
        assertEquals("abcd", session.state.value.content)
        assertEquals(3, session.state.value.caret)
        assertTrue(session.state.value.redoAvailable)

        assertTrue(session.undo())
        assertEquals("abc", session.state.value.content)
        assertEquals(0, session.state.value.caret)

        assertTrue(session.redo())
        assertEquals("abcd", session.state.value.content)
        assertEquals(3, session.state.value.caret)
    }

    @Test
    fun `continuous typing collapses into one undo step`() = runTest {
        val drafts = FakeDrafts()
        var clock = 0L
        val session = EditorSession(FileSaveCoordinator(FakeRemote(), drafts), drafts, this, now = { clock })
        session.open("p", "f.md", "a", "r0")

        // 三次按键都在合并窗口内（700ms）：只应留下「a」这一个基线。
        clock += 100
        session.edit("ab", caret = 2)
        clock += 100
        session.edit("abc", caret = 3)
        clock += 100
        session.edit("abcd", caret = 4)

        assertTrue(session.undo())
        assertEquals("a", session.state.value.content)
        // 一步退回突发开始，再撤已无内容。
        assertFalse(session.state.value.undoAvailable)
        assertFalse(session.undo())
    }

    @Test
    fun `load external clears undo history and resets caret`() = runTest {
        val remote = FakeRemote().apply {
            conflictOnFirstSave = true
            serverContent = "标题\n段落一\n段落二服务器\n段落三\n结尾"
            serverRevision = "r2"
        }
        val session = conflictSession(remote, FakeDrafts(), RecordingArchive(), this)
        advanceUntilIdle()
        assertTrue(session.state.value.undoAvailable)

        session.loadExternalVersion()
        advanceUntilIdle()

        // 基线 loadExternalVersion 的 resetHistory：被丢弃的本地改动不得再通过撤销回到编辑区。
        assertFalse(session.state.value.undoAvailable)
        assertFalse(session.state.value.redoAvailable)
        assertEquals(0, session.state.value.caret)
        assertFalse(session.undo())
        assertEquals("标题\n段落一\n段落二服务器\n段落三\n结尾", session.state.value.content)
    }

    // ---- 草稿的服务器作用域（AUTH-006）----

    @Test
    fun `draft under the same origin is restored`() = runTest {
        val drafts = FakeDrafts().apply {
            map["http://a\u0000p\u0000f.md"] = Draft("draftA", "r0", "base")
        }
        val session = EditorSession(
            FileSaveCoordinator(FakeRemote(), drafts), drafts, this, origin = "http://a",
        )
        session.open("p", "f.md", "server", "r1")
        assertEquals("draftA", session.state.value.content)
    }

    @Test
    fun `draft from another server is not restored`() = runTest {
        // 同一 projectId + path 在服务器 A 上有草稿，连到 B 时不能串过来。
        val drafts = FakeDrafts().apply {
            map["http://a\u0000p\u0000f.md"] = Draft("draftA", "r0", "base")
        }
        val session = EditorSession(
            FileSaveCoordinator(FakeRemote(), drafts), drafts, this, origin = "http://b",
        )
        session.open("p", "f.md", "server", "r1")
        assertEquals("server", session.state.value.content)
        assertNotNull(drafts.map["http://a\u0000p\u0000f.md"])
    }

    @Test
    fun `legacy draft without server scope is restored and migrated once`() = runTest {
        // 升级前保存的草稿只有 `projectId\0path`，没有服务器归属：首次打开恢复到当前 origin 并迁移。
        val drafts = FakeDrafts().apply {
            map["p\u0000f.md"] = Draft("legacy", "r0", "base")
        }
        val session = EditorSession(
            FileSaveCoordinator(FakeRemote(), drafts), drafts, this, origin = "http://a",
        )
        session.open("p", "f.md", "server", "r1")
        assertEquals("legacy", session.state.value.content)
        // 迁移完成：旧 key 删除、新 key 存在，所以另一台服务器不会再读到它。
        assertNull(drafts.map["p\u0000f.md"])
        assertNotNull(drafts.map["http://a\u0000p\u0000f.md"])
    }

    @Test
    fun `migrated legacy draft does not leak to another server`() = runTest {
        val drafts = FakeDrafts().apply {
            map["p\u0000f.md"] = Draft("legacy", "r0", "base")
        }
        val onA = EditorSession(
            FileSaveCoordinator(FakeRemote(), drafts), drafts, this, origin = "http://a",
        )
        onA.open("p", "f.md", "server", "r1")
        assertEquals("legacy", onA.state.value.content)

        val onB = EditorSession(
            FileSaveCoordinator(FakeRemote(), drafts), drafts, this, origin = "http://b",
        )
        onB.open("p", "f.md", "server", "r1")
        assertEquals("server", onB.state.value.content)
        // B 不能留下迁移产物（否则下次它自己会变成「B 的草稿」）。
        assertNull(drafts.map["http://b\u0000p\u0000f.md"])
    }

    @Test
    fun `autosave writes draft under the server scoped key`() = runTest {
        // 保存编排与 EditorSession 必须用同一个 key：协调器自己拼旧 key 的话，
        // 「A 上保存失败留下草稿」会以无作用域的 key 落盘，切到 B 后被迁移过去（AUTH-006 失效）。
        val remote = FakeRemote().apply {
            conflictOnFirstSave = true
            serverContent = "标题\n段落一\n段落二服务器\n段落三\n结尾"
            serverRevision = "r2"
        }
        val drafts = FakeDrafts()
        val coordinator = FileSaveCoordinator(remote, drafts) { projectId, path ->
            DraftKey.of("http://a", projectId, path)
        }
        val session = EditorSession(coordinator, drafts, this, origin = "http://a")
        val base = "标题\n段落一\n段落二\n段落三\n结尾"
        val ours = "标题\n段落一\n段落二本地\n段落三\n结尾"
        session.open("p", "f.md", base, "r0")
        session.edit(ours)
        advanceUntilIdle()

        assertNull(drafts.map["p\u0000f.md"])
        val scoped = drafts.map["http://a\u0000p\u0000f.md"]
        assertNotNull(scoped)
        assertEquals(ours, scoped?.content)
    }

    @Test
    fun `scoped draft supersedes stale legacy key`() = runTest {
        // 上次迁移在 save 与 clear 之间被杀，旧 key 残留：新 key 命中时要顺手清掉旧 key。
        val drafts = FakeDrafts().apply {
            map["http://a\u0000p\u0000f.md"] = Draft("scoped", "r0", "base")
            map["p\u0000f.md"] = Draft("scoped", "r0", "base")
        }
        val session = EditorSession(
            FileSaveCoordinator(FakeRemote(), drafts), drafts, this, origin = "http://a",
        )
        session.open("p", "f.md", "server", "r1")
        assertEquals("scoped", session.state.value.content)
        assertNull(drafts.map["p\u0000f.md"])
    }
}
