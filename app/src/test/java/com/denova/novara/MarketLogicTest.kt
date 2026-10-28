package com.denova.novara

import com.denova.novara.R
import com.denova.novara.core.i18n.UiText
import com.denova.novara.core.market.MarketLogic
import com.denova.novara.core.network.Installation
import com.denova.novara.core.network.MarketEntry
import com.denova.novara.core.network.MarketPackage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 资源市场纯逻辑（MKT-001…005）：客户端搜索、按种类分组、本地化取名与「本机限定」错误映射。
 */
class MarketLogicTest {

    private fun localized(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    private fun entry(
        id: String = "cultivation-starter",
        zh: String = "照夜长河",
        en: String = "Lanterns",
        descriptionZh: String = "大世界修仙模拟",
        kinds: List<String> = listOf("game"),
        tags: List<String> = listOf("xianxia"),
    ) = MarketEntry(
        id = id,
        name = localized("""{"zh-CN":"$zh","en-US":"$en"}"""),
        description = localized("""{"zh-CN":"$descriptionZh","en-US":"cultivation sim"}"""),
        kinds = kinds,
        tags = tags,
    )

    @Test
    fun `localized text prefers the active language and falls back`() {
        val value = localized("""{"zh-CN":"中文名","en-US":"English name"}""")
        assertEquals("中文名", MarketLogic.localized(value, "zh"))
        assertEquals("English name", MarketLogic.localized(value, "en"))
        assertEquals("only english", MarketLogic.localized(localized("""{"en-US":"only english"}"""), "zh"))
        assertEquals("", MarketLogic.localized(JsonObject(emptyMap()), "zh"))
    }

    @Test
    fun `search requires every token to appear in name description id or tags`() {
        val entries = listOf(
            entry(id = "cultivation-starter", zh = "照夜长河", descriptionZh = "大世界修仙模拟"),
            entry(id = "xianxia-lore", zh = "仙侠资料", descriptionZh = "资料库素材", kinds = listOf("plugin")),
        )
        assertEquals(2, MarketLogic.search(entries, "", "zh").size)
        assertEquals(
            listOf("cultivation-starter"),
            MarketLogic.search(entries, "修仙 长河", "zh").map { it.id },
        )
        // tags 也参与匹配：两条都带 xianxia 标签时都命中。
        assertEquals(
            listOf("cultivation-starter", "xianxia-lore"),
            MarketLogic.search(entries, "xianxia", "zh").map { it.id },
        )
        assertEquals(listOf("xianxia-lore"), MarketLogic.search(entries, "仙侠", "zh").map { it.id })
        assertTrue(MarketLogic.search(entries, "不存在的词", "zh").isEmpty())
    }

    @Test
    fun `entries are grouped by resource format`() {
        val entries = listOf(
            entry(id = "a").copy(format = "denova.resource-pack"),
            entry(id = "b").copy(format = "denova.resource-pack"),
            entry(id = "c").copy(format = "denova.plugin"),
        )
        val groups = MarketLogic.groupByFormat(entries)
        assertEquals(listOf("denova.plugin", "denova.resource-pack"), groups.map { it.first })
        assertEquals(listOf("c"), groups[0].second.map { it.id })
        assertEquals(listOf("a", "b"), groups[1].second.map { it.id })
    }

    @Test
    fun `entries without a format fall back to a resource group`() {
        val groups = MarketLogic.groupByFormat(listOf(entry(kinds = emptyList())))
        assertEquals(listOf("resource"), groups.map { it.first })
    }

    @Test
    fun `host only errors map to the local-host requirement`() {
        assertEquals(
            UiText.of(R.string.market_local_only),
            MarketLogic.hostOnlyText("LOCAL_HOST_ONLY", null),
        )
        assertEquals(
            UiText.of(R.string.market_local_only),
            MarketLogic.hostOnlyText(null, "该操作只能从运行 Denova 的本机触发"),
        )
        assertNull(MarketLogic.hostOnlyText("NOT_FOUND", "resource not found"))
    }

    @Test
    fun `update modes and install states have readable labels`() {
        assertEquals(UiText.of(R.string.market_update_mode_manual), MarketLogic.updateModeLabel("manual"))
        assertEquals(UiText.of(R.string.market_update_mode_auto), MarketLogic.updateModeLabel("auto_apply"))
        assertEquals(UiText.raw("weird"), MarketLogic.updateModeLabel("weird"))
        assertEquals(UiText.of(R.string.market_state_missing), MarketLogic.localStateLabel("missing"))
        assertEquals(UiText.of(R.string.market_state_unknown), MarketLogic.localStateLabel(""))
    }

    @Test
    fun `installations group by project and sort by label`() {
        val first = Installation(
            installationId = "i1",
            `package` = MarketPackage(id = "p1", name = "Zeta"),
            projectId = "project-b",
        )
        val second = first.copy(installationId = "i2", `package` = MarketPackage(id = "p2", name = "Alpha"))
        val groups = MarketLogic.groupByProject(listOf(first, second))
        assertEquals(1, groups.size)
        assertEquals(listOf("i2", "i1"), groups[0].second.map { it.installationId })
    }

    @Test
    fun `installation label falls back from name to id`() {
        assertEquals("Zani", Installation(`package` = MarketPackage(id = "character-card", name = "Zani")).label)
        assertEquals("character-card", Installation(`package` = MarketPackage(id = "character-card")).label)
        assertFalse(Installation(installationId = "abcdefgh").label.isBlank())
    }
}
