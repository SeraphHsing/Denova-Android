package com.denova.novara.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import com.denova.novara.ui.theme.NovaRadius
import com.denova.novara.ui.theme.NovaSize
import com.denova.novara.ui.theme.NovaSpacing
import com.denova.novara.ui.theme.NovaText
import com.denova.novara.ui.theme.NovaTheme

/**
 * 共用件（P5 VG-005…009）：只从 [com.denova.novara.ui.theme.DesignTokens] 取尺寸与颜色。
 *
 * 取值依据固定 Web 基线 `SeraphHsing/denova@85ec97c` 的移动端 computed 读数
 * （`docs/evidence/web-baseline/2026-10-30/metrics.json`）：
 * - 顶栏 48px、左右 12px，动作图标 20px；
 * - 字段与按钮 32px 高 / 圆角 10px / 描边 1px / 字号 16px，label 15px/500；
 * - 触控目标另按 [NovaSize.touchTarget]（44dp）落地：高度 +12dp 是**有意偏离**，
 *   视觉参数（圆角/描边/字号/内边距）仍照抄基线，差异登记在 09 的 VG-006 / VG-007。
 */

/** 顶栏：48dp 高、左右 12dp、标题 17sp/600（窄屏压缩省略）、可选左侧导航图标与尾部动作。 */
@Composable
fun NovaTopBar(
    title: String,
    modifier: Modifier = Modifier,
    navigationIcon: ImageVector? = null,
    navigationContentDescription: String? = null,
    onNavigationClick: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val colors = NovaTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = NovaSize.topBar)
            .background(colors.bgDeep)
            .padding(horizontal = NovaSize.topBarPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (navigationIcon != null && onNavigationClick != null) {
            NovaIconButton(
                icon = navigationIcon,
                contentDescription = navigationContentDescription,
                onClick = onNavigationClick,
                modifier = Modifier.padding(end = NovaSpacing.xs),
            )
        }
        Text(
            title,
            style = NovaText.drawerTitle,
            color = colors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NovaSpacing.xxs),
            content = actions,
        )
    }
}

/** 顶栏文字动作：44dp 触控目标、15sp/500、左右 8dp（基线「保存 / 终端 / 写作」这类）。 */
@Composable
fun NovaTextAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = NovaTheme.colors
    Box(
        modifier = modifier
            .height(NovaSize.touchTarget)
            .clip(RoundedCornerShape(NovaRadius.field))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = NovaSpacing.sm)
            .alpha(if (enabled) 1f else 0.45f),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = NovaText.navEntryLabel,
            color = colors.text,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 图标动作：44dp 触控目标、20dp 图标、按下态用 `--nova-hover`。 */
@Composable
fun NovaIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color? = null,
) {
    val colors = NovaTheme.colors
    Box(
        modifier = modifier
            .size(NovaSize.touchTarget)
            .clip(RoundedCornerShape(NovaRadius.field))
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.45f),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = tint ?: colors.text,
            modifier = Modifier.size(NovaSize.iconAction),
        )
    }
}

/**
 * 文本输入：label 在框外上方（基线登录卡样式）、框高 [NovaSize.input]、
 * 圆角 [NovaRadius.field]、1dp 描边（聚焦切成 `fieldFocusBorder`）。
 */
@Composable
fun NovaTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    enabled: Boolean = true,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    leadingIcon: ImageVector? = null,
) {
    val colors = NovaTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(NovaRadius.field)
    val borderColor = when {
        isError -> colors.danger
        focused -> colors.fieldFocusBorder
        else -> colors.borderSoft
    }
    Column(modifier) {
        if (label != null) {
            Text(
                label,
                style = NovaText.fieldLabel,
                color = if (isError) colors.danger else colors.text,
                modifier = Modifier.padding(bottom = NovaSpacing.xs),
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = true,
            textStyle = NovaText.large.copy(color = colors.text),
            cursorBrush = SolidColor(colors.text),
            visualTransformation = visualTransformation,
            keyboardOptions = keyboardOptions,
            interactionSource = interaction,
            modifier = Modifier
                .fillMaxWidth()
                .height(NovaSize.input)
                .clip(shape)
                .background(colors.surface)
                .border(1.dp, borderColor, shape),
            decorationBox = { inner ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(NovaSize.input)
                        .padding(horizontal = NovaSpacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (leadingIcon != null) {
                        Icon(
                            leadingIcon,
                            contentDescription = null,
                            tint = colors.textMuted,
                            modifier = Modifier
                                .size(NovaSize.iconAction)
                                .padding(end = NovaSpacing.sm),
                        )
                    }
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty() && placeholder != null) {
                            Text(
                                placeholder,
                                style = NovaText.large,
                                color = colors.textMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        inner()
                    }
                }
            },
        )
    }
}

/** 按钮：44dp 高、圆角 10dp、15sp/500；`primary` 用反色主色，次按钮用 surface + 描边。 */
@Composable
fun NovaButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = true,
) {
    val colors = NovaTheme.colors
    val shape = RoundedCornerShape(NovaRadius.field)
    val container = if (primary) colors.primary else colors.surface
    val content = if (primary) colors.onPrimary else colors.text
    Box(
        modifier = modifier
            .height(NovaSize.button)
            .widthIn(min = 72.dp)
            .clip(shape)
            .background(container)
            .then(if (primary) Modifier else Modifier.border(1.dp, colors.borderSoft, shape))
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.5f),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = NovaText.buttonLabel,
            color = content,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = NovaSpacing.lg),
        )
    }
}

/** 搜索框：基线的紧凑变体（无 label、左侧 20dp 放大镜）。 */
@Composable
fun NovaSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    NovaTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = placeholder,
        enabled = enabled,
        leadingIcon = Icons.Filled.Search,
        modifier = modifier,
    )
}
