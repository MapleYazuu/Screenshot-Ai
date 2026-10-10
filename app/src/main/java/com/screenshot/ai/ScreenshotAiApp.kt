package com.screenshot.ai

import android.app.Application
import android.content.res.Configuration
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import com.screenshot.ai.data.prefs.AppSettings
import com.screenshot.ai.service.FloatingServiceController
import com.screenshot.ai.util.LocaleManager
import com.screenshot.ai.util.PermissionUtils
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 应用入口。
 *
 * 职责：
 * 1. 初始化 [ServiceLocator]（数据库、仓库、加密存储）；
 * 2. 应用用户选择的语言与主题（AppCompatDelegate 全局生效）；
 * 3. 若用户开启「悬浮工具栏」但服务已被系统回收，启动时自愈补拉起。
 */
class ScreenshotAiApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // 1. 依赖初始化（手动 ServiceLocator，避免引入 Hilt 增加编译与学习成本）
        ServiceLocator.init(this)

        // 2. 读取配置并应用主题 / 语言；随后处理「服务被回收后自愈」
        ServiceLocator.appScope.launch {
            runCatching {
                val settings = ServiceLocator.settingsRepository.settings.first()
                applyThemeMode(settings.themeMode)
                applyLanguageSafely(settings.languageTag)

                // 开关处于「开」但服务已不在运行（进程被系统杀掉），补启动一次，
                // 避免出现「开关是开的但悬浮球不见了」的迷惑状态。
                if (settings.floatingEnabled &&
                    !FloatingServiceController.isRunning() &&
                    PermissionUtils.canDrawOverlays(this@ScreenshotAiApp)
                ) {
                    Log.i(TAG, "检测到悬浮服务未运行，尝试重新启动")
                    FloatingServiceController.start(this@ScreenshotAiApp)
                }
            }.onFailure { Log.w(TAG, "初始化配置失败", it) }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.d(TAG, "系统配置变化：${newConfig.locales}")
    }

    private fun applyThemeMode(mode: String) {
        val nightMode = toNightMode(mode)
        if (AppCompatDelegate.getDefaultNightMode() != nightMode) {
            AppCompatDelegate.setDefaultNightMode(nightMode)
        }
    }

    /**
     * 应用语言（启动兜底）。
     *
     * 语言应用本身绝不抛异常（LocaleManager 内部 try-catch + 系统语言回退），
     * 这里再加一层保险：万一失败，把 DataStore 里保存的语言标记重置为
     * 「跟随系统」，保证**下次启动不会再加载坏配置**——这正是
     * 「语言配置已保存导致重启即卡死」的根治手段。
     */
    private suspend fun applyLanguageSafely(tag: String) {
        val applied = LocaleManager.applyLanguage(this, tag)
        if (!applied) {
            Log.e(TAG, "语言应用失败，重置为跟随系统（安全模式）")
            LocaleManager.resetToSystem(this)
            runCatching { ServiceLocator.settingsRepository.setLanguage(LocaleManager.TAG_SYSTEM) }
        }
    }

    companion object {
        private const val TAG = "ScreenshotAiApp"

        /** 供设置页切换主题后立即生效调用 */
        fun applyThemeModeStatic(mode: String) {
            AppCompatDelegate.setDefaultNightMode(toNightMode(mode))
        }

        private fun toNightMode(mode: String): Int =
            when (mode) {
                AppSettings.THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                AppSettings.THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
    }
}
