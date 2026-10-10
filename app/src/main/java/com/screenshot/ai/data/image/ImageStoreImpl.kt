package com.screenshot.ai.data.image

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.screenshot.ai.data.repo.ImageStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.FilenameFilter
import java.io.IOException
import kotlin.random.Random

/**
 * 截图文件仓库实现：把 Bitmap 落盘到 filesDir/screenshots/。
 *
 * 放在私有目录而非相册的原因见 [ImageStore] 接口注释；这里只补充两点工程约束：
 * - 所有文件操作都切到 [Dispatchers.IO]，JPEG 压缩在部分机型上可达数百毫秒，
 *   放在主线程会直接造成掉帧甚至 ANR；
 * - 文件名带时间戳 + 随机后缀，保证并发保存也不撞名（覆盖会让旧历史记录指向新图片）。
 */
class ImageStoreImpl(
    private val context: Context,
) : ImageStore {
    /** 截图目录惰性求值：避免构造时访问磁盘，也保证 filesDir 变化后始终取到最新路径 */
    private val directory: File
        get() = File(context.filesDir, DIR_NAME)

    override suspend fun saveScreenshot(
        bitmap: Bitmap,
        quality: Int,
    ): String =
        withContext(Dispatchers.IO) {
            val target = ensureDirectory()
            val file = File(target, buildFileName())

            val compressed =
                FileOutputStream(file).use { output ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(0, 100), output)
                }
            if (!compressed) {
                // 压缩失败时磁盘上会留下 0 字节或半截文件，必须删掉：
                // 否则历史列表会渲染出一张「打不开的图」，用户完全无从判断原因。
                file.delete()
                throw IOException("截图压缩失败，已放弃写入：${file.absolutePath}")
            }

            // 刻意不调用 bitmap.recycle()：Bitmap 由调用方持有，截图之后可能还要预览、
            // 重试或上传，仓库层擅自回收会导致调用方 use-after-recycle 崩溃。
            file.absolutePath
        }

    override suspend fun delete(path: String) {
        withContext(Dispatchers.IO) {
            if (path.isBlank()) return@withContext

            val root =
                try {
                    directory.canonicalFile
                } catch (e: IOException) {
                    Log.w(TAG, "截图目录路径解析失败，跳过删除", e)
                    return@withContext
                }
            val target =
                try {
                    File(path).canonicalFile
                } catch (e: IOException) {
                    Log.w(TAG, "截图路径解析失败，跳过删除：$path", e)
                    return@withContext
                }

            // 安全校验：只允许删除 screenshots 目录内的文件。
            // 路径虽然来自本应用数据库，但 canonicalFile 会把 ".." 与软链接展开成真实路径；
            // 一旦数据库被外部篡改或迁移出错，缺少这层前缀校验就可能删掉私有目录之外的文件
            // （例如 filesDir 下的数据库或其他业务文件）。前缀额外拼上分隔符，
            // 避免 "screenshots_backup" 被误判为 "screenshots" 的子路径。
            val prefix = root.path + File.separator
            if (!target.path.startsWith(prefix)) {
                Log.w(TAG, "拒绝删除截图目录之外的文件：${target.path}")
                return@withContext
            }
            if (target.exists() && !target.delete()) {
                Log.w(TAG, "截图文件删除失败：${target.path}")
            }
        }
    }

    override suspend fun cleanUp(keepDays: Int) {
        withContext(Dispatchers.IO) {
            val threshold = System.currentTimeMillis() - keepDays.coerceAtLeast(0) * MILLIS_PER_DAY
            // 只处理 .jpg：目录原则上只放截图，按扩展名过滤可避免将来放进来的
            // 其他文件（例如调试日志）被顺带清掉。
            val files =
                directory.listFiles(
                    FilenameFilter { _, name -> name.endsWith(EXTENSION, ignoreCase = true) },
                ) ?: return@withContext

            var removed = 0
            for (file in files) {
                if (file.isFile && file.lastModified() < threshold && file.delete()) {
                    removed++
                }
            }
            if (removed > 0) {
                Log.i(TAG, "已清理 $removed 个超过 $keepDays 天的截图")
            }
        }
    }

    override suspend fun totalSizeBytes(): Long =
        withContext(Dispatchers.IO) {
            var total = 0L
            directory.listFiles()?.forEach { file ->
                if (file.isFile) total += file.length()
            }
            total
        }

    /** 生成 `shot_<时间戳>_<4 位随机>.jpg` 形式的文件名 */
    private fun buildFileName(): String {
        val stamp = System.currentTimeMillis()
        val suffix = Random.nextInt(0, RANDOM_BOUND).toString().padStart(4, '0')
        return "shot_${stamp}_$suffix$EXTENSION"
    }

    /** 按需创建截图目录，创建失败直接抛 IOException，让上层把失败暴露出来而不是静默丢图 */
    private fun ensureDirectory(): File {
        val target = directory
        // mkdirs 返回 false 也可能是并发创建导致的，因此再判断一次真实结果
        if (!target.isDirectory && !target.mkdirs() && !target.isDirectory) {
            throw IOException("无法创建截图目录：${target.absolutePath}")
        }
        return target
    }

    companion object {
        private const val TAG = "ImageStore"

        /** 私有目录下的子目录名 */
        private const val DIR_NAME = "screenshots"

        /** 固定使用 JPEG：截图无透明通道，JPEG 体积远小于 PNG */
        private const val EXTENSION = ".jpg"

        /** 4 位随机数上界（含前导零，保证文件名长度稳定） */
        private const val RANDOM_BOUND = 10_000

        /** 一天的毫秒数，用于把 keepDays 换算成时间阈值 */
        private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000
    }
}
