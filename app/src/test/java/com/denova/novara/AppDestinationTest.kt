package com.denova.novara

import com.denova.novara.feature.shell.appDestinations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 左导航抽屉的一级目的地契约（P2 验收，对齐 01 §3）。 */
class AppDestinationTest {

    @Test
    fun `has 14 first-level destinations with unique routes`() {
        // 13 个常驻入口 + 1 个条件入口（trajectory，仅开发者模式可见，P5 TRJ-001）。
        assertEquals(14, appDestinations.size)
        assertEquals(appDestinations.size, appDestinations.map { it.route }.toSet().size)
        assertTrue(appDestinations.any { it.route == "trajectory" })
    }

    @Test
    fun `implemented destinations match the shipped pages`() {
        // P4-D 收口后 13 个入口全部可进入；P5 补上统计页，14 个全部有实现。
        val implemented = appDestinations.filter { it.implemented }.map { it.route }
        assertEquals(appDestinations.map { it.route }, implemented)
    }

    @Test
    fun `writing and game are adjacent entries and settings is last`() {
        val routes = appDestinations.map { it.route }
        assertTrue(routes.indexOf("game") == routes.indexOf("editor") + 1)
        assertEquals("settings", routes.last())
    }
}
