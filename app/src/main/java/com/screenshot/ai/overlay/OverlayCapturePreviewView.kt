package com.screenshot.ai.overlay

import android.content.Context
import android.graphics.Bitmap
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import com.screenshot.ai.R
import com.screenshot.ai.databinding.OverlayCapturePreviewBinding
import com.screenshot.ai.util.Haptics

/**
 * 截图预览面板：预览图 + 确认 / 重拍 / 取消 + 固定图钉。
 *
 * 悬浮球是常驻控件，单击即发图存在误触风险（既浪费 token，也可能把隐私内容发给模型），
 * 因此「截图」入口走这条二次确认链路；而单击快捷模式刻意跳过预览，换取「即点即答」的速度。
 *
 * 固定 / 拖动：
 * - 标题栏是拖拽把手，手势监听由 OverlayManager 注入（[setDragEnabled]）；
 * - 图钉按钮切换固定状态：固定后 [setDragEnabled](false) 屏蔽拖拽，
 *   图标在空心/实心图钉之间切换（[setPinned]），业务回调走 [pinListener]。
 */
class OverlayCapturePreviewView(
    context: Context,
) : FrameLayout(context) {
    /**
     * 截图预览交互回调。
     * 预览图本身由调用方持有并在 [onRetry] 时回收，因为位图的生命周期
     * 需要跨「重拍」多次迭代，放在 View 里容易漏回收。
     */
    interface Listener {
        /** 确认使用当前截图 */
        fun onConfirm()

        /** 重新截屏 */
        fun onRetry()

        /** 取消预览（关闭面板） */
        fun onCancel()
    }

    var listener: Listener? = null

    /** 图钉点击回调：参数为点击后的目标状态（true = 固定） */
    var pinListener: ((pinned: Boolean) -> Unit)? = null

    /** 当前是否已固定 */
    private var pinned: Boolean = false

    /** 弹出/收起动画（单飞：新动画开始前先取消旧动画） */
    private var enterAnimator: android.view.ViewPropertyAnimator? = null
    private var exitAnimator: android.view.ViewPropertyAnimator? = null

    /** 预览图是否已做过首次淡入 */
    private var imageRevealed = false

    private val binding: OverlayCapturePreviewBinding =
        OverlayCapturePreviewBinding.inflate(LayoutInflater.from(context), this, true)

    init {
        isClickable = true
        val primary = resolveThemeColor(android.R.attr.textColorPrimary)
        val secondary = resolveThemeColor(android.R.attr.textColorSecondary)
        binding.previewTitle.setTextColor(primary)
        binding.previewTip.setTextColor(secondary)
        binding.previewConfirm.setTextColor(primary)
        binding.previewRetry.setTextColor(primary)
        // 取消：次级文字按钮（无 × 图标），颜色用次级色弱化层级
        binding.previewCancel.setTextColor(secondary)
        binding.previewConfirm.setOnClickListener { view ->
            Haptics.tap(view)
            listener?.onConfirm()
        }
        binding.previewRetry.setOnClickListener { view ->
            Haptics.tap(view)
            listener?.onRetry()
        }
        binding.previewCancel.setOnClickListener { view ->
            Haptics.tap(view)
            listener?.onCancel()
        }
        binding.previewPin.setOnClickListener { view ->
            Haptics.tap(view)
            pinListener?.invoke(!pinned)
        }
        applyPinVisual()
    }

    /** 点阵拖拽把手：只有按住这里才能拖动窗口（其余区域不响应拖拽，避免误触） */
    fun dragHandle(): View = binding.previewDragHandle

    /** 右下角缩放把手：只有按住这里才能调整窗口大小（与移动把手严格区分） */
    fun resizeGrip(): View = binding.previewResizeGrip

    /**
     * 窗口被手动缩放后按新尺寸重新布局：
     * 内容卡片撑满新宽高（默认 wrap），内部控件自适应、不裁剪。
     */
    fun applyWindowSize(
        widthPx: Int,
        heightPx: Int,
    ) {
        binding.previewContent.layoutParams = FrameLayout.LayoutParams(widthPx, heightPx)
        binding.previewContent.requestLayout()
    }

    /** 内容区最大高度（px）：窗口超过屏幕 70% 时内部滚动，不留空白 */
    fun setContentMaxHeight(maxHeightPx: Int) {
        binding.previewScroll.maxHeight = maxHeightPx
    }

    /** 固定/取消固定的视觉状态切换（实心图钉=已固定，空心图钉=未固定） */
    fun setPinned(pinned: Boolean) {
        this.pinned = pinned
        applyPinVisual()
    }

    private fun applyPinVisual() {
        val tint =
            if (pinned) {
                resolveThemeColor(androidx.appcompat.R.attr.colorPrimary)
            } else {
                resolveThemeColor(android.R.attr.textColorSecondary)
            }
        binding.previewPin.apply {
            setCompoundDrawablesWithIntrinsicBounds(
                if (pinned) R.drawable.ic_pin else R.drawable.ic_pin_outline,
                0,
                0,
                0,
            )
            compoundDrawables[0]?.mutate()?.setTintList(android.content.res.ColorStateList.valueOf(tint))
            contentDescription =
                context.getString(
                    if (pinned) R.string.overlay_preview_unpin else R.string.overlay_preview_pin,
                )
        }
    }

    /**
     * 开关拖拽能力：固定时传 enabled=false 屏蔽手势，取消固定后恢复。
     * 拖拽监听只挂在点阵把手上（dragHandle），其余区域绝不误触拖拽。
     */
    fun setDragEnabled(
        enabled: Boolean,
        dragListener: View.OnTouchListener?,
    ) {
        binding.previewDragHandle.setOnTouchListener(if (enabled) dragListener else null)
    }

    /**
     * 展示预览图。
     *
     * 布局里 imageView 用 wrap_content + adjustViewBounds，
     * 这里再把 Bitmap 等比缩放到 overlay_preview_max_* 之内：
     * 直接把 1080×2400 的原图塞进 ImageView，会先按窗口上限放大再缩小，白白多占一份显存。
     * 首次显示做一次 150ms 淡入，轻量缩略图 → 高清图替换时视觉无缝。
     */
    fun showBitmap(bitmap: Bitmap) {
        binding.previewImage.setImageBitmap(bitmap)
        binding.previewTip.visibility = View.GONE
        if (!imageRevealed) {
            imageRevealed = true
            binding.previewImage.alpha = 0f
            binding.previewImage
                .animate()
                .alpha(1f)
                .setDuration(150)
                .start()
        }
    }

    /** 展示提示文案（例如「正在截图…」「截图失败」） */
    fun showTip(text: CharSequence?) {
        if (text.isNullOrBlank()) {
            binding.previewTip.visibility = View.GONE
        } else {
            binding.previewTip.visibility = View.VISIBLE
            binding.previewTip.text = text
        }
    }

    /**
     * 弹出动画：alpha 0→1、scale 0.92→1、translationY 8dp→0，
     * 220ms + FastOutSlowIn（PathInterpolator 0.4/0/0.2/1）。
     *
     * 全部是变换属性（transform-only），不触发 requestLayout：
     * 窗口尺寸在 addView 之前已 measure 定稿，动画期间零重布局。
     */
    fun playEnterAnimation() {
        enterAnimator?.cancel()
        exitAnimator?.cancel()
        alpha = 0f
        scaleX = 0.92f
        scaleY = 0.92f
        translationY = dp(8).toFloat()
        val animator =
            animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .translationY(0f)
                .setDuration(ENTER_ANIMATION_MS)
                .setInterpolator(FAST_OUT_SLOW_IN)
        enterAnimator = animator
        animator.start()
    }

    /**
     * 收起动画：反向执行（alpha→0、scale→0.92、translationY→8dp），
     * 160ms，动画结束后再回调移除视图 —— 绝不瞬间消失。
     */
    fun playExitAnimation(onEnd: () -> Unit) {
        enterAnimator?.cancel()
        exitAnimator?.cancel()
        val animator =
            animate()
                .alpha(0f)
                .scaleX(0.92f)
                .scaleY(0.92f)
                .translationY(dp(8).toFloat())
                .setDuration(EXIT_ANIMATION_MS)
                .setInterpolator(FAST_OUT_SLOW_IN)
                .withEndAction(onEnd)
        exitAnimator = animator
        animator.start()
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    /** 读取当前主题颜色属性，浅色/深色模式都能正确显示 */
    private fun resolveThemeColor(attr: Int): Int {
        val value = TypedValue()
        if (!context.theme.resolveAttribute(attr, value, true)) {
            return ContextCompat.getColor(context, android.R.color.black)
        }
        return if (value.resourceId != 0) {
            ContextCompat.getColor(context, value.resourceId)
        } else {
            value.data
        }
    }

    companion object {
        /** 弹出动画时长 */
        private const val ENTER_ANIMATION_MS = 220L

        /** 收起动画时长 */
        private const val EXIT_ANIMATION_MS = 160L

        /** FastOutSlowIn：PathInterpolator(0.4, 0, 0.2, 1)，弹出如丝般顺滑 */
        private val FAST_OUT_SLOW_IN = PathInterpolator(0.4f, 0f, 0.2f, 1f)
    }
}
