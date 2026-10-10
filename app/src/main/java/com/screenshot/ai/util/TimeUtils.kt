package com.screenshot.ai.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 时间格式化工具。
 *
 * 全部方法都支持注入 `now` 与 `locale`，方便单元测试写出确定性断言：
 * 依赖 `System.currentTimeMillis()` 的代码在测试里只能断言「大概」，等于没测。
 */
object TimeUtils {
    /**
     * 相对时间描述。
     *
     * 分段策略（阈值均为左闭右开，避免边界上出现「60分钟前」这种别扭文案）：
     * - < 60 秒：刚刚
     * - < 60 分钟：N分钟前
     * - < 24 小时：N小时前
     * - 昨天（自然日，按日历比较而非固定 24 小时）：昨天 HH:mm
     * - 今年内：M月d日
     * - 更早：yyyy年M月d日
     *
     * 未来时间（设备时间被改动或服务端时间偏移）统一显示「刚刚」，
     * 避免出现「-3分钟前」。
     *
     * @param timestamp 目标时间戳（毫秒）
     * @param now       当前时间戳（毫秒），测试可注入
     * @param locale    语言环境，决定月/日等文案的数字格式
     */
    fun relativeTime(
        timestamp: Long,
        now: Long = System.currentTimeMillis(),
        locale: Locale = Locale.CHINA,
    ): String {
        val target = Calendar.getInstance(locale).apply { timeInMillis = timestamp }
        val current = Calendar.getInstance(locale).apply { timeInMillis = now }
        val diff = now - timestamp

        // 注意：TimeUnit.xxx.toMillis() 返回 Long，不能直接与 Int 常量比较，
        // 因此这里统一以「毫秒 Long 常量」为基准，避免 Int/Long 混算。
        val oneMinuteMs = TimeUnit.MINUTES.toMillis(1)
        val oneHourMs = TimeUnit.HOURS.toMillis(1)

        // 「刚刚 / N分钟前 / N小时前」只在**同一个自然日**内使用。
        // 若按固定 24 小时判断，昨天 23:30 会在今天 12:00 显示成「12小时前」，
        // 而用户的心理模型是「昨天的事」，因此先做自然日判定。
        if (isSameDay(target, current)) {
            // 未来时间（设备时间被改动或服务端偏移）统一显示「刚刚」，避免出现负数
            return when {
                diff < oneMinuteMs -> "刚刚"
                diff < oneHourMs -> "${diff / oneMinuteMs}分钟前"
                else -> "${diff / oneHourMs}小时前"
            }
        }

        if (isYesterday(target, current)) {
            return "昨天 ${format(timestamp, PATTERN_TIME, locale)}"
        }
        if (target.get(Calendar.YEAR) == current.get(Calendar.YEAR)) {
            return format(timestamp, PATTERN_MONTH_DAY, locale)
        }
        return format(timestamp, PATTERN_FULL_DATE, locale)
    }

    /**
     * 按指定 pattern 格式化时间戳。
     *
     * 每次都新建 SimpleDateFormat 实例：它是非线程安全的，
     * 复用静态实例在并发调用（悬浮窗 + 列表同时渲染）时会得到错乱结果。
     */
    fun formatDateTime(
        timestamp: Long,
        pattern: String = PATTERN_DATE_TIME,
        locale: Locale = Locale.CHINA,
    ): String = format(timestamp, pattern, locale)

    /**
     * 格式化耗时，用于展示请求耗时或音频时长。
     *
     * 规则：不足 1 秒显示 `Nms`；不足 1 小时显示 `m:ss`；超过 1 小时显示 `h:mm:ss`，
     * 秒与毫秒都补零，保证同一列表里的文本宽度一致。
     */
    fun formatDuration(ms: Long): String {
        val safeMs = if (ms < 0) 0 else ms
        if (safeMs < TimeUnit.SECONDS.toMillis(1)) {
            return "${safeMs}ms"
        }
        val totalSeconds = safeMs / TimeUnit.SECONDS.toMillis(1)
        val hours = totalSeconds / SECONDS_PER_HOUR
        val minutes = (totalSeconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
        val seconds = totalSeconds % SECONDS_PER_MINUTE
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
    }

    private fun format(
        timestamp: Long,
        pattern: String,
        locale: Locale,
    ): String = SimpleDateFormat(pattern, locale).format(Date(timestamp))

    private fun isSameDay(
        a: Calendar,
        b: Calendar,
    ): Boolean =
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
            a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

    /** 判断 target 是否落在 current 的前一个自然日 */
    private fun isYesterday(
        target: Calendar,
        current: Calendar,
    ): Boolean {
        val yesterday = current.clone() as Calendar
        yesterday.add(Calendar.DAY_OF_YEAR, -1)
        return isSameDay(target, yesterday)
    }

    /** 默认完整时间格式 */
    const val PATTERN_DATE_TIME = "yyyy-MM-dd HH:mm:ss"

    /** 仅时间 */
    const val PATTERN_TIME = "HH:mm"

    /** 月日 */
    const val PATTERN_MONTH_DAY = "M月d日"

    /** 年月日 */
    const val PATTERN_FULL_DATE = "yyyy年M月d日"

    private const val SECONDS_PER_MINUTE = 60

    private const val SECONDS_PER_HOUR = 3600
}
