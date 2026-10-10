package com.screenshot.ai.ai.providers

import com.screenshot.ai.ai.AiModel

/**
 * DeepSeek（深度求索）。
 *
 * baseUrl 只填域名时，[com.screenshot.ai.ai.net.UrlBuilder] 会补成
 * https://api.deepseek.com/v1/chat/completions —— DeepSeek 官方说明 /v1 与不带版本号等价，
 * 因此两种写法都能正常调用。
 *
 * 标记为 open：自建网关或代理可继承本类覆写地址与模型清单，无需改动本文件。
 */
open class DeepSeekProvider : OpenAiCompatibleProvider() {
    override val id: String = "deepseek"

    override val displayName: String = "DeepSeek"

    override val supportsVision: Boolean get() = models.any { it.supportsVision }

    override val defaultBaseUrl: String = "https://api.deepseek.com/"

    override val apiKeyUrl: String = "https://platform.deepseek.com/api_keys"

    /**
     * deepseek-flash 支持图片输入；chat / reasoner 为纯文本模型，
     * 选中它们时上层需先做本地 OCR 降级，否则带图请求会被服务端拒绝。
     */
    override val models: List<AiModel> =
        listOf(
            AiModel(
                id = "deepseek-flash",
                displayName = "DeepSeek Flash",
                supportsVision = true,
                contextWindow = 64_000,
            ),
            AiModel(
                id = "deepseek-chat",
                displayName = "DeepSeek Chat",
                supportsVision = false,
                contextWindow = 64_000,
            ),
            AiModel(
                id = "deepseek-reasoner",
                displayName = "DeepSeek Reasoner",
                supportsVision = false,
                contextWindow = 64_000,
            ),
        )
}
