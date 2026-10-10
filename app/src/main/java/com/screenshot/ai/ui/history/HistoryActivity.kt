package com.screenshot.ai.ui.history

import android.os.Bundle
import com.screenshot.ai.ui.base.LocalizedActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.screenshot.ai.ui.theme.ScreenshotAiTheme

/**
 * 提问历史页。
 */
class HistoryActivity : LocalizedActivity() {
    private val viewModel: HistoryViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            ScreenshotAiTheme(themeMode = settings.themeMode) {
                HistoryScreen(
                    viewModel = viewModel,
                    onBack = { finish() },
                )
            }
        }
    }
}
