package com.denova.novara.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.denova.novara.ui.theme.NovaRadius
import com.denova.novara.ui.theme.NovaSize
import com.denova.novara.ui.theme.NovaText

/**
 * 设计 token 唯一来源（04 §2 硬要求，P5 第一步）。
 *
 * 取值来自固定 Web 基线 `SeraphHsing/denova@85ec97c`：
 * - 颜色：`web/src/index.css` 的 `--nova-*` 与 `--primary` / `--primary-foreground`（`oklch` 已换算为 sRGB）；
 * - 尺寸：`web/src/mobile.css` 的 `@media (max-width: 1023px)` 段落，以及
 *   `docs/evidence/web-baseline/2026-10-30/metrics.json` 的 computed 读数。
 *
 * 页面与共用件只从这里取色与取尺寸；`MaterialTheme` 保留为 Compose 适配层，
 * 由 [NovaTheme] 把本文件的 token 映射成 `ColorScheme` / `Typography` / `Shapes`。
 */
object NovaSpacing {
    val xxs: Dp = 2.dp
    val xs: Dp = 4.dp
    val sm: Dp = 8.dp
    val md: Dp = 12.dp
    val lg: Dp = 16.dp
    val xl: Dp = 24.dp
    val xxl: Dp = 32.dp
}

/** 圆角阶梯：`--radius: 10px`（字段/按钮）、`--nova-mobile-radius: 18px`（分组行）、抽屉 24px。 */
object NovaRadius {
    val xs: Dp = 6.dp
    val sm: Dp = 8.dp
    val field: Dp = 10.dp
    val button: Dp = 12.dp
    val card: Dp = 14.dp
    val group: Dp = 18.dp
    val sheet: Dp = 24.dp
    val pill: Dp = 999.dp
}

/** 组件尺寸契约（Web 390×844 实测，见 `docs/evidence/web-baseline/2026-10-30/README.md`）。 */
object NovaSize {
    /** 移动顶栏 48px（`metrics.json` → `topBar.h`），左右 padding 12px。 */
    val topBar: Dp = 48.dp
    val topBarPadding: Dp = 12.dp

    /** 抽屉标题栏 56px（`sheetHeader.h`），左右 padding 12px。 */
    val drawerHeader: Dp = 56.dp
    val drawerHeaderPadding: Dp = 12.dp

    /** 工具组行 52px、创作组入口 58px。 */
    val navRow: Dp = 52.dp
    val navEntryRow: Dp = 58.dp

    /** 触控目标下限（`mobile.css` 对 button/tab/menuitem 的 `min-height: 44px`）。 */
    val touchTarget: Dp = 44.dp

    /** 输入框与按钮：Web 登录页实测 32px，触控目标要求 ≥44dp，暂按 44dp 落地（差异见 09 VG-006）。 */
    val input: Dp = 44.dp
    val button: Dp = 44.dp
    val iconButton: Dp = 40.dp

    /** 抽屉宽度：`min(可用宽度 - 32px, 24rem=384px)`，右抽屉上限 `32rem=512px`。 */
    val drawerMaxStart: Dp = 384.dp
    val drawerMaxEnd: Dp = 512.dp
    val drawerEdgeGap: Dp = 32.dp

    /** 图标尺寸：抽屉动作 svg 20px、导航图标容器 22×24、尾部 chevron 14px。 */
    val iconChevron: Dp = 14.dp
    val iconSm: Dp = 16.dp
    val iconAction: Dp = 20.dp
    val iconNavBoxWidth: Dp = 22.dp
    val iconNavBoxHeight: Dp = 24.dp
    val iconLarge: Dp = 22.dp
    val iconXl: Dp = 28.dp
}

/** 抽屉所在侧：决定宽度上限与圆角方向。 */
enum class NovaDrawerSide { START, END }

/**
 * 抽屉宽度 = `min(可用宽度 - 32dp, 上限)`，对应基线 `.nova-mobile-sheet` 的
 * `width: calc(100% - 2rem); max-width: 24rem` 与右抽屉的 `max-width: 32rem`。
 */
fun novaDrawerWidth(available: Dp, side: NovaDrawerSide): Dp {
    val max = if (side == NovaDrawerSide.END) NovaSize.drawerMaxEnd else NovaSize.drawerMaxStart
    val width = minOf(available - NovaSize.drawerEdgeGap, max)
    return if (width > 0.dp) width else available
}

/** 抽屉内容侧 24dp 圆角：左抽屉圆右侧，右抽屉圆左侧（`metrics.json` 的 `border-radius`）。 */
fun novaDrawerShape(side: NovaDrawerSide): RoundedCornerShape =
    if (side == NovaDrawerSide.END) {
        RoundedCornerShape(
            topStart = NovaRadius.sheet,
            bottomStart = NovaRadius.sheet,
            topEnd = 0.dp,
            bottomEnd = 0.dp,
        )
    } else {
        RoundedCornerShape(
            topStart = 0.dp,
            bottomStart = 0.dp,
            topEnd = NovaRadius.sheet,
            bottomEnd = NovaRadius.sheet,
        )
    }

/** 语义色板：与基线 `--nova-*` 一一对应，不含 Material3 推导色。 */
@Immutable
data class NovaColors(
    val bg: Color,
    val bgDeep: Color,
    val surface: Color,
    val surface2: Color,
    val surface3: Color,
    val hover: Color,
    val active: Color,
    val border: Color,
    val borderSoft: Color,
    val text: Color,
    val textMuted: Color,
    val textFaint: Color,
    val accent: Color,
    val primary: Color,
    val onPrimary: Color,
    val danger: Color,
    val dangerBg: Color,
    val dangerBorder: Color,
    val warning: Color,
    val warningBg: Color,
    val success: Color,
    val successMuted: Color,
    val successBg: Color,
    val fieldFocusBorder: Color,
    val chipBg: Color,
    val codeBg: Color,
    val codeText: Color,
    val markdownBorder: Color,
    val scrim: Color,
) {
    companion object {
        /** 基线默认（无 `data-theme`，深色）：`web/src/index.css` `:root`。 */
        val Dark = NovaColors(
            bg = Color(0xFF000000),
            bgDeep = Color(0xFF000000),
            surface = Color(0xFF0A0A0A),
            surface2 = Color(0xFF121212),
            surface3 = Color(0xFF1A1A1A),
            hover = Color(0xFF202020),
            active = Color(0xFF2A2A29),
            border = Color(0xFF292929),
            borderSoft = Color(0xFF1E1E1E),
            text = Color(0xFFF7F7F4),
            textMuted = Color(0xFFBDBDB8),
            textFaint = Color(0xFF92928D),
            accent = Color(0xFFD5D5CF),
            primary = Color(0xFFE5E5E5),
            onPrimary = Color(0xFF171717),
            danger = Color(0xFFD37A73),
            dangerBg = Color(0x21A85852),
            dangerBorder = Color(0x61BE655E),
            warning = Color(0xFFC49A58),
            warningBg = Color(0x26A17B43),
            success = Color(0xFF91B39B),
            successMuted = Color(0xFF789881),
            successBg = Color(0x2964836F),
            fieldFocusBorder = Color(0xFF555552),
            chipBg = Color(0x12FFFFFA),
            codeBg = Color(0xFF1C1C1B),
            codeText = Color(0xFFE0E0DC),
            markdownBorder = Color(0xFF2B2B29),
            scrim = Color(0x66000000),
        )

        /** 基线 `[data-theme="light"]`。 */
        val Light = NovaColors(
            bg = Color(0xFFFFFFFF),
            bgDeep = Color(0xFFF2F2F2),
            surface = Color(0xFFFAFAFA),
            surface2 = Color(0xFFF5F5F5),
            surface3 = Color(0xFFEDEDED),
            hover = Color(0xFFEEEEEE),
            active = Color(0xFFE3E3E3),
            border = Color(0xFFD4D4D4),
            borderSoft = Color(0xFFE5E5E5),
            text = Color(0xFF171717),
            textMuted = Color(0xFF525252),
            textFaint = Color(0xFF737373),
            accent = Color(0xFF2F2F2F),
            primary = Color(0xFF171717),
            onPrimary = Color(0xFFFAFAFA),
            danger = Color(0xFFB42318),
            dangerBg = Color(0x1AB42318),
            dangerBorder = Color(0x52B42318),
            warning = Color(0xFF7A5200),
            warningBg = Color(0x2EB47A10),
            success = Color(0xFF156F3F),
            successMuted = Color(0xFF2F6F46),
            successBg = Color(0x1F156F3F),
            fieldFocusBorder = Color(0xFF8A8A8A),
            chipBg = Color(0x0F000000),
            codeBg = Color(0xFFEEEEEE),
            codeText = Color(0xFF242424),
            markdownBorder = Color(0xFFD8D8D8),
            scrim = Color(0x66000000),
        )
    }
}

/** 字号阶梯：`--nova-ui-*`（正文 14/20）与移动端 `--nova-mobile-title-size`（17px）、`label`（15px）。 */
object NovaText {
    val nano = TextStyle(fontSize = 10.sp, lineHeight = 14.sp)
    val micro = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)
    val compact = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)
    val body = TextStyle(fontSize = 14.sp, lineHeight = 20.sp)
    val large = TextStyle(fontSize = 16.sp, lineHeight = 22.sp)
    val title = TextStyle(fontSize = 18.sp, lineHeight = 24.sp)
    val heading = TextStyle(fontSize = 20.sp, lineHeight = 28.sp)
    val display = TextStyle(fontSize = 24.sp, lineHeight = 32.sp)

    /** 抽屉标题：17px/600，letter-spacing −0.025em（Web 实测 −0.425px）。 */
    val drawerTitle = TextStyle(
        fontSize = 17.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.425).sp,
    )

    /** 抽屉导航标签：15px；创作组入口为 600，工具组为 400。 */
    val navLabel = TextStyle(fontSize = 15.sp, lineHeight = 22.sp)
    val navEntryLabel = navLabel.copy(fontWeight = FontWeight.SemiBold)

    /** 字段 label 与按钮文字：Web 登录页 16px/500（`metrics.json` → `login.fields` / `login.submit`）。 */
    val fieldLabel = TextStyle(
        fontSize = 16.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.Medium,
    )
    val buttonLabel = fieldLabel

    /** 分组标签：12px/500，基线 `--nova-ui-micro-font-size`。 */
    val sectionLabel = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Medium,
    )

    /** 阅读正文 18px、行高 1.7；源码编辑器 14px、终端 12px。 */
    val reading = TextStyle(fontSize = 18.sp, lineHeight = 30.sp)
    val sourceEditor = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontFamily = FontFamily.Monospace,
    )
    val terminal = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontFamily = FontFamily.Monospace,
    )
}

/** Material3 适配层：把 token 映射成 `Typography`，页面可继续用 `MaterialTheme.typography`。 */
fun novaTypography(): Typography = Typography(
    displaySmall = NovaText.display,
    headlineSmall = NovaText.heading,
    titleLarge = NovaText.title,
    titleMedium = NovaText.large,
    titleSmall = NovaText.body,
    bodyLarge = NovaText.body,
    bodyMedium = NovaText.body,
    bodySmall = NovaText.micro,
    labelLarge = NovaText.body.copy(fontWeight = FontWeight.Medium),
    labelMedium = NovaText.micro.copy(fontWeight = FontWeight.Medium),
    labelSmall = NovaText.micro.copy(fontWeight = FontWeight.Medium),
)

/** Material3 适配层：圆角阶梯。 */
fun novaShapes(): Shapes = Shapes(
    extraSmall = RoundedCornerShape(NovaRadius.xs),
    small = RoundedCornerShape(NovaRadius.sm),
    medium = RoundedCornerShape(NovaRadius.field),
    large = RoundedCornerShape(NovaRadius.card),
    extraLarge = RoundedCornerShape(NovaRadius.group),
)

/** 当前主题的语义色板；由 [NovaraTheme] 提供。 */
val LocalNovaColors = staticCompositionLocalOf { NovaColors.Dark }
