package com.denova.novara.feature.shell

/** 抽屉的左右两侧（与基线 `Side = 'left' | 'right'` 对齐）。 */
enum class DrawerSide { LEFT, RIGHT }

/** 起手位置语义：决定「是否允许成手势」而不需要访问节点树。 */
enum class SwipeZone {
    /** 主内容区（页面本身）。 */
    SURFACE,

    /** 抽屉标题栏：已打开时唯一允许关闭的起手区。 */
    DRAWER_HEADER,

    /** 抽屉内容区：已打开时不允许起手关闭（保留内部横向控件）。 */
    DRAWER_CONTENT,

    /** 输入框 / 滑杆 / 显式忽略区。 */
    INTERACTIVE_CONTROL,

    /** 对话框、菜单、popover：任何方向都不认领。 */
    OVERLAY,
}

/**
 * 已认领的手势（按下并落在允许的起手区）。
 *
 * @param startXDp 按下点横坐标（dp，相对窗口左边）
 * @param startYDp 按下点纵坐标（dp）
 * @param closing 是否处于「已打开、从标题栏起手关闭」的方向
 * @param claimed 横向位移是否已超过方向阈值（超过后可消费事件）
 */
data class PendingSwipe(
    val side: DrawerSide,
    val startXDp: Float,
    val startYDp: Float,
    val closing: Boolean,
    val claimed: Boolean = false,
)

/** 手势结束后的动作。 */
sealed interface SwipeOutcome {
    /** 不触发任何动作（位移不足、方向比例不符、已取消）。 */
    data object None : SwipeOutcome

    /** 打开指定一侧抽屉。 */
    data class Open(val side: DrawerSide) : SwipeOutcome

    /** 收起当前打开的抽屉。 */
    data object Close : SwipeOutcome
}

/**
 * 抽屉边缘手势判定（NAV-008）。
 *
 * 口径来源固定 Web 基线 `web/src/components/layout/use-mobile-drawer-swipe.ts`：
 * - 未打开时只有在左右各 24px 的边缘带起手才认领（[EdgeSwipeGesture.EDGE_BAND_DP]）；
 * - 已打开时只有抽屉标题栏起手才允许关闭，抽屉内容里的横向编辑器保持可用；
 * - 输入框、滑杆、`data-nova-swipe-ignore`、对话框 / 菜单 / popover 内的起手一律忽略；
 * - 移动过程中若纵向位移超过 8px 且不小于横向位移，判定为「垂直阅读」，立即放弃；
 * - 横向位移超过 8px 视为已认领（可以消费事件，避免父容器一起滚）；
 * - 抬起时要求 `dx * direction >= 48` 且 `|dx| >= |dy| * 1.35` 才真正开合。
 *
 * 本对象只做纯函数判定，不接触 Compose、不读布局、不分配集合，便于 JVM 单测；
 * 指针采样与状态切换在 `DrawerSwipeModifier` / `MainActivity` 里完成。
 */
object EdgeSwipeGesture {

    /** 左右边缘起手带宽（dp），对应基线 `x <= 24` / `x >= innerWidth - 24`。 */
    const val EDGE_BAND_DP = 24f

    /** 方向判定阈值（dp），对应基线 `Math.abs(dx) > 8` 与纵向放弃条件。 */
    const val DIRECTION_SLOP_DP = 8f

    /** 抬起时要求的最小横向位移（dp）。 */
    const val DISTANCE_DP = 48f

    /** 抬起时要求的横纵位移比。 */
    const val DISTANCE_RATIO = 1.35f

    /**
     * 按下时判定是否认领手势。
     *
     * @param xDp 按下点横坐标（dp）
     * @param yDp 按下点纵坐标（dp）
     * @param viewportWidthDp 窗口可用宽度（dp）
     * @param openSide 当前已打开的一侧；null 表示都关闭
     * @param zone 起手区语义
     * @param allowedSides 允许滑出的方向（基线 `sides`）
     */
    fun begin(
        xDp: Float,
        yDp: Float,
        viewportWidthDp: Float,
        openSide: DrawerSide?,
        zone: SwipeZone,
        allowedSides: Set<DrawerSide> = setOf(DrawerSide.LEFT, DrawerSide.RIGHT),
    ): PendingSwipe? {
        if (zone == SwipeZone.OVERLAY || zone == SwipeZone.INTERACTIVE_CONTROL) return null
        if (openSide != null) {
            // 已打开：只有标题栏起手可以关闭，方向与打开相反。
            if (zone != SwipeZone.DRAWER_HEADER) return null
            return PendingSwipe(side = openSide, startXDp = xDp, startYDp = yDp, closing = true)
        }
        if (zone != SwipeZone.SURFACE) return null
        val side = when {
            xDp <= EDGE_BAND_DP && DrawerSide.LEFT in allowedSides -> DrawerSide.LEFT
            xDp >= viewportWidthDp - EDGE_BAND_DP && DrawerSide.RIGHT in allowedSides -> DrawerSide.RIGHT
            else -> null
        } ?: return null
        return PendingSwipe(side = side, startXDp = xDp, startYDp = yDp, closing = false)
    }

    /**
     * 移动中更新判定。
     *
     * @return 仍在跟踪时返回（可能带 `claimed = true` 的新状态）；判定为纵向阅读时返回 null，调用方应停止跟踪。
     */
    fun move(pending: PendingSwipe?, xDp: Float, yDp: Float): PendingSwipe? {
        if (pending == null) return null
        val dx = xDp - pending.startXDp
        val dy = yDp - pending.startYDp
        val absDx = kotlin.math.abs(dx)
        val absDy = kotlin.math.abs(dy)
        // 纵向为主：让给页面滚动 / 阅读，不再跟踪。
        if (absDy > DIRECTION_SLOP_DP && absDy >= absDx) return null
        val claimed = pending.claimed || absDx > DIRECTION_SLOP_DP
        return if (claimed == pending.claimed) pending else pending.copy(claimed = true)
    }

    /** 抬起时判定最终动作；未满足位移与比例阈值一律 [SwipeOutcome.None]。 */
    fun end(pending: PendingSwipe?, xDp: Float, yDp: Float): SwipeOutcome {
        if (pending == null) return SwipeOutcome.None
        val dx = xDp - pending.startXDp
        val dy = yDp - pending.startYDp
        val absDx = kotlin.math.abs(dx)
        val absDy = kotlin.math.abs(dy)
        // direction：向左滑打开左抽屉为 +1；关闭时方向取反（基线 (side === 'left' ? 1 : -1) * (closing ? -1 : 1)）。
        val openDirection = if (pending.side == DrawerSide.LEFT) 1f else -1f
        val direction = openDirection * if (pending.closing) -1f else 1f
        if (dx * direction < DISTANCE_DP) return SwipeOutcome.None
        if (absDx < absDy * DISTANCE_RATIO) return SwipeOutcome.None
        return if (pending.closing) SwipeOutcome.Close else SwipeOutcome.Open(pending.side)
    }
}
