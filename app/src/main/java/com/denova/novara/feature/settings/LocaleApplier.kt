package com.denova.novara.feature.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/** 运行时语言应用器抽象，便于单元测试注入 fake（避免 AppCompatDelegate 依赖）。 */
interface LocaleApplier {
    fun applyLocale(tag: String)
}

/** 生产实现：经 AppCompatDelegate 应用 per-app locale。 */
object AppCompatLocaleApplier : LocaleApplier {
    override fun applyLocale(tag: String) {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
    }
}
