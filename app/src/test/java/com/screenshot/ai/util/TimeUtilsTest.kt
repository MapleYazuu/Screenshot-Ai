package com.screenshot.ai.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale

/**
 * [TimeUtils] 单元测试。
 *
 * 全部用例都注入固定的 `now`，因此不依赖系统时钟、不会在凌晨或跨年时随机失败。
 */
class TimeUtilsTest {
    /** 固定的「当前时间」：2024-06-15 12:00:00（本地时区） */
    private val noon: Long = timestampOf(2024, 6, 15, 12, 0, 0)

    // ---------------- relativeTime ----------------

    @Test
    fun `小于60秒显示刚刚`() {
        assertEquals("刚刚", TimeUtils.relativeTime(noon, noon, Locale.CHINA))
        assertEquals("刚刚", TimeUtils.relativeTime(noon - 59_000L, noon, Locale.CHINA))
    }

    @Test
    fun `恰好60秒进入分钟档`() {
        // 边界：60_000 应该落到「1分钟前」而不是「刚刚」
        assertEquals("1分钟前", TimeUtils.relativeTime(noon - 60_000L, noon, Locale.CHINA))
        assertEquals("5分钟前", TimeUtils.relativeTime(noon - 5 * 60_000L, noon, Locale.CHINA))
        assertEquals("59分钟前", TimeUtils.relativeTime(noon - 59 * 60_000L, noon, Locale.CHINA))
    }

    @Test
    fun `恰好60分钟进入小时档`() {
        // 边界：60 分钟不能再显示为「60分钟前」。
        // 注意使用「同一天内」的样本，避免与自然日规则交叉
        // （跨自然日时会走「昨天 HH:mm」分支，见下一个用例）。
        val evening = timestampOf(2024, 6, 15, 20, 0, 0)
        assertEquals("1小时前", TimeUtils.relativeTime(evening - 60 * 60_000L, evening, Locale.CHINA))
        assertEquals("3小时前", TimeUtils.relativeTime(evening - 3 * 60 * 60_000L, evening, Locale.CHINA))
        assertEquals("11小时前", TimeUtils.relativeTime(evening - 11 * 60 * 60_000L, evening, Locale.CHINA))
    }

    @Test
    fun `跨自然日优先显示昨天而不是小时数`() {
        // 设计取舍：以「自然日」而不是「固定 24 小时」划分档位。
        // 23 小时前虽然不足一天，但已经是昨天的事，用户看到「昨天 13:00」
        // 比看到「23小时前」更容易定位。
        val result = TimeUtils.relativeTime(noon - 23 * 60 * 60_000L, noon, Locale.CHINA)
        assertEquals("昨天 13:00", result)
    }

    @Test
    fun `恰好24小时进入昨天档`() {
        // 边界：24 小时整既不能再显示「24小时前」，也不该直接跳到日期
        val result = TimeUtils.relativeTime(noon - 24 * 60 * 60_000L, noon, Locale.CHINA)
        assertEquals("昨天 12:00", result)
    }

    @Test
    fun `前天的同一时刻显示月日`() {
        val result = TimeUtils.relativeTime(noon - 48 * 60 * 60_000L, noon, Locale.CHINA)
        assertEquals("6月13日", result)
    }

    @Test
    fun `跨年时间显示完整年月日`() {
        val result = TimeUtils.relativeTime(noon - 300L * 24 * 60 * 60_000L, noon, Locale.CHINA)
        // 预期形如「2023年8月20日」：包含年/月/日三个部分即可，
        // 不强制个位数是否补零（pattern 为 yyyy年M月d日）
        assertTrue("跨年应显示完整年月日，实际=$result", result.contains("年"))
        assertTrue("跨年应显示完整年月日，实际=$result", result.contains("月"))
        assertTrue("跨年应显示完整年月日，实际=$result", result.endsWith("日"))
        assertTrue("跨年应显示 2023 年，实际=$result", result.contains("2023"))
    }

    @Test
    fun `未来时间显示刚刚而不是负数`() {
        // 设备时间被改动或服务端时间偏移时会拿到「未来」的时间戳
        assertEquals("刚刚", TimeUtils.relativeTime(noon + 60_000L, noon, Locale.CHINA))
    }

    @Test
    fun `昨天档使用自然日而不是固定24小时`() {
        // 昨天 23:30 距今只有 12.5 小时，但属于上一个自然日：仍应按「昨天」展示
        val yesterdayLateNight = timestampOf(2024, 6, 14, 23, 30, 0)
        val morningNow = timestampOf(2024, 6, 15, 12, 0, 0)
        assertEquals(
            "昨天 23:30",
            TimeUtils.relativeTime(yesterdayLateNight, morningNow, Locale.CHINA),
        )
    }

    // ---------------- formatDateTime ----------------

    @Test
    fun `默认格式为年月日时分秒`() {
        val timestamp = timestampOf(2024, 1, 2, 3, 4, 5)
        assertEquals("2024-01-02 03:04:05", TimeUtils.formatDateTime(timestamp))
    }

    @Test
    fun `自定义pattern生效`() {
        val timestamp = timestampOf(2024, 1, 2, 3, 4, 5)
        // 显式传 Locale.US 与主代码默认值一致；pattern 本身决定输出，避免主机 locale 差异
        assertEquals(
            "03:04",
            TimeUtils.formatDateTime(timestamp, TimeUtils.PATTERN_TIME, Locale.US),
        )
        assertEquals(
            "1月2日",
            TimeUtils.formatDateTime(timestamp, TimeUtils.PATTERN_MONTH_DAY, Locale.US),
        )
        assertEquals(
            "2024年1月2日",
            TimeUtils.formatDateTime(timestamp, TimeUtils.PATTERN_FULL_DATE, Locale.US),
        )
    }

    // ---------------- formatDuration ----------------

    @Test
    fun `不足一秒显示毫秒`() {
        assertEquals("0ms", TimeUtils.formatDuration(0L))
        assertEquals("999ms", TimeUtils.formatDuration(999L))
    }

    @Test
    fun `分钟与秒补零`() {
        assertEquals("0:01", TimeUtils.formatDuration(1_000L))
        assertEquals("0:59", TimeUtils.formatDuration(59_000L))
        assertEquals("1:00", TimeUtils.formatDuration(60_000L))
        assertEquals("59:59", TimeUtils.formatDuration(59 * 60_000L + 59_000L))
    }

    @Test
    fun `超过一小时显示时分秒`() {
        assertEquals("1:00:00", TimeUtils.formatDuration(60 * 60_000L))
        assertEquals("2:03:04", TimeUtils.formatDuration(2 * 3_600_000L + 3 * 60_000L + 4_000L))
    }

    @Test
    fun `负数耗时按零处理`() {
        // 打点顺序颠倒时可能算出负数，展示层不应该出现 -5ms
        assertEquals("0ms", TimeUtils.formatDuration(-5L))
    }

    /** 构造本地时区的固定时间戳，避免测试受运行环境时区影响（同一时区内比较恒定） */
    private fun timestampOf(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        second: Int,
    ): Long =
        Calendar
            .getInstance()
            .apply {
                clear()
                set(year, month - 1, day, hour, minute, second)
            }.timeInMillis
}
