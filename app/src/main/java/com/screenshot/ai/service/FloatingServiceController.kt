package com.screenshot.ai.service

import android.content.Context
import android.util.Log
import com.screenshot.ai.util.PermissionUtils

/**
 * 悬浮工具栏启停的统一入口（主界面开关 / 快捷设置 Tile / 开机自启共用）。
 *
 * 为什么抽出来：
 * 此前主界面、开机广播、应用自愈各写一套 `startForegroundService` 逻辑，
 * 权限判断、异常处理三处各写一遍，改一处漏两处。统一收敛到本类后，
 * 所有入口都走同一条链路，行为完全一致。
 *
 * 线程约束：方法内部不强制切线程，调用方必须在主线程调用
 * （主界面 / TileService 回调 / 广播 goAsync 的协程都满足）。
 */
object FloatingServiceController {

    /** 服务是否正在运行（与 FloatingService 内部状态一致） */
    fun isRunning(): Boolean = FloatingService.isRunning()

    /**
     * 启动悬浮工具栏。
     *
     * @return true 表示已发起启动；false 表示缺少悬浮窗权限（调用方应引导授权）
     */
    fun start(context: Context): Boolean {
        val app = context.applicationContext
        if (!PermissionUtils.canDrawOverlays(app)) {
            Log.w(TAG, "缺少悬浮窗权限，拒绝启动悬浮工具栏")
            return false
        }
        return runCatching {
            FloatingService.start(app)
        }.onFailure { e ->
            Log.e(TAG, "启动悬浮工具栏失败", e)
        }.isSuccess
    }

    /** 停止悬浮工具栏（重复停止安全） */
    fun stop(context: Context) {
        runCatching {
            FloatingService.stop(context.applicationContext)
        }.onFailure { e ->
            Log.e(TAG, "停止悬浮工具栏失败", e)
        }
    }

    /**
     * 切换运行状态。
     *
     * @return 切换后的目标状态（true = 已开启）
     */
    fun toggle(context: Context): Boolean =
        if (isRunning()) {
            stop(context)
            false
        } else {
            start(context)
        }

    private const val TAG = "FloatingController"
}
