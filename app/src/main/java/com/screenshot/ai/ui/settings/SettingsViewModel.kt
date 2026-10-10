package com.screenshot.ai.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.screenshot.ai.R
import com.screenshot.ai.ServiceLocator
import com.screenshot.ai.ai.AiException
import com.screenshot.ai.ai.AiProvider
import com.screenshot.ai.ai.AiProviderRegistry
import com.screenshot.ai.ai.providers.CustomProvider
import com.screenshot.ai.data.model.CustomModelConfig
import com.screenshot.ai.data.model.Preset
import com.screenshot.ai.data.prefs.AppSettings
import com.screenshot.ai.util.AiErrorMessages
import com.screenshot.ai.util.LocaleManager
import com.screenshot.ai.util.PermissionUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 设置页 ViewModel。
 *
 * 除配置读写外，还负责「测试连接」与权限状态查询。
 * 测试连接走一次极短的流式请求（max_tokens=1），能同时验证
 * 网络、鉴权与模型名三件事。
 */
class SettingsViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val settingsRepo = ServiceLocator.settingsRepository
    private val cryptoRepo = ServiceLocator.cryptoRepository

    /** 测试连接状态 */
    sealed interface TestState {
        data object Idle : TestState

        /** 正在测试某个 Provider */
        data class Testing(
            val providerId: String,
        ) : TestState

        data class Success(
            val providerName: String,
        ) : TestState

        /** 失败：携带结构化异常，由 UI 按当前界面语言渲染成文案 */
        data class Failure(
            val error: AiException,
        ) : TestState
    }

    val settings: StateFlow<AppSettings> =
        settingsRepo.settings.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = AppSettings(),
        )

    val presets: StateFlow<List<Preset>> =
        ServiceLocator.presetRepository.observeAll().stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    private val _testState = MutableStateFlow<TestState>(TestState.Idle)
    val testState: StateFlow<TestState> = _testState.asStateFlow()

    /** 可测试连接的 Provider（当前选中的那个） */
    /**
     * 设置页展示的 Provider 列表。
     *
     * **排除自定义模型**：CustomProvider 会注入 AiProviderRegistry 供「解析 / 调用」
     * 使用，但它不该以内置卡片的形式出现在设置页 —— 否则会与底部专门的
     * [CustomModelCard] 重复渲染（此前就是这里造成「Custom model 卡片缺模型 ID、
     * 默认模型显示未知、与下方自定义模型卡片割裂」的 Bug）。
     */
    val providers: List<AiProvider> =
        AiProviderRegistry.all().filterNot { it.id == CustomProvider.CUSTOM_PROVIDER_ID }

    // ---------------------------------------------------------------------
    // 模型管理
    // ---------------------------------------------------------------------

    /** 切换 Provider：同时把模型切到该 Provider 的默认模型 */
    fun selectProvider(providerId: String) {
        val provider = AiProviderRegistry.findOrDefault(providerId)
        viewModelScope.launch {
            settingsRepo.setProvider(
                providerId = provider.id,
                modelId =
                    provider.defaultModel?.id ?: provider.models
                        .firstOrNull()
                        ?.id
                        .orEmpty(),
            )
            _testState.value = TestState.Idle
        }
    }

    /** 切换模型 */
    fun selectModel(modelId: String) {
        viewModelScope.launch { settingsRepo.setModel(modelId) }
    }

    /** 读取某 Provider 的 Key（用于回填输入框） */
    fun apiKeyOf(providerId: String): String = cryptoRepo.getApiKey(providerId)

    /** 是否已配置 Key */
    fun hasApiKey(providerId: String): Boolean = cryptoRepo.hasApiKey(providerId)

    /** 保存 API Key */
    fun saveApiKey(
        providerId: String,
        key: String,
    ) {
        cryptoRepo.setApiKey(providerId, key.trim())
        _testState.value = TestState.Idle
    }

    /** 清除 API Key */
    fun clearApiKey(providerId: String) {
        cryptoRepo.clearApiKey(providerId)
        _testState.value = TestState.Idle
    }

    /** 自定义 Base URL */
    fun baseUrlOf(providerId: String): String = cryptoRepo.getBaseUrl(providerId)

    fun saveBaseUrl(
        providerId: String,
        baseUrl: String,
    ) {
        cryptoRepo.setBaseUrl(providerId, baseUrl.trim())
    }

    /** 实际生效的 Base URL */
    fun effectiveBaseUrl(provider: AiProvider): String =
        cryptoRepo.getBaseUrl(provider.id).ifBlank { provider.defaultBaseUrl }

    /**
     * 测试连接。
     *
     * **直接读取输入框里的内容**（apiKeyOverride / baseUrlOverride / modelOverride），
     * 而不是已保存的 DataStore 值：用户没点「保存」也能测试；
     * 对应字段留空时才回退到已保存值；仍为空则给出明确错误提示（不静默失败）。
     * 结果通过 [testState] 暴露，UI 负责把 [AiException] 渲染成当前语言的提示。
     */
    fun testConnection(
        providerId: String,
        apiKeyOverride: String? = null,
        baseUrlOverride: String? = null,
        modelOverride: String? = null,
    ) {
        val provider = AiProviderRegistry.findOrDefault(providerId)
        val apiKey =
            apiKeyOverride
                ?.trim()
                .orEmpty()
                .ifBlank { cryptoRepo.getApiKey(provider.id) }
        if (apiKey.isBlank()) {
            // 直接复用领域异常，避免在 ViewModel 里写死某种语言的文案
            _testState.value = TestState.Failure(AiException.MissingApiKey(provider.displayName))
            return
        }
        val baseUrl =
            baseUrlOverride
                ?.trim()
                .orEmpty()
                .ifBlank { effectiveBaseUrl(provider) }
        val model =
            modelOverride
                ?.trim()
                .orEmpty()
                .ifBlank {
                    provider.findModel(settings.value.modelId)?.id
                        ?: provider.defaultModel?.id
                        ?: provider.models.firstOrNull()?.id
                        ?: ""
                }
        if (model.isBlank()) return

        _testState.value = TestState.Testing(provider.id)
        viewModelScope.launch {
            val error = provider.testConnection(apiKey, baseUrl, model)
            _testState.value =
                if (error == null) {
                    TestState.Success(provider.displayName)
                } else {
                    TestState.Failure(error)
                }
        }
    }

    fun resetTestState() {
        _testState.value = TestState.Idle
    }

    // ---------------------------------------------------------------------
    // 默认指令 / 悬浮球 / 其他
    // ---------------------------------------------------------------------

    fun selectDefaultPreset(presetId: String) {
        viewModelScope.launch {
            ServiceLocator.presetRepository.setDefault(presetId)
            settingsRepo.setDefaultPreset(presetId)
        }
    }

    fun setFloatingAlpha(alpha: Float) {
        viewModelScope.launch { settingsRepo.setFloatingAlpha(alpha) }
    }

    fun setSnapToEdge(snap: Boolean) {
        viewModelScope.launch { settingsRepo.setSnapToEdge(snap) }
    }

    fun setLowQuality(low: Boolean) {
        viewModelScope.launch { settingsRepo.setLowQuality(low) }
    }

    fun setAutoStart(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.setAutoStart(enabled) }
    }

    fun setThemeMode(mode: String) {
        viewModelScope.launch {
            settingsRepo.setThemeMode(mode)
            com.screenshot.ai.ScreenshotAiApp
                .applyThemeModeStatic(mode)
        }
    }

    /**
     * 切换界面语言。
     *
     * 只负责持久化「用户选了什么」；真正生效由 [LocaleManager.applyLanguage] 完成
     * （它会触发 Activity 重建，因此本方法不需要也不应该手动 recreate）。
     * 顺序上先落盘再应用：Activity 重建时会重新读一次配置，避免出现「界面已是日语、
     * 偏好里还写着中文」的中间态。
     */
    fun setLanguage(tag: String) {
        viewModelScope.launch {
            // 先异步落盘（DataStore 内部是协程 IO，不阻塞主线程）
            settingsRepo.setLanguage(tag)
            // 应用语言：Android 13+ 走系统 LocaleManager、12 及以下走
            // AppCompatDelegate —— 二者都自带 Activity 重建，这里绝不手动 recreate
            LocaleManager.applyLanguage(getApplication(), tag)
        }
    }

    // ---------------------------------------------------------------------
    // 权限状态（每次进入页面重新查询，不做缓存）
    // ---------------------------------------------------------------------

    fun canDrawOverlays(): Boolean = PermissionUtils.canDrawOverlays(getApplication())

    fun isAccessibilityEnabled(): Boolean = PermissionUtils.isAccessibilityServiceEnabled(getApplication())

    fun isNotificationGranted(): Boolean = PermissionUtils.isNotificationPermissionGranted(getApplication())

    fun isIgnoringBatteryOptimizations(): Boolean = PermissionUtils.isIgnoringBatteryOptimizations(getApplication())

    fun vendorName(): String = PermissionUtils.vendorKey()

    /** 厂商展示名称（按当前界面语言）；非定制 ROM 返回 null */
    fun vendorDisplayName(): String? = PermissionUtils.vendorDisplayName(getApplication())

    /** 当前实际生效的语言码（用户选「跟随系统」时即系统语言） */
    fun currentLanguage(): String = LocaleManager.currentLanguage(getApplication())

    // ---------------------------------------------------------------------
    // 自定义模型（OpenAI 兼容第三方接口）
    // ---------------------------------------------------------------------

    /** 自定义模型配置流 */
    val customConfig: StateFlow<CustomModelConfig> =
        ServiceLocator.customModelRepository.observe().stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = CustomModelConfig(),
        )

    /** 自定义模型测试连接状态 */
    data class CustomTestState(
        val testing: Boolean,
        val error: String?,
        val succeeded: Boolean = false,
    )

    private val _customTestState =
        MutableStateFlow(CustomTestState(testing = false, error = null, succeeded = false))
    val customTestState: StateFlow<CustomTestState> = _customTestState.asStateFlow()

    /** 自定义模型的 API Key（加密存储，providerId = "custom"） */
    fun customApiKey(): String = ServiceLocator.customModelRepository.apiKey()

    /**
     * 保存自定义模型。
     *
     * 保存成功后自动把「当前 Provider / 模型」切到自定义模型，
     * 这样首页的「默认模型」标签与悬浮球截图提问都会立即使用它。
     */
    fun saveCustomModel(
        displayName: String,
        baseUrl: String,
        modelId: String,
        apiKey: String,
        supportsVision: Boolean,
    ) {
        viewModelScope.launch {
            val config = CustomModelConfig(displayName, baseUrl, modelId, supportsVision)
            ServiceLocator.customModelRepository.save(config)
            ServiceLocator.customModelRepository.setApiKey(apiKey)
            // 切换当前模型到自定义：首页默认模型标签 + 截图提问链路同步生效
            settingsRepo.setProvider(
                providerId = CustomProvider.CUSTOM_PROVIDER_ID,
                modelId = config.normalizedModelId(),
            )
        }
    }

    /**
     * 测试自定义模型连接。
     *
     * - 模型 ID 为空：直接给出界面提示，不发请求；
     * - URL 自动补全 http(s)://；留空用标准 OpenAI 地址；
     * - 错误经 AiErrorMessages 渲染成当前界面语言，展示在卡片内（不崩溃、不静默）。
     */
    fun testCustomModel(
        displayName: String,
        baseUrl: String,
        modelId: String,
        apiKey: String,
        supportsVision: Boolean,
    ) {
        val config = CustomModelConfig(displayName, baseUrl, modelId, supportsVision)
        if (config.normalizedModelId().isBlank()) {
            _customTestState.value =
                CustomTestState(
                    testing = false,
                    error = getApplication<Application>().getString(R.string.custom_error_model_id),
                    succeeded = false,
                )
            return
        }
        _customTestState.value = CustomTestState(testing = true, error = null, succeeded = false)
        viewModelScope.launch {
            // 优先使用表单里刚输入的 Key，留空则读加密存储里的旧 Key
            val key =
                apiKey.trim().ifBlank {
                    ServiceLocator.customModelRepository.apiKey()
                }
            val provider = CustomProvider { config }
            val error =
                provider.testConnection(
                    apiKey = key,
                    baseUrl = config.normalizedBaseUrl().ifBlank { CustomProvider.FALLBACK_BASE_URL },
                    model = config.normalizedModelId(),
                )
            _customTestState.value =
                CustomTestState(
                    testing = false,
                    error = error?.let { AiErrorMessages.localize(getApplication(), it) },
                    succeeded = error == null,
                )
        }
    }

    fun resetCustomTestState() {
        _customTestState.value = CustomTestState(testing = false, error = null, succeeded = false)
    }
}
