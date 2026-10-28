package com.denova.novara

import com.denova.novara.core.network.SkillFileEntry
import com.denova.novara.core.network.SkillSummary
import com.denova.novara.core.skills.SkillLibrary
import com.denova.novara.core.skills.SkillNameError
import com.denova.novara.core.skills.SkillStatusFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 技能库纯逻辑（P4-C / SKL-001·002）：名称规则、分组、筛选、目录文件。 */
class SkillLibraryTest {

    private fun skill(
        name: String,
        scope: String = "user",
        category: String = "",
        description: String = "",
        enabled: Boolean = true,
        active: Boolean = true,
    ) = SkillSummary(
        name = name,
        scope = scope,
        category = category,
        description = description,
        enabled = enabled,
        active = active,
    )

    @Test
    fun `name rules accept letters digits marks and separators`() {
        assertNull(SkillLibrary.validateName("novel-craft"))
        assertNull(SkillLibrary.validateName("去AI味_2"))
        assertNull(SkillLibrary.validateName("a1"))
    }

    @Test
    fun `name rules reject empty leading punctuation and oversize`() {
        assertEquals(SkillNameError.EMPTY, SkillLibrary.validateName("   "))
        assertEquals(SkillNameError.INVALID_START, SkillLibrary.validateName("-abc"))
        assertEquals(SkillNameError.INVALID_CHAR, SkillLibrary.validateName("a b"))
        assertEquals(SkillNameError.TOO_LONG, SkillLibrary.validateName("a".repeat(65)))
        // 64 个汉字 = 192 字节，未超字节上限；70 个汉字 = 210 字节但超字符上限。
        assertEquals(SkillNameError.TOO_LONG, SkillLibrary.validateName("汉".repeat(70)))
        // 240 字节上限：81 个汉字 = 243 字节，但 81 > 64 字符，先触发字符上限。
        // 用 3 字节汉字 + 达到 64 字符但超字节的组合不可构造，故字节上限只在
        // 4 字节字符（如 emoji 外的补充平面字母）时先触发。
        assertEquals(SkillNameError.TOO_MANY_BYTES, SkillLibrary.validateName("𠀀".repeat(61)))
    }

    @Test
    fun `groups follow the fixed scope order and keep unknown scopes`() {
        val grouped = SkillLibrary.groupByScope(
            listOf(
                skill("a", scope = "user"),
                skill("b", scope = "builtin"),
                skill("c", scope = "zeta"),
                skill("d", scope = "shared"),
            ),
        )

        assertEquals(listOf("shared", "builtin", "user", "zeta"), grouped.map { it.first })
        assertEquals(listOf("a"), grouped.first { it.first == "user" }.second.map { it.name })
        assertEquals(listOf("c"), grouped.first { it.first == "zeta" }.second.map { it.name })
    }

    @Test
    fun `categories list standard ones first then custom sorted`() {
        val skills = listOf(
            skill("a", category = "writing"),
            skill("b", category = ""),
            skill("c", category = "development"),
            skill("d", category = "game"),
        )

        assertEquals(listOf("general", "writing", "game", "development"), SkillLibrary.categories(skills))
    }

    @Test
    fun `search requires every word and matches name or description`() {
        val target = skill("novel-craft", description = "网文创作判断手册")

        assertTrue(SkillLibrary.matches(target, SkillLibrary.wordsOf("novel 手册")))
        assertTrue(!SkillLibrary.matches(target, SkillLibrary.wordsOf("novel 不存在")))
        assertTrue(SkillLibrary.matches(target, emptyList()))
    }

    @Test
    fun `filter combines category status and query`() {
        val skills = listOf(
            skill("novel-craft", category = "writing", enabled = true),
            skill("novel-lite", category = "writing", enabled = false),
            skill("game-craft", category = "game", enabled = true),
        )

        assertEquals(
            listOf("novel-craft", "novel-lite"),
            SkillLibrary.filter(skills, query = "novel").map { it.name },
        )
        assertEquals(
            listOf("novel-lite"),
            SkillLibrary.filter(skills, query = "novel", status = SkillStatusFilter.DISABLED).map { it.name },
        )
        assertEquals(
            listOf("game-craft"),
            SkillLibrary.filter(skills, category = "game").map { it.name },
        )
        assertEquals(emptyList<String>(), SkillLibrary.filter(skills, query = "novel", category = "game").map { it.name })
    }

    @Test
    fun `writable scopes prefer the declared list`() {
        assertEquals(listOf("user"), SkillLibrary.writableScopes(listOf("user")))
        assertEquals(emptyList<String>(), SkillLibrary.writableScopes(listOf("builtin", "shared")))
        assertEquals(listOf("user", "workspace"), SkillLibrary.writableScopes(emptyList()))
    }

    @Test
    fun `override target falls back to the first writable scope`() {
        val builtin = skill("novel-craft", scope = "builtin")

        assertEquals("user", SkillLibrary.overrideScope(builtin, listOf("user")))
        assertNull(SkillLibrary.overrideScope(builtin, emptyList()))
        assertEquals("workspace", SkillLibrary.overrideScope(skill("x", scope = "workspace"), listOf("user", "workspace")))
    }

    @Test
    fun `editable files keep SKILL md first and drop directories`() {
        val files = listOf(
            SkillFileEntry(path = "references/a.md", entry = false, editable = true),
            SkillFileEntry(path = "references", entry = true, editable = false),
            SkillFileEntry(path = "SKILL.md", entry = true, editable = true),
            SkillFileEntry(path = "references/b.md", entry = false, editable = false),
        )

        assertEquals(
            listOf("SKILL.md", "references/a.md"),
            SkillLibrary.editableFiles(files).map { it.path },
        )
    }
}
