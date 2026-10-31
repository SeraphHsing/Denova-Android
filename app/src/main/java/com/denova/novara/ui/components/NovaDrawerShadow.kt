package com.denova.novara.ui.components

import android.graphics.BlurMaskFilter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.denova.novara.ui.theme.NovaDrawerSide
import com.denova.novara.ui.theme.NovaShadows

/**
 * 抽屉内容侧阴影：按基线 `.nova-mobile-sheet` 的两层 `box-shadow` 自绘。
 *
 * 基线是
 * `0 10px 15px -3px rgba(0,0,0,.1)` + `0 4px 6px -4px rgba(0,0,0,.1)` 两层叠加；
 * Compose 的 `Modifier.shadow(elevation)` 只有单层、且 elevation 与 CSS blur 不等价，
 * 因此这里按 [NovaShadows.drawerLayers] 的 offsetY / blur / spread 逐个绘制。
 *
 * 绘制顺序：本修饰符位于 Surface / ModalDrawerSheet 的外层 modifier 链，
 * 先于组件自身背景绘制，所以盒子内部会被背景盖住，只有朝内容区一侧的阴影外溢可见——
 * 与 CSS `box-shadow` 的观感一致，也不参与命中测试（`drawBehind` 不拦截触摸）。
 *
 * [side] 只影响调用方可读性（左抽屉阴影落在右侧、右抽屉落在左侧）；
 * 阴影矩形以组件自身边界为准，两侧都会画，屏幕外的部分自然不可见。
 */
@Composable
fun Modifier.novaDrawerShadow(side: NovaDrawerSide): Modifier {
    val density = LocalDensity.current
    val layers = NovaShadows.drawerLayers
    // Paint 与 MaskFilter 每帧重建代价高，按 side / density 缓存。
    val paints = remember(side, density) {
        layers.map { layer ->
            android.graphics.Paint().apply {
                isAntiAlias = true
                color = layer.color.toArgb()
                if (layer.blur > 0.dp) {
                    // CSS blur-radius 与高斯 sigma 近似 2:1，BlurMaskFilter 用半径表达。
                    maskFilter = BlurMaskFilter(
                        with(density) { layer.blur.toPx() } / 2f,
                        BlurMaskFilter.Blur.NORMAL,
                    )
                }
            }
        }
    }
    return drawBehind {
        drawIntoCanvas { canvas ->
            layers.forEachIndexed { index, layer ->
                val spread = with(density) { layer.spread.toPx() }
                val offsetY = with(density) { layer.offsetY.toPx() }
                // 负 spread 让阴影矩形内缩，正 offsetY 让阴影整体下移（CSS 语义）。
                canvas.nativeCanvas.drawRect(
                    -spread,
                    -spread + offsetY,
                    size.width + spread,
                    size.height + spread + offsetY,
                    paints[index],
                )
            }
        }
    }
}
