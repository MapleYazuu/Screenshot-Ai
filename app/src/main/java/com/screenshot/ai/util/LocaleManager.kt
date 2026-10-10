package com.screenshot.ai.util

import android.content.Context
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

/**
 * 语言与「AI 回复语言」的统一出口。
 *
 * ## 语言切换死锁（ANR）修复说明
 *
 * 上一版实现有三处会引发无限重建 / 死锁：
 * 1. 手动 `recreate()` 包在 `setApplicationLocales` 外面 —— 而后者
 *    （Android 13+ 的系统 LocaleManager / AndroidX 兼容库）**本身就负责重建
 *    Activity**，两套重建叠加会无限循环，直接 ANR；
 * 2. `attachBaseContext` 里自定义 ContextWrapper 强制改语言 —— 与
 *    AppCompat 内部的 locale 应用重复，造成 Context 配置冲突；
 * 3. 语言版本号 + onResume 补重建的「自愈」逻辑，在 ViewModel 存活于
 *    recreate 之间的场景下会反复触发 recreate。
 *
 * 本版彻底移除上述机制，改为标准方案：
 * - **Android 13+（API 33）**：使用系统
 *   `LocaleManager.setApplicationLocales()`，由系统负责持久化 + 全局生效 +
 *   Activity 自动重建；
 * - **Android 12 及以下**：使用 AndroidX
 *   `AppCompatDelegate.setApplicationLocales()`，配合 AppCompatActivity
 *   （本 App 的 Theme.ScreenshotAI 就是 Theme.AppCompat 后代）自动生效 + 自动重建；
 * - **两条路径绝不混用**，也不在外部再包一层重建逻辑。
 *
 * 数据持久化在 DataStore（协程异步），本类不碰主线程 IO。
 */
object LocaleManager {

    /** 「跟随系统」的哨兵值 */
    const val TAG_SYSTEM = "system"
    const val TAG_ZH = "zh"
    const val TAG_EN = "en"
    const val TAG_JA = "ja"
    const val TAG_KO = "ko"
    const val TAG_RU = "ru"

    /** 全部可选语言码（不含「跟随系统」） */
    val SUPPORTED_LANGUAGES = listOf(TAG_ZH, TAG_EN, TAG_JA, TAG_KO, TAG_RU)

    /**
     * 当前**实际生效**的语言码。
     *
     * 取值优先级：
     * 1. Android 13+：系统 LocaleManager 的 per-app locale；
     * 2. AppCompatDelegate 存储的 per-app locale（Android 12 及以下）；
     * 3. 系统 Locale；
     * 4. 英文兜底。
     *
     * @param context 用于读取系统 LocaleManager（Android 13+）；传 null 时跳过第 1 步
     */
    fun currentLanguage(context: Context? = null): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && context != null) {
            runCatching {
                val lm = context.getSystemService(android.app.LocaleManager::class.java)
                val first = lm?.applicationLocales?.get(0)
                if (first != null) {
                    return normalize(first.language)
                }
            }
        }
        val fromAppCompat = AppCompatDelegate.getApplicationLocales().get(0)
        if (fromAppCompat != null) {
            return normalize(fromAppCompat.language)
        }
        return normalize(Locale.getDefault().language)
    }

    /**
     * 把任意语言码归一到受支持的语言。
     *
     * - "zh" / "zh-CN" / "zh-Hans" / "zh-TW" 都归到 "zh"（当前仅提供简体翻译，
     *   繁体用户看到简体界面也好过看到英文）；
     * - 其它未支持的语言统一回退英文，与资源 Fallback 行为一致。
     */
    fun normalize(languageCode: String?): String {
        val code = languageCode?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return when {
            code.startsWith(TAG_ZH) -> TAG_ZH
            code.startsWith(TAG_JA) -> TAG_JA
            code.startsWith(TAG_KO) -> TAG_KO
            code.startsWith(TAG_RU) -> TAG_RU
            else -> TAG_EN
        }
    }

    /**
     * 应用语言设置（按 Android 版本二选一，绝不混用）。
     *
     * 成功返回 true；任何异常都吞掉并回退系统默认语言，返回 false。
     * 该方法**不会**手动 recreate：Activity 重建由系统 LocaleManager /
     * AppCompatDelegate 各自负责，外部再重建只会造成无限循环。
     *
     * @param tag [TAG_SYSTEM] 表示跟随系统，否则传具体语言码
     */
    fun applyLanguage(context: Context, tag: String): Boolean =
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Android 13+：系统 per-app language（持久化 + 自动重建都由系统完成）
                val lm = context.getSystemService(android.app.LocaleManager::class.java)
                val desired =
                    if (tag == TAG_SYSTEM || tag.isBlank()) {
                        android.os.LocaleList.getEmptyLocaleList()
                    } else {
                        android.os.LocaleList.forLanguageTags(tag)
                    }
                lm?.applicationLocales = desired
                true
            } else {
                // Android 12 及以下：AndroidX 兼容库（配合 AppCompatActivity 自动生效 + 自动重建）
                val desired =
                    if (tag == TAG_SYSTEM || tag.isBlank()) {
                        LocaleListCompat.getEmptyLocaleList()
                    } else {
                        LocaleListCompat.forLanguageTags(tag)
                    }
                AppCompatDelegate.setApplicationLocales(desired)
                true
            }
        }.getOrElse {
            android.util.Log.e("LocaleManager", "应用语言失败，回退系统默认语言", it)
            // 安全兜底：异常时清掉错误配置，避免下次启动继续踩坑
            resetToSystem(context)
            false
        }

    /**
     * 紧急恢复：把语言重置为「跟随系统」。
     * 用于启动时检测到错误配置的兜底，内部同样 try-catch，绝不抛异常。
     */
    fun resetToSystem(context: Context) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.getSystemService(android.app.LocaleManager::class.java)
                    ?.applicationLocales = android.os.LocaleList.getEmptyLocaleList()
            } else {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
            }
        }
    }

    /**
     * AI 回复语言的英文名，用于拼进 Prompt。
     *
     * 刻意用英文写「请用日语回答」而不是用日语写：
     * 模型对英文元指令的遵循度最稳定，且能避免「用目标语言下指令、模型却用中文思考」的干扰。
     */
    fun aiReplyLanguageName(languageCode: String): String = when (languageCode) {
        TAG_ZH -> "Simplified Chinese"
        TAG_JA -> "Japanese"
        TAG_KO -> "Korean"
        TAG_RU -> "Russian"
        else -> "English"
    }

    /** 语言码 → 该语言的本地名称，用于提示文案（例如「AI 将使用 日本語 回复」） */
    fun displayName(languageCode: String): String = when (normalize(languageCode)) {
        TAG_ZH -> "简体中文"
        TAG_JA -> "日本語"
        TAG_KO -> "한국어"
        TAG_RU -> "Русский"
        else -> "English"
    }

    /** 供 UI 展示「当前生效语言」的本地名称 */
    fun currentDisplayName(context: Context): String {
        val code = currentLanguage(context)
        // 语言自名用各自语言书写，不随界面语言变化（这是通用的做法）
        return displayName(code)
    }
}
