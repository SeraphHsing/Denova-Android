package com.denova.novara

import com.denova.novara.feature.shell.ActivityNav
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 一级导航顺序（NAV-001 / NAV-004 / NAV-005）：默认顺序、保存顺序优先、新增项按默认位插入、分组边界。
 */
class ActivityNavTest {

    @Test
    fun `default order matches the web baseline`() {
        assertEquals(
            listOf(
                "editor", "game", "agent", "workbench", "lore", "teller",
                "versions", "books", "skills", "agents", "automations", "market",
            ),
            ActivityNav.DEFAULT_ORDER,
        )
        // settings 不进顺序表：它固定落在设置组，不参与重排。
        assertTrue(ActivityNav.DEFAULT_ORDER.none { it == "settings" })
    }

    @Test
    fun `missing items are inserted after their default predecessor`() {
        // 用户只保留了后半段：新增/缺失项按默认顺序插在已存在的前项之后。
        val stored = listOf("books", "skills")
        val result = ActivityNav.insertMissing(stored)
        assertEquals("books", result[result.indexOf("books")])
        // teller / versions 在 books 之前且都不存在 → 依次插到 books 之前（保持默认相对顺序）。
        assertTrue(result.indexOf("versions") < result.indexOf("books"))
        assertTrue(result.indexOf("teller") < result.indexOf("versions"))
        // agents/automations/market 在 skills 之后。
        assertTrue(result.indexOf("agents") > result.indexOf("skills"))
        assertEquals(ActivityNav.DEFAULT_ORDER.size, result.size)
    }

    @Test
    fun `saved order takes precedence over the default order`() {
        val custom = listOf("market", "editor", "game", "agent", "workbench", "lore", "teller",
            "versions", "books", "skills", "agents", "automations")
        val sorted = ActivityNav.sort(order = custom).map { it.id }
        assertEquals("market", sorted.first())
        assertEquals("editor", sorted[1])
    }

    @Test
    fun `every default item survives sorting even if the order omits it`() {
        // 只存了一个 id 时，缺失项按「默认顺序里紧邻的已存在项」补位（基线同口径），
        // 因此 market 之前的所有条目都会排在它前面，不会被丢掉；settings 不在顺序表里，固定排最后。
        val sorted = ActivityNav.sort(order = listOf("market")).map { it.id }
        assertEquals(ActivityNav.SPECS.size, sorted.size)
        assertTrue(sorted.containsAll(ActivityNav.DEFAULT_ORDER))
        assertEquals("market", sorted[sorted.size - 2])
        assertEquals("settings", sorted.last())
    }

    @Test
    fun `move shifts one step and clamps at both ends`() {
        val order = ActivityNav.DEFAULT_ORDER
        val down = ActivityNav.move(order, "editor", 1)
        assertEquals(listOf("game", "editor"), down.take(2))

        val up = ActivityNav.move(order, "game", -1)
        assertEquals(listOf("game", "editor"), up.take(2))

        // 首位再上移保持原样。
        assertEquals(order, ActivityNav.move(order, "editor", -1))
        // 末位再下移保持原样。
        assertEquals(order, ActivityNav.move(order, "market", 1))
    }

    @Test
    fun `grouping puts writing and story in creation and settings alone at the end`() {
        val groups = ActivityNav.grouped(ActivityNav.sort())
        assertEquals(3, groups.size)
        assertEquals(listOf("editor", "game"), groups[0].second.map { it.id })
        assertEquals("settings", groups[2].second.single().id)
        // 工具组是其余全部（手机端不过滤 hiddenActivityIDs，入口齐全）。
        assertEquals(ActivityNav.DEFAULT_ORDER.size - 2, groups[1].second.size)
    }

    @Test
    fun `search matches labels ignoring case and surrounding spaces`() {
        assertTrue(ActivityNav.matches("sk", "Skills"))
        assertTrue(ActivityNav.matches("  SK  ", "Skills"))
        assertTrue(ActivityNav.matches("写作", "写作 Agent"))
        assertTrue(ActivityNav.matches("agents", "Agents"))
        // 空搜索视为全部命中（抽屉显示完整入口）。
        assertTrue(ActivityNav.matches("   ", "Skills"))
        assertTrue(ActivityNav.matches("", "写作"))
        // 无匹配（界面据此显示「没有匹配的动作」）。
        assertFalse(ActivityNav.matches("zzz", "Skills"))
        assertFalse(ActivityNav.matches("写作", "Skills"))
    }
}
