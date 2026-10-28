package com.denova.novara.core.skills

import com.denova.novara.core.network.SkillScopes
import com.denova.novara.core.network.SkillSummary

/** Skill 名称校验结果（对齐基线 `skills.create.invalidName` 的规则）。 */
enum class SkillNameError {
    EMPTY,
    INVALID_START,
    INVALID_CHAR,
    TOO_LONG,
    TOO_MANY_BYTES,
}

/** 技能库的启用状态筛选（基线 `skills.library.{all,enabled,disabled}`）。 */
enum class SkillStatusFilter { ALL, ENABLED, DISABLED }

/** Skill 的稳定身份：作用域 + 名称（同名不同作用域是两条记录）。 */
data class SkillKey(val scope: String, val name: String) {
    override fun toString(): String = "$scope\u0000$name"
}

fun SkillSummary.key(): SkillKey = SkillKey(scope = scope, name = name)

/**
 * 技能库的纯逻辑（P4-C / SKL-001·002）。
 *
 * 这里只做「名称规则、分组、筛选、可写作用域」这类可单测的判断；网络与状态在
 * ViewModel 里，页面只负责渲染。规则来源：
 * - 名称：以任意语言的字母或数字开头，可含组合字符、连接字符、下划线、连字符；
 *   最多 64 个字符、240 个 UTF-8 字节（`skills.create.invalidName`）；
 * - 分组顺序 `shared → builtin → user → workspace`（`skill-utils.ts` 的 `skillScopes`）；
 * - 分类顺序 `general → writing → game → image → research → configuration`，自定义分类排在后面。
 */
object SkillLibrary {

    /** 基线标准分类（`skills.category.*`）。 */
    val standardCategories: List<String> = listOf(
        "general", "writing", "game", "image", "research", "configuration",
    )

    const val MAX_NAME_CHARS = 64
    const val MAX_NAME_BYTES = 240

    /** 校验 Skill 名称；返回 null 表示合法。 */
    fun validateName(raw: String): SkillNameError? {
        val name = raw.trim()
        if (name.isEmpty()) return SkillNameError.EMPTY
        val codePoints = name.codePoints().toArray()
        val first = codePoints.first()
        if (!Character.isLetterOrDigit(first)) return SkillNameError.INVALID_START
        for (codePoint in codePoints.drop(1)) {
            if (Character.isLetterOrDigit(codePoint)) continue
            if (codePoint == '_'.code || codePoint == '-'.code) continue
            when (Character.getType(codePoint)) {
                Character.NON_SPACING_MARK.toInt(),
                Character.COMBINING_SPACING_MARK.toInt(),
                Character.ENCLOSING_MARK.toInt(),
                Character.CONNECTOR_PUNCTUATION.toInt(),
                -> continue
                else -> return SkillNameError.INVALID_CHAR
            }
        }
        if (codePoints.size > MAX_NAME_CHARS) return SkillNameError.TOO_LONG
        if (name.toByteArray(Charsets.UTF_8).size > MAX_NAME_BYTES) return SkillNameError.TOO_MANY_BYTES
        return null
    }

    /** 可写作用域（服务端 `scopes[].writable` 优先，缺省回落到 user/workspace）。 */
    fun writableScopes(declared: List<String>): List<String> =
        SkillScopes.ordered.filter { scope ->
            scope in SkillScopes.writable && (declared.isEmpty() || scope in declared)
        }

    /** 按作用域分组；作用域顺序固定，未知作用域追加在末尾（不丢条目）。 */
    fun groupByScope(skills: List<SkillSummary>): List<Pair<String, List<SkillSummary>>> {
        val known = SkillScopes.ordered
        val extra = skills.map { it.scope }.filterNot { it in known }.distinct().sorted()
        return (known + extra)
            .map { scope -> scope to skills.filter { it.scope == scope } }
            .filter { (_, items) -> items.isNotEmpty() }
    }

    /** 分类枚举：先用标准顺序列出实际出现的分类，再追加自定义分类（按字典序）。 */
    fun categories(skills: List<SkillSummary>): List<String> {
        val discovered = skills.map { categoryOf(it) }.distinct()
        val standard = standardCategories.filter { it in discovered }
        val custom = discovered.filterNot { it in standardCategories }.sorted()
        return standard + custom
    }

    /** 基线把空分类当 `general` 展示与筛选。 */
    fun categoryOf(skill: SkillSummary): String =
        skill.category.trim().ifEmpty { "general" }

    /** 搜索：按空白分词，每个词都要出现在 名称 / 触发说明 / 分类 里（大小写不敏感）。 */
    fun matches(skill: SkillSummary, words: List<String>): Boolean {
        if (words.isEmpty()) return true
        val haystack = buildString {
            append(skill.name).append('\n')
            append(skill.description).append('\n')
            append(categoryOf(skill))
        }.lowercase()
        return words.all { haystack.contains(it) }
    }

    /** 搜索词分词（连续空白合并，空词丢弃）。 */
    fun wordsOf(query: String): List<String> =
        query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }

    /**
     * 列表筛选：分类 + 启用状态 + 搜索词，三者同时满足才保留。
     *
     * `status` 只作用于 `enabled`；`active=false`（被上层覆盖）仍按启用态展示，
     * 由界面另行标记「已被覆盖」。
     */
    fun filter(
        skills: List<SkillSummary>,
        query: String = "",
        category: String = "all",
        status: SkillStatusFilter = SkillStatusFilter.ALL,
    ): List<SkillSummary> {
        val words = wordsOf(query)
        return skills.filter { skill ->
            if (category != "all" && categoryOf(skill) != category) return@filter false
            when (status) {
                SkillStatusFilter.ALL -> Unit
                SkillStatusFilter.ENABLED -> if (!skill.enabled) return@filter false
                SkillStatusFilter.DISABLED -> if (skill.enabled) return@filter false
            }
            matches(skill, words)
        }
    }

    /**
     * 新建 / 另存为的目标位置：优先用户选定的可写作用域，缺省取第一个可写位置。
     * 没有任何可写作用域时返回 null（界面据此禁用「新建」）。
     */
    fun defaultScope(writable: List<String>): String? = writable.firstOrNull()

    /** 该 Skill 能否在当前目标里被覆盖（内置/通用 Skill 需要落到可写作用域）。 */
    fun overrideScope(skill: SkillSummary, writable: List<String>): String? =
        when {
            skill.scope in writable -> skill.scope
            else -> writable.firstOrNull()
        }

    /**
     * 目录内的可编辑文件；`SKILL.md` 始终排在最前。
     *
     * 服务端把 `SKILL.md` 也标成 `entry=true`（它是 Skill 的入口文件），
     * 所以这里按路径放行入口文件，其余目录项（如 `references/`）仍被排除。
     */
    fun editableFiles(files: List<com.denova.novara.core.network.SkillFileEntry>): List<com.denova.novara.core.network.SkillFileEntry> {
        val content = files.filter { it.editable && (!it.entry || it.path == "SKILL.md") }
        val main = content.firstOrNull { it.path == "SKILL.md" }
        return if (main == null) content else listOf(main) + content.filterNot { it.path == "SKILL.md" }
    }
}
