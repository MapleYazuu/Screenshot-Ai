package com.screenshot.ai.ai.providers

import com.screenshot.ai.ai.AiModel
import com.screenshot.ai.ai.ChatMessage
import org.json.JSONObject

/** o 系列推理模型的 id 前缀：这些模型不接受 max_tokens / temperature 组合中的旧字段 */
private val REASONING_MODEL_PREFIXES = listOf("o1", "o3", "o4")

/** 推理模型改用 max_completion_tokens 时的输出上限（需为推理过程预留额度） */
private const val REASONING_MAX_COMPLETION_TOKENS = 4096

/**
 * OpenAI 官方接口。
 *
 * 与其它 OpenAI 兼容厂商的唯一差异在 o 系列推理模型：
 * 它们只接受 `max_completion_tokens`，传 `max_tokens` 会直接报 400，
 * 因此这里按模型 id 覆写请求体字段（见 [buildRequestBody]）。
 *
 * 标记为 open：自建网关或代理可继承本类覆写地址与模型清单，无需改动本文件。
 */
open class OpenAiProvider : OpenAiCompatibleProvider() {
    override val id: String = "openai"

    override val displayName: String = "OpenAI"

    override val supportsVision: Boolean get() = models.any { it.supportsVision }

    override val defaultBaseUrl: String = "https://api.openai.com/"

    override val apiKeyUrl: String = "https://platform.openai.com/api-keys"

    override val models: List<AiModel> =
        listOf(
            AiModel(
                id = "gpt-4o",
                displayName = "GPT-4o",
                supportsVision = true,
                contextWindow = 128_000,
            ),
            AiModel(
                id = "gpt-4o-mini",
                displayName = "GPT-4o mini",
                supportsVision = true,
                contextWindow = 128_000,
            ),
            AiModel(
                id = "gpt-4-turbo",
                displayName = "GPT-4 Turbo",
                supportsVision = true,
                contextWindow = 128_000,
            ),
            AiModel(
                id = "o3-mini",
                displayName = "o3-mini",
                supportsVision = false,
                contextWindow = 200_000,
            ),
        )

    /**
     * o 系列推理模型：写入 max_completion_tokens 而不是 max_tokens。
     *
     * 基类的 [maxTokens] 保持 null（不写 max_tokens），这里只补上新字段名。
     */
    override fun buildRequestBody(
        model: String,
        messages: List<ChatMessage>,
        temperature: Double,
        stream: Boolean,
    ): JSONObject {
        val body = super.buildRequestBody(model, messages, temperature, stream)
        if (isReasoningModel(model)) {
            body.remove("max_tokens")
            body.put("max_completion_tokens", REASONING_MAX_COMPLETION_TOKENS)
        }
        return body
    }

    /** 连通性测试同样要把 token 上限字段换成 max_completion_tokens，否则 o3-mini 会 400 */
    override fun buildTestRequestBody(model: String): JSONObject {
        val body = super.buildTestRequestBody(model)
        if (isReasoningModel(model)) {
            body.remove("max_tokens")
            body.put("max_completion_tokens", 1)
        }
        return body
    }

    /** 用前缀判断而不是全量枚举，便于 o 系列出新模型时无需改代码 */
    private fun isReasoningModel(model: String): Boolean = REASONING_MODEL_PREFIXES.any { model.startsWith(it) }
}
