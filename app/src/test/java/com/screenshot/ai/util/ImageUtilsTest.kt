package com.screenshot.ai.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ImageUtils] 纯函数单元测试。
 *
 * 这里刻意只测试不依赖 [android.graphics.Bitmap] 的算法部分：
 * 采样率与缩放尺寸一旦算错，轻则图片被放大浪费 token，重则解码 OOM，
 * 属于必须被回归测试覆盖的逻辑。Bitmap 相关的编解码需要真机/仪器测试。
 */
class ImageUtilsTest {
    // ---------------- calculateInSampleSize ----------------

    @Test
    fun `典型截图按2的幂降采样且保持不小于请求尺寸`() {
        // 1440x3200 是最常见的手机截图尺寸
        val sampleSize = ImageUtils.calculateInSampleSize(1440, 3200, 1024)
        assertTrue("采样率必须是 2 的幂，实际=$sampleSize", isPowerOfTwo(sampleSize))
        assertTrue("降采样后宽不能小于 1024", 1440 / sampleSize >= 1024)
        assertTrue("降采样后高不能小于 1024", 3200 / sampleSize >= 1024)
    }

    @Test
    fun `大图采样的两个维度都不低于请求尺寸`() {
        val width = 4000
        val height = 3000
        val reqSize = 1024
        val sampleSize = ImageUtils.calculateInSampleSize(width, height, reqSize)

        assertTrue("采样率必须是 2 的幂，实际=$sampleSize", isPowerOfTwo(sampleSize))
        // 采样率可以是 2 或 4（两者都满足约束），这里断言的是「约束本身」而不是某个魔数
        assertTrue("采样率应在 2~4 之间，实际=$sampleSize", sampleSize in 2..4)
        assertTrue("宽不能低于请求尺寸", width / sampleSize >= reqSize)
        assertTrue("高不能低于请求尺寸", height / sampleSize >= reqSize)
    }

    @Test
    fun `图片小于请求尺寸时不降采样`() {
        assertEquals(1, ImageUtils.calculateInSampleSize(800, 600, 1024))
        assertEquals(1, ImageUtils.calculateInSampleSize(1024, 768, 1024))
    }

    @Test
    fun `超大图采样率有上限`() {
        // 长截图拼接可能得到极高分辨率，采样率必须有上界，避免除出 0 或内存抖动
        val sampleSize = ImageUtils.calculateInSampleSize(20000, 40000, 1024)
        assertTrue("采样率必须是 2 的幂，实际=$sampleSize", isPowerOfTwo(sampleSize))
        assertTrue("采样率不应超过 16，实际=$sampleSize", sampleSize <= 16)
    }

    @Test
    fun `非法参数返回最小值1`() {
        assertEquals(1, ImageUtils.calculateInSampleSize(0, 0, 1024))
        assertEquals(1, ImageUtils.calculateInSampleSize(-10, 100, 1024))
        assertEquals(1, ImageUtils.calculateInSampleSize(100, 100, 0))
    }

    // ---------------- scaledSize ----------------

    @Test
    fun `横图等比缩放到最长边`() {
        val (width, height) = ImageUtils.scaledSize(2048, 1024, 1024)
        assertEquals(1024, width)
        assertEquals(512, height)
    }

    @Test
    fun `竖图等比缩放到最长边`() {
        // 1440x3200 的截图压缩到 1024：长边为高。
        // 短边 1440 * 1024 / 3200 = 460.8，采用向上取整，因此是 461 而不是 460。
        // 这里刻意固定「长边恰好等于上限」的语义：若允许四舍五入，
        // 某些比例下长边会算出 1025，越过上传尺寸上限。
        val (width, height) = ImageUtils.scaledSize(1440, 3200, 1024)
        assertEquals(1024, height)
        assertEquals(461, width)
    }

    @Test
    fun `长边永远不超过上限`() {
        // 遍历一批常见与刁钻的分辨率，长边都必须落在 maxEdge 上且不越界
        val cases =
            listOf(
                Triple(1440, 3200, 1024),
                Triple(1080, 2400, 512),
                Triple(3200, 1440, 1024),
                Triple(1080, 1920, 512),
                Triple(1000, 1500, 460),
                Triple(2000, 2000, 1000),
            )
        for ((w, h, maxEdge) in cases) {
            val (tw, th) = ImageUtils.scaledSize(w, h, maxEdge)
            val longest = maxOf(tw, th)
            assertEquals("缩放后长边应恰好等于上限：$w x $h -> $tw x $th", maxEdge, longest)
        }
    }

    @Test
    fun `缩放后宽高比例与原始比例一致`() {
        val sourceWidth = 1440
        val sourceHeight = 3200
        val (width, height) = ImageUtils.scaledSize(sourceWidth, sourceHeight, 512)
        val sourceRatio = sourceWidth.toDouble() / sourceHeight.toDouble()
        val targetRatio = width.toDouble() / height.toDouble()
        assertEquals("宽高比必须保持", sourceRatio, targetRatio, 0.01)
    }

    @Test
    fun `永不放大`() {
        val (width, height) = ImageUtils.scaledSize(100, 200, 1024)
        assertEquals("小图不应被放大", 100, width)
        assertEquals("小图不应被放大", 200, height)
    }

    @Test
    fun `最长边正好等于上限时原样返回`() {
        val (width, height) = ImageUtils.scaledSize(1024, 768, 1024)
        assertEquals(1024, width)
        assertEquals(768, height)
    }

    @Test
    fun `正方形按上限缩放`() {
        val (width, height) = ImageUtils.scaledSize(2000, 2000, 1000)
        assertEquals(1000, width)
        assertEquals(1000, height)
    }

    @Test
    fun `缩放到极小时不会出现0尺寸`() {
        // 极端长图缩放到小尺寸时，短边可能被四舍五入成 0，必须兜到 1
        val (width, height) = ImageUtils.scaledSize(10000, 10, 64)
        assertEquals(64, width)
        assertTrue("短边至少为 1，实际=$height", height >= 1)
    }

    @Test
    fun `非法尺寸返回1x1`() {
        assertEquals(1 to 1, ImageUtils.scaledSize(0, 0, 1024))
        assertEquals(1 to 1, ImageUtils.scaledSize(-1, 100, 1024))
    }

    @Test
    fun `缩放结果符合小图判定`() {
        // compressForUpload 用「尺寸未变」判断可以复用原 Bitmap 实例
        val (sameWidth, sameHeight) = ImageUtils.scaledSize(800, 600, 1024)
        assertFalse("800x600 不需要缩放", sameWidth != 800 || sameHeight != 600)
        val (smallWidth, smallHeight) = ImageUtils.scaledSize(1440, 3200, 1024)
        assertTrue("1440x3200 需要缩放", smallWidth != 1440 || smallHeight != 3200)
    }

    /** 判断是否为 2 的幂（inSampleSize 只允许 2 的幂） */
    private fun isPowerOfTwo(value: Int): Boolean = value > 0 && value and (value - 1) == 0
}
