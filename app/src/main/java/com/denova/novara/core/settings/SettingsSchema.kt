package com.denova.novara.core.settings

import com.denova.novara.R

/**
 * 设置页 14 个 section 的字段声明（P4-D / SET-001…014）。
 *
 * 事实来源是固定 Web 基线 `web/src/features/settings/settings-sections.ts` + 后端
 * `config.Settings` 的 JSON tag：设置是**扁平结构**，section 只是界面分组，不改变写入作用域。
 * 因此这里每个字段都用一个「点分 JSON 路径」寻址（例如 `labs.developer_mode`），
 * 读取时取 `effective`（继承后的最终值），写入时只落到 `user` 层。
 *
 * 复杂结构（模型 / 图像 / 决策 / 终端命令 / Web 访问）在基线上是独立子编辑器；Android 侧按
 * `PLN-002` 已确立的做法用 JSON 文本编辑整份结构，保存时校验可解析再回写。
 */
enum class SettingsFieldKind {
    /** 三态开关：继承 / 开 / 关（继承等于在 merge patch 里写 null）。 */
    BOOL,

    /** 整数，带范围校验。 */
    INTEGER,

    /** 单行文本。 */
    TEXT,

    /** 密钥类文本（输入框遮蔽，仓库与日志都不落明文）。 */
    SECRET,

    /** 有限枚举，值必须来自 [SettingsFieldSpec.options]。 */
    ENUM,

    /** 结构（对象 / 数组），以 JSON 文本编辑。 */
    JSON,

    /** 只读展示（来自 `paths` / `access` / `runtime`），不参与 patch。 */
    READ_ONLY,
}

/**
 * 一个设置字段。
 *
 * @param path 点分 JSON 路径，相对于 `user` / `effective` 的根
 * @param labelRes 字段标题
 * @param kind 控件形态
 * @param options [SettingsFieldKind.ENUM] 的取值
 * @param minimum / [maximum] [SettingsFieldKind.INTEGER] 的合法范围
 */
data class SettingsFieldSpec(
    val path: String,
    val labelRes: Int,
    val kind: SettingsFieldKind,
    val options: List<String> = emptyList(),
    val minimum: Long? = null,
    val maximum: Long? = null,
) {
    val writable: Boolean get() = kind != SettingsFieldKind.READ_ONLY
}

/** 一个设置 section：界面分组 + 字段表。 */
data class SettingsSectionSpec(
    val id: String,
    val titleRes: Int,
    val fields: List<SettingsFieldSpec>,
)

/** 14 个可写 section 的字段表，顺序对齐基线 `SETTINGS_SECTIONS`。 */
object SettingsSchema {

    private fun bool(path: String, label: Int) =
        SettingsFieldSpec(path, label, SettingsFieldKind.BOOL)

    private fun int(path: String, label: Int, min: Long? = null, max: Long? = null) =
        SettingsFieldSpec(path, label, SettingsFieldKind.INTEGER, minimum = min, maximum = max)

    private fun text(path: String, label: Int) =
        SettingsFieldSpec(path, label, SettingsFieldKind.TEXT)

    private fun secret(path: String, label: Int) =
        SettingsFieldSpec(path, label, SettingsFieldKind.SECRET)

    private fun json(path: String, label: Int) =
        SettingsFieldSpec(path, label, SettingsFieldKind.JSON)

    private fun readOnly(path: String, label: Int) =
        SettingsFieldSpec(path, label, SettingsFieldKind.READ_ONLY)

    /** `common.defaultValue` 的占位：枚举列出全部合法值，基线这里就是下拉。 */
    private fun enum(path: String, label: Int, options: List<String>) =
        SettingsFieldSpec(path, label, SettingsFieldKind.ENUM, options = options)

    private val LANGUAGES = listOf("auto", "zh-CN", "en-US")
    private val THEMES = listOf("dark", "light", "system")
    private val MOTION = listOf("system", "full", "reduced", "off")
    private val APPROVAL_MODES = listOf("ask", "write", "full_access")
    private val SHELL_ENVIRONMENTS = listOf("auto", "process")

    val SECTIONS: List<SettingsSectionSpec> = listOf(
        SettingsSectionSpec(
            id = "general",
            titleRes = R.string.settings_section_general,
            fields = listOf(
                enum("language", R.string.settings_language, LANGUAGES),
                enum("theme", R.string.settings_theme, THEMES),
                enum("motion_intensity", R.string.settings_motion, MOTION),
                text("ui_font_family", R.string.settings_ui_font),
                int("ui_font_size", R.string.settings_ui_font_size, 10, 32),
                text("reading_font_family", R.string.settings_reading_font),
                int("reading_font_size", R.string.settings_reading_font_size, 10, 48),
                text("source_editor_font_family", R.string.settings_source_font),
                bool("statistics_enabled", R.string.settings_statistics),
            ),
        ),
        SettingsSectionSpec(
            id = "writing",
            titleRes = R.string.settings_section_writing,
            fields = listOf(
                bool("auto_save_enabled", R.string.settings_auto_save),
                int("auto_save_interval_ms", R.string.settings_auto_save_interval, 200, 60_000),
                text("chapter_filename_format", R.string.settings_chapter_filename),
                text("volume_dir_format", R.string.settings_volume_dir),
                int("max_open_tabs", R.string.settings_max_tabs, 1, 50),
                int("chapter_group_min", R.string.settings_chapter_group_min, 1, 99),
                int("chapter_group_max", R.string.settings_chapter_group_max, 1, 999),
                text("ide_story_teller_id", R.string.settings_story_teller),
                text("writing_skill_default", R.string.settings_writing_skill),
            ),
        ),
        SettingsSectionSpec(
            id = "model",
            titleRes = R.string.settings_section_model,
            fields = listOf(
                json("model_endpoints", R.string.settings_model_endpoints),
                json("model_profiles", R.string.settings_model_profiles),
                text("agent_models.default.profile_id", R.string.settings_default_model),
            ),
        ),
        SettingsSectionSpec(
            id = "image",
            titleRes = R.string.settings_section_image,
            fields = listOf(
                json("image_api_endpoints", R.string.settings_image_endpoints),
                json("image_api_profiles", R.string.settings_image_profiles),
                text("default_image_api_profile_id", R.string.settings_default_image_profile),
                text("default_image_agent_id", R.string.settings_default_image_agent),
            ),
        ),
        SettingsSectionSpec(
            id = "decision",
            titleRes = R.string.settings_section_decision,
            fields = listOf(
                json("decision", R.string.settings_decision_json),
            ),
        ),
        SettingsSectionSpec(
            id = "speech",
            titleRes = R.string.settings_section_speech,
            fields = listOf(
                text("speech.endpoint", R.string.settings_speech_endpoint),
                secret("speech.api_key", R.string.settings_speech_key),
                text("speech.model", R.string.settings_speech_model),
                text("speech.voice", R.string.settings_speech_voice),
            ),
        ),
        SettingsSectionSpec(
            id = "approvals",
            titleRes = R.string.settings_section_approvals,
            fields = listOf(
                enum("agent_approval_mode", R.string.settings_approval_mode, APPROVAL_MODES),
                json("agent_approval_rules", R.string.settings_approval_rules),
            ),
        ),
        SettingsSectionSpec(
            id = "terminal",
            titleRes = R.string.settings_section_terminal,
            fields = listOf(
                bool("terminal_enabled", R.string.settings_terminal_enabled),
                text("terminal_shell", R.string.settings_terminal_shell),
                json("terminal_commands", R.string.settings_terminal_commands),
                int("terminal_max_sessions", R.string.settings_terminal_max_sessions, 1, 64),
                int("terminal_scrollback_kb", R.string.settings_terminal_scrollback, 16, 65_536),
                enum("shell_environment_mode", R.string.settings_shell_mode, SHELL_ENVIRONMENTS),
                text("shell_environment_shell", R.string.settings_shell_path),
                text("agent_bash_path", R.string.settings_bash_path),
            ),
        ),
        SettingsSectionSpec(
            id = "web-access",
            titleRes = R.string.settings_section_web_access,
            fields = listOf(
                json("web_access", R.string.settings_web_access_json),
            ),
        ),
        SettingsSectionSpec(
            id = "market",
            titleRes = R.string.settings_section_market,
            fields = listOf(
                text("market.registry_url", R.string.settings_market_registry),
                secret("market.github_token", R.string.settings_market_token),
            ),
        ),
        SettingsSectionSpec(
            id = "paths",
            titleRes = R.string.settings_section_paths,
            fields = listOf(
                text("skills_dir", R.string.settings_skills_dir),
                readOnly("paths.nova_dir", R.string.settings_nova_dir),
                readOnly("paths.user_config", R.string.settings_user_config),
            ),
        ),
        SettingsSectionSpec(
            id = "versions",
            titleRes = R.string.settings_section_versions,
            fields = listOf(
                bool("version_timed_enabled", R.string.settings_version_timed),
                int("version_timed_interval_minutes", R.string.settings_version_interval, 1, 1440),
            ),
        ),
        SettingsSectionSpec(
            id = "access",
            titleRes = R.string.settings_section_access,
            fields = listOf(
                bool("allow_lan_access", R.string.settings_allow_lan),
                text("remote_access_username", R.string.settings_lan_username),
                secret("remote_access_password", R.string.settings_lan_password),
                readOnly("effective.remote_access_password_set", R.string.settings_lan_password_set),
            ),
        ),
        SettingsSectionSpec(
            id = "updates",
            titleRes = R.string.settings_section_updates,
            fields = listOf(
                bool("update_check_enabled", R.string.settings_update_check),
                bool("update_download_enabled", R.string.settings_update_download),
            ),
        ),
        SettingsSectionSpec(
            id = "advanced",
            titleRes = R.string.settings_section_advanced,
            fields = listOf(
                int("model_max_retries", R.string.settings_model_retries, 0, 20),
                int("agent_idle_timeout_seconds", R.string.settings_idle_timeout, 0, 86_400),
                int("agent_tool_result_limit_kb", R.string.settings_tool_result_limit, 1, 4096),
                int("agent_tool_parallelism", R.string.settings_tool_parallelism, 1, 64),
                int("agent_subagent_parallelism", R.string.settings_subagent_parallelism, 1, 64),
                int("agent_script_timeout_seconds", R.string.settings_script_timeout, 0, 86_400),
                bool("plan_mode_default", R.string.settings_plan_mode),
                bool("labs.developer_mode", R.string.settings_developer_mode),
            ),
        ),
    )

    private val byPath: Map<String, SettingsFieldSpec> =
        SECTIONS.flatMap { it.fields }.associateBy { it.path }

    fun field(path: String): SettingsFieldSpec? = byPath[path]

    fun section(id: String): SettingsSectionSpec? = SECTIONS.firstOrNull { it.id == id }
}
