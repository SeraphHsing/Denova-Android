package com.denova.novara

import com.denova.novara.core.persistence.Preferences
import com.denova.novara.feature.settings.LocaleApplier
import com.denova.novara.feature.settings.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private class FakePreferences : Preferences {
        val dark = MutableStateFlow(false)
        val lang = MutableStateFlow("")
        override val darkTheme: Flow<Boolean> = dark
        override val language: Flow<String> = lang
        override suspend fun setDarkTheme(enabled: Boolean) { dark.value = enabled }
        override suspend fun setLanguage(tag: String) { lang.value = tag }
    }

    private class FakeLocaleApplier : LocaleApplier {
        val applied = mutableListOf<String>()
        override fun applyLocale(tag: String) { applied.add(tag) }
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `toggle dark theme flips and persists`() = runTest(dispatcher) {
        val prefs = FakePreferences()
        val vm = SettingsViewModel(prefs, FakeLocaleApplier())
        advanceUntilIdle()
        assertEquals(false, vm.darkTheme.value)

        vm.toggleDarkTheme()
        advanceUntilIdle()
        assertEquals(true, vm.darkTheme.value)
        assertEquals(true, prefs.dark.value)

        vm.toggleDarkTheme()
        advanceUntilIdle()
        assertEquals(false, vm.darkTheme.value)
    }

    @Test
    fun `set language persists and applies locale`() = runTest(dispatcher) {
        val prefs = FakePreferences()
        val applier = FakeLocaleApplier()
        val vm = SettingsViewModel(prefs, applier)
        advanceUntilIdle()

        vm.setLanguage("zh")
        advanceUntilIdle()

        assertEquals("zh", prefs.lang.value)
        assertEquals(listOf("zh"), applier.applied)
    }
}
