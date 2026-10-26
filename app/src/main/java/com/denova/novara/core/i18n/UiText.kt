package com.denova.novara.core.i18n

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/**
 * 可延迟解析的界面文案（NAV-007 第二遍）。
 *
 * ViewModel / 纯逻辑层不能直接用 `stringResource`（没有 Compose 作用域，也不该持有 Context），
 * 所以状态里存「资源 id + 参数」，由界面层在渲染时解析：
 *
 * - [Res]：固定文案，走 `values/` 与 `values-zh/`，随语言切换；
 * - [Raw]：服务端返回或运行时拼接的文本（例如后端错误 `message`），不做翻译但保持同一出口。
 *
 * 这样纯逻辑层的单测可以断言 `UiText.Res(R.string.xxx)`，不需要 Robolectric，也不会因为改文案而挂。
 */
sealed interface UiText {

    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText

    data class Raw(val text: String) : UiText

    companion object {
        /** 无参数的资源文案。 */
        fun of(@StringRes id: Int): UiText = Res(id)

        /** 带格式参数的资源文案（`%1$s` / `%1$d`）。 */
        fun of(@StringRes id: Int, vararg args: Any): UiText = Res(id, args.toList())

        /** 服务端返回 / 运行时拼接的原文。 */
        fun raw(text: String): UiText = Raw(text)
    }
}

/** 日志 / 非界面场景用的稳定标识（不参与展示）：资源文案给 `res:<id>`，原文原样返回。 */
fun UiText.debugName(): String = when (this) {
    is UiText.Res -> "res:$id"
    is UiText.Raw -> text
}

/** 非 Compose 场景解析（例如把文案拼进导航参数）。 */
fun UiText.resolve(context: android.content.Context): String = when (this) {
    is UiText.Res -> if (args.isEmpty()) context.getString(id) else context.getString(id, *args.toTypedArray())
    is UiText.Raw -> text
}

/** 界面层解析：Compose 作用域内调用。 */
@Composable
fun UiText.resolve(): String = when (this) {
    is UiText.Res -> if (args.isEmpty()) stringResource(id) else stringResource(id, *args.toTypedArray())
    is UiText.Raw -> text
}
