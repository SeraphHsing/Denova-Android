package com.denova.novara.core.logging

/**
 * 日志脱敏：在写入任何 sink 前，把敏感键值对与 Bearer/Basic 凭证替换为 `***`。
 * 覆盖密码、Cookie、配对 / attach token、API key、secret、session、authorization 等，
 * 避免敏感信息进入日志（见 AGENTS.md §5）。
 */
object Redactor {

    private val sensitiveAssignment = Regex(
        """(?i)(\b(?:password|passwd|pwd|token|pair_?token|attach_?token|apikey|api_?key|secret|session|sessionid|cookie|authorization)\b\s*[=:])[^\s,;&]*""",
    )

    private val credential = Regex("""(?i)\b(Bearer|Basic)\s+[^\s,;&]+""")

    fun redact(message: String): String {
        val masked = sensitiveAssignment.replace(message) { "${it.groupValues[1]}***" }
        return credential.replace(masked) { "${it.value.substringBefore(' ')} ***" }
    }
}
