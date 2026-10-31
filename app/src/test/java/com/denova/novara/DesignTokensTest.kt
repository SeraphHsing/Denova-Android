package com.denova.novara

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import com.denova.novara.ui.theme.NovaColors
import com.denova.novara.ui.theme.NovaDrawerSide
import com.denova.novara.ui.theme.NovaMotion
import com.denova.novara.ui.theme.NovaRadius
import com.denova.novara.ui.theme.NovaSpacing
import com.denova.novara.ui.theme.NovaText
import com.denova.novara.ui.theme.novaDrawerWidth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DesignTokens 的回归护栏：锁住「从基线变量解析出的值」而不是手挑近似色。
 *
 * 断言对象是固定 Web 基线 `85ec97c` 的 `web/src/index.css`（`:root` 与 `[data-theme="light"]`）：
 * 半透明 token 的 alpha 由 CSS 的 `0~1` 乘 255 四舍五入得到，RGB 分量原样保留；
 * 明暗两套的主题方向（深底浅字 / 浅底深字）与阶梯单调性同样属于契约。
 */
class DesignTokensTest {

    @Test
    fun `dark and light palettes keep baseline contrast direction`() {
        assertTrue(NovaColors.Dark.text.luminance() > NovaColors.Dark.surface.luminance())
        assertTrue(NovaColors.Dark.surface.luminance() > NovaColors.Dark.bg.luminance())
        assertTrue(NovaColors.Dark.hover.luminance() > NovaColors.Dark.surface3.luminance())

        // 浅色基线：白色只属于内容画布，chrome 用逐级变深的中性灰（见 index.css 的 light 段注释）。
        assertTrue(NovaColors.Light.text.luminance() < NovaColors.Light.surface.luminance())
        assertTrue(NovaColors.Light.surface.luminance() < NovaColors.Light.bg.luminance())
        assertTrue(NovaColors.Light.hover.luminance() < NovaColors.Light.surface.luminance())
    }

    @Test
    fun `translucent tokens keep the values parsed from css rgba`() {
        // :root
        assertColor(0x21A85852, NovaColors.Dark.dangerBg)
        assertColor(0x61BE655E, NovaColors.Dark.dangerBorder)
        assertColor(0x26A17B43, NovaColors.Dark.warningBg)
        assertColor(0x2964836F, NovaColors.Dark.successBg)
        assertColor(0x2164836F, NovaColors.Dark.loreChipBg)
        assertColor(0x3064836F, NovaColors.Dark.loreChipHoverBg)
        assertColor(0x12FFFFFA, NovaColors.Dark.chipBg)
        assertColor(0x1AFFFFFA, NovaColors.Dark.chipHoverBg)
        assertColor(0x14FFFFFA, NovaColors.Dark.menuItemHoverBg)
        assertColor(0xFA0A0A0A, NovaColors.Dark.menuBg)

        // [data-theme="light"]
        assertColor(0x1AB42318, NovaColors.Light.dangerBg)
        assertColor(0x52B42318, NovaColors.Light.dangerBorder)
        assertColor(0x2EB47A10, NovaColors.Light.warningBg)
        assertColor(0x1F156F3F, NovaColors.Light.successBg)
        assertColor(0x17396A4B, NovaColors.Light.loreChipBg)
        assertColor(0x24396A4B, NovaColors.Light.loreChipHoverBg)
        assertColor(0x0F000000, NovaColors.Light.chipBg)
        assertColor(0x17000000, NovaColors.Light.chipHoverBg)
        assertColor(0x0E000000, NovaColors.Light.styleChipBg)
        assertColor(0x16000000, NovaColors.Light.styleChipHoverBg)
        assertColor(0xFAFFFFFF, NovaColors.Light.menuBg)
    }

    @Test
    fun `solid tokens match the css hex literals`() {
        assertColor(0xFF0A0A0A, NovaColors.Dark.surface)
        assertColor(0xFF1A1A1A, NovaColors.Dark.surface3)
        assertColor(0xFF2A2A29, NovaColors.Dark.active)
        assertColor(0xFF1E1E1E, NovaColors.Dark.borderSoft)
        assertColor(0xFFBDBDB8, NovaColors.Dark.textMuted)
        assertColor(0xFF92928D, NovaColors.Dark.textFaint)
        assertColor(0xFF91B39B, NovaColors.Dark.success)
        assertColor(0xFF789881, NovaColors.Dark.successMuted)
        assertColor(0xFFC39A58, NovaColors.Dark.accentBlue)

        assertColor(0xFFEEEEEE, NovaColors.Light.hover)
        assertColor(0xFF525252, NovaColors.Light.textMuted)
        assertColor(0xFF737373, NovaColors.Light.textFaint)
        assertColor(0xFFB42318, NovaColors.Light.danger)
        assertColor(0xFF7A5200, NovaColors.Light.warning)
        assertColor(0xFF156F3F, NovaColors.Light.success)
        assertColor(0xFF2F6F46, NovaColors.Light.successMuted)
        assertColor(0xFF396A4B, NovaColors.Light.accentGreen)
    }

    @Test
    fun `spacing and radius ramps stay monotonic`() {
        val spacing = listOf(
            NovaSpacing.xxs,
            NovaSpacing.xs,
            NovaSpacing.chip,
            NovaSpacing.sm,
            NovaSpacing.md,
            NovaSpacing.lg,
            NovaSpacing.xl,
            NovaSpacing.xxl,
        )
        assertEquals(spacing.sortedBy { it.value }, spacing)

        val radius = listOf(
            NovaRadius.xs,
            NovaRadius.sm,
            NovaRadius.field,
            NovaRadius.button,
            NovaRadius.card,
            NovaRadius.group,
            NovaRadius.sheet,
        )
        assertEquals(radius.sortedBy { it.value }, radius)
    }

    @Test
    fun `type ramp follows the nova-ui scale`() {
        val ramp = listOf(
            NovaText.nano,
            NovaText.micro,
            NovaText.compact,
            NovaText.body,
            NovaText.large,
            NovaText.title,
            NovaText.heading,
            NovaText.display,
        )
        assertEquals(ramp.sortedBy { it.fontSize.value }, ramp)
        assertEquals(17f, NovaText.drawerTitle.fontSize.value, 0.01f)
        assertEquals(15f, NovaText.navLabel.fontSize.value, 0.01f)
        assertEquals(16f, NovaText.fieldLabel.fontSize.value, 0.01f)
        assertEquals(18f, NovaText.reading.fontSize.value, 0.01f)
    }

    @Test
    fun `motion tokens degrade to the baseline reduced and off values`() {
        assertEquals(140, NovaMotion.FAST_MILLIS)
        assertEquals(220, NovaMotion.NORMAL_MILLIS)
        assertEquals(280, NovaMotion.SLOW_MILLIS)
        assertEquals(180, NovaMotion.PANEL_MILLIS)
        assertEquals(120, NovaMotion.PANEL_FADE_MILLIS)

        assertTrue(NovaMotion.REDUCED_FAST_MILLIS < NovaMotion.FAST_MILLIS)
        assertTrue(NovaMotion.REDUCED_NORMAL_MILLIS < NovaMotion.NORMAL_MILLIS)
        assertTrue(NovaMotion.REDUCED_SLOW_MILLIS < NovaMotion.SLOW_MILLIS)
        assertEquals(NovaMotion.OFF_MILLIS, NovaMotion.panelMillis(reduced = true))
        assertEquals(NovaMotion.OFF_MILLIS, NovaMotion.panelFadeMillis(reduced = true))
    }

    @Test
    fun `drawer width follows the baseline clamp`() {
        assertEquals(358.dp, novaDrawerWidth(390.dp, NovaDrawerSide.START))
        assertEquals(288.dp, novaDrawerWidth(320.dp, NovaDrawerSide.START))
        // 宽屏达到上限，但仍不超过「可用宽度 − 32dp」。
        assertEquals(384.dp, novaDrawerWidth(800.dp, NovaDrawerSide.START))
        assertEquals(512.dp, novaDrawerWidth(800.dp, NovaDrawerSide.END))
    }

    private fun assertColor(expectedArgb: Long, actual: Color) {
        val expected = Color(expectedArgb)
        assertEquals("alpha", expected.alpha, actual.alpha, 0.0005f)
        assertEquals("red", expected.red, actual.red, 0.0005f)
        assertEquals("green", expected.green, actual.green, 0.0005f)
        assertEquals("blue", expected.blue, actual.blue, 0.0005f)
    }
}
