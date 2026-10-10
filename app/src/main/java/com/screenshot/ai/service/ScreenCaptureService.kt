package com.screenshot.ai.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Bitmap
import android.hardware.HardwareBuffer
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * 无障碍截屏服务。
 *
 * 为什么用无障碍而不是 MediaProjection：
 * - MediaProjection 每次截屏都要弹一次系统授权框（Android 11+ 每次会话都必须重新授权），
 *   悬浮球这种「点一下就要立刻出结果」的场景完全不可用；
 * - 无障碍服务的 takeScreenshot() 只需用户开启一次服务，之后随时可截，
 *   并且不需要任何存储权限，截图也不会进相册。
 *
 * 隐私边界：本服务**不读取任何界面内容**（[onAccessibilityEvent] 为空实现），
 * 只使用 Android 11 起提供的 takeScreenshot() 能力，且仅在用户主动点击悬浮按钮时触发。
 */
@RequiresApi(Build.VERSION_CODES.R)
class ScreenCaptureService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        captureSupported = isScreenshotCapabilityDeclared()
        Log.i(TAG, "无障碍截屏服务已连接, canTakeScreenshot=$captureSupported")
    }

    /**
     * 无障碍事件回调。
     *
     * 刻意留空：本服务只需要 takeScreenshot() 这一项能力，不需要读取任何界面内容。
     * 保持空实现可以让「本应用不采集用户屏幕内容」成为代码层面可直接验证的事实，
     * 而不是只写在隐私政策里的承诺。
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    /** 服务被系统中断（例如用户切换到另一个无障碍服务）：无内部状态需要清理，保持空实现 */
    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        Log.i(TAG, "无障碍截屏服务已解绑")
        clearInstance()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        Log.i(TAG, "无障碍截屏服务已销毁")
        clearInstance()
        super.onDestroy()
    }

    /** 清空静态引用，避免服务销毁后仍被截屏请求命中 */
    private fun clearInstance() {
        captureSupported = false
        instance = null
    }

    /** 读取系统是否真的授予了截屏能力（service 配置里的 canTakeScreenshot，可由用户单独关闭） */
    private fun isScreenshotCapabilityDeclared(): Boolean =
        try {
            val info = serviceInfo
            if (info == null) {
                false
            } else {
                (info.getCapabilities() and AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT) != 0
            }
        } catch (e: Exception) {
            Log.w(TAG, "读取无障碍服务能力失败", e)
            false
        }

    companion object {
        private const val TAG = "ScreenCaptureService"

        /** 最多尝试次数（首次 + 2 次重试） */
        private const val MAX_ATTEMPTS = 3

        /** 普通失败后的重试间隔：足以跨过瞬时抖动，又不会让用户觉得卡住 */
        private const val RETRY_DELAY_MS = 300L

        /** 命中限流后的重试间隔：必须大于系统允许的最小截屏间隔，否则重试必然再次失败 */
        private const val RETRY_DELAY_WHEN_THROTTLED_MS = 600L

        /** 无障碍服务实例；服务由系统创建，静态引用是外部获取它的唯一途径 */
        @Volatile
        private var instance: ScreenCaptureService? = null

        /** 截屏能力是否可用（服务未连接或系统未授权时为 false） */
        @Volatile
        private var captureSupported: Boolean = false

        /**
         * 截屏串行化锁。
         *
         * 这是防限流的根本手段：即使用户连点悬浮球，也保证同一时刻只有一次 takeScreenshot 在途，
         * 从源头上避免 ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT。
         */
        private val captureMutex = Mutex()

        /**
         * 截屏回调执行器，必须是单线程。
         *
         * 系统会在这个 Executor 上回调结果；多线程执行器会让「多次截屏的回调顺序」不可预测。
         * 单线程还顺带串行化了硬件缓冲区的拷贝，降低瞬时内存峰值。
         */
        private val callbackExecutor: Executor =
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "screenshot-callback").apply { isDaemon = true }
            }

        /** 无障碍截屏服务是否正在运行 */
        fun isRunning(): Boolean = instance != null

        /** 当前是否具备截屏能力（服务未连接或系统未授权时返回 false） */
        fun isCaptureSupported(): Boolean = instance != null && captureSupported

        /**
         * 截取当前屏幕。
         *
         * 重试策略：首次调用在「服务刚连接」或「与上一次截屏间隔过短」时经常失败，
         * 因此最多尝试 [MAX_ATTEMPTS] 次；命中限流类错误时使用更长的间隔。
         *
         * @return 软件位图（`Config.ARGB_8888`）；服务未运行或全部尝试失败时返回 null。
         *         调用方负责在用完后 [Bitmap.recycle]。
         */
        suspend fun capture(): Bitmap? {
            val service = instance
            if (service == null) {
                Log.w(TAG, "截屏失败：无障碍服务未运行")
                return null
            }
            val executor = callbackExecutor
            return captureMutex.withLock {
                var lastError = "未知错误"
                for (attempt in 1..MAX_ATTEMPTS) {
                    when (val result = service.requestScreenshot(executor)) {
                        is CaptureResult.Success -> {
                            Log.i(TAG, "截屏成功（第 $attempt 次尝试）")
                            return@withLock result.bitmap
                        }

                        is CaptureResult.Failure -> {
                            lastError = result.message
                            Log.w(TAG, "截屏失败（第 $attempt/$MAX_ATTEMPTS 次）：${result.message}")
                            if (attempt < MAX_ATTEMPTS) {
                                // 限流类错误必须等更久，否则下一次调用会撞进同一个时间窗
                                delay(if (result.throttled) RETRY_DELAY_WHEN_THROTTLED_MS else RETRY_DELAY_MS)
                            }
                        }
                    }
                }
                Log.e(TAG, "截屏最终失败：$lastError")
                null
            }
        }

        /**
         * 发起一次系统截屏并等待回调。
         *
         * 结果在 [callbackExecutor] 的线程上返回。位图拷贝在回调线程内同步完成后再恢复协程，
         * 这样「关闭硬件缓冲区」永远发生在回调返回之前，不存在跨线程悬挂的缓冲区。
         */
        private suspend fun AccessibilityService.requestScreenshot(executor: Executor): CaptureResult =
            suspendCancellableCoroutine { continuation ->
                try {
                    takeScreenshot(
                        Display.DEFAULT_DISPLAY,
                        executor,
                        object : TakeScreenshotCallback {
                            override fun onSuccess(screenshot: ScreenshotResult) {
                                val buffer: HardwareBuffer = screenshot.hardwareBuffer
                                // 在回调线程内同步完成拷贝并立即释放缓冲区，
                                // 之后再恢复协程，避免图形内存跨线程悬挂。
                                when (val conversion = toSoftwareBitmap(screenshot)) {
                                    is Conversion.Ok ->
                                        continuation.resume(CaptureResult.Success(conversion.bitmap))

                                    is Conversion.Failed ->
                                        continuation.resume(CaptureResult.Failure(conversion.message, false))
                                }
                                // 协程若已被取消（用户中途关闭窗口），继续兜底关闭一次；
                                // HardwareBuffer.close() 是幂等的，重复关闭不会抛异常。
                                continuation.invokeOnCancellation { closeQuietly(buffer) }
                            }

                            override fun onFailure(errorCode: Int) {
                                continuation.resume(
                                    CaptureResult.Failure(describeError(errorCode), isThrottled(errorCode)),
                                )
                            }
                        },
                    )
                } catch (e: Exception) {
                    // 极端情况下 takeScreenshot 会抛异常（例如显示设备正在切换）
                    Log.e(TAG, "takeScreenshot 调用异常", e)
                    continuation.resume(CaptureResult.Failure(e.message ?: "调用异常", false))
                }
            }

        /**
         * 把系统返回的硬件缓冲区转换成软件位图。
         *
         * 这是整条链路最容易造成显存泄漏的地方，三个动作缺一不可：
         * 1. [Bitmap.wrapHardwareBuffer] 返回的是 `Config.HARDWARE` 位图，它**只是**对
         *    [HardwareBuffer] 的包装，像素仍由图形内存持有，因此必须
         *    `copy(ARGB_8888, false)` 拷出一份软件位图；
         * 2. 包装位图必须 `recycle()`，否则每一张截图都会额外挂住一块图形缓冲区；
         * 3. [HardwareBuffer.close] 必须调用，否则连续截几十次就会耗尽图形内存并导致进程崩溃。
         *
         * 同时保证**绝不**向外返回 `Config.HARDWARE` 位图：HARDWARE 位图不能 `getPixels()`、
         * 不能 `compress()`，也无法参与软件 Canvas 绘制，AI 上传与 OCR 都会直接失败。
         */
        private fun toSoftwareBitmap(result: ScreenshotResult): Conversion {
            val buffer: HardwareBuffer = result.hardwareBuffer
            var wrapped: Bitmap? = null
            return try {
                wrapped = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                // copy 会立刻把图形内存中的像素搬进普通堆内存，之后即可安全关闭缓冲区
                val software = wrapped?.copy(Bitmap.Config.ARGB_8888, false)
                if (software == null) {
                    Conversion.Failed("wrapHardwareBuffer 返回 null")
                } else {
                    Conversion.Ok(software)
                }
            } catch (e: Exception) {
                Log.e(TAG, "硬件缓冲区转换为软件位图失败", e)
                Conversion.Failed(e.message ?: "硬件缓冲区转换失败")
            } finally {
                // 顺序很重要：先回收包装位图，再关闭硬件缓冲区
                wrapped?.recycle()
                closeQuietly(buffer)
            }
        }

        /** 关闭硬件缓冲区；关闭失败只记录日志，不能影响已经拿到的位图 */
        private fun closeQuietly(buffer: HardwareBuffer) {
            try {
                buffer.close()
            } catch (e: Exception) {
                Log.w(TAG, "关闭 HardwareBuffer 失败", e)
            }
        }

        /** 错误码转中文描述，便于在日志中直接定位问题（常量值见 API 30 的 AccessibilityService） */
        private fun describeError(errorCode: Int): String =
            when (errorCode) {
                AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR -> "系统内部错误（INTERNAL_ERROR）"
                AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS ->
                    "无障碍权限尚未就绪（NO_ACCESSIBILITY_ACCESS）"

                AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT ->
                    "两次截屏间隔过短（INTERVAL_TIME_SHORT）"

                AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY ->
                    "显示设备无效（INVALID_DISPLAY）"

                else -> "未知错误（errorCode=$errorCode）"
            }

        /**
         * 是否为「等一会儿再试就能成功」的错误。
         *
         * INTERVAL_TIME_SHORT 是系统限流；NO_ACCESSIBILITY_ACCESS 在服务刚连接后的极短时间内
         * 也会出现，两者都值得用更长的间隔重试。
         */
        private fun isThrottled(errorCode: Int): Boolean =
            errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT ||
                errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS

        /** 单次截屏的内部结果，避免用异常做流程控制 */
        private sealed class CaptureResult {
            /** 成功：携带已经脱离图形内存的软件位图 */
            class Success(
                val bitmap: Bitmap,
            ) : CaptureResult()

            /**
             * @param message   中文错误描述
             * @param throttled 是否属于限流类错误（决定重试间隔）
             */
            class Failure(
                val message: String,
                val throttled: Boolean,
            ) : CaptureResult()
        }

        /** 硬件缓冲区转软件位图的结果 */
        private sealed class Conversion {
            /** 转换成功，位图已脱离图形内存 */
            class Ok(
                val bitmap: Bitmap,
            ) : Conversion()

            /** 转换失败，附带原因（用于日志与用户提示） */
            class Failed(
                val message: String,
            ) : Conversion()
        }
    }
}
