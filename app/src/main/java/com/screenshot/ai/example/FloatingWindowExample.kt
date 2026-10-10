package com.screenshot.ai.example

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.screenshot.ai.service.OverlayManager

/**
 * ============================================================================
 * 最小可运行示例 ①：悬浮窗（WindowManager + TYPE_APPLICATION_OVERLAY）
 * ============================================================================
 *
 * 完整形态的悬浮工具栏见 [com.screenshot.ai.service.FloatingService] 与
 * [OverlayManager]，本文件把它们压缩成 ~80 行，方便想单独借鉴悬浮窗实现的读者
 * 快速看懂「一个能拖动、能点击、能贴边的桌面悬浮球」最少需要哪些代码。
 *
 * 三步走：
 *   1. 权限：SYSTEM_ALERT_WINDOW（用户必须在系统设置里手动授予）；
 *   2. 窗口：WindowManager.addView + TYPE_APPLICATION_OVERLAY；
 *   3. 手势：触摸阈值区分「单击 / 长按 / 拖动」，并在拖动结束时把坐标换算回窗口位置。
 *
 * 真正在项目里运行时走的是完整实现：入口为
 * [com.screenshot.ai.service.FloatingService.start]，窗口由 [OverlayManager] 管理，
 * 本示例仅用于阅读与调试。
 *
 * 权限提示：本示例不会自动申请权限（悬浮窗权限只能由用户去系统设置手动开启），
 * [canDrawOverlays] 返回 false 时请调用 [openOverlaySettings] 引导用户。
 */
object FloatingWindowExample {
    private const val TAG = "FloatingWindowExample"

    /** 是否已获得悬浮窗权限 */
    fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

    /**
     * 跳转系统「显示在其他应用上层」设置页。
     *
     * 注意：这个页面**无法**用 startActivityForResult 拿到结果，
     * 用户授权后必须重新查询 Settings.canDrawOverlays()。
     */
    fun openOverlaySettings(context: Context) {
        val intent =
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure { Log.w(TAG, "跳转悬浮窗设置页失败", it) }
    }

    /**
     * 启动完整版的悬浮工具栏（推荐用法）。
     *
     * 之所以不在示例里手写 WindowManager.addView，是因为生产实现已经处理了
     * 大量容易被忽略的细节：贴边半隐藏、截屏前临时隐藏、面板避让屏幕边界、
     * 深色模式配色、ROM 差异等。
     */
    fun startFloatingToolbar(context: Context): Boolean {
        if (!canDrawOverlays(context)) {
            openOverlaySettings(context)
            return false
        }
        com.screenshot.ai.service.FloatingService
            .start(context)
        return true
    }

    /** 停止悬浮工具栏 */
    fun stopFloatingToolbar(context: Context) {
        com.screenshot.ai.service.FloatingService
            .stop(context)
    }

    /**
     * 项目内置的四按钮（长按悬浮球展开）：截图 / 录入 / 历史 / 设置。
     * 这里只列出它们的语义，实际实现位于 [OverlayManager.toggleActionPanel]。
     */
    val ACTION_LABELS: List<String> = listOf("截图", "录入", "历史", "设置")

    /** 示例用：把 Bitmap 转成可直接上传的 Base64（详见 [com.screenshot.ai.util.ImageUtils]） */
    fun bitmapToBase64ForUpload(bitmap: Bitmap): String =
        com.screenshot.ai.util.ImageUtils.toBase64(
            com.screenshot.ai.util.ImageUtils
                .compressForUpload(bitmap, maxEdge = 1024),
        )

    /** 当前 SDK 是否满足使用 TYPE_APPLICATION_OVERLAY 的条件（本项目 minSdk 30，恒为 true） */
    fun isOverlayTypeSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
}
