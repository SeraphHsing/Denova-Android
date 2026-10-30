package com.denova.novara.feature.shell

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import com.denova.novara.core.logging.DenovaLog

/**
 * 把 [EdgeSwipeGesture] 的判定接到 Compose 指针流上（NAV-008）。
 *
 * 必须挂在整个抽屉壳的父节点上：`PointerEventPass.Initial` 从根往叶分发，父节点先于
 * 页面里的 `LazyColumn` / `HorizontalPager` 看到事件；只有横向位移超过 8dp 认领后才消费事件，
 * 纵向阅读与点击都不受影响。
 *
 * 起手区语义由调用方给出的 [openSide] 与 [headerHeight] 决定：
 * - 都关闭：只在左右 24dp 边缘带起手；
 * - 某侧已打开：只有顶部标题栏高度的条带能起手关闭，抽屉内容里的横向控件保持可用。
 */
fun Modifier.drawerEdgeSwipe(
    openSide: DrawerSide?,
    headerHeight: Dp,
    onOpen: (DrawerSide) -> Unit,
    onClose: () -> Unit,
): Modifier = pointerInput(openSide, headerHeight) {
    val headerHeightPx = headerHeight.toPx()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val zone = when {
            openSide == null -> SwipeZone.SURFACE
            down.position.y <= headerHeightPx -> SwipeZone.DRAWER_HEADER
            else -> SwipeZone.DRAWER_CONTENT
        }
        DenovaLog.d(
            TAG,
            "down openSide=$openSide zone=$zone y=${down.position.y.toInt()} headerPx=${headerHeightPx.toInt()}",
        )
        var pending = EdgeSwipeGesture.begin(
            xDp = down.position.x / density,
            yDp = down.position.y / density,
            viewportWidthDp = size.width / density,
            openSide = openSide,
            zone = zone,
        ) ?: return@awaitEachGesture
        DenovaLog.d(TAG, "begin side=$openSide zone=$zone x=${down.position.x} y=${down.position.y}")

        var lastX = down.position.x
        var lastY = down.position.y
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            lastX = change.position.x
            lastY = change.position.y
            val next = EdgeSwipeGesture.move(pending, lastX / density, lastY / density)
                // 判定为纵向阅读：停止跟踪，把事件留给页面。
                ?: return@awaitEachGesture
            pending = next
            if (next.claimed && change.pressed) change.consume()
            if (!change.pressed) break
        }

        val outcome = EdgeSwipeGesture.end(pending, lastX / density, lastY / density)
        when (outcome) {
            is SwipeOutcome.Open -> onOpen(outcome.side)
            SwipeOutcome.Close -> onClose()
            SwipeOutcome.None -> Unit
        }
        if (outcome != SwipeOutcome.None) {
            DenovaLog.d(TAG, "end outcome=$outcome claimed=${pending.claimed}")
        }
    }
}

private const val TAG = "DrawerSwipe"
