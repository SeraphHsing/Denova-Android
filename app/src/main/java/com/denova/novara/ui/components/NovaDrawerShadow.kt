package com.denova.novara.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import com.denova.novara.ui.theme.NovaDrawerSide
import com.denova.novara.ui.theme.NovaShadows

/**
 * 抽屉内容侧阴影：按基线 `.nova-mobile-sheet` 的两层 `box-shadow` 叠加。
 *
 * 基线是
 * `0 10px 15px -3px rgba(0,0,0,.1)` + `0 4px 6px -4px rgba(0,0,0,.1)`。
 *
 * 实现取舍（2026-11-01 真机实测后定的）：
 * 1. `drawBehind` + `BlurMaskFilter`：Android 硬件加速画布不支持 MaskFilter，抽屉边缘完全没有阴影；
 * 2. `Modifier.shadow` 叠加两层：被 `ModalDrawerSheet` 内部图层吞掉，采样边界像素是 255→168 硬切；
 * 3. 现在这版：在抽屉**朝内容区的一侧**按每层的 blur 宽度画线性渐变（颜色取该层自身 alpha），
 *    两层叠加形成由深到浅的过渡。不依赖 MaskFilter，硬件加速有效，也不参与命中测试。
 */
@Composable
fun Modifier.novaDrawerShadow(side: NovaDrawerSide): Modifier = drawWithContent {
    drawContent()
    NovaShadows.drawerLayers.forEach { layer ->
        val extent = layer.blur.toPx()
        if (extent <= 0f) return@forEach
        val solid = layer.color
        val clear = layer.color.copy(alpha = 0f)
        val brush: Brush
        val left: Float
        if (side == NovaDrawerSide.END) {
            // 右抽屉：阴影落在左侧，靠抽屉一侧最深，向外淡出。
            brush = Brush.horizontalGradient(
                0f to clear,
                1f to solid,
                startX = -extent,
                endX = 0f,
            )
            left = -extent
        } else {
            // 左抽屉：阴影落在右侧。
            brush = Brush.horizontalGradient(
                0f to solid,
                1f to clear,
                startX = size.width,
                endX = size.width + extent,
            )
            left = size.width
        }
        drawRect(
            brush = brush,
            topLeft = Offset(left, layer.offsetY.toPx()),
            size = Size(extent, size.height),
        )
    }
}
