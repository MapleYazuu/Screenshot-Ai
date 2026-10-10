package com.screenshot.ai.ai.providers

import com.screenshot.ai.ai.AiModel

/**
 * Google Gemini。
 *
 * 使用官方提供的 OpenAI 兼容层（/v1beta/openai），因此鉴权仍是标准 Bearer 头，
 * 不需要把 API Key 拼进 query 参数；请求体也沿用 OpenAI 结构。
 * 注意：该兼容层返回的分片内容可能是数组形式，已由基类的 parseChunk 统一处理。
 *
 * 标记为 open：自建网关或代理可继承本类覆写地址与模型清单，无需改动本文件。
 */
open class GeminiProvider : OpenAiCompatibleProvider() {
    override val id: String = "gemini"

    override val displayName: String = "Google Gemini"

    override val supportsVision: Boolean get() = models.any { it.supportsVision }

    override val defaultBaseUrl: String = "https://generativelanguage.googleapis.com/v1beta/openai/"

    override val apiKeyUrl: String = "https://aistudio.google.com/app/apikey"

    /** Gemini 全系支持图片输入，超长上下文是其主要优势（整屏长截图场景友好） */
    override val models: List<AiModel> =
        listOf(
            AiModel(
                id = "gemini-1.5-flash",
                displayName = "Gemini 1.5 Flash",
                supportsVision = true,
                contextWindow = 1_000_000,
            ),
            AiModel(
                id = "gemini-1.5-pro",
                displayName = "Gemini 1.5 Pro",
                supportsVision = true,
                contextWindow = 2_000_000,
            ),
            AiModel(
                id = "gemini-2.0-flash",
                displayName = "Gemini 2.0 Flash",
                supportsVision = true,
                contextWindow = 1_000_000,
            ),
        )
}
