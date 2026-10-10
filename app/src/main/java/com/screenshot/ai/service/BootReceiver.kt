package com.screenshot.ai.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.screenshot.ai.ServiceLocator
import com.screenshot.ai.util.PermissionUtils
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 开机自启接收器。
 *
 * 处理两个广播：
 * - `BOOT_COMPLETED`：开机完成后按用户设置恢复悬浮工具栏；
 * - `MY_PACKAGE_REPLACED`：应用升级 / 覆盖安装后重新拉起
 *   （升级会杀掉进程，且多数 ROM 不会补发 BOOT_COMPLETED）。
 *
 * 为什么必须用 [goAsync] + 协程读取配置：
 * DataStore 的读取是挂起操作，而 [onReceive] 只有约 10 秒的执行预算，
 * 且运行在主线程上——直接在 onReceive 里 `runBlocking` 读配置一旦磁盘繁忙就会 ANR。
 * goAsync() 让系统知道「这个广播还在处理」，从而允许我们在后台线程完成读取；
 * 处理结束必须调用 `pendingResult.finish()`，否则系统会判定接收器卡死。
 *
 * 安全边界：只有在「用户显式开启自启」且「悬浮窗权限仍然有效」时才启动服务。
 * 权限可能已被用户在系统设置里撤销，此时启动前台服务会直接抛异常并让开机流程报错。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }
        Log.i(TAG, "收到广播：$action，准备按设置决定是否启动悬浮工具栏")

        val appContext = context.applicationContext
        val pendingResult = goAsync()
        ServiceLocator.init(appContext)
        ServiceLocator.appScope.launch {
            try {
                // NonCancellable：广播处理必须跑到 finish()，中途被取消会让系统认为接收器异常
                withContext(NonCancellable) {
                    handleStartUp(appContext)
                }
            } catch (e: Exception) {
                Log.w(TAG, "开机自启处理失败", e)
            } finally {
                // 必须调用：告知系统广播已处理完毕，可以回收该接收器
                pendingResult.finish()
            }
        }
    }

    /** 读取配置并决定是否拉起悬浮服务 */
    private suspend fun handleStartUp(context: Context) {
        val settings = ServiceLocator.settingsRepository.settings.first()
        if (!settings.autoStartOnBoot) {
            Log.i(TAG, "用户未开启开机自启，跳过")
            return
        }
        if (!PermissionUtils.canDrawOverlays(context)) {
            // 没有悬浮窗权限时强行启动，服务会在 showBubble 阶段被 WindowManager 拒绝
            Log.w(TAG, "缺少悬浮窗权限，跳过开机自启")
            return
        }
        if (FloatingServiceController.isRunning()) {
            Log.i(TAG, "悬浮服务已在运行，跳过重复启动")
            return
        }
        Log.i(TAG, "满足自启条件，启动悬浮工具栏")
        // 统一入口：与主界面开关、快捷设置 Tile 共用同一套启停逻辑
        FloatingServiceController.start(context)
    }

    private companion object {
        private const val TAG = "BootReceiver"
    }
}
