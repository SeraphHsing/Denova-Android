package com.denova.novara.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
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
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * 设计 token 唯一来源（04 §3 硬要求，P5 全部比对的度量基准）。
 *
 * 取值全部从固定 Web 基线 `SeraphHsing/denova@85ec97c` 的主题变量解析后抄写，禁止凭截图挑近似色：
 * - 颜色：`web/src/index.css` 的 `:root`（dark，基线默认）与 `[data-theme="light"]` 两段 `--nova-*`；
 *   其中 `oklch(...)` 值按 sRGB 换算（`--primary` / `--primary-foreground`，`[data-theme="dark"]` 段覆盖）；
 *   `rgba(r,g,b,a)` 按 `a × 255` 四舍五入写成 `0xAARRGGBB`。
 * - 字号 / 行高：同文件的 `--nova-ui-*`、`--nova-reading-*`、`--nova-source-editor-*`、`--nova-terminal-*`；
 *   移动端 `--nova-mobile-title-size: max(17px, UI字号)`、`--nova-mobile-label-size: max(15px, compact字号)`
 *   来自 `web/src/mobile.css` 的 `@media (max-width: 1023px)` 段。
 * - 尺寸 / 圆角 / 阴影 / 动效：`--nova-radius`、`--nova-mobile-radius`、`--nova-shadow`、
 *   `--nova-motion-*`、`--nova-panel-*-duration`、`--nova-ease`、`--nova-panel-motion-ease`；
 *   组件读数另见 `docs/evidence/web-baseline/2026-10-30/metrics.json`。
 *
 * 页面与共用件只从这里取色与取尺寸；`MaterialTheme` 保留为 Compose 适配层，
 * 由 [NovaraTheme] 把本文件的 token 映射成 `ColorScheme` / `Typography` / `Shapes`。
 */

/** 间距：基线控件内边距为 2 / 4 / 6 / 8 / 12 / 16 / 24 / 32px 的阶梯。 */
object NovaSpacing {
    val xxs: Dp = 2.dp
    val xs: Dp = 4.dp

    /** 小元素之间（chip、图标与文字）的间距，基线里的 6px。 */
    val chip: Dp = 6.dp
    val sm: Dp = 8.dp
    val md: Dp = 12.dp
    val lg: Dp = 16.dp
    val xl: Dp = 24.dp
    val xxl: Dp = 32.dp

    /** 文件标签条起始内边距 10px（编辑器标签行，介于 [sm] 与 [md] 之间）。 */
    val tab: Dp = 10.dp
}

/**
 * 圆角阶梯。
 *
 * - `--nova-radius: 8px`（dark 段）/ `.preset-workspace --radius: 0.625rem = 10px`（字段、按钮）；
 * - `--nova-mobile-radius: 18px`（分组卡片行）；
 * - 抽屉内容侧 `24px`；`1rem` 圆角（sheet）与胶囊按钮另计。
 */
object NovaRadius {
    /** `--radius-sm: calc(--radius × 0.6)`。 */
    val xs: Dp = 6.dp

    /** `--nova-radius`。 */
    val sm: Dp = 8.dp

    /** `--radius`（0.625rem）：输入框、按钮。 */
    val field: Dp = 10.dp
    val button: Dp = 12.dp

    /** `--radius-xl: calc(--radius × 1.4)`。 */
    val card: Dp = 14.dp

    /** `--nova-mobile-radius`：分组行、卡片。 */
    val group: Dp = 18.dp

    /** 抽屉内容侧圆角（`metrics.json` → `sheetHeader` 同层 `border-radius`）。 */
    val sheet: Dp = 24.dp
    val pill: Dp = 999.dp
}

/** 边框 / 描边厚度与图标线宽（04 §2：顶栏图标 1.8、导航图标 1.7）。 */
object NovaStroke {
    val hairline: Dp = 1.dp
    val field: Dp = 1.dp

    /** 字段聚焦时 `0 0 0 2px` 的外环。 */
    val focusRing: Dp = 2.dp

    /** 选中指示条等 2dp 粗描边（编辑器标签下沿）。 */
    val thick: Dp = 2.dp

    val iconStroke: Float = 1.8f
    val navIconStroke: Float = 1.7f
}

/** 组件尺寸契约（Web 390×844 实测，见 `docs/evidence/web-baseline/2026-10-30/README.md`）。 */
object NovaSize {
    /** 移动顶栏 48px（`metrics.json` → `topBar.h`），左右 padding 12px。 */
    val topBar: Dp = 48.dp
    val topBarPadding: Dp = 12.dp

    /** 抽屉标题栏 56px（`sheetHeader.h`），左右 padding 12px。 */
    val drawerHeader: Dp = 56.dp
    val drawerHeaderPadding: Dp = 12.dp

    /** 工具组行 52px、创作组入口 58px（`--nova-mobile-*` 与 04 §2 的「最低 52px / 58px」）。 */
    val navRow: Dp = 52.dp
    val navEntryRow: Dp = 58.dp

    /** 触控目标下限（`mobile.css` 对 button/tab/menuitem 的 `min-height: 44px`）。 */
    val touchTarget: Dp = 44.dp

    /** 输入框与按钮：Web 登录页实测 32px，触控目标要求 ≥44dp，按 44dp 落地（差异见 09 VG-006）。 */
    val input: Dp = 44.dp
    val button: Dp = 44.dp
    val iconButton: Dp = 40.dp

    /** 次按钮最小宽度：保证「打开 / 保存」这类两字按钮不被压成两行。 */
    val buttonMinWidth: Dp = 72.dp

    /** 抽屉宽度：`min(可用宽度 - 32px, 24rem=384px)`，右抽屉上限 `32rem=512px`。 */
    val drawerMaxStart: Dp = 384.dp
    val drawerMaxEnd: Dp = 512.dp
    val drawerEdgeGap: Dp = 32.dp

    /** 1px 分隔线 / 描边厚度，等价 [NovaStroke.hairline]。 */
    val hairline: Dp = NovaStroke.hairline

    /** 抽屉行内边距 14px（基线 navRow `padding-left: 14px`）。 */
    val navRowPadding: Dp = 14.dp

    /** 分隔线左缩进 48px（基线行内分隔线从图标右侧开始）。 */
    val dividerInset: Dp = 48.dp

    /** 可滚动抽屉底部兜底：手势导航区可能报 0，按 240dp 留白（NAV-010 实机修复）。 */
    val drawerBottomInset: Dp = 240.dp

    /** 抽屉内容侧阴影的 Compose elevation（几何见 [NovaShadows.drawer]）。 */
    val drawerShadow: Dp = 10.dp

    /** 树形缩进：首层 12dp、每级 +16dp（右抽屉文件树）。 */
    val treeIndent: Dp = 12.dp
    val treeIndentStep: Dp = 16.dp

    /** 图标尺寸：抽屉动作 svg 20px、导航图标容器 22×24、尾部 chevron 14px。 */
    val iconChevron: Dp = 14.dp
    val iconSm: Dp = 16.dp
    val iconAction: Dp = 20.dp
    val iconNavBoxWidth: Dp = 22.dp
    val iconNavBoxHeight: Dp = 24.dp
    val iconLarge: Dp = 22.dp
    val iconXl: Dp = 28.dp

    /** Composer 文本区域最大高度 `min(25dvh, 10rem)`；游戏行动列表 `min(25dvh, 12rem)`。 */
    val composerMaxHeight: Dp = 160.dp
    val actionListMaxHeight: Dp = 192.dp

    /** 弹窗内可滚动列表的最大高度（编辑器插图候选列表 360dp）。 */
    val menuMaxHeight: Dp = 360.dp

    /** 多行文本输入的最小高度（设置页长文本字段 120dp）。 */
    val textAreaMinHeight: Dp = 120.dp

    /** 面板 / 弹窗内可滚动区的统一上限（终端输出、资料库面板、预设与 Skills 列表共用 420dp）。 */
    val panelMaxHeight: Dp = 420.dp

    /** 游戏控制台日志区最大高度（避免长日志把整屏撑满）。 */
    val consoleLogMaxHeight: Dp = panelMaxHeight

    /** 预设编辑区高度区间（长表单不挤走底部按钮，也不无限拉长）。 */
    val presetEditorMinHeight: Dp = 200.dp
    val presetEditorMaxHeight: Dp = panelMaxHeight

    /** 调试面板的日志框高度与输入框宽度。 */
    val debugLogHeight: Dp = 160.dp
    val debugBoxWidth: Dp = 240.dp

    /** 书籍封面宽度（列表与详情共用，3:4 竖版）与摘要相对封面的左缩进。 */
    val coverWidth: Dp = 48.dp
    val bookSummaryIndent: Dp = 70.dp

    /** 书籍简介的展开上限与折叠高度。 */
    val descriptionMaxHeight: Dp = 140.dp
    val descriptionCollapsedHeight: Dp = 120.dp

    /** Agent 指令编辑区上限（下限复用 [textAreaMinHeight]）。 */
    val agentEditorMaxHeight: Dp = 300.dp

    /** Skills 编辑区高度区间与列表上限。 */
    val skillEditorMinHeight: Dp = 160.dp
    val skillEditorMaxHeight: Dp = 360.dp
    val skillListMaxHeight: Dp = panelMaxHeight

    /** 小尺寸加载指示器（资料库行内）与图片预览高度。 */
    val spinner: Dp = 18.dp
    val imagePreviewHeight: Dp = 140.dp

    /** 工作台历史抽屉的列表上限。 */
    val historyListMaxHeight: Dp = 460.dp

    /** 工作台下拉/命令列表上限。 */
    val dropdownMaxHeight: Dp = 220.dp

    /** 状态圆点直径（未读 / 变更标记）。 */
    val statusDot: Dp = 6.dp

    /** 游戏页：行动按钮最大宽度、选项最大宽度、列表上限与舞台区高度。 */
    val gameActionMaxWidth: Dp = 260.dp
    val gameChoiceMaxWidth: Dp = 220.dp
    val gameListMaxHeight: Dp = 380.dp
    val gameStageHeight: Dp = 180.dp

    /** 卡片与 chip 表面的 tonal elevation（Material3 适配层用）。 */
    val cardElevation: Dp = 6.dp
    val chipElevation: Dp = 3.dp

    /** 文件标签最大宽度与关闭按钮盒，避免长路径把标签条撑爆（编辑器标签条）。 */
    val tabMaxWidth: Dp = 120.dp
    val tabCloseBox: Dp = 18.dp
}

/** 字重：基线顶栏 / 移动标题 / 创作组入口为 600，按钮与字段 label 为 500，正文 400。 */
object NovaWeight {
    val regular: FontWeight = FontWeight.Normal
    val medium: FontWeight = FontWeight.Medium
    val semibold: FontWeight = FontWeight.SemiBold
    val bold: FontWeight = FontWeight.Bold
}

/** 字距：基线移动标题 `-0.025em`（17px 实测 −0.425px），工作台标题 `-0.012em`。 */
object NovaTracking {
    val none = 0.sp
    val title = (-0.025f).em
    val tight = (-0.012f).em
}

/** 字体族：`--nova-ui-font-family` / `--nova-reading-font-family` / `--nova-source-editor-font-family`。 */
object NovaFont {
    /** UI 与正文：`"SF Pro Text", "PingFang SC", …` 落到系统无衬线。 */
    val ui: FontFamily = FontFamily.SansSerif
    val reading: FontFamily = NovaFont.ui

    /** 源码编辑器与终端：`"SFMono-Regular", "Cascadia Code", …`。 */
    val mono: FontFamily = FontFamily.Monospace
}

/**
 * 阴影：Compose 只有 elevation 语义，这里保留基线的完整 `box-shadow` 几何（offset / blur / spread / color），
 * 需要精确还原时按 [NovaShadow] 自绘，只需近似层次时用 [NovaShadow.elevation]。
 */
@Immutable
data class NovaShadow(
    val offsetY: Dp,
    val blur: Dp,
    val spread: Dp,
    val color: Color,
) {
    /** `Modifier.shadow(elevation = …)` 的等效值。 */
    val elevation: Dp get() = blur
}

object NovaShadows {
    /** `--nova-shadow`：dark `0 18px 50px rgba(0,0,0,.36)`，light `0 18px 44px rgba(0,0,0,.14)`。 */
    fun panel(dark: Boolean): NovaShadow = if (dark) {
        NovaShadow(offsetY = 18.dp, blur = 50.dp, spread = 0.dp, color = Color(0x5C000000))
    } else {
        NovaShadow(offsetY = 18.dp, blur = 44.dp, spread = 0.dp, color = Color(0x24000000))
    }

    /** 抽屉内容侧 `.nova-mobile-sheet`：`0 10px 15px -3px rgba(0,0,0,.1)`。 */
    val drawer = NovaShadow(
        offsetY = 10.dp,
        blur = 15.dp,
        spread = (-3).dp,
        color = Color(0x1A000000),
    )

    /** 抽屉第二层：`0 4px 6px -4px rgba(0,0,0,.1)`（基线 `box-shadow` 是两层叠加）。 */
    val drawerSecondary = NovaShadow(
        offsetY = 4.dp,
        blur = 6.dp,
        spread = (-4).dp,
        color = Color(0x1A000000),
    )

    /** 抽屉的两层阴影，按基线顺序（先大后小）自绘，见 `Modifier.novaDrawerShadow`。 */
    val drawerLayers: List<NovaShadow> = listOf(drawer, drawerSecondary)

    /** 输入框聚焦外环：`0 0 0 2px color-mix(--preset-signal 20%, transparent)`。 */
    val fieldFocus = NovaShadow(
        offsetY = 0.dp,
        blur = 0.dp,
        spread = 2.dp,
        color = Color(0x33D5D5CF),
    )
}

/**
 * 动效：`--nova-motion-fast/normal/slow`、`--nova-panel-motion-duration`、`--nova-panel-fade-duration`。
 *
 * 基线按 `data-nova-motion` 提供 reduced / off 两档降级，Android 侧由系统动画缩放接入时取对应档位。
 */
object NovaMotion {
    const val FAST_MILLIS: Int = 140
    const val NORMAL_MILLIS: Int = 220
    const val SLOW_MILLIS: Int = 280
    const val PANEL_MILLIS: Int = 180
    const val PANEL_FADE_MILLIS: Int = 120

    /** `html[data-nova-motion="reduced"]` 覆盖值。 */
    const val REDUCED_FAST_MILLIS: Int = 80
    const val REDUCED_NORMAL_MILLIS: Int = 120
    const val REDUCED_SLOW_MILLIS: Int = 140

    /** `html[data-nova-motion="off"]` 覆盖值（等价于关闭动画）。 */
    const val OFF_MILLIS: Int = 1

    /** `--nova-ease: cubic-bezier(0.25, 1.1, 0.4, 1)`。 */
    val ease: Easing = CubicBezierEasing(0.25f, 1.1f, 0.4f, 1f)

    /** `--nova-panel-motion-ease: cubic-bezier(0.2, 0, 0, 1)`。 */
    val panelEase: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** 面板过渡时长，`reduced` 时按基线降到 1ms。 */
    fun panelMillis(reduced: Boolean = false): Int = if (reduced) OFF_MILLIS else PANEL_MILLIS

    /** 面板淡入淡出时长。 */
    fun panelFadeMillis(reduced: Boolean = false): Int = if (reduced) OFF_MILLIS else PANEL_FADE_MILLIS
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

/**
 * 语义色板：与基线 `--nova-*` 一一对应，不含 Material3 推导色。
 *
 * 字段顺序与 `index.css` 的 `:root` / `[data-theme="light"]` 顺序一致，便于逐项核对；
 * 半透明值写作 `0xAARRGGBB`，alpha 由 CSS 的 `0~1` 乘以 255 四舍五入得到。
 */
@Immutable
data class NovaColors(
    // 底座
    val bg: Color,
    val bgDeep: Color,
    val surface: Color,
    val surface2: Color,
    val surface3: Color,
    val hover: Color,
    val active: Color,
    val border: Color,
    val borderSoft: Color,
    // 文字
    val text: Color,
    val textMuted: Color,
    val textFaint: Color,
    // 强调与主色
    val accent: Color,
    val accentBlue: Color,
    val accentGreen: Color,
    val primary: Color,
    val onPrimary: Color,
    // 开关
    val switchChecked: Color,
    val switchCheckedBorder: Color,
    val switchThumb: Color,
    // 语义状态
    val danger: Color,
    val dangerForeground: Color,
    val dangerBg: Color,
    val dangerBorder: Color,
    val warning: Color,
    val warningBg: Color,
    val success: Color,
    val successMuted: Color,
    val successBg: Color,
    // 控件与外壳
    val fieldFocusBorder: Color,
    val appShellGlow: Color,
    val topbarShadow: Color,
    val controlShadow: Color,
    // 消息与对话
    val userMessageBorder: Color,
    val userMessageBgFrom: Color,
    val userMessageBgTo: Color,
    val userMessageText: Color,
    val assistantMessageBorder: Color,
    val assistantMessageBg: Color,
    val chatHeading: Color,
    // 浮层与选择
    val menuBg: Color,
    val menuItemHoverBg: Color,
    val selectionBg: Color,
    val dropBg: Color,
    /** Android 侧 M3 抽屉遮罩取值；基线抽屉遮罩由组件库自带，不单独开变量。 */
    val scrim: Color,
    // 文件树
    val treeText: Color,
    val treeIcon: Color,
    val treeFolder: Color,
    val treeChevron: Color,
    // chip
    val chipBg: Color,
    val chipHoverBg: Color,
    val styleChipBg: Color,
    val styleChipHoverBg: Color,
    val styleChipText: Color,
    val loreChipBg: Color,
    val loreChipHoverBg: Color,
    val loreChipText: Color,
    // 内容排版
    val codeBg: Color,
    val codeText: Color,
    val codeBlockBg: Color,
    val tableHeaderBg: Color,
    val markdownBorder: Color,
    val dialogueHighlight: Color,
    // 滚动条
    val scrollbarTrack: Color,
    val scrollbarThumb: Color,
    val scrollbarThumbHover: Color,
    // IDE 面板
    val editorIdeBg: Color,
    val editorIdeColor: Color,
    val editorIdeAccent: Color,
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
            accentBlue = Color(0xFFC39A58),
            accentGreen = Color(0xFF85AA90),
            // --primary / --primary-foreground：[data-theme="dark"] 段 oklch(0.922 0 0) / oklch(0.205 0 0)。
            primary = Color(0xFFE5E5E5),
            onPrimary = Color(0xFF171717),
            switchChecked = Color(0xFF405F4C),
            switchCheckedBorder = Color(0xFF527660),
            switchThumb = Color(0xFFF4FFF7),
            danger = Color(0xFFD37A73),
            dangerForeground = Color(0xFF111111),
            dangerBg = Color(0x21A85852), // rgba(168, 88, 82, 0.13)
            dangerBorder = Color(0x61BE655E), // rgba(190, 101, 94, 0.38)
            warning = Color(0xFFC49A58),
            warningBg = Color(0x26A17B43), // rgba(161, 123, 67, 0.15)
            success = Color(0xFF91B39B),
            successMuted = Color(0xFF789881),
            successBg = Color(0x2964836F), // rgba(100, 131, 111, 0.16)
            fieldFocusBorder = Color(0xFF555552),
            appShellGlow = Color(0x00000000),
            topbarShadow = Color(0x0AFFFFFF), // rgba(255, 255, 255, 0.04)
            controlShadow = Color(0x09FFFFFF), // rgba(255, 255, 255, 0.035)
            userMessageBorder = Color(0xFF3D3D3A),
            userMessageBgFrom = Color(0xFF2B2B29),
            userMessageBgTo = Color(0xFF242422),
            userMessageText = Color(0xFFF7F7F4),
            assistantMessageBorder = Color(0xFF292929),
            assistantMessageBg = Color(0xFF0D0D0D),
            chatHeading = Color(0xFFEFEFEB),
            menuBg = Color(0xFA0A0A0A), // rgba(10, 10, 10, 0.98)
            menuItemHoverBg = Color(0x14FFFFFA), // rgba(255, 255, 250, 0.08)
            selectionBg = Color(0x14FFFFFA), // rgba(255, 255, 250, 0.08)
            dropBg = Color(0xFF242422),
            scrim = Color(0x66000000),
            treeText = Color(0xFFC0C0BA),
            treeIcon = Color(0xFF969690),
            treeFolder = Color(0xFFB6B6B0),
            treeChevron = Color(0xFF85857F),
            chipBg = Color(0x12FFFFFA), // rgba(255, 255, 250, 0.07)
            chipHoverBg = Color(0x1AFFFFFA), // rgba(255, 255, 250, 0.1)
            styleChipBg = Color(0x12FFFFFA), // rgba(255, 255, 250, 0.07)
            styleChipHoverBg = Color(0x1AFFFFFA), // rgba(255, 255, 250, 0.1)
            styleChipText = Color(0xFFD8D8D2),
            loreChipBg = Color(0x2164836F), // rgba(100, 131, 111, 0.13)
            loreChipHoverBg = Color(0x3064836F), // rgba(100, 131, 111, 0.19)
            loreChipText = Color(0xFFA9C5B0),
            codeBg = Color(0xFF1C1C1B),
            codeText = Color(0xFFE0E0DC),
            codeBlockBg = Color(0xFF101010),
            tableHeaderBg = Color(0xFF181817),
            markdownBorder = Color(0xFF2B2B29),
            dialogueHighlight = Color(0xFFFFD166),
            scrollbarTrack = Color(0xFF080808),
            scrollbarThumb = Color(0xFF343432),
            scrollbarThumbHover = Color(0xFF50504C),
            editorIdeBg = Color(0xFF121212),
            editorIdeColor = Color(0xFFF7F7F4),
            editorIdeAccent = Color(0xFF242422),
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
            accentBlue = Color(0xFF595959),
            accentGreen = Color(0xFF396A4B),
            // [data-theme="light"] 段 oklch(0.205 0 0) / oklch(0.985 0 0)。
            primary = Color(0xFF171717),
            onPrimary = Color(0xFFFAFAFA),
            switchChecked = Color(0xFF2F2F2F),
            switchCheckedBorder = Color(0xFF2F2F2F),
            switchThumb = Color(0xFFFFFFFF),
            danger = Color(0xFFB42318),
            dangerForeground = Color(0xFFFFFFFF),
            dangerBg = Color(0x1AB42318), // rgba(180, 35, 24, 0.1)
            dangerBorder = Color(0x52B42318), // rgba(180, 35, 24, 0.32)
            warning = Color(0xFF7A5200),
            warningBg = Color(0x2EB47A10), // rgba(180, 122, 16, 0.18)
            success = Color(0xFF156F3F),
            successMuted = Color(0xFF2F6F46),
            successBg = Color(0x1F156F3F), // rgba(21, 111, 63, 0.12)
            fieldFocusBorder = Color(0xFF8A8A8A),
            appShellGlow = Color(0x00000000),
            topbarShadow = Color(0x12000000), // rgba(0, 0, 0, 0.07)
            controlShadow = Color(0x0D000000), // rgba(0, 0, 0, 0.05)
            userMessageBorder = Color(0xFFCCCCCC),
            userMessageBgFrom = Color(0xFFEEEEEE),
            userMessageBgTo = Color(0xFFE5E5E5),
            userMessageText = Color(0xFF171717),
            assistantMessageBorder = Color(0xFFDDDDDD),
            assistantMessageBg = Color(0xFFFFFFFF),
            chatHeading = Color(0xFF242424),
            menuBg = Color(0xFAFFFFFF), // rgba(255, 255, 255, 0.98)
            menuItemHoverBg = Color(0x12000000), // rgba(0, 0, 0, 0.07)
            selectionBg = Color(0x14000000), // rgba(0, 0, 0, 0.08)
            dropBg = Color(0xFFECECEC),
            scrim = Color(0x66000000),
            treeText = Color(0xFF242424),
            treeIcon = Color(0xFF666666),
            treeFolder = Color(0xFF4A4A4A),
            treeChevron = Color(0xFF777777),
            chipBg = Color(0x0F000000), // rgba(0, 0, 0, 0.06)
            chipHoverBg = Color(0x17000000), // rgba(0, 0, 0, 0.09)
            styleChipBg = Color(0x0E000000), // rgba(0, 0, 0, 0.055)
            styleChipHoverBg = Color(0x16000000), // rgba(0, 0, 0, 0.085)
            styleChipText = Color(0xFF3C3C3C),
            loreChipBg = Color(0x17396A4B), // rgba(57, 106, 75, 0.09)
            loreChipHoverBg = Color(0x24396A4B), // rgba(57, 106, 75, 0.14)
            loreChipText = Color(0xFF315F40),
            codeBg = Color(0xFFEEEEEE),
            codeText = Color(0xFF242424),
            codeBlockBg = Color(0xFFF5F5F5),
            tableHeaderBg = Color(0xFFEEEEEE),
            markdownBorder = Color(0xFFD8D8D8),
            dialogueHighlight = Color(0xFFA04A1A),
            scrollbarTrack = Color(0xFFF5F5F5),
            scrollbarThumb = Color(0xFFC7C7C7),
            scrollbarThumbHover = Color(0xFFA3A3A3),
            editorIdeBg = Color(0xFFFFFFFF),
            editorIdeColor = Color(0xFF171717),
            editorIdeAccent = Color(0xFFE0E0E0),
        )
    }
}

/**
 * 字号阶梯：`--nova-ui-*`（正文 14/20、nano 10/14、micro 12/16、compact 13/18、large 16/22、
 * title 18/24、heading 20/28、display 24/32），移动端 `--nova-mobile-title-size`（≥17px）、
 * `--nova-mobile-label-size`（≥15px），阅读正文 18px / 行高 1.7，源码 14px，终端 12px。
 */
object NovaText {
    val nano = TextStyle(fontSize = 10.sp, lineHeight = 14.sp)
    val micro = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)
    val compact = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)
    val body = TextStyle(fontSize = 14.sp, lineHeight = 20.sp)
    val large = TextStyle(fontSize = 16.sp, lineHeight = 22.sp)
    val title = TextStyle(fontSize = 18.sp, lineHeight = 24.sp)
    val heading = TextStyle(fontSize = 20.sp, lineHeight = 28.sp)
    val display = TextStyle(fontSize = 24.sp, lineHeight = 32.sp)

    /** 抽屉标题：`max(17px, UI字号)` / 600，字距 −0.025em。 */
    val drawerTitle = TextStyle(
        fontSize = 17.sp,
        lineHeight = 22.sp,
        fontWeight = NovaWeight.semibold,
        letterSpacing = NovaTracking.title,
    )

    /** 顶栏标题：与抽屉标题同口径（17sp / 600 / −0.025em）。 */
    val topBarTitle = drawerTitle

    /** 抽屉导航标签：`max(15px, compact 字号)`；创作组入口为 600，工具组为 400。 */
    val navLabel = TextStyle(fontSize = 15.sp, lineHeight = 22.sp)
    val navEntryLabel = navLabel.copy(fontWeight = NovaWeight.semibold)

    /** 字段 label 与按钮文字：Web 登录页 16px/500（`metrics.json` → `login.fields` / `login.submit`）。 */
    val fieldLabel = TextStyle(
        fontSize = 16.sp,
        lineHeight = 22.sp,
        fontWeight = NovaWeight.medium,
    )
    val buttonLabel = fieldLabel

    /** 分组标签：12px/500，基线 `--nova-ui-micro-font-size`。 */
    val sectionLabel = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = NovaWeight.medium,
    )

    /** 阅读正文 18px、行高 1.7（=30.6sp），字体族跟随 `--nova-reading-font-family`。 */
    val reading = TextStyle(
        fontSize = 18.sp,
        lineHeight = 30.6.sp,
        fontFamily = NovaFont.reading,
    )

    /**
     * 富文本编辑器正文：Web 390 实测 16px / 行高 30.4px（1.9×）/ 400 / 字距 `normal` / 段距 0。
     *
     * 与 [reading] 区分：`--nova-reading-font-size` 是阅读态的 18px，编辑器正文跟的是编辑器自身的
     * 字号档位（`编辑工具 → 编辑器设置` 里的 Aa 档），实测为 16px。
     */
    val editorBody = TextStyle(
        fontSize = 16.sp,
        lineHeight = 30.4.sp,
        fontFamily = NovaFont.ui,
    )

    /** 源码编辑器 14px / 20px。 */
    val sourceEditor = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontFamily = NovaFont.mono,
    )

    /** 终端 12px / 16px。 */
    val terminal = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontFamily = NovaFont.mono,
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
    labelLarge = NovaText.body.copy(fontWeight = NovaWeight.medium),
    labelMedium = NovaText.micro.copy(fontWeight = NovaWeight.medium),
    labelSmall = NovaText.micro.copy(fontWeight = NovaWeight.medium),
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
