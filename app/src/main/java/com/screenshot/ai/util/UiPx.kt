package com.screenshot.ai.util

import android.content.Context
import kotlin.math.roundToInt

/**
 * dp → px 全应用唯一换算入口。
 *
 * 此前三个悬浮窗视图（历史面板 / 结果窗口 / 径向菜单）各写了一份私有 `dp()`，
 * 逻辑完全相同 —— 已合并到这里的 [dp] 扩展函数。
 */
fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

/** dp → px（保留浮点精度版本） */
fun Context.dp(value: Float): Float = value * resources.displayMetrics.density
