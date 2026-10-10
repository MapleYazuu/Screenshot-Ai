package com.screenshot.ai.ui.main

import android.content.Intent
import android.os.Bundle
import com.screenshot.ai.ui.base.LocalizedActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.screenshot.ai.ui.about.AboutActivity
import com.screenshot.ai.ui.history.HistoryActivity
import com.screenshot.ai.ui.preset.PresetActivity
import com.screenshot.ai.ui.settings.SettingsActivity
import com.screenshot.ai.ui.theme.ScreenshotAiTheme

/**
 * 主界面 Activity。
 *
 * 说明：主题模式从 DataStore 读取后交给 Compose 主题，
 * 因此用户在设置页切换深色 / 浅色后无需重启即可生效。
 */
class MainActivity : LocalizedActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val settings by viewModel.settingsFlow.collectAsStateWithLifecycle()

            ScreenshotAiTheme(themeMode = settings.themeMode) {
                MainScreen(
                    viewModel = viewModel,
                    onOpenHistory = { startActivity(Intent(this, HistoryActivity::class.java)) },
                    onOpenPreset = { startActivity(Intent(this, PresetActivity::class.java)) },
                    onOpenSettings = { startActivity(Intent(this, SettingsActivity::class.java)) },
                    onOpenAbout = { startActivity(Intent(this, AboutActivity::class.java)) },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 从系统设置页返回时刷新权限状态（无障碍 / 悬浮窗可能刚被授权）
        viewModel.refresh()
    }
}
