package com.denova.novara

import com.denova.novara.feature.shell.appDestinations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 左导航抽屉的一级目的地契约（P2 验收，对齐 01 §3）。 */
class AppDestinationTest {

    @Test
    fun `has 13 first-level destinations with unique routes`() {
        assertEquals(13, appDestinations.size)
        assertEquals(appDestinations.size, appDestinations.map { it.route }.toSet().size)
    }

    @Test
    fun `implemented destinations match the shipped pages`() {
        // P4-B：工作台宿主页面；P4-C：书籍 / 资料库 / 创作方案 / Skills / Agents；
        // P4-D：设置页（此后 automations / market 依次接入）。
        val implemented = appDestinations.filter { it.implemented }.map { it.route }
        assertEquals(
            listOf(
                "editor", "game", "agent", "workbench", "lore", "teller",
                "books", "skills", "agents", "settings",
            ),
            implemented,
        )
    }

    @Test
    fun `writing and game are adjacent entries and settings is last`() {
        val routes = appDestinations.map { it.route }
        assertTrue(routes.indexOf("game") == routes.indexOf("editor") + 1)
        assertEquals("settings", routes.last())
    }
}
