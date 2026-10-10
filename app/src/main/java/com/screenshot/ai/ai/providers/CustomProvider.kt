package com.screenshot.ai.ai.providers

import com.screenshot.ai.ai.AiModel
import com.screenshot.ai.data.model.CustomModelConfig

/**
 * 自定义模型 Provider：用户手动配置的「OpenAI 兼容第三方接口」。
 *
 * 设计要点：
 * - 与内置 Provider 最大的区别是**一切由配置驱动**：displayName / baseUrl /
 *   模型列表 / 视觉能力都来自 [CustomModelConfig]；
 * - 网络格式完全复用 [OpenAiCompatibleProvider]（/chat/completions + SSE），
 *   因此任意 OpenAI 兼容网关（Ollama / one-api / 各家代理商）都能接入；
 * - baseUrl 留空时使用标准 OpenAI 地址；缺少 http(s):// 的地址已在
 *   CustomModelConfig.normalizedBaseUrl 中自动补全；
 * - 视觉开关真实生效：supportsVision = false 时模型不带图，
 *   由 FloatingService 走本地 OCR 降级把截图文字发给模型。
 *
 * @param configProvider 同步读取当前配置（由 CustomModelRepository 的缓存提供）
 */
class CustomProvider(
    private val configProvider: () -> CustomModelConfig,
) : OpenAiCompatibleProvider() {

    override val id: String = CUSTOM_PROVIDER_ID

    /** 是否支持视觉：直接反映用户配置里的开关 */
    override val supportsVision: Boolean
        get() = configProvider().supportsVision

    override val displayName: String
        get() = configProvider().normalizedDisplayName().ifBlank { DEFAULT_DISPLAY_NAME }

    override val defaultBaseUrl: String
        get() = configProvider().normalizedBaseUrl().ifBlank { FALLBACK_BASE_URL }

    override val models: List<AiModel>
        get() {
            val config = configProvider()
            val modelId = config.normalizedModelId()
            if (modelId.isBlank()) return emptyList()
            return listOf(
                AiModel(
                    id = modelId,
                    displayName = config.normalizedDisplayName().ifBlank { modelId },
                    supportsVision = config.supportsVision,
                    contextWindow = null,
                ),
            )
        }

    /** 自定义模型没有固定的 Key 申请页 */
    override val apiKeyUrl: String = ""

    companion object {
        const val CUSTOM_PROVIDER_ID = "custom"

        /** 配置未就绪时的兜底展示名（对应 strings 里的 settings_custom_model） */
        const val DEFAULT_DISPLAY_NAME = "Custom model"

        /** 留空时的标准 OpenAI 地址 */
        const val FALLBACK_BASE_URL = "https://api.openai.com/"
    }
}
