package com.screenshot.ai.ai.providers

import com.screenshot.ai.ai.AiModel
import com.screenshot.ai.ai.net.UrlBuilder

/** chat completions 路径后缀，用于识别用户已粘贴完整地址的情况 */
private const val CHAT_COMPLETIONS_SUFFIX = "/chat/completions"

/**
 * 豆包（火山方舟 Ark）。
 *
 * 有两处与标准 OpenAI 兼容接口不同，容易踩坑，务必注意：
 * 1. 地址形态：控制台给出的 baseUrl 是 https://ark.cn-beijing.volces.com/api/v3，
 *    本身已带版本段，所以这里直接追加 /chat/completions，不能再补 /v1；
 * 2. 模型名即接入点：方舟要求把「推理接入点 ID（ep-xxxxxxxx）」或模型 ID 传给 model 字段。
 *    用户若在方舟控制台创建了自定义接入点，需要把接入点 ID 当作模型名填入设置页，
 *    否则会收到 ModelNotFound / 404，而不是像其它厂商那样直接用模型名即可。
 *
 * 标记为 open：自建网关或代理可继承本类覆写地址与模型清单，无需改动本文件。
 */
open class DoubaoProvider : OpenAiCompatibleProvider() {
    override val id: String = "doubao"

    override val displayName: String = "豆包"

    override val supportsVision: Boolean get() = models.any { it.supportsVision }

    override val defaultBaseUrl: String = "https://ark.cn-beijing.volces.com/api/v3/"

    override val apiKeyUrl: String = "https://console.volcengine.com/ark"

    /** doubao-*-vision-* 支持图片输入；doubao-pro-32k 为纯文本模型 */
    override val models: List<AiModel> =
        listOf(
            AiModel(
                id = "doubao-vision-pro-32k",
                displayName = "Doubao Vision Pro 32k",
                supportsVision = true,
                contextWindow = 32_000,
            ),
            AiModel(
                id = "doubao-vision-lite-32k",
                displayName = "Doubao Vision Lite 32k",
                supportsVision = true,
                contextWindow = 32_000,
            ),
            AiModel(
                id = "doubao-pro-32k",
                displayName = "Doubao Pro 32k",
                supportsVision = false,
                contextWindow = 32_000,
            ),
        )

    /**
     * 默认 base（含 /api/v3）直接拼 /chat/completions；
     * 用户若粘贴的是 OpenAI 风格地址（含 /v1，甚至已是完整 /v1/chat/completions），
     * 仍交回通用规则，避免拼出重复路径或漏掉版本段。
     */
    override fun buildChatUrl(baseUrl: String): String {
        val base = UrlBuilder.normalize(baseUrl)
        // 空地址交给通用规则抛出可读异常
        if (base.isEmpty() || base.contains("/v1", ignoreCase = true)) {
            return UrlBuilder.chatCompletions(baseUrl)
        }
        return if (base.endsWith(CHAT_COMPLETIONS_SUFFIX)) base else "$base$CHAT_COMPLETIONS_SUFFIX"
    }
}
