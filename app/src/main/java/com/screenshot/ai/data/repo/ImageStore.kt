package com.screenshot.ai.data.repo

import android.graphics.Bitmap

/**
 * 截图文件仓库：负责把 Bitmap 落盘到应用私有目录，并管理生命周期。
 *
 * 为什么不放相册：
 * - 截图可能包含隐私内容，放在私有目录（filesDir/screenshots）不会被其他 App 读取；
 * - 不会污染用户相册；
 * - 卸载应用即自动清理。
 */
interface ImageStore {
    /**
     * 保存截图，返回私有目录下的绝对路径。
     * @param quality JPEG 压缩质量 0-100
     */
    suspend fun saveScreenshot(
        bitmap: Bitmap,
        quality: Int = 90,
    ): String

    /** 删除单张截图 */
    suspend fun delete(path: String)

    /** 清理超过 [keepDays] 天的截图文件（历史记录被删除后调用） */
    suspend fun cleanUp(keepDays: Int = 30)

    /** 统计截图占用的磁盘空间（字节） */
    suspend fun totalSizeBytes(): Long
}
