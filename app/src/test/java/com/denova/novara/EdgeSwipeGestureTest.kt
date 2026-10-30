package com.denova.novara

import com.denova.novara.feature.shell.DrawerSide
import com.denova.novara.feature.shell.EdgeSwipeGesture
import com.denova.novara.feature.shell.SwipeOutcome
import com.denova.novara.feature.shell.SwipeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 抽屉边缘手势（NAV-008）：逐条对齐基线 `use-mobile-drawer-swipe.ts` 的阈值与排除区。
 *
 * 视口统一取 390dp（基线验收宽度），所有坐标与阈值都是 dp。
 */
class EdgeSwipeGestureTest {

    private val width = 390f

    private fun beginSurface(x: Float, y: Float = 400f) = EdgeSwipeGesture.begin(
        xDp = x,
        yDp = y,
        viewportWidthDp = width,
        openSide = null,
        zone = SwipeZone.SURFACE,
    )

    @Test
    fun `swipe right from left edge opens left drawer`() {
        val pending = beginSurface(10f)
        assertNotNull(pending)
        val moved = EdgeSwipeGesture.move(pending, 40f, 402f)
        assertTrue(moved?.claimed == true)
        assertEquals(SwipeOutcome.Open(DrawerSide.LEFT), EdgeSwipeGesture.end(moved, 70f, 405f))
    }

    @Test
    fun `swipe left from right edge opens right drawer`() {
        val pending = beginSurface(width - 10f)
        val moved = EdgeSwipeGesture.move(pending, width - 60f, 400f)
        assertEquals(SwipeOutcome.Open(DrawerSide.RIGHT), EdgeSwipeGesture.end(moved, width - 70f, 400f))
    }

    @Test
    fun `gesture outside the edge bands is ignored`() {
        assertNull(beginSurface(width / 2f))
        // 24dp 是闭区间边界，超过 24dp 就不在带内。
        assertNotNull(beginSurface(EdgeSwipeGesture.EDGE_BAND_DP))
        assertNull(beginSurface(EdgeSwipeGesture.EDGE_BAND_DP + 0.01f))
        assertNotNull(beginSurface(width - EdgeSwipeGesture.EDGE_BAND_DP))
        assertNull(beginSurface(width - EdgeSwipeGesture.EDGE_BAND_DP - 0.01f))
    }

    @Test
    fun `only allowed sides are claimed`() {
        val rightOnly = EdgeSwipeGesture.begin(
            xDp = 10f,
            yDp = 400f,
            viewportWidthDp = width,
            openSide = null,
            zone = SwipeZone.SURFACE,
            allowedSides = setOf(DrawerSide.RIGHT),
        )
        assertNull(rightOnly)
    }

    @Test
    fun `short horizontal move does not open`() {
        val pending = beginSurface(10f)
        val moved = EdgeSwipeGesture.move(pending, 30f, 400f)
        assertEquals(SwipeOutcome.None, EdgeSwipeGesture.end(moved, 50f, 400f))
    }

    @Test
    fun `move claims the gesture after eight dp`() {
        val pending = beginSurface(10f)
        assertFalse(EdgeSwipeGesture.move(pending, 17f, 400f)?.claimed ?: false)
        assertTrue(EdgeSwipeGesture.move(pending, 19f, 400f)?.claimed ?: false)
    }

    @Test
    fun `vertical reading abandons the gesture`() {
        val pending = beginSurface(10f)
        // 纵向超过 8dp 且不小于横向：让给页面滚动。
        assertNull(EdgeSwipeGesture.move(pending, 12f, 430f))
        // 纵向未超过 8dp 时仍在跟踪。
        assertNotNull(EdgeSwipeGesture.move(pending, 12f, 405f))
    }

    @Test
    fun `open drawer only closes from the header`() {
        val fromHeader = EdgeSwipeGesture.begin(
            xDp = 200f,
            yDp = 20f,
            viewportWidthDp = width,
            openSide = DrawerSide.LEFT,
            zone = SwipeZone.DRAWER_HEADER,
        )
        assertTrue(fromHeader?.closing == true)
        val moved = EdgeSwipeGesture.move(fromHeader, 160f, 22f)
        assertEquals(SwipeOutcome.Close, EdgeSwipeGesture.end(moved, 120f, 24f))

        // 抽屉内容区起手不认领，内部横向控件（编辑器、分页）保持可用。
        assertNull(
            EdgeSwipeGesture.begin(
                xDp = 200f,
                yDp = 300f,
                viewportWidthDp = width,
                openSide = DrawerSide.LEFT,
                zone = SwipeZone.DRAWER_CONTENT,
            ),
        )
    }

    @Test
    fun `header swipe in the wrong direction does not close`() {
        val pending = EdgeSwipeGesture.begin(
            xDp = 120f,
            yDp = 20f,
            viewportWidthDp = width,
            openSide = DrawerSide.RIGHT,
            zone = SwipeZone.DRAWER_HEADER,
        )
        // 右抽屉关闭需要向右滑；向左滑是继续开的方向。
        val moved = EdgeSwipeGesture.move(pending, 60f, 20f)
        assertEquals(SwipeOutcome.None, EdgeSwipeGesture.end(moved, 50f, 20f))
    }

    @Test
    fun `overlay and interactive controls never claim the gesture`() {
        assertNull(
            EdgeSwipeGesture.begin(5f, 400f, width, null, SwipeZone.OVERLAY),
        )
        assertNull(
            EdgeSwipeGesture.begin(5f, 400f, width, null, SwipeZone.INTERACTIVE_CONTROL),
        )
        // 抽屉打开时同样不认领。
        assertNull(
            EdgeSwipeGesture.begin(5f, 20f, width, DrawerSide.LEFT, SwipeZone.INTERACTIVE_CONTROL),
        )
    }

    @Test
    fun `distance threshold is inclusive at forty eight dp`() {
        val pending = beginSurface(10f)
        assertEquals(
            SwipeOutcome.Open(DrawerSide.LEFT),
            EdgeSwipeGesture.end(pending, 10f + EdgeSwipeGesture.DISTANCE_DP, 400f),
        )
        assertEquals(
            SwipeOutcome.None,
            EdgeSwipeGesture.end(pending, 10f + EdgeSwipeGesture.DISTANCE_DP - 0.1f, 400f),
        )
    }

    @Test
    fun `ratio threshold rejects diagonal swipes`() {
        val pending = beginSurface(10f)
        // 54 / 40 = 1.35：恰好通过。
        assertEquals(SwipeOutcome.Open(DrawerSide.LEFT), EdgeSwipeGesture.end(pending, 64f, 440f))
        // 54 / 40.1 < 1.35：判为斜向，不触发。
        assertEquals(SwipeOutcome.None, EdgeSwipeGesture.end(pending, 64f, 440.1f))
    }

    @Test
    fun `closed pending never triggers`() {
        assertEquals(SwipeOutcome.None, EdgeSwipeGesture.end(null, 100f, 400f))
        assertNull(EdgeSwipeGesture.move(null, 100f, 400f))
    }
}
