package com.denova.novara

import com.denova.novara.core.network.BranchesResponse
import com.denova.novara.core.network.SendInteractiveChatRequest
import com.denova.novara.core.network.UpdateBranchPlanResult
import com.denova.novara.core.network.UpdateTurnNarrativeRequest
import com.denova.novara.core.stream.NarrativeBuilder
import com.denova.novara.core.stream.StoryPaging
import com.denova.novara.core.stream.StoryTurnText
import com.denova.novara.core.stream.TurnEvent
import com.denova.novara.core.stream.TurnVersion
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 历史分页、轮次锚点与分支契约（P4-A / GAM-007、GAM-008、GAM-009）。 */
class StoryPagingTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `narrative blocks start a new block per turn`() {
        val builder = NarrativeBuilder()
        builder.resetTurns(listOf("t1" to "> a\n\nfirst", "t2" to "> b\n\nsecond"))

        assertEquals(0, builder.blockIndexFor("t1"))
        assertEquals(1, builder.blockIndexFor("t2"))
        assertEquals(listOf("> a\n\nfirst", "> b\n\nsecond"), builder.snapshot)
    }

    @Test
    fun `empty turn text creates no anchor`() {
        val builder = NarrativeBuilder()
        builder.resetTurns(listOf("t1" to "text", "t2" to "   "))

        assertEquals(0, builder.blockIndexFor("t1"))
        assertNull(builder.blockIndexFor("t2"))
    }

    @Test
    fun `long turn spans blocks and next turn starts after them`() {
        val builder = NarrativeBuilder(maxBlockChars = 10)
        builder.resetTurns(listOf("t1" to "0123456789ABCDEFGHIJ", "t2" to "next"))

        assertEquals(0, builder.blockIndexFor("t1"))
        // 第一节占 2 块（10 + 10），第二轮从第 3 块（索引 2）开始。
        assertEquals(2, builder.blockIndexFor("t2"))
        assertTrue(builder.snapshot.size >= 3)
    }

    @Test
    fun `merge older dedupes by id and keeps old to new order`() {
        val older = listOf(turn("t1"), turn("t2"))
        val current = listOf(turn("t2"), turn("t3"))

        val merged = StoryPaging.mergeOlder(older, current)

        assertEquals(listOf("t1", "t2", "t3"), merged.map { it.id })
    }

    @Test
    fun `anchors number by first number and skip missing blocks`() {
        val turns = listOf(turn("t1", "开门"), turn("t2", "前进"))

        val anchors = StoryPaging.anchors(turns, firstNumber = 3) { id ->
            if (id == "t2") 5 else null
        }

        assertEquals(1, anchors.size)
        assertEquals("t2", anchors[0].turnId)
        assertEquals(4, anchors[0].number)
        assertEquals(5, anchors[0].blockIndex)
        assertEquals("前进", anchors[0].label)
    }

    @Test
    fun `turn text hides source-only user prompts`() {
        val internal = TurnEvent(id = "t1", user = "[Source: opening config]", narrative = "正文")
        assertEquals("正文", StoryTurnText.of(internal))

        val normal = TurnEvent(id = "t2", user = "推门", narrative = "门开了")
        assertEquals("> 推门\n\n门开了", StoryTurnText.of(normal))
    }

    @Test
    fun `version target walks the version list and stops at edges`() {
        val turn = TurnEvent(
            id = "t2",
            user = "前进",
            narrative = "n",
            versions = listOf(
                TurnVersion(turnId = "t2v1"),
                TurnVersion(turnId = "t2v2", current = true),
                TurnVersion(turnId = "t2v3"),
            ),
        )

        assertEquals(2, StoryPaging.currentVersionNumber(turn))
        assertEquals("t2v1", StoryPaging.versionTarget(turn, -1))
        assertEquals("t2v3", StoryPaging.versionTarget(turn, 1))

        val newest = turn.copy(versions = listOf(
            TurnVersion(turnId = "a"),
            TurnVersion(turnId = "b", current = true),
        ))
        assertNull(StoryPaging.versionTarget(newest, 1))
        // 只有一个版本时不提供切换。
        assertNull(StoryPaging.versionTarget(TurnEvent(id = "t9"), 1))
    }

    @Test
    fun `anchors expose version count and regenerate eligibility`() {
        val withVersions = TurnEvent(
            id = "t1",
            user = "推门",
            narrative = "n",
            versions = listOf(TurnVersion(turnId = "t1"), TurnVersion(turnId = "t1b", current = true)),
        )
        val opening = TurnEvent(id = "t0", user = "[Source: opening]", narrative = "n")

        val anchors = StoryPaging.anchors(listOf(withVersions, opening), firstNumber = 1) { 0 }

        assertEquals(2, anchors.size)
        assertEquals(2, anchors[0].versionCount)
        assertEquals(2, anchors[0].versionNumber)
        assertTrue(anchors[0].canRegenerate)
        // 内部提示（开场注入）不作为可重放输入。
        assertNull(StoryPaging.replayInput(opening))
        assertTrue(!anchors[1].canRegenerate)
    }

    @Test
    fun `chat request carries regenerate target only when set`() {
        val plain = SendInteractiveChatRequest(commandId = "c1", storyId = "s1", message = "hi")
        val jsonText = json.encodeToString(SendInteractiveChatRequest.serializer(), plain)
        assertTrue(!jsonText.contains("regenerate_from_turn_id"))

        val regen = plain.copy(regenerateFromTurnId = "t7")
        val regenText = json.encodeToString(SendInteractiveChatRequest.serializer(), regen)
        assertTrue(regenText.contains("\"regenerate_from_turn_id\":\"t7\""))
    }

    @Test
    fun `narrative edit request carries expected text`() {
        val request = UpdateTurnNarrativeRequest(
            branchId = "main",
            narrative = "新正文",
            expectedNarrative = "旧正文",
        )
        val text = json.encodeToString(UpdateTurnNarrativeRequest.serializer(), request)

        assertTrue(text.contains("\"branch_id\":\"main\""))
        assertTrue(text.contains("\"narrative\":\"新正文\""))
        assertTrue(text.contains("\"expected_narrative\":\"旧正文\""))
    }

    @Test
    fun `branch list and plan update parse server shapes`() {
        val branches = json.decodeFromString(
            BranchesResponse.serializer(),
            """{"branches":[{"id":"main","head":"t3","current":true,"from_event":"t1","created_at":"2026-10-27T10:00:00Z"}]}""",
        )
        assertEquals(1, branches.branches.size)
        assertTrue(branches.branches[0].current)
        assertEquals("t1", branches.branches[0].fromEvent)
        assertEquals("t3", branches.branches[0].head)

        val plan = json.decodeFromString(
            UpdateBranchPlanResult.serializer(),
            """{"branch_plan":{"markdown":"# 方案","revision":"r2"},"context_revision":7}""",
        )
        assertEquals("# 方案", plan.branchPlan.markdown)
        assertEquals("r2", plan.branchPlan.revision)
        assertEquals(7L, plan.contextRevision)
    }

    private fun turn(id: String, user: String = id) = TurnEvent(id = id, user = user, narrative = "n-$id")
}
