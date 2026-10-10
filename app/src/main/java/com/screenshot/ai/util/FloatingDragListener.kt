package com.screenshot.ai.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * 悬浮球手势监听器：区分「单击 / 长按 / 拖动」三种意图。
 *
 * 系统不会帮我们区分这三种手势，必须自己实现，且规则上有两个容易踩的坑：
 * 1. 长按触发后抬起手指，绝不能再回调 [onTap]——否则用户长按一次会同时收到
 *    「长按」和「单击」两个动作（例如既打开了对话框又发起了截图）；
 * 2. 拖动阈值不能用很小的值，手指按在屏幕上天然会抖动几像素，
 *    阈值过小会把「单击」误判成「拖动」，悬浮球会莫名其妙地跑偏。
 *
 * 使用 Handler 而不是 View.postDelayed：本监听器会被复用到不同 View 上，
 * 绑定单一 Handler 便于在 ACTION_CANCEL 时可靠地取消待执行的长按。
 */
class FloatingDragListener(
    private val onTap: () -> Unit,
    private val onLongPress: () -> Unit,
    private val onDragEnd: (rawX: Int, rawY: Int) -> Unit,
    private val moveThresholdPx: Int = DEFAULT_MOVE_THRESHOLD_PX,
    private val longPressTimeoutMs: Long = DEFAULT_LONG_PRESS_TIMEOUT_MS,
) : View.OnTouchListener {
    /**
     * 拖动过程中的实时回调，由悬浮窗实现用它移动窗口位置。
     *
     * 声明成可空属性而不是构造参数：实时移动依赖具体 WindowManager 实例，
     * 而 [FloatingDragListener] 需要在窗口创建之前就能构造，
     * 这样也方便测试里只关心单击/长按的用法。
     */
    var onDrag: ((rawX: Int, rawY: Int) -> Unit)? = null

    /**
     * 拖动开始回调：越过阈值那一帧触发一次。
     *
     * 参数是「手指落点相对被触摸 View 左上角的偏移（touchX, touchY）」。
     * 上层必须用 `event.rawX - touchX` 反推窗口坐标，而不是把 rawX 直接当窗口 x，
     * 否则球会跳到手指下方（就是「没对准手」的错位感）。
     */
    var onDragStart: ((touchX: Int, touchY: Int) -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())

    private var downRawX = 0
    private var downRawY = 0

    /** 按下时手指相对 View 左上角的偏移：保证拖动第一帧不跳变 */
    private var downTouchOffsetX = 0
    private var downTouchOffsetY = 0

    /** 是否已经超过拖动阈值（一旦为 true 本次手势就不会再触发单击） */
    private var moved = false

    /** 本次手势的长按是否已经触发（防止长按后抬手又触发单击） */
    private var longPressFired = false

    /** 待执行的长按任务，用于在移动、抬手、取消时撤销 */
    private val longPressRunnable =
        Runnable {
            if (!moved && !longPressFired) {
                longPressFired = true
                // 震动反馈先于业务回调：用户按住的瞬间就该有触感确认
                performLongPressFeedback()
                onLongPress()
            }
        }

    /** 长按触发时所在的手势目标 View，用于震动反馈 */
    private var gestureTarget: View? = null

    override fun onTouch(
        view: View?,
        event: MotionEvent,
    ): Boolean {
        val rawX = event.rawX.roundToInt()
        val rawY = event.rawY.roundToInt()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gestureTarget = view
                downRawX = rawX
                downRawY = rawY
                // 关键修复：按下时记录「手指 - View 左上角」偏移。
                // 用 getLocationOnScreen 而不是 view.left/top：View 可能带
                // translationX（贴边半隐藏），left/top 不含平移量，会算错。
                val location = IntArray(2)
                view?.getLocationOnScreen(location)
                downTouchOffsetX = rawX - location[0]
                downTouchOffsetY = rawY - location[1]
                moved = false
                longPressFired = false
                handler.removeCallbacks(longPressRunnable)
                // 只有按下期间没有移动才算长按，因此延时任务里要再判一次 moved
                handler.postDelayed(longPressRunnable, longPressTimeoutMs)
            }

            MotionEvent.ACTION_MOVE -> {
                if (!moved) {
                    val distance =
                        hypot(
                            (rawX - downRawX).toDouble(),
                            (rawY - downRawY).toDouble(),
                        )
                    if (distance > moveThresholdPx) {
                        moved = true
                        // 已判定为拖动，长按必须立刻取消，否则拖到一半会弹出长按菜单
                        handler.removeCallbacks(longPressRunnable)
                        // 拖动开始：把按下时的偏移一次性交给上层，之后每帧只传 raw 坐标
                        onDragStart?.invoke(downTouchOffsetX, downTouchOffsetY)
                    }
                }
                if (moved) {
                    onDrag?.invoke(rawX, rawY)
                }
            }

            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPressRunnable)
                when {
                    // 长按已处理过本次手势，抬手时不再重复响应
                    longPressFired -> Unit
                    moved -> onDragEnd(rawX, rawY)
                    else -> onTap()
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                // 手势被系统打断（例如窗口被移除），不应该产生任何业务回调
                handler.removeCallbacks(longPressRunnable)
            }
        }
        return true
    }

    /** 长按触发时给一次震动反馈，让用户确认操作已生效（无马达的设备会自动忽略） */
    private fun performLongPressFeedback() {
        try {
            gestureTarget?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        } catch (e: Exception) {
            Log.w(TAG, "performLongPressFeedback: 震动反馈失败", e)
        }
    }

    companion object {
        /** 默认拖动阈值：约等于手指的自然抖动幅度上限 */
        const val DEFAULT_MOVE_THRESHOLD_PX = 12

        /** 默认长按判定时间，与系统 ViewConfiguration 保持一致 */
        const val DEFAULT_LONG_PRESS_TIMEOUT_MS = 500L

        private const val TAG = "FloatingDragListener"

        /**
         * dp 转 px。
         *
         * 阈值必须用 dp 表达：同样 12 在 3x 屏幕上只有 4dp，
         * 手指轻微抖动就会被判成拖动，所以对外提供这个换算入口。
         */
        fun distanceThresholdDp(
            context: Context,
            dp: Int,
        ): Int {
            val density = context.resources.displayMetrics.density
            return (dp * density).roundToInt()
        }
    }
}
