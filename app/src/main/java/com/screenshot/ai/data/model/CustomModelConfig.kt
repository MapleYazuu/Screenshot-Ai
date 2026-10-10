package com.screenshot.ai.data.model

import kotlinx.serialization.Serializable

/**
 * 自定义模型（OpenAI 兼容第三方接口）配置。
 *
 * 与内置 Provider 的区别：
 * - 内置 Provider 的 baseUrl / 模型列表 / 视觉能力都是代码常量；
 * - 自定义模型完全由用户填写，运行时动态生成 [com.screenshot.ai.ai.AiModel]。
 *
 * API Key 不放在本结构里：它走 [com.screenshot.ai.data.crypto.CryptoRepository]
 * 加密存储（providerId = "custom"），与内置 Provider 的安全策略一致。
 */
@Serializable
data class CustomModelConfig(
    /** 显示名称，例如「我的 Ollama」 */
    val displayName: String = "",

    /** 接口地址（OpenAI 兼容根地址），例如 https://api.openai.com/v1 */
    val baseUrl: String = "",

    /** 模型 ID，例如 gpt-4-turbo / qwen-max / llama3 */
    val modelId: String = "",

    /** 是否支持图片输入：true 直接发 Base64 截图，false 走本地 OCR 降级 */
    val supportsVision: Boolean = false,
) {
    /** 是否已完成可用的配置（以模型 ID 为准） */
    val isConfigured: Boolean get() = modelId.isNotBlank()

    /**
     * 规范化接口地址：
     * 1. 去掉首尾空白；
     * 2. 缺少 http:// 或 https:// 时自动补全 https://；
     * 3. 留空表示「使用标准 OpenAI 地址」（由 Provider 兜底）。
     */
    fun normalizedBaseUrl(): String {
        val trimmed = baseUrl.trim()
        if (trimmed.isEmpty()) return ""
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "https://$trimmed"
        }
    }

    /** 模型 ID 去首尾空白后的值 */
    fun normalizedModelId(): String = modelId.trim()

    /** 显示名称去首尾空白后的值（可为空，UI 层兜底显示「自定义模型」） */
    fun normalizedDisplayName(): String = displayName.trim()
}
