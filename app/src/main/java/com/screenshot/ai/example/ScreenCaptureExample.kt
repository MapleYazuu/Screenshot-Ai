package com.screenshot.ai.example

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.screenshot.ai.service.ScreenCaptureService
import com.screenshot.ai.util.ImageUtils
import com.screenshot.ai.util.PermissionUtils

/**
 * ============================================================================
 * 最小可运行示例 ②：无障碍截屏（AccessibilityService.takeScreenshot）
 * ============================================================================
 *
 * 为什么用无障碍而不是 MediaProjection：
 * - MediaProjection 每次开始截屏都要弹一次系统授权弹窗，Android 11+ 甚至要求
 *   每次会话重新授权，「点一下悬浮球就出结果」的交互完全没法做；
 * - AccessibilityService.takeScreenshot() 只需要用户开启一次服务，之后随时可截，
 *   不需要任何存储权限，截图也不会进相册。
 *
 * 使用前提（两者缺一不可）：
 *   1. 系统设置中开启本应用的无障碍服务；
 *   2. `accessibility_service_config.xml` 里声明 `android:canTakeScreenshot="true"`。
 *
 * 三个最容易踩的坑（生产实现在 [ScreenCaptureService] 里都已处理）：
 *   - **图形内存泄漏**：`ScreenshotResult.hardwareBuffer` 必须 `close()`，
 *     并且 `Bitmap.wrapHardwareBuffer()` 得到的 HARDWARE 位图必须 `copy()` 成软件位图
 *     后再 `recycle()`，否则连续截几十次就会耗尽图形内存；
 *   - **首次调用失败**：服务刚连接或距上次截屏过近时会返回
 *     `ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT`，需要重试（本项目重试 3 次）；
 *   - **悬浮球入镜**：截屏前必须先隐藏悬浮窗并等待 ~120ms，否则截图里会有自己的悬浮球。
 */
object ScreenCaptureExample {
    private const val TAG = "ScreenCaptureExample"

    /** 无障碍服务是否已开启 */
    fun isAccessibilityEnabled(context: Context): Boolean = PermissionUtils.isAccessibilityServiceEnabled(context)

    /** 跳转无障碍设置页引导用户开启 */
    fun openAccessibilitySettings(context: Context) {
        PermissionUtils.openAccessibilitySettings(context)
    }

    /**
     * 截取当前屏幕。
     *
     * 这是整个示例的核心 —— 一行调用，内部完成「硬件缓冲区 -> 软件位图」的转换与释放。
     *
     * @return 软件位图（`Config.ARGB_8888`），失败返回 null；调用方负责 recycle()
     */
    suspend fun captureScreen(): Bitmap? {
        if (!ScreenCaptureService.isRunning()) {
            Log.w(TAG, "截屏失败：无障碍服务未开启")
            return null
        }
        return ScreenCaptureService.capture()
    }

    /**
     * 截屏并压缩成适合上传的尺寸，返回 Base64（不含 `data:` 前缀）。
     *
     * @param lowQuality true 时压缩到 512px（省 token），否则 1024px（保精度）
     */
    suspend fun captureAsBase64(lowQuality: Boolean = false): String? {
        val bitmap = captureScreen() ?: return null
        return try {
            val compressed = ImageUtils.compressForUpload(bitmap, maxEdge = if (lowQuality) 512 else 1024)
            // NO_WRAP 很关键：带换行的 Base64 放进 JSON 会导致解析失败
            ImageUtils.toBase64(compressed)
        } finally {
            // 原始截图用完立刻回收，避免长会话累积占用内存
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    /**
     * 非视觉模型的降级路径：本地 OCR 提取文字。
     *
     * 这样即使模型不支持图片输入（例如 deepseek-chat），截图场景依然可用。
     */
    suspend fun captureAsOcrText(): String? {
        val bitmap = captureScreen() ?: return null
        return try {
            com.screenshot.ai.util.OcrUtils
                .recognizeText(bitmap)
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }
}
