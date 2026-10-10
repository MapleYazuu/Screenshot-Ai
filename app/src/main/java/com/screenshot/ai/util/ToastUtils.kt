package com.screenshot.ai.util

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.annotation.StringRes

/**
 * Toast 工具。
 *
 * 悬浮窗与后台线程都会调用，必须切主线程：
 * Toast.show() 内部依赖 WindowManager 的 Looper 检查，
 * 在 IO / 计算线程直接调用会抛 RuntimeException。
 * 因此这里统一 post 到主线程 Handler，调用方不需要关心自己在哪个线程。
 */
object ToastUtils {
    private const val TAG = "ToastUtils"

    /** 主线程 Handler，用于把 show 动作切回主线程 */
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 显示一段文本。
     *
     * context 已经销毁（Activity 已 finish）时直接丢弃：此时再创建 Toast
     * 会泄漏已销毁的 Activity，或在部分 ROM 上抛出 WindowManager$BadTokenException。
     */
    fun show(
        context: Context,
        message: String,
    ) {
        if (!isAlive(context)) {
            return
        }
        val appContext = context.applicationContext ?: context
        mainHandler.post {
            try {
                // 这里重新判断一次：post 到真正执行之间 Context 可能已经被销毁
                if (isAlive(appContext)) {
                    Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.w(TAG, "show: 显示 Toast 失败", e)
            }
        }
    }

    /** 显示字符串资源，避免调用方每次都写 getString 再判空 */
    fun show(
        context: Context,
        @StringRes resId: Int,
    ) {
        val message =
            try {
                context.getString(resId)
            } catch (e: Exception) {
                Log.w(TAG, "show: 读取字符串资源失败, resId=$resId", e)
                return
            }
        show(context, message)
    }

    /**
     * Context 是否仍然可用。
     *
     * 只有 Activity 需要额外判断销毁状态；Application Context 与 Service
     * 的生命周期由进程决定，能拿到就一定能用。
     */
    private fun isAlive(context: Context): Boolean {
        if (context is Activity) {
            return !context.isFinishing && !context.isDestroyed
        }
        return true
    }
}
