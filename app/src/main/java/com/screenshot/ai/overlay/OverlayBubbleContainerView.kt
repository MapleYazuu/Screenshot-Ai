package com.screenshot.ai.overlay

import android.content.Context
import android.view.Gravity
import android.widget.FrameLayout

/**
 * 悬浮球容器（**重写版**：只承载悬浮球本体）。
 *
 * 径向四按钮菜单不再与悬浮球同窗口（旧方案展开时要改变窗口尺寸，
 * 贴边吸附/拖拽/动画三者互相干扰，导致「四个按钮只剩一个」）。
 * 新方案：悬浮球窗口恒为 56dp；长按时**另开一个独立的固定 220dp 正方形窗口**
 * 作为菜单（见 [OverlayActionPanelView]，由 OverlayManager 以球心为中心定位），
 * 收起时移除菜单窗口。悬浮球窗口的尺寸与坐标全程不变。
 */
class OverlayBubbleContainerView(
    context: Context,
) : FrameLayout(context) {

    /** 悬浮球（手势挂在这里） */
    val bubble: OverlayBubbleView = OverlayBubbleView(context)

    /**
     * 径向四按钮菜单（**不**作为本容器的子 View）。
     * 它是独立的悬浮窗根视图，由 OverlayManager 在展开时 addView、
     * 收起时 removeView；这里持有引用只为统一管理监听器与文案刷新。
     */
    val panel: OverlayActionPanelView = OverlayActionPanelView(context)

    init {
        addView(
            bubble,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START),
        )
    }
}
