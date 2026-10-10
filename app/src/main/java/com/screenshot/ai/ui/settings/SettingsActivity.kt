package com.screenshot.ai.ui.settings

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.screenshot.ai.service.FloatingService
import com.screenshot.ai.ui.base.LocalizedActivity
import com.screenshot.ai.ui.theme.ScreenshotAiTheme

/**
 * 设置页：
 * - 模型管理（Provider / API Key / 默认模型 / 测试连接）
 * - 默认指令
 * - 悬浮球配置（透明度 / 大小 / 贴边半隐藏 / 低质量模式）
 * - 权限管理（无障碍、悬浮窗、通知、开机自启、电池优化）
 * - 语言 / 主题
 *
 * 语言切换说明（曾因手动 recreate 叠加平台自动重建导致无限循环 ANR）：
 * 本类**不做任何 recreate**——Android 13+ 由系统 LocaleManager、
 * 12 及以下由 AppCompatDelegate 自动重建本 Activity；
 * onCreate 里顺手刷新悬浮窗按钮文案（重建后的 Context 已是新语言）。
 */
class SettingsActivity : LocalizedActivity() {
    private val viewModel: SettingsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 悬浮窗按钮文案跑在服务 Context 里，不随 Activity 重建；
        // 每次进入设置页都用当前（可能刚被系统重建、已是新语言的）Context 刷新一次
        FloatingService.refreshOverlayLanguage(this)

        setContent {
            val settings by viewModel.settings.collectAsStateWithLifecycle()

            ScreenshotAiTheme(themeMode = settings.themeMode) {
                SettingsScreen(
                    viewModel = viewModel,
                    onBack = { finish() },
                    onOpenAbout = {
                        startActivity(
                            android.content.Intent(
                                this,
                                com.screenshot.ai.ui.about.AboutActivity::class.java,
                            ),
                        )
                    },
                )
            }
        }
    }
}
