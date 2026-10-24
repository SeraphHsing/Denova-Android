package com.denova.novara

import com.denova.novara.core.network.DenovaApiException
import com.denova.novara.core.network.ProjectFileDocument
import com.denova.novara.core.network.SaveFileResponse
import com.denova.novara.core.persistence.Draft
import com.denova.novara.core.persistence.DraftStorage
import com.denova.novara.core.save.FileRemote
import com.denova.novara.core.save.FileSaveCoordinator
import com.denova.novara.core.save.SaveOutcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileSaveCoordinatorTest {

    private class FakeRemote : FileRemote {
        var serverContent = ""
        var serverRevision = "r0"
        var saveCount = 0
        var conflictOnFirstSave = false

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
    }

    private class FakeDrafts : DraftStorage {
        val map = mutableMapOf<String, Draft>()
        override suspend fun saveDraft(fileKey: String, draft: Draft) { map[fileKey] = draft }
        override suspend fun clearDraft(fileKey: String) { map.remove(fileKey) }
    }

    @Test
    fun `no conflict saves directly and clears draft`() = runBlocking {
        val remote = FakeRemote()
        val drafts = FakeDrafts()
        val coordinator = FileSaveCoordinator(remote, drafts)
        val outcome = coordinator.save("p", "chapter.md", "r0", "base", "ours")
        assertTrue(outcome is SaveOutcome.Saved)
        assertEquals("r1", (outcome as SaveOutcome.Saved).revision)
        assertEquals(1, remote.saveCount)
        assertTrue(drafts.map.isEmpty())
    }

    @Test
    fun `conflict with clean merge retries and saves`() = runBlocking {
        val remote = FakeRemote().apply {
            conflictOnFirstSave = true
            serverContent = "标题\n段落一\n段落二\n段落三服务器改\n结尾"
            serverRevision = "r2"
        }
        val drafts = FakeDrafts()
        val coordinator = FileSaveCoordinator(remote, drafts)
        val base = "标题\n段落一\n段落二\n段落三\n结尾"
        val ours = "标题\n段落一\n段落二本地改\n段落三\n结尾"
        val outcome = coordinator.save("p", "chapter.md", "r0", base, ours)
        assertTrue(outcome is SaveOutcome.MergedAndSaved)
        assertEquals(2, remote.saveCount)
        // 合并结果必须回传：两端修改都在，且与写回服务端的内容一致。
        val merged = (outcome as SaveOutcome.MergedAndSaved).mergedText
        assertTrue(merged.contains("段落二本地改"))
        assertTrue(merged.contains("段落三服务器改"))
        assertEquals(merged, remote.serverContent)
    }

    @Test
    fun `clean merge result must be adopted so the next save keeps remote edits`() = runBlocking {
        val remote = FakeRemote().apply {
            conflictOnFirstSave = true
            serverContent = "标题\n段落一\n段落二\n段落三服务器改\n结尾"
            serverRevision = "r2"
        }
        val drafts = FakeDrafts()
        val coordinator = FileSaveCoordinator(remote, drafts)
        val base = "标题\n段落一\n段落二\n段落三\n结尾"
        val ours = "标题\n段落一\n段落二本地改\n段落三\n结尾"
        val first = coordinator.save("p", "chapter.md", "r0", base, ours) as SaveOutcome.MergedAndSaved

        // 模拟界面采纳合并结果后的再次保存：服务端内容不应被本地旧文本覆盖。
        coordinator.save("p", "chapter.md", first.revision, first.mergedText, first.mergedText)
        assertTrue(remote.serverContent.contains("段落三服务器改"))
        assertTrue(remote.serverContent.contains("段落二本地改"))
    }

    @Test
    fun `conflict with overlapping changes returns conflict material`() = runBlocking {
        val remote = FakeRemote().apply {
            conflictOnFirstSave = true
            serverContent = "标题\n段落一\n段落二服务器\n段落三\n结尾"
            serverRevision = "r2"
        }
        val drafts = FakeDrafts()
        val coordinator = FileSaveCoordinator(remote, drafts)
        val base = "标题\n段落一\n段落二\n段落三\n结尾"
        val ours = "标题\n段落一\n段落二本地\n段落三\n结尾"
        val outcome = coordinator.save("p", "chapter.md", "r0", base, ours)
        assertTrue(outcome is SaveOutcome.Conflict)
        val conflict = outcome as SaveOutcome.Conflict
        assertEquals(1, conflict.conflictCount)
        assertTrue(conflict.mergedText.contains("<<<<<<< 本地修改"))
        assertEquals("r2", conflict.serverRevision)
    }
}
