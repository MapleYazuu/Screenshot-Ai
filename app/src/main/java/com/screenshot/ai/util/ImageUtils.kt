package com.screenshot.ai.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * 图片处理工具。
 *
 * 截图 AI 助手的图片链路有三个硬约束，所有实现都围绕它们展开：
 * 1. 手机截图原图常见 1440x3200，直接解码会 OOM，必须先算采样率；
 * 2. 上传给多模态模型的图片有 token 成本与体积上限，需要等比压缩；
 * 3. Base64 字符串要放进 JSON，任何换行都会破坏请求体。
 */
object ImageUtils {
    private const val TAG = "ImageUtils"

    /** 采样率上限，避免超大图（如长截图拼接）把 inSampleSize 推到离谱的值 */
    private const val MAX_SAMPLE_SIZE = 16

    /**
     * 按需采样解码图片文件。
     *
     * 分两遍解码：第一遍只读图片头（inJustDecodeBounds）拿到原始宽高，
     * 第二遍带上计算好的 inSampleSize 才真正分配像素内存。
     * 截图原图可能 1440x3200，直接 decode 会 OOM，必须先算采样率。
     *
     * @param path    图片文件路径
     * @param maxSize 期望的最长边上限（px）
     * @return 解码后的 Bitmap；文件不存在或解码失败时返回 null，绝不抛异常
     */
    fun decodeSampled(
        path: String,
        maxSize: Int,
    ): Bitmap? =
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)

            val width = bounds.outWidth
            val height = bounds.outHeight
            if (width <= 0 || height <= 0) {
                // 文件损坏或不是图片：此时 decodeFile 已经把 outWidth/outHeight 置为 -1
                Log.w(TAG, "decodeSampled: 无法解析图片尺寸, path=$path")
                null
            } else {
                val options =
                    BitmapFactory.Options().apply {
                        inSampleSize = calculateInSampleSize(width, height, maxSize)
                        // 截图是 RGB 内容，不需要每像素 4 字节的 ARGB_8888，能显著降低内存峰值
                        inPreferredConfig = Bitmap.Config.RGB_565
                    }
                BitmapFactory.decodeFile(path, options).also { bitmap ->
                    if (bitmap == null) {
                        Log.w(TAG, "decodeSampled: 解码返回 null, path=$path")
                    }
                }
            }
        } catch (e: OutOfMemoryError) {
            // 采样后仍 OOM（例如并发解码多张图）时不能让进程崩溃
            Log.e(TAG, "decodeSampled: 内存不足, path=$path", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "decodeSampled: 解码异常, path=$path", e)
            null
        }

    /**
     * 压缩到上传尺寸。
     *
     * 1024px 是精度与 token 成本的经验平衡点：再大对识别准确率几乎没有提升，
     * 却会让 token 数与上传耗时明显上升；低质量模式传入 512 进一步省钱。
     *
     * @param bitmap  原图
     * @param maxEdge 最长边目标尺寸（px）
     * @param quality 保留参数以统一调用签名（实际压缩质量在 [toBase64] 生效）
     * @return 缩放后的 Bitmap；原图已经足够小则直接返回同一个实例，避免无意义的拷贝
     */
    fun compressForUpload(
        bitmap: Bitmap,
        maxEdge: Int = 1024,
        quality: Int = 85,
    ): Bitmap {
        val (targetWidth, targetHeight) = scaledSize(bitmap.width, bitmap.height, maxEdge)
        if (targetWidth == bitmap.width && targetHeight == bitmap.height) {
            return bitmap
        }
        return try {
            Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "compressForUpload: 缩放内存不足", e)
            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "compressForUpload: 缩放失败", e)
            bitmap
        }
    }

    /**
     * 编码为 Base64 字符串（不含 data: 前缀）。
     *
     * NO_WRAP 是必须的：默认的 Base64.DEFAULT 每 76 个字符插入一个换行，
     * 换行会直接破坏 JSON 字符串，导致服务端解析请求体失败。
     */
    fun toBase64(
        bitmap: Bitmap,
        quality: Int = 85,
        format: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG,
    ): String =
        try {
            val output = ByteArrayOutputStream()
            // use 保证异常路径下流也会关闭
            output.use { stream ->
                bitmap.compress(format, quality.coerceIn(MIN_QUALITY, MAX_QUALITY), stream)
                val bytes = stream.toByteArray()
                // JVM 单测中 android.util.Base64 是桩实现会返回 null，这里兜底为空串而不是抛 NPE
                Base64.encodeToString(bytes, Base64.NO_WRAP) ?: ""
            }
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "toBase64: 内存不足", e)
            ""
        } catch (e: Exception) {
            Log.e(TAG, "toBase64: 编码失败", e)
            ""
        }

    /**
     * 生成缩略图，用于历史列表等只需要小图预览的场景。
     *
     * 与 [compressForUpload] 分开是为了让「预览尺寸」与「上传尺寸」可以独立调整。
     */
    fun generateThumbnail(
        bitmap: Bitmap,
        maxEdge: Int = 480,
    ): Bitmap {
        val (targetWidth, targetHeight) = scaledSize(bitmap.width, bitmap.height, maxEdge)
        if (targetWidth == bitmap.width && targetHeight == bitmap.height) {
            return bitmap
        }
        return try {
            Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "generateThumbnail: 内存不足", e)
            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "generateThumbnail: 生成缩略图失败", e)
            bitmap
        }
    }

    /**
     * 保存 Bitmap 到文件。
     *
     * @return 是否写盘成功；调用方（历史记录）据此决定要不要提示用户
     */
    fun saveBitmap(
        bitmap: Bitmap,
        file: File,
        quality: Int = 90,
    ): Boolean =
        try {
            val parent = file.parentFile
            if (parent != null && !parent.exists()) {
                parent.mkdirs()
            }
            java.io.FileOutputStream(file).use { stream ->
                bitmap.compress(
                    Bitmap.CompressFormat.JPEG,
                    quality.coerceIn(MIN_QUALITY, MAX_QUALITY),
                    stream,
                )
                // compress 返回 true 只代表编码成功，flush 才能确认数据落到文件
                stream.flush()
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "saveBitmap: 保存失败, path=${file.absolutePath}", e)
            false
        }

    /**
     * 计算 BitmapFactory 的 inSampleSize（纯函数，可单测）。
     *
     * 采用标准的二次幂降采样：inSampleSize 只能是 2 的幂，
     * 且必须保证降采样后宽高都不小于请求尺寸，否则算出来的图会糊到不可用。
     * 注意 [calculateInSampleSize] 保证「不小于」，因此采样后仍可能大于 maxSize，
     * 需要精确尺寸时由调用方再用 [scaledSize] 兜一次。
     *
     * @param width   原图宽
     * @param height  原图高
     * @param reqSize 期望的最长边上限
     * @return 2 的幂，至少为 1
     */
    internal fun calculateInSampleSize(
        width: Int,
        height: Int,
        reqSize: Int,
    ): Int {
        if (width <= 0 || height <= 0 || reqSize <= 0) {
            return 1
        }
        var sampleSize = 1
        var halfWidth = width / 2
        var halfHeight = height / 2
        // 每次翻倍后仍然「宽高都不小于 reqSize」才继续降采样
        while (sampleSize < MAX_SAMPLE_SIZE &&
            halfWidth / sampleSize >= reqSize &&
            halfHeight / sampleSize >= reqSize
        ) {
            sampleSize *= 2
        }
        return sampleSize
    }

    /**
     * 等比缩放到最长边为 maxEdge（纯函数，可单测）。
     *
     * 绝不放大：最长边已经不超过 maxEdge 时原样返回，
     * 放大既增加体积又不会带来任何清晰度收益。
     *
     * @return 目标 (宽, 高)，两个值都至少为 1
     */
    internal fun scaledSize(
        width: Int,
        height: Int,
        maxEdge: Int,
    ): Pair<Int, Int> {
        if (width <= 0 || height <= 0) {
            return 1 to 1
        }
        val limitedEdge = min(maxEdge, MAX_EDGE_LIMIT)
        if (limitedEdge <= 0) {
            return 1 to 1
        }
        val longestEdge = max(width, height)
        if (longestEdge <= limitedEdge) {
            // 已经足够小，直接返回原尺寸（调用方据此判断可以复用原 Bitmap 实例）
            return width to height
        }
        // 用整数运算而非 Float 缩放：
        // (width * scale).roundToInt() 在 1000 → 460 这类比例上会因 Float 精度
        // 得到 460.00001 并被四舍五入成 461，反而超过 maxEdge 上限。
        // 这里改为「向上取整的整数除法」，再把最长边钳到 maxEdge，
        // 保证结果既不超过上限、也不会因为取整而失真。
        var targetWidth = divRoundUp(width * limitedEdge, longestEdge).coerceAtLeast(1)
        var targetHeight = divRoundUp(height * limitedEdge, longestEdge).coerceAtLeast(1)
        if (width >= height) {
            targetWidth = limitedEdge
        } else {
            targetHeight = limitedEdge
        }
        return targetWidth to targetHeight
    }

    /** 整数向上取整除法：(a + b - 1) / b */
    private fun divRoundUp(
        a: Int,
        b: Int,
    ): Int = (a + b - 1) / b

    /** JPEG 压缩质量下限，低于 30 会出现肉眼可见的块效应 */
    private const val MIN_QUALITY = 30

    /** JPEG 压缩质量上限 */
    private const val MAX_QUALITY = 100

    /** 单边尺寸上限，防御性限制，避免误传超大值导致内存翻车 */
    private const val MAX_EDGE_LIMIT = 4096
}
