package com.screenshot.ai.ui.about

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.screenshot.ai.BuildConfig
import com.screenshot.ai.util.UpdateChecker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 关于页 ViewModel：只负责「检查更新」这一个异步动作。
 */
class AboutViewModel(
    application: Application,
) : AndroidViewModel(application) {
    /** 更新检查状态机 */
    sealed interface UpdateState {
        data object Idle : UpdateState

        data object Checking : UpdateState

        data object UpToDate : UpdateState

        data class Available(
            val version: String,
            val downloadUrl: String,
            val changelog: String,
        ) : UpdateState

        data class Failed(
            val message: String,
        ) : UpdateState
    }

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    val versionName: String = BuildConfig.VERSION_NAME
    val versionCode: Int = BuildConfig.VERSION_CODE

    /** 仓库地址（About 页展示与跳转共用） */
    val repoUrl: String = "https://github.com/${UpdateChecker.GITHUB_OWNER}/${UpdateChecker.GITHUB_REPO}"

    /**
     * 检查更新。
     *
     * 走 GitHub Releases API，不需要任何鉴权；
     * 失败时把原因交给 UI 展示，而不是静默忽略——用户主动点了按钮，就应该得到反馈。
     */
    fun checkUpdate() {
        if (_updateState.value is UpdateState.Checking) return
        _updateState.value = UpdateState.Checking

        viewModelScope.launch {
            _updateState.value =
                runCatching {
                    val result = UpdateChecker.check(versionName)
                    if (result.hasUpdate) {
                        UpdateState.Available(
                            version = result.latest.version,
                            downloadUrl = result.latest.apkUrl ?: result.latest.htmlUrl,
                            changelog = result.latest.body,
                        )
                    } else {
                        UpdateState.UpToDate
                    }
                }.getOrElse { error ->
                    UpdateState.Failed(error.message ?: error.javaClass.simpleName)
                }
        }
    }

    fun resetUpdateState() {
        _updateState.value = UpdateState.Idle
    }
}
