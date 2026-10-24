package com.denova.novara

import com.denova.novara.core.stream.TurnEvent
import com.denova.novara.core.stream.buildCanonicalNarrative
import org.junit.Assert.assertEquals
import org.junit.Test

class GameHistoryTest {

    @Test
    fun `empty turns produce empty narrative`() {
        assertEquals("", buildCanonicalNarrative(emptyList()))
    }

    @Test
    fun `joins user input and narrative`() {
        val turns = listOf(TurnEvent(id = "1", user = "玩家输入", narrative = "叙事正文"))
        assertEquals("> 玩家输入\n\n叙事正文", buildCanonicalNarrative(turns))
    }

    @Test
    fun `narrative only turn`() {
        val turns = listOf(TurnEvent(id = "1", narrative = "只有叙事"))
        assertEquals("只有叙事", buildCanonicalNarrative(turns))
    }

    @Test
    fun `joins multiple turns with blank line`() {
        val turns = listOf(
            TurnEvent(id = "1", narrative = "第一段"),
            TurnEvent(id = "2", narrative = "第二段"),
        )
        assertEquals("第一段\n\n第二段", buildCanonicalNarrative(turns))
    }

    @Test
    fun `skips empty turns`() {
        val turns = listOf(
            TurnEvent(id = "1"),
            TurnEvent(id = "2", narrative = "有效"),
        )
        assertEquals("有效", buildCanonicalNarrative(turns))
    }

    @Test
    fun `skips internal source prompt but keeps narrative`() {
        val turns = listOf(
            TurnEvent(
                id = "1",
                user = "[Source: story opening configuration; purpose: generate the first playable turn]\n\nStory premise: ...",
                narrative = "开场叙事",
            ),
        )
        assertEquals("开场叙事", buildCanonicalNarrative(turns))
    }
}
