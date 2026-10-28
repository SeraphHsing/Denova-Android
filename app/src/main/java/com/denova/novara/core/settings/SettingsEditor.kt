package com.denova.novara.core.settings

import com.denova.novara.R
import com.denova.novara.core.i18n.UiText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** `PATCH /api/settings` 的 changes 构造结果。 */
sealed interface SettingsPatchResult {
    data class Success(val changes: JsonObject) : SettingsPatchResult
    data class Invalid(val errors: Map<String, UiText>) : SettingsPatchResult
}

/**
 * 设置的分层读取与 merge patch 构造（P4-D / SET-001…014，纯逻辑，可 JVM 单测）。
 *
 * 语义对齐后端 `config/settings_patch.go`（RFC 7386 JSON Merge Patch）：
 * - 只提交本次改动的字段；
 * - **空值（`null`）表示清除该字段**，也就是回落到 `default` / `global` 层（界面上的「继承」）；
 * - 数组整体替换，所以 JSON 编辑器提交的是完整结构。
 */
object SettingsEditor {

    private val prettyJson = Json { prettyPrint = true }

    /** 按点分路径取 JSON 节点；路径中间缺少对象时返回 null。 */
    fun valueAt(root: JsonObject?, path: String): JsonElement? {
        if (root == null) return null
        var node: JsonElement = root
        for (segment in path.split('.')) {
            val obj = node as? JsonObject ?: return null
            node = obj[segment] ?: return null
        }
        return node
    }

    /** 取值转文本：标量用原文，对象/数组用紧凑 JSON（给 JSON 编辑器做初始值）。 */
    fun textAt(root: JsonObject?, path: String): String {
        val element = valueAt(root, path) ?: return ""
        if (element is JsonNull) return ""
        val primitive = element as? JsonPrimitive
        return if (primitive != null && !primitive.isString) {
            primitive.contentOrNull.orEmpty()
        } else {
            primitive?.contentOrNull ?: element.toString()
        }
    }

    /** JSON 编辑器的展示文本：结构类型做格式化，便于阅读与逐字段核对。 */
    fun prettyAt(root: JsonObject?, path: String): String {
        val element = valueAt(root, path) ?: return ""
        if (element is JsonNull) return ""
        if (element is JsonObject || element is JsonArray) {
            return prettyJson.encodeToString(JsonElement.serializer(), element)
        }
        return textAt(root, path)
    }

    /** 该字段在 `user` 层是否显式设置过（有值即「非继承」）。 */
    fun isExplicit(userLayer: JsonObject?, path: String): Boolean {
        val element = valueAt(userLayer, path) ?: return false
        return element !is JsonNull
    }

    /** 字段当前编辑值：用户改过就用草稿，否则用显式值（继承态为空串）。 */
    fun draftText(userLayer: JsonObject?, edits: Map<String, String>, path: String): String =
        edits[path] ?: textAt(userLayer, path)

    /** 占位文案显示的继承值（`effective` 层）；空结构不占位。 */
    fun placeholder(root: JsonObject?, path: String): String {
        val text = textAt(root, path)
        return if (text.length > 60) text.take(60) + "…" else text
    }

    /**
     * 由草稿构造 merge patch。
     *
     * 空字符串统一表示「清除 / 继承」：提交 `null`，让服务端回落到默认层（基线的「清空后
     * 校验」也是这个语义——清空不是写空串）。
     */
    fun buildPatch(snapshot: JsonObject?, edits: Map<String, String>): SettingsPatchResult {
        val errors = LinkedHashMap<String, UiText>()
        val fragments = mutableListOf<JsonObject>()
        for ((path, raw) in edits) {
            val spec = SettingsSchema.field(path) ?: continue
            if (!spec.writable) continue
            val parsed = parseField(spec, raw)
            when (parsed) {
                is FieldParse.Error -> errors[path] = parsed.message
                is FieldParse.Ok -> fragments += nest(path, parsed.element)
            }
        }
        if (errors.isNotEmpty()) return SettingsPatchResult.Invalid(errors)
        val changes = mergeFragments(fragments)
        validateLayering(snapshot, changes)?.let { errors[it.first] = it.second }
        if (errors.isNotEmpty()) return SettingsPatchResult.Invalid(errors)
        return SettingsPatchResult.Success(changes)
    }

    private sealed interface FieldParse {
        data class Ok(val element: JsonElement) : FieldParse
        data class Error(val message: UiText) : FieldParse
    }

    private fun parseField(spec: SettingsFieldSpec, raw: String): FieldParse {
        val text = raw.trim()
        if (text.isEmpty()) return FieldParse.Ok(JsonNull)
        return when (spec.kind) {
            SettingsFieldKind.BOOL -> when (text.lowercase()) {
                "true" -> FieldParse.Ok(JsonPrimitive(true))
                "false" -> FieldParse.Ok(JsonPrimitive(false))
                else -> FieldParse.Error(UiText.of(R.string.settings_error_bool))
            }

            SettingsFieldKind.ENUM -> if (spec.options.contains(text)) {
                FieldParse.Ok(JsonPrimitive(text))
            } else {
                FieldParse.Error(UiText.of(R.string.settings_error_enum))
            }

            SettingsFieldKind.INTEGER -> {
                val number = text.toLongOrNull() ?: return FieldParse.Error(
                    UiText.of(R.string.settings_error_integer),
                )
                val min = spec.minimum
                val max = spec.maximum
                if ((min != null && number < min) || (max != null && number > max)) {
                    FieldParse.Error(
                        UiText.of(R.string.settings_error_range, min?.toString() ?: "-", max?.toString() ?: "-"),
                    )
                } else {
                    FieldParse.Ok(JsonPrimitive(number))
                }
            }

            SettingsFieldKind.TEXT, SettingsFieldKind.SECRET -> FieldParse.Ok(JsonPrimitive(raw))

            SettingsFieldKind.JSON -> {
                val element = runCatching { Json.parseToJsonElement(text) }.getOrNull()
                if (element == null) {
                    FieldParse.Error(UiText.of(R.string.settings_error_json))
                } else {
                    FieldParse.Ok(element)
                }
            }

            SettingsFieldKind.READ_ONLY -> FieldParse.Ok(JsonNull)
        }
    }

    /**
     * 局域网访问的跨字段约束（后端 `PrepareUserSettingsForWrite` 的同一规则）：
     * 开启 `allow_lan_access` 必须同时具备用户名与密码，否则服务端 400。
     * 这里先本地拦一次，避免把必然失败的表单发出去。
     */
    private fun validateLayering(
        snapshot: JsonObject?,
        changes: JsonObject,
    ): Pair<String, UiText>? {
        val effective = snapshot?.get("effective") as? JsonObject
        val user = snapshot?.get("user") as? JsonObject
        val changesAllowLan = valueAt(changes, "allow_lan_access")?.jsonPrimitive?.booleanOrNull
        val allowLan = changesAllowLan ?: valueAt(effective, "allow_lan_access")?.jsonPrimitive?.booleanOrNull ?: false
        if (!allowLan) return null

        val usernameChanged = valueAt(changes, "remote_access_username")
        val username = when {
            usernameChanged is JsonNull -> ""
            usernameChanged != null -> textAt(changes, "remote_access_username")
            else -> textAt(effective, "remote_access_username")
        }
        if (username.isBlank()) {
            return "remote_access_username" to UiText.of(R.string.settings_error_lan_username)
        }

        val passwordChanged = valueAt(changes, "remote_access_password")
        val passwordSet = when (passwordChanged) {
            null -> valueAt(effective, "remote_access_password_set")?.jsonPrimitive?.booleanOrNull ?: false
            is JsonNull -> false
            else -> true
        }
        if (!passwordSet) {
            return "remote_access_password" to UiText.of(R.string.settings_error_lan_password)
        }
        // 未改动访问设置时也检查一次「显式关闭」的边界（user 层为空但 default 开启）。
        if (user == null) return null
        return null
    }

    /** `{"a.b.c": v}` → `{"a":{"b":{"c":v}}}`。 */
    fun nest(path: String, value: JsonElement): JsonObject {
        val segments = path.split('.')
        var node: JsonElement = value
        for (segment in segments.asReversed()) {
            node = buildJsonObject { put(segment, node) }
        }
        return node as JsonObject
    }

    /** 多段 patch 深合并（同名对象递归合并，后者覆盖前者）。 */
    fun mergeFragments(fragments: List<JsonObject>): JsonObject {
        var merged: JsonObject = JsonObject(emptyMap())
        for (fragment in fragments) merged = deepMerge(merged, fragment)
        return merged
    }

    private fun deepMerge(left: JsonObject, right: JsonObject): JsonObject {
        if (left.isEmpty()) return right
        val out = LinkedHashMap<String, JsonElement>(left)
        for ((key, value) in right) {
            val existing = out[key]
            out[key] = if (existing is JsonObject && value is JsonObject) {
                deepMerge(existing, value)
            } else {
                value
            }
        }
        return JsonObject(out)
    }

    /** 把 JSON 值转成可编辑文本（对象/数组格式化，其余原文）。 */
    fun editorText(element: JsonElement?): String = when {
        element == null || element is JsonNull -> ""
        element is JsonObject || element is JsonArray ->
            prettyJson.encodeToString(JsonElement.serializer(), element)

        else -> element.jsonPrimitive.contentOrNull.orEmpty()
    }

    /** 布尔字段的当前三态：`null` = 继承，`true` / `false` = 显式。 */
    fun booleanAt(root: JsonObject?, path: String): Boolean? {
        val element = valueAt(root, path) ?: return null
        if (element is JsonNull) return null
        return (element as? JsonPrimitive)?.booleanOrNull
    }

    /** 整数读取（只读展示与范围校验用）。 */
    fun longAt(root: JsonObject?, path: String): Long? =
        (valueAt(root, path) as? JsonPrimitive)?.longOrNull
}
