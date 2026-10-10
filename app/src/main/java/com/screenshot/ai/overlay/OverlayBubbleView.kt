package com.screenshot.ai.overlay

import android.content.Context
import android.util.TypedValue
import android.view.LayoutInflater
import android.widget.FrameLayout
import com.screenshot.ai.databinding.OverlayBubbleBinding

/**
 * 桌面悬浮球（固定 56dp 圆形按钮，图标固定 24dp）。
 *
 * 尺寸说明：悬浮球大小调节功能已移除（用户反馈调节始终不生效、大小异常），
 * 改为固定尺寸，且**只在 [Companion] 里定义一处**（[BUBBLE_SIZE_DP] / [ICON_SIZE_DP]），
 * 后续要改大小只动这两个常量。
 *
 * 职责边界：
 * - 只负责「长什么样」：尺寸、透明度、图标；触摸手势交给
 *   [com.screenshot.ai.util.FloatingDragListener]，窗口定位交给
 *   [com.screenshot.ai.service.OverlayManager]；
 * - 这样切分的原因是贴边半隐藏需要同时改「窗口 x 坐标」和「View 自身偏移」，
 *   如果手势识别也写在这里，两类状态会互相覆盖。
 */
class OverlayBubbleView(
    context: Context,
) : FrameLayout(context) {
    private val binding: OverlayBubbleBinding =
        OverlayBubbleBinding.inflate(LayoutInflater.from(context), this, true)

    init {
        // 悬浮球本身不参与无障碍焦点：它长期悬浮在其它应用之上，
        // 加入无障碍树会干扰 TalkBack 用户对底层界面的浏览。
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        applyFixedSize()
    }

    /** 应用固定尺寸（56dp 球 + 24dp 图标），布局 XML 中的数值仅作首次膨胀的视觉占位 */
    private fun applyFixedSize() {
        val sizePx = dpToPx(BUBBLE_SIZE_DP.toFloat()).toInt()
        val iconPx = dpToPx(ICON_SIZE_DP.toFloat()).toInt()
        layoutParams =
            (
                layoutParams as? FrameLayout.LayoutParams
                    ?: FrameLayout.LayoutParams(sizePx, sizePx)
            ).apply {
                width = sizePx
                height = sizePx
            }
        binding.bubbleIcon.layoutParams =
            (
                binding.bubbleIcon.layoutParams
                    ?: FrameLayout.LayoutParams(iconPx, iconPx)
            ).apply {
                width = iconPx
                height = iconPx
            }
        requestLayout()
    }

    /**
     * 设置整体透明度。
     *
     * 与 [setVisibility] 的区别：这里只改绘制透明度，窗口仍然接收触摸事件，
     * 用户调低透明度是为了「看不那么见」，而不是「点不到」。
     */
    fun applyAlpha(alpha: Float) {
        this.alpha = alpha.coerceIn(0.3f, 1f)
    }

    /**
     * 贴边半隐藏时把可见部分整体推出屏幕。
     *
     * 不用 [setTranslationX] 的负值配合窗口外的做法，因为 TYPE_APPLICATION_OVERLAY
     * 窗口超出屏幕的部分本身就被裁掉，只需要平移 View 内容即可达到「只露一半」的效果。
     */
    fun setHorizontalOffset(offsetPx: Float) {
        translationX = offsetPx
    }

    /** 当前横向偏移（贴边动画的起点） */
    fun currentHorizontalOffset(): Float = translationX

    /**
     * 恢复显示时必须撤销贴边偏移。
     *
     * 重写而不是让调用方记得调用 [setHorizontalOffset]：`visibility` 会被多处代码
     * （动画、截屏隐藏、面板切换）改动，把复位逻辑收在这里才不会有漏改的分支。
     */
    override fun setVisibility(visibility: Int) {
        super.setVisibility(visibility)
        if (visibility == VISIBLE) {
            translationX = 0f
        }
    }

    private fun dpToPx(dp: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics)

    companion object {
        /** 悬浮球固定直径（dp）——尺寸全应用只在此定义一处 */
        const val BUBBLE_SIZE_DP = 56

        /** 悬浮球内部图标固定边长（dp） */
        const val ICON_SIZE_DP = 24
    }
}
