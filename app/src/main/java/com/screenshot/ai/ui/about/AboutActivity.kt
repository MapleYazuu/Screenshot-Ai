package com.screenshot.ai.ui.about

import android.os.Bundle
import com.screenshot.ai.ui.base.LocalizedActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.screenshot.ai.ServiceLocator
import com.screenshot.ai.ui.theme.ScreenshotAiTheme

/**
 * 关于页：版本、开源协议、GitHub 仓库、作者、致谢、检查更新。
 */
class AboutActivity : LocalizedActivity() {
    private val viewModel: AboutViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            // 主题模式跟随全局设置，避免出现「其他页面深色、这里浅色」的割裂感
            val settings by ServiceLocator.settingsRepository.settings
                .collectAsStateWithLifecycle(initialValue = null)

            ScreenshotAiTheme(
                themeMode = settings?.themeMode ?: com.screenshot.ai.data.prefs.AppSettings.THEME_SYSTEM,
            ) {
                AboutScreen(viewModel = viewModel, onBack = { finish() })
            }
        }
    }
}
