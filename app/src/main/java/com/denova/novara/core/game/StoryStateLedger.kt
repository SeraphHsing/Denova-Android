package com.denova.novara.core.game

import com.denova.novara.core.network.ActorStateSchema
import com.denova.novara.core.network.ActorStateTemplate
import com.denova.novara.core.network.InteractiveSnapshot
import com.denova.novara.core.stream.ActorStateOp
import com.denova.novara.core.stream.StateOp
import com.denova.novara.core.stream.TurnEvent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 台账的一行：字段 id（变更摘要用）、显示名与已格式化值。 */
data class LedgerField(val name: String, val value: String, val id: String = name)

/** 一个角色条目；[isLead] 用于把主角排在最前。 */
data class LedgerActor(
    val id: String,
    val name: String,
    val role: String,
    val fields: List<LedgerField>,
    val isLead: Boolean = false,
)

/**
 * 状态台账（GAM-006）：角色 / 世界事实 / 本轮变更摘要。
 *
 * 数据源只有快照：`state.actors` 出角色，`state` 的其余顶层键出世界事实，
 * `current_turn.state_delta` 出变更摘要。流事件不单独维护台账——收尾后重取快照即可，
 * 这与 Web `buildStoryStateModel` 的口径一致（快照是规范事实源）。
 */
data class StoryLedger(
    val actors: List<LedgerActor> = emptyList(),
    val world: List<LedgerField> = emptyList(),
    val changes: List<String> = emptyList(),
    val stateStatus: String = "",
    val stateError: String = "",
) {
    val isEmpty: Boolean get() = actors.isEmpty() && world.isEmpty()
}

object StoryStateLedgerBuilder {

    private const val ACTORS_KEY = "actors"
    private const val ARCHIVES_KEY = "actor_archives"
    private const val MAX_VALUE_CHARS = 240

    fun build(snapshot: InteractiveSnapshot): StoryLedger {
        val state = snapshot.state
        val schema = snapshot.actorStateSchema
        val actors = parseActors(state[ACTORS_KEY] as? JsonObject, schema)

        val world = state.entries
            .filterNot { it.key == ACTORS_KEY || it.key == ARCHIVES_KEY }
            .flatMap { (key, value) ->
                // 对象类顶层键（地点 / 阵营 / 时钟等）展开成「key.子项」逐行可读。
                if (value is JsonObject) {
                    value.entries
                        .filterNot { isBlank(it.value) }
                        .map { (sub, item) -> LedgerField("$key.$sub", formatValue(item)) }
                } else if (isBlank(value)) {
                    emptyList()
                } else {
                    listOf(LedgerField(key, formatValue(value)))
                }
            }

        val currentTurn = snapshot.currentTurn
        return StoryLedger(
            actors = actors,
            world = world,
            changes = buildChanges(currentTurn, actors),
            stateStatus = currentTurn?.stateStatus.orEmpty(),
            stateError = currentTurn?.stateError.orEmpty(),
        )
    }

    private fun parseActors(
        actors: JsonObject?,
        schema: ActorStateSchema?,
    ): List<LedgerActor> {
        if (actors == null) return emptyList()
        val templates = schema?.system?.templates.orEmpty()
        return actors.entries.mapNotNull { (actorId, element) ->
            val actor = element as? JsonObject ?: return@mapNotNull null
            val role = (actor["role"] as? JsonPrimitive)?.content.orEmpty()
            val templateId = (actor["template_id"] as? JsonPrimitive)?.content.orEmpty()
            val fieldsState = actor["state"] as? JsonObject
            val isLead = actorId == LEAD_ACTOR_ID || role == LEAD_ACTOR_ID || role == "lead"

            val template = templates.firstOrNull { it.id == templateId }
            val fields = if (template != null && fieldsState != null) {
                templateFields(template, fieldsState)
            } else {
                fieldsState?.entries.orEmpty().map { (key, value) -> LedgerField(key, formatValue(value), key) }
            }

            LedgerActor(
                id = actorId,
                name = (actor["name"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: actorId,
                role = role,
                fields = fields,
                isLead = isLead,
            )
        }.sortedWith(compareByDescending<LedgerActor> { it.isLead }.thenBy { it.name })
    }

    /** 按模板字段顺序与显示名取值；模板里没有的字段追加在末尾，避免丢数据。 */
    private fun templateFields(template: ActorStateTemplate, state: JsonObject): List<LedgerField> {
        val fromTemplate = template.fields.mapNotNull { field ->
            val key = field.id.ifBlank { field.path }
            val value = state[key] ?: state[field.path]
            if (value == null) null else LedgerField(field.name.ifBlank { key }, formatValue(value), key)
        }
        val known = template.fields.mapNotNull { it.id.ifBlank { it.path } }.toSet()
        val extra = state.entries
            .filterNot { it.key in known }
            .map { (key, value) -> LedgerField(key, formatValue(value), key) }
        return fromTemplate + extra
    }

    private fun buildChanges(turn: TurnEvent?, actors: List<LedgerActor>): List<String> {
        val delta = turn?.stateDelta ?: return emptyList()
        val actorNames = actors.associate { it.id to it.name }
        val fieldNames = actors
            .flatMap { actor -> actor.fields.map { "${actor.id}\u0000${it.id}" to it.name } }
            .toMap()

        return delta.actorOps.mapNotNull { op -> actorChange(op, actorNames, fieldNames) } +
            delta.ops.mapNotNull { op -> stateChange(op) }
    }

    private fun actorChange(
        op: ActorStateOp,
        actorNames: Map<String, String>,
        fieldNames: Map<String, String>,
    ): String? {
        val actor = actorNames[op.actorId] ?: op.actorId.ifBlank { return null }
        val field = fieldNames["${op.actorId}\u0000${op.fieldId}"] ?: op.fieldId
        val label = when (op.op) {
            "unset", "remove", "delete" -> "×"
            else -> formatValue(op.value)
        }
        return "$actor.$field: $label"
    }

    private fun stateChange(op: StateOp): String? {
        val path = op.path.ifBlank { return null }
        return "$path: ${formatValue(op.value)}"
    }

    /**
     * 空值判定：`null` / 空串 / 空数组 / 空对象都视为「无内容」。
     * 世界侧键在快照里常以空骨架存在（`events` / `rules` / `threads` 等），
     * 逐行展示 `—` 会让人误以为字段有值但读不出，直接过滤更准确。
     */
    private fun isBlank(element: JsonElement?): Boolean = when (element) {
        null, JsonNull -> true
        is JsonPrimitive -> element.isString && element.content.isBlank()
        is JsonArray -> element.isEmpty()
        is JsonObject -> element.isEmpty()
        else -> false
    }

    /** 值的可读化：原始值直出，数组/对象用 `, ` 与 `; ` 拼平，超长截断。 */
    fun formatValue(element: JsonElement?): String {
        val text = when (element) {
            null, JsonNull -> ""
            is JsonArray -> element.joinToString(", ") { formatValue(it) }
            is JsonObject -> element.entries.joinToString("; ") { "${it.key}=${formatValue(it.value)}" }
            is JsonPrimitive -> element.content
            else -> element.toString()
        }
        return if (text.length > MAX_VALUE_CHARS) text.take(MAX_VALUE_CHARS) + "…" else text
    }

    private const val LEAD_ACTOR_ID = "protagonist"
}

/** 行动选项：`turn_result.choices` 优先，缺失时回退 `hot_state.choices`（与 Web 一致）。 */
object GameChoices {

    fun of(turn: TurnEvent?): List<String> {
        val primary = turn?.turnResult?.choices.orEmpty()
        val source = if (primary.isNotEmpty()) primary else turn?.hotState?.choices.orEmpty()
        return source.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }
}
