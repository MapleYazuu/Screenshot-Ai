package com.screenshot.ai.ai.providers

import com.screenshot.ai.ai.AiModel

/**
 * 通义千问（阿里云百炼 DashScope）。
 *
 * 使用 DashScope 的 OpenAI 兼容模式：baseUrl 已含 /compatible-mode/v1，
 * 因此最终地址为 /compatible-mode/v1/chat/completions，鉴权仍是标准 Bearer 头。
 *
 * 标记为 open：自建网关或代理可继承本类覆写地址与模型清单，无需改动本文件。
 */
open class QwenProvider : OpenAiCompatibleProvider() {
    override val id: String = "qwen"

    override val displayName: String = "通义千问"

    override val supportsVision: Boolean get() = models.any { it.supportsVision }

    override val defaultBaseUrl: String = "https://dashscope.aliyuncs.com/compatible-mode/v1/"

    override val apiKeyUrl: String = "https://bailian.console.aliyun.com/?apiKey=1"

    /** qwen-vl-* 支持图片输入；qwen-plus 为纯文本长上下文模型 */
    override val models: List<AiModel> =
        listOf(
            AiModel(
                id = "qwen-vl-max",
                displayName = "Qwen-VL-Max",
                supportsVision = true,
                contextWindow = 32_000,
            ),
            AiModel(
                id = "qwen-vl-plus",
                displayName = "Qwen-VL-Plus",
                supportsVision = true,
                contextWindow = 32_000,
            ),
            AiModel(
                id = "qwen-plus",
                displayName = "Qwen-Plus",
                supportsVision = false,
                contextWindow = 131_072,
            ),
        )
}
