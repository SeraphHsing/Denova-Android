package com.denova.novara

import com.denova.novara.core.settings.SettingsEditor
import com.denova.novara.core.settings.SettingsFieldKind
import com.denova.novara.core.settings.SettingsPatchResult
import com.denova.novara.core.settings.SettingsSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置页的契约与 merge patch 构造（SET-001…014）。
 *
 * 重点覆盖三条会真正写坏服务端配置的路径：清空字段要用 `null` 而不是空串、
 * 复杂结构要整体上送、开启局域网访问必须先具备用户名与密码。
 */
class SettingsEditorTest {

    private fun snapshot(
        user: String = "{}",
        effective: String = "{}",
    ): JsonObject = Json.parseToJsonElement(
        """{"user":$user,"effective":$effective,"revisions":{"user":"r1"}}""",
    ).jsonObject

    private fun patchOf(result: SettingsPatchResult): JsonObject {
        assertTrue("expected success, got $result", result is SettingsPatchResult.Success)
        return (result as SettingsPatchResult.Success).changes
    }

    private fun errorsOf(result: SettingsPatchResult): Set<String> {
        assertTrue("expected invalid, got $result", result is SettingsPatchResult.Invalid)
        return (result as SettingsPatchResult.Invalid).errors.keys
    }

    @Test
    fun `schema follows the baseline section order`() {
        // 基线 `SETTINGS_SECTIONS` 的顺序；`decision` 是矩阵 14 行之外多出的一个 section（同基线）。
        assertEquals(
            listOf(
                "general", "writing", "model", "image", "decision", "speech", "approvals",
                "terminal", "web-access", "market", "paths", "versions", "access", "updates",
                "advanced",
            ),
            SettingsSchema.SECTIONS.map { it.id },
        )
    }

    @Test
    fun `dotted path becomes nested merge patch`() {
        val changes = patchOf(
            SettingsEditor.buildPatch(snapshot(), mapOf("labs.developer_mode" to "true")),
        )
        val labs = changes["labs"]!!.jsonObject
        assertTrue(labs["developer_mode"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `empty draft clears the field with explicit null`() {
        val changes = patchOf(
            SettingsEditor.buildPatch(
                snapshot(user = """{"auto_save_enabled":true}"""),
                mapOf("auto_save_enabled" to ""),
            ),
        )
        assertEquals("null", changes["auto_save_enabled"].toString())
    }

    @Test
    fun `integer outside range is rejected before sending`() {
        val result = SettingsEditor.buildPatch(
            snapshot(),
            mapOf("auto_save_interval_ms" to "10"),
        )
        assertEquals(setOf("auto_save_interval_ms"), errorsOf(result))
    }

    @Test
    fun `non numeric integer is rejected`() {
        val result = SettingsEditor.buildPatch(snapshot(), mapOf("max_open_tabs" to "abc"))
        assertEquals(setOf("max_open_tabs"), errorsOf(result))
    }

    @Test
    fun `malformed json structure is rejected`() {
        val result = SettingsEditor.buildPatch(snapshot(), mapOf("model_profiles" to "[{oops"))
        assertEquals(setOf("model_profiles"), errorsOf(result))
    }

    @Test
    fun `json array is sent as a whole replacement`() {
        val raw = """[{"id":"p1","model":"m"}]"""
        val changes = patchOf(SettingsEditor.buildPatch(snapshot(), mapOf("model_profiles" to raw)))
        assertEquals(1, changes["model_profiles"]!!.let { (it as kotlinx.serialization.json.JsonArray).size })
    }

    @Test
    fun `enum value must be one of the declared options`() {
        val result = SettingsEditor.buildPatch(snapshot(), mapOf("theme" to "neon"))
        assertEquals(setOf("theme"), errorsOf(result))
    }

    @Test
    fun `enabling lan access without username is rejected`() {
        val result = SettingsEditor.buildPatch(
            snapshot(effective = """{"allow_lan_access":false}"""),
            mapOf("allow_lan_access" to "true"),
        )
        assertEquals(setOf("remote_access_username"), errorsOf(result))
    }

    @Test
    fun `enabling lan access without password is rejected`() {
        val result = SettingsEditor.buildPatch(
            snapshot(effective = """{"allow_lan_access":false,"remote_access_password_set":false}"""),
            mapOf(
                "allow_lan_access" to "true",
                "remote_access_username" to "Hsing",
            ),
        )
        assertEquals(setOf("remote_access_password"), errorsOf(result))
    }

    @Test
    fun `enabling lan access with username and password is accepted`() {
        val changes = patchOf(
            SettingsEditor.buildPatch(
                snapshot(effective = """{"allow_lan_access":false,"remote_access_password_set":false}"""),
                mapOf(
                    "allow_lan_access" to "true",
                    "remote_access_username" to "Hsing",
                    "remote_access_password" to "secret-value",
                ),
            ),
        )
        assertEquals(3, changes.size)
        assertEquals("Hsing", changes["remote_access_username"]!!.jsonPrimitive.content)
    }

    @Test
    fun `disabling lan access needs no credentials`() {
        val changes = patchOf(
            SettingsEditor.buildPatch(
                snapshot(effective = """{"allow_lan_access":true}"""),
                mapOf("allow_lan_access" to "false"),
            ),
        )
        assertEquals("false", changes["allow_lan_access"].toString())
    }

    @Test
    fun `read only fields never enter the patch`() {
        val changes = patchOf(
            SettingsEditor.buildPatch(snapshot(), mapOf("paths.nova_dir" to "/tmp/x")),
        )
        assertTrue(changes.isEmpty())
    }

    @Test
    fun `read helpers distinguish explicit and inherited values`() {
        val root = snapshot(
            user = """{"auto_save_enabled":false}""",
            effective = """{"auto_save_enabled":true,"language":"zh-CN"}""",
        )
        val user = root["user"] as JsonObject
        val effective = root["effective"] as JsonObject
        assertTrue(SettingsEditor.isExplicit(user, "auto_save_enabled"))
        assertFalse(SettingsEditor.isExplicit(user, "language"))
        assertEquals("false", SettingsEditor.textAt(user, "auto_save_enabled"))
        assertEquals("zh-CN", SettingsEditor.textAt(effective, "language"))
        assertNull(SettingsEditor.booleanAt(user, "language"))
    }

    @Test
    fun `secret field keeps the typed value verbatim`() {
        val changes = patchOf(
            SettingsEditor.buildPatch(snapshot(), mapOf("market.github_token" to " tok ")),
        )
        assertEquals(" tok ", changes["market"]!!.jsonObject["github_token"]!!.jsonPrimitive.content)
    }

    @Test
    fun `json field editor text is pretty printed for structures`() {
        val root = snapshot(effective = """{"model_profiles":[{"id":"a"}]}""")
        val text = SettingsEditor.prettyAt(root["effective"] as JsonObject, "model_profiles")
        assertTrue(text.contains("\n"))
        assertTrue(text.contains("\"id\""))
    }

    @Test
    fun `every declared field path is unique and reachable in its section`() {
        val all = SettingsSchema.SECTIONS.flatMap { it.fields }
        assertEquals(all.size, all.map { it.path }.toSet().size)
        assertEquals(14, SettingsSchema.SECTIONS.count { it.id != "decision" })
        assertTrue(all.any { it.kind == SettingsFieldKind.SECRET })
    }
}
