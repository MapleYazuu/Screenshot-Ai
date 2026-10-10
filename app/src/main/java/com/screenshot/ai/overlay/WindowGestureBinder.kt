package com.screenshot.ai.overlay

import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.util.Log

/**
 * 悬浮窗手势统一底座（方案 B：彻底重构）。
 *
 * 之前每个窗口各写一套拖拽/缩放状态机（previewDragStarted / resultTouchOffsetX /
 * historyDragStarted…），任何一处漏复位就会「卡死拖不动」。本类把「移动」和「缩放」
 * 收敛成**每窗口一个实例、每个手势一份局部状态**的单一实现：
 *
 * ## 拖拽（移动把手）
 * - DOWN 返回 true 并调用 `requestDisallowInterceptTouchEvent(true)`：
 *   任何父容器（ScrollView 等）都无法再拦截后续 MOVE —— 从根源上杜绝「把手被抢事件」；
 * - DOWN 时把居中窗口换算成显式坐标（左上角固定，无跳变）；
 * - MOVE 用 `event.rawX - 按下时触点偏移` 直接更新 params.x/y，
 *   每次更新都钳制进屏幕可用区，并调用 `updateViewLayout`（触摸回调天然在主线程）；
 * - UP/CANCEL 复位本手势状态并恢复父容器拦截权；仅在实际移动后才持久化位置。
 *
 * ## 缩放（右下角把手）
 * - 同样单一状态机：DOWN 记录起始宽高；首次 MOVE 把 WRAP_CONTENT 固化为显式宽高
 *   与显式左上角坐标；新尺寸 = 起始尺寸 + 手指位移，钳制在
 *   [minWidthPx/minHeightPx, 屏幕可用区右下边界]；UP 持久化尺寸。
 *
 * ## 保证
 * - 窗口 flags 不含 FLAG_NOT_TOUCHABLE（OverlayManager.PANEL_FLAGS/RESULT_FLAGS 已断言）；
 * - 所有 WindowManager 操作都在主线程（触摸事件回调）；
 * - 本类不持有任何跨手势状态，不存在「卡死状态」。
 */
class WindowGestureBinder(
    private val windowManager: WindowManager,
    /** 窗口右下角允许的最大坐标（x = 屏幕宽；y = 屏幕高 - 导航栏），由 OverlayManager 提供 */
    private val maxRightPx: () -> Int,
    private val maxBottomPx: () -> Int,
    private val clamp: (WindowManager.LayoutParams) -> Unit,
) {
    private companion object {
        const val TAG = "WindowGestureBinder"
    }

    /**
     * 绑定移动把手。
     *
     * @param window 整个悬浮窗 View（用于测位与 updateViewLayout）
     * @param handle 点阵把手（将置为 clickable，确保手势流完整）
     * @param paramsProvider 窗口参数的实时提供者（OverlayManager 持有真值）
     * @param onPersistPosition 拖动结束（x, y）持久化回调
     * @return 绑定到把手上的监听器（固定状态切换时由调用方解绑/重绑）
     */
    fun moveHandle(
        window: View,
        handle: View,
        paramsProvider: () -> WindowManager.LayoutParams?,
        onPersistPosition: (x: Int, y: Int) -> Unit,
    ): View.OnTouchListener {
        handle.isClickable = true
        handle.isFocusable = false
        val listener =
            object : View.OnTouchListener {
                private var touchOffsetX = 0
                private var touchOffsetY = 0
                private var moved = false
                private var active = false

                override fun onTouch(
                    v: View,
                    event: MotionEvent,
                ): Boolean {
                    val params = paramsProvider() ?: return false
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            // 关键：声明本把手独占本次手势，父容器（ScrollView）不得拦截
                            v.parent?.requestDisallowInterceptTouchEvent(true)
                            active = true
                            moved = false
                            // 居中 → 显式坐标：左上角固定，拖动第一帧零跳变
                            if (params.gravity == Gravity.CENTER) {
                                val loc = IntArray(2)
                                window.getLocationOnScreen(loc)
                                params.gravity = Gravity.TOP or Gravity.START
                                params.x = loc[0]
                                params.y = loc[1]
                            }
                            touchOffsetX = event.rawX.toInt() - params.x
                            touchOffsetY = event.rawY.toInt() - params.y
                            return true
                        }

                        MotionEvent.ACTION_MOVE ->
                            if (active) {
                                moved = true
                                params.x = event.rawX.toInt() - touchOffsetX
                                params.y = event.rawY.toInt() - touchOffsetY
                                clamp(params)
                                runCatching {
                                    windowManager.updateViewLayout(window, params)
                                }.onFailure { Log.w(TAG, "updateViewLayout 失败", it) }
                                return true
                            }

                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            if (active) {
                                active = false
                                v.parent?.requestDisallowInterceptTouchEvent(false)
                                if (moved) {
                                    onPersistPosition(params.x, params.y)
                                }
                            }
                            return true
                        }
                    }
                    return true
                }
            }
        handle.setOnTouchListener(listener)
        return listener
    }

    /**
     * 绑定缩放把手（右下角）。
     *
     * @param onSizeChanged 每帧回调（视图据此让内容自适应新宽高）
     * @param onPersistSize 缩放结束（宽, 高）持久化回调
     */
    fun resizeHandle(
        window: View,
        handle: View,
        paramsProvider: () -> WindowManager.LayoutParams?,
        onSizeChanged: (widthPx: Int, heightPx: Int) -> Unit,
        onPersistSize: (widthPx: Int, heightPx: Int) -> Unit,
        minWidthPx: Int,
        minHeightPx: Int,
    ): View.OnTouchListener {
        handle.isClickable = true
        handle.isFocusable = false
        val listener =
            object : View.OnTouchListener {
                private var downRawX = 0
                private var downRawY = 0
                private var startWidth = 0
                private var startHeight = 0
                private var active = false

                override fun onTouch(
                    v: View,
                    event: MotionEvent,
                ): Boolean {
                    val params = paramsProvider() ?: return false
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            v.parent?.requestDisallowInterceptTouchEvent(true)
                            active = true
                            downRawX = event.rawX.toInt()
                            downRawY = event.rawY.toInt()
                            startWidth = window.width.coerceAtLeast(1)
                            startHeight = window.height.coerceAtLeast(1)
                            return true
                        }

                        MotionEvent.ACTION_MOVE ->
                            if (active) {
                                // 首次缩放：WRAP/居中 → 显式尺寸 + 显式左上角（左上角固定）
                                if (params.width == WindowManager.LayoutParams.WRAP_CONTENT ||
                                    params.gravity == Gravity.CENTER
                                ) {
                                    val loc = IntArray(2)
                                    window.getLocationOnScreen(loc)
                                    params.gravity = Gravity.TOP or Gravity.START
                                    params.x = loc[0]
                                    params.y = loc[1]
                                    params.width = startWidth
                                    params.height = startHeight
                                }
                                clamp(params)
                                params.width = (startWidth + (event.rawX.toInt() - downRawX))
                                    .coerceAtLeast(minWidthPx)
                                    .coerceAtMost((maxRightPx() - params.x).coerceAtLeast(minWidthPx))
                                params.height = (startHeight + (event.rawY.toInt() - downRawY))
                                    .coerceAtLeast(minHeightPx)
                                    .coerceAtMost((maxBottomPx() - params.y).coerceAtLeast(minHeightPx))
                                runCatching {
                                    windowManager.updateViewLayout(window, params)
                                }.onFailure { Log.w(TAG, "updateViewLayout 失败", it) }
                                onSizeChanged(params.width, params.height)
                                return true
                            }

                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            if (active) {
                                active = false
                                v.parent?.requestDisallowInterceptTouchEvent(false)
                                onPersistSize(params.width, params.height)
                            }
                            return true
                        }
                    }
                    return true
                }
            }
        handle.setOnTouchListener(listener)
        return listener
    }
}
