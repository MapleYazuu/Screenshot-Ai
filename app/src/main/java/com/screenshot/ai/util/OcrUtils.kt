package com.screenshot.ai.util

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 本地 OCR 工具（ML Kit 端侧中文识别）。
 *
 * 用途是「降级路径」：当用户选择的模型不支持图片输入（supportsVision = false）时，
 * 先本地识别文字再把纯文本发给模型，仍然能得到可用结果。
 *
 * 因此这里的错误策略很关键——OCR 只是降级路径，任何异常都不应该打断主流程，
 * 全部吞掉并返回空串，让上层决定是提示用户还是直接放弃。
 */
object OcrUtils {
    private const val TAG = "OcrUtils"

    /** 中文识别器同时支持中英文混排，且模型已随 APK 打包 */
    private val recognizer: TextRecognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    /**
     * 识别 Bitmap 中的文字。
     *
     * ML Kit 的 Task API 是回调式的，这里手工桥接成协程：
     * 项目没有引入 kotlinx-coroutines-play-services，所以不能用 Task.await()。
     * 使用 suspendCancellableCoroutine 而不是 suspendCoroutine，
     * 是为了让上层取消协程时能立即释放等待中的挂起点（识别本身无法中断）。
     *
     * @return 识别到的文字，按 block（段落）用换行拼接并 trim；失败返回 ""
     */
    suspend fun recognizeText(bitmap: Bitmap): String {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
            return ""
        }
        return try {
            suspendCancellableCoroutine { continuation ->
                val image = InputImage.fromBitmap(bitmap, 0)
                recognizer
                    .process(image)
                    .addOnSuccessListener { text ->
                        // 协程可能已被取消，此时 resume 会抛 IllegalStateException，必须先判断
                        if (continuation.isActive) {
                            continuation.resume(joinBlocks(text))
                        }
                    }.addOnFailureListener { error ->
                        Log.w(TAG, "recognizeText: 识别失败", error)
                        if (continuation.isActive) {
                            // 降级路径失败不抛异常，交给上层用空串判定
                            continuation.resume("")
                        }
                    }
            }
        } catch (e: Exception) {
            Log.w(TAG, "recognizeText: 识别异常", e)
            ""
        }
    }

    /**
     * 识别图片文件中的文字。
     *
     * 先采样解码到 2048px：OCR 对分辨率的需求低于人眼阅读，
     * 2048 已经足够识别小字号，同时避免大截图解码 OOM。
     */
    suspend fun recognizeText(path: String): String {
        val bitmap =
            ImageUtils.decodeSampled(path, OCR_MAX_EDGE)
                ?: return ""
        return try {
            recognizeText(bitmap)
        } finally {
            // 采样出来的 Bitmap 由本方法独占，识别结束后立即回收，避免长截图常驻内存
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
    }

    /**
     * OCR 是否可用。
     *
     * ML Kit 中文模型随 APK 打包（无需下载），识别可完全离线进行，
     * 因此这里恒为 true；保留方法是为了让上层以后能替换成可下载模型而不改调用点。
     */
    @Suppress("UNUSED_PARAMETER")
    fun isAvailable(context: Context): Boolean = true

    /**
     * 释放识别器。
     *
     * ML Kit 的 TextRecognizer 持有原生资源，进程退出前关闭可避免泄漏；
     * 识别器是 lazy 单例，关闭后本进程内不应再调用 [recognizeText]。
     */
    fun close() {
        try {
            recognizer.close()
        } catch (e: Exception) {
            Log.w(TAG, "close: 关闭识别器失败", e)
        }
    }

    /**
     * 把 ML Kit 的识别结果拍平成纯文本。
     *
     * 只取 block 级别的 \n 而不是 line 级别，是为了保留原始段落结构；
     * 行内文字用空串拼接，符合 OCR 原文的排版（ML Kit 已经按视觉行切分好）。
     */
    private fun joinBlocks(text: Text): String =
        text.textBlocks
            .map { block -> block.lines.joinToString(separator = "") { it.text } }
            .filter { it.isNotBlank() }
            .joinToString(separator = "\n")
            .trim()

    /** OCR 采样上限：再高对识别率无增益，只会增加内存与耗时 */
    private const val OCR_MAX_EDGE = 2048
}
