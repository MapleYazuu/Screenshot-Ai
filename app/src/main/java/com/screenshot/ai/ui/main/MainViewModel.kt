package com.screenshot.ai.ui.main

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.screenshot.ai.ServiceLocator
import com.screenshot.ai.data.model.Preset
import com.screenshot.ai.data.prefs.AppSettings
import com.screenshot.ai.service.FloatingService
import com.screenshot.ai.service.FloatingServiceController
import com.screenshot.ai.util.PermissionUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 主界面状态。
 *
 * @param floatingEnabled 开关的目标状态（用户意图）
 * @param serviceRunning  悬浮服务实际是否在运行
 * @param overlayGranted  悬浮窗权限
 * @param accessibilityGranted 无障碍截屏权限
 * @param defaultPresetName 当前默认指令名，展示在开关说明里
 */
data class MainUiState(
    val floatingEnabled: Boolean = false,
    val serviceRunning: Boolean = false,
    val overlayGranted: Boolean = false,
    val accessibilityGranted: Boolean = false,
    val defaultPresetName: String = "",
    val modelLabel: String = "",
)

/**
 * 主界面 ViewModel。
 *
 * 关键点：开关状态以「服务真实运行状态」为准渲染。
 * 当服务被系统杀掉时，ServiceLocator.serviceRunning 变为 false，
 * 开关会自动回弹，并通过 [consumeServiceKilledEvent] 通知 UI 弹提示。
 */
class MainViewModel(
    application: Application,
) : AndroidViewModel(application) {
    /** 服务被回收的一次性提示事件 */
    private val _serviceKilledEvent = MutableStateFlow(false)
    val serviceKilledEvent: StateFlow<Boolean> = _serviceKilledEvent.asStateFlow()

    val uiState: StateFlow<MainUiState> =
        combine(
            ServiceLocator.settingsRepository.settings,
            ServiceLocator.serviceRunning,
            ServiceLocator.presetRepository.observeDefault(),
            ServiceLocator.refreshTrigger,
        ) { settings, running, preset, _ ->
            MainUiState(
                floatingEnabled = settings.floatingEnabled,
                serviceRunning = running,
                overlayGranted = PermissionUtils.canDrawOverlays(getApplication()),
                accessibilityGranted = PermissionUtils.isAccessibilityServiceEnabled(getApplication()),
                defaultPresetName = preset?.name.orEmpty(),
                modelLabel = "${settings.provider.displayName} · ${settings.model.displayName}",
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = MainUiState(),
        )

    /** 页面回到前台时刷新权限状态（用户可能刚在系统设置里授权） */
    fun refresh() {
        ServiceLocator.notifyRefresh()
        // 服务被杀掉的检测：开关为开但服务不在运行
        val settings = uiState.value
        if (settings.floatingEnabled && !FloatingService.isRunning()) {
            _serviceKilledEvent.value = true
        }
    }

    /**
     * 切换悬浮工具栏。
     * @return true 表示已接受操作；false 表示缺少权限，需要 UI 引导
     */
    fun setFloatingEnabled(enabled: Boolean): Boolean {
        val context = getApplication<Application>()
        if (enabled && !PermissionUtils.canDrawOverlays(context)) return false

        viewModelScope.launch {
            ServiceLocator.settingsRepository.setFloatingEnabled(enabled)
            // 统一入口：主界面 / 快捷设置 Tile / 开机自启共用同一套启停逻辑
            if (enabled) {
                FloatingServiceController.start(context)
            } else {
                FloatingServiceController.stop(context)
            }
        }
        return true
    }

    /** 已消费一次性事件 */
    fun consumeServiceKilledEvent() {
        _serviceKilledEvent.value = false
    }

    /** 当前默认指令（可为空），供开关说明展示 */
    suspend fun currentDefaultPreset(): Preset? = ServiceLocator.presetRepository.getDefault()

    /** 供 UI 判断语言/主题 */
    val settingsFlow: StateFlow<AppSettings> =
        ServiceLocator.settingsRepository.settings.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = AppSettings(),
        )
}
