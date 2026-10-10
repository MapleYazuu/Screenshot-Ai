package com.screenshot.ai.util

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * 全局振动反馈出口。
 *
 * 分层设计：
 * - [tap]：轻触（KEYBOARD_TAP 或 12ms 一次振动），用于普通按钮点击；
 * - [longPress]：稍强长按反馈（LONG_PRESS），用于长按进入多选/拖拽等；
 * - 兼容性：Android 11+（API 31）用 VibratorManager，低版本直接用 Vibrator，
 *   时长控制在 10~20ms，不过度振动。
 *
 * 悬浮球长按的振动在 [FloatingDragListener] 内已有（LONG_PRESS 常量），
 * 本类负责按钮与面板侧的统一出口。
 */
object Haptics {

    /** 按钮轻触（View 体系，如悬浮窗面板按钮） */
    fun tap(view: View) {
        runCatching { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
    }

    /** 长按反馈（View 体系） */
    fun longPress(view: View) {
        runCatching { view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }
    }

    /** 显式时长振动（无 View 场景）：Android 11+ 走 VibratorManager，低版本走 Vibrator */
    fun vibrate(
        context: Context,
        durationMs: Long = 12L,
    ) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = context.getSystemService(VibratorManager::class.java)
                manager?.defaultVibrator?.vibrate(
                    VibrationEffect.createOneShot(
                        durationMs,
                        VibrationEffect.DEFAULT_AMPLITUDE,
                    ),
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Vibrator::class.java)
                @Suppress("DEPRECATION")
                vibrator?.vibrate(durationMs)
            }
        }
    }
}
