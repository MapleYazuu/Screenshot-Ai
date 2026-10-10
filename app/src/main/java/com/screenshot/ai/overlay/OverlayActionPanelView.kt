package com.screenshot.ai.overlay

import android.content.Context
import android.content.res.ColorStateList
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.screenshot.ai.R
import com.screenshot.ai.databinding.OverlayActionPanelBinding
import com.screenshot.ai.util.Haptics
import com.screenshot.ai.util.dp

/**
 * 径向弹出的四按钮菜单（截图 / 选择指令 / 历史 / 设置）——**独立悬浮窗根视图（重写版）**。
 *
 * ## 为什么独立成窗
 * 旧方案把菜单塞进悬浮球同一个窗口、展开时把窗口从 56dp 撑到 436dp：
 * 窗口尺寸变化 + 贴边吸附 + 拖拽手势互相干扰，按钮会飞出窗口（只剩一个）。
 *
 * ## 新方案（固定尺寸，零运行时几何计算）
 * - 本视图恒为 [RADIAL_SIZE_DP] 正方形，四个按钮在**构造时**一次性摆到四角
 *   （对角线 45° 布局），运行期不再计算任何坐标；
 * - 展开 = OverlayManager 以球心为中心 addView 本视图 + [showRadial] 缩放淡入；
 *   收起 = [hideRadial] 反向动画后 OverlayManager removeView；
 * - 点击菜单外空白（ACTION_OUTSIDE，窗口带 FLAG_WATCH_OUTSIDE_TOUCH）即收起；
 * - 无标题行、无 × 按钮。
 */
class OverlayActionPanelView(
    context: Context,
) : FrameLayout(context) {
    /** 面板交互回调 */
    interface Listener {
        /** 截图：截屏 → 预览 → 选择指令 → 提问 */
        fun onScreenshot()

        /** 选择预设指令 */
        fun onInputPreset()

        /** 查看历史记录 */
        fun onHistory()

        /** 打开设置页 */
        fun onSettings()
    }

    var listener: Listener? = null

    /** 点击菜单外空白时的回调（由 OverlayManager 注入以收起菜单） */
    var onOutsideTouch: (() -> Unit)? = null

    /** 菜单是否可见（= 已作为窗口加入且处于显示态） */
    val isMenuVisible: Boolean
        get() = visibility == View.VISIBLE

    private val binding: OverlayActionPanelBinding =
        OverlayActionPanelBinding.inflate(LayoutInflater.from(context), this, true)

    private val actionColor: Int = resolveThemeColor(android.R.attr.textColorPrimary)

    /** 展开 / 收起动画集合 */
    private val panelAnimators = mutableListOf<android.view.ViewPropertyAnimator>()

    /** 收起动画是否进行中（单飞标志） */
    private var collapsing = false

    init {
        // 菜单是浮在别的应用之上的临时控件，独立处理触摸，不把事件透传给下层窗口
        isClickable = true
        binding.actionScreenshot.text = context.getString(R.string.overlay_action_screenshot)
        binding.actionInput.text = context.getString(R.string.overlay_action_input)
        binding.actionHistory.text = context.getString(R.string.overlay_action_history)
        binding.actionSettings.text = context.getString(R.string.overlay_action_settings)
        applySatelliteStyle(binding.actionScreenshot)
        applySatelliteStyle(binding.actionInput)
        applySatelliteStyle(binding.actionHistory)
        applySatelliteStyle(binding.actionSettings)

        // 四角布局：中心留空（悬浮球在菜单窗口下方透过中心可见），按钮固定在四角
        val buttonPx = context.dp(SATELLITE_BUTTON_DP)
        val half = buttonPx / 2
        val centers =
            listOf(
                context.dp(38) to context.dp(38),
                context.dp(182) to context.dp(38),
                context.dp(38) to context.dp(182),
                context.dp(182) to context.dp(182),
            )
        buttons().forEachIndexed { index, button ->
            button.layoutParams =
                LayoutParams(buttonPx, buttonPx, Gravity.TOP or Gravity.START).apply {
                    leftMargin = centers[index].first - half
                    topMargin = centers[index].second - half
                }
        }

        binding.actionScreenshot.setOnClickListener { view ->
            Haptics.tap(view)
            listener?.onScreenshot()
        }
        binding.actionInput.setOnClickListener { view ->
            Haptics.tap(view)
            listener?.onInputPreset()
        }
        binding.actionHistory.setOnClickListener { view ->
            Haptics.tap(view)
            listener?.onHistory()
        }
        binding.actionSettings.setOnClickListener { view ->
            Haptics.tap(view)
            listener?.onSettings()
        }
    }

    /** 四个卫星按钮（顺序：截图 / 选择指令 / 历史 / 设置） */
    fun buttons(): List<TextView> =
        listOf(
            binding.actionScreenshot,
            binding.actionInput,
            binding.actionHistory,
            binding.actionSettings,
        )

    /** 语言切换后刷新按钮文案 */
    fun refreshLabels(localized: Context) {
        binding.actionScreenshot.text = localized.getString(R.string.overlay_action_screenshot)
        binding.actionInput.text = localized.getString(R.string.overlay_action_input)
        binding.actionHistory.text = localized.getString(R.string.overlay_action_history)
        binding.actionSettings.text = localized.getString(R.string.overlay_action_settings)
    }

    /** 展开：只动可见性与缩放/透明度（transform-only），窗口尺寸与坐标全程不变 */
    fun showRadial(animate: Boolean = true) {
        cancelAnimators()
        collapsing = false
        visibility = View.VISIBLE
        if (!animate) return

        buttons().forEachIndexed { index, button ->
            button.alpha = 0f
            button.scaleX = 0.2f
            button.scaleY = 0.2f
            button.pivotX = button.width / 2f
            button.pivotY = button.height / 2f
            val animator =
                button
                    .animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(RADIAL_ANIMATION_MS)
                    .setStartDelay(index * RADIAL_STAGGER_MS)
                    .setInterpolator(FAST_OUT_SLOW_IN)
            panelAnimators += animator
            animator.start()
        }
    }

    /** 收起：按钮缩小淡出，动画结束回调 [onHidden]（由 OverlayManager removeView） */
    fun hideRadial(
        animate: Boolean = true,
        onHidden: (() -> Unit)? = null,
    ) {
        if (collapsing) return
        collapsing = true
        cancelAnimators()

        val list = buttons()
        if (!animate || list.isEmpty()) {
            finishCollapse(onHidden)
            return
        }
        list.forEachIndexed { index, button ->
            val animator =
                button
                    .animate()
                    .alpha(0f)
                    .scaleX(0.2f)
                    .scaleY(0.2f)
                    .setDuration(RADIAL_ANIMATION_MS)
                    .setStartDelay(index * RADIAL_STAGGER_MS / 2)
                    .setInterpolator(FAST_OUT_SLOW_IN)
                    .withEndAction {
                        if (index == list.size - 1) {
                            finishCollapse(onHidden)
                        }
                    }
            panelAnimators += animator
            animator.start()
        }
    }

    private fun finishCollapse(onHidden: (() -> Unit)?) {
        if (visibility == View.GONE) {
            onHidden?.invoke()
            return
        }
        visibility = View.GONE
        collapsing = false
        buttons().forEach { button ->
            button.alpha = 1f
            button.scaleX = 1f
            button.scaleY = 1f
        }
        onHidden?.invoke()
    }

    private fun cancelAnimators() {
        panelAnimators.forEach { it.cancel() }
        panelAnimators.clear()
    }

    /** 菜单外空白点击即收起（窗口带 FLAG_WATCH_OUTSIDE_TOUCH） */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_OUTSIDE && isMenuVisible) {
            onOutsideTouch?.invoke()
            return true
        }
        return super.onTouchEvent(event)
    }

    /** 卫星按钮样式：图标在上、标签在下，56dp 圆角方形，点击区充足 */
    private fun applySatelliteStyle(button: TextView) {
        button.apply {
            gravity = Gravity.CENTER
            setPadding(context.dp(6), context.dp(6), context.dp(6), context.dp(6))
            setBackgroundResource(R.drawable.bg_overlay_action_button)
            setTextColor(actionColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, SATELLITE_LABEL_SP)
            compoundDrawablePadding = context.dp(2)
            tintCompoundDrawables(ColorStateList.valueOf(actionColor))
        }
    }

    /** 给 drawableTop 的矢量图标统一着色（fillColor 是占位色，深色模式必须重染） */
    private fun tintCompoundDrawables(tint: ColorStateList) {
        buttons().forEach { view ->
            val icon = view.compoundDrawables[1] ?: return@forEach
            icon.mutate().setTintList(tint)
            view.setCompoundDrawables(null, icon, null, null)
        }
    }

    private fun resolveThemeColor(attr: Int): Int {
        val value = TypedValue()
        return if (context.theme.resolveAttribute(attr, value, true)) {
            if (value.resourceId != 0) {
                ContextCompat.getColor(context, value.resourceId)
            } else {
                value.data
            }
        } else {
            ContextCompat.getColor(context, android.R.color.black)
        }
    }


    companion object {
        /** 菜单窗口固定边长（dp）：中心留空给悬浮球，四角放四个 56dp 按钮 */
        const val RADIAL_SIZE_DP = 220

        /** 卫星按钮固定边长（dp） */
        private const val SATELLITE_BUTTON_DP = 56

        /** 卫星按钮标签字号（sp） */
        private const val SATELLITE_LABEL_SP = 10f

        /** 径向弹出 / 收回动画时长 */
        private const val RADIAL_ANIMATION_MS = 220L

        /** 每个按钮的错峰延迟 */
        private const val RADIAL_STAGGER_MS = 30L

        /** FastOutSlowIn 曲线 */
        private val FAST_OUT_SLOW_IN = PathInterpolator(0.4f, 0f, 0.2f, 1f)
    }
}
