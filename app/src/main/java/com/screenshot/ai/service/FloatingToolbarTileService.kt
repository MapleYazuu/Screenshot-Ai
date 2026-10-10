package com.screenshot.ai.service

import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.screenshot.ai.R
import com.screenshot.ai.ServiceLocator
import com.screenshot.ai.ui.main.MainActivity
import com.screenshot.ai.util.PermissionUtils

/**
 * 快捷设置（下拉通知栏 Quick Settings）的悬浮工具栏开关。
 *
 * ## 单击：切换开关
 * - 服务运行中 → 停止服务，Tile 置为 STATE_INACTIVE；
 * - 未运行 → 检查悬浮窗权限后启动服务，Tile 置为 STATE_ACTIVE；
 * - 未授权悬浮窗权限时，点击**跳转应用主页**引导授权（同时补偿"长按进主页"）。
 *
 * ## 长按
 * 原生 [TileService] 没有 onLongClick 回调：长按由系统打开该 Tile 的详情页，
 * 应用无法拦截。作为可用方案的补偿：
 * 1. 未授权时单击即 `startActivityAndCollapse` 跳转主页；
 * 2. Tile 详情/长按仍由系统提供（设置 > 快捷开关可移除 Tile）。
 *
 * ## 状态同步（三方一致：主界面开关 / Tile / 服务被系统杀死）
 * - 服务生命周期由 [FloatingService.onCreate] / [onDestroy] 写入
 *   [ServiceLocator.serviceRunning]（真实运行状态为唯一事实源）；
 * - Tile 在 [onStartListening]（下拉面板每次显示时）与单击切换后读取该状态并刷新
 *   图标 / 描述 —— 服务被系统回收、主界面开关切换等任何来源的变化都会在
 *   面板下一次显示时反映到 Tile。
 */
class FloatingToolbarTileService : TileService() {

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 下拉面板显示时重新读取服务状态（服务可能已被系统杀死） */
    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    /** 单击：切换悬浮工具栏开关（TileService 回调运行在主线程） */
    override fun onClick() {
        super.onClick()
        if (!PermissionUtils.canDrawOverlays(this)) {
            // 未授权：点击直接进主页引导（原生无法拦截长按，以此作为进主页的补偿入口）
            startActivityAndCollapse(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        if (FloatingServiceController.isRunning()) {
            FloatingServiceController.stop(this)
        } else {
            FloatingServiceController.start(this)
        }
        // 服务启停是异步的，延迟一小段再刷新，保证图标状态与真实运行状态一致
        mainHandler.postDelayed({ updateTile() }, 300L)
    }

    /** 按真实运行状态刷新 Tile 的开关状态、图标与描述 */
    private fun updateTile() {
        val tile = qsTile ?: return
        val running = FloatingServiceController.isRunning() || ServiceLocator.serviceRunning.value
        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_floating_label)
        tile.stateDescription =
            getString(
                if (running) R.string.tile_floating_on else R.string.tile_floating_off,
            )
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_floating)
        tile.updateTile()
    }
}
