package com.screenshot.ai.ai

/**
 * AI Provider 抽象。
 *
 * 设计要点：
 * - 内置 6 家厂商（DeepSeek / OpenAI / Google / Anthropic / 通义千问 / 豆包）；
 * - 其中 5 家走 OpenAI 兼容的 /chat/completions，差异仅体现在 baseUrl 与请求体微调，
 *   由 [com.screenshot.ai.ai.providers.OpenAiCompatibleProvider] 统一实现；
 * - Anthropic 的 /v1/messages 格式差异较大，单独实现；
 * - 新增厂商只需实现本接口并注册到 [AiProviderRegistry]，无需改动上层代码。
 */
interface AiProvider {
    /** 唯一标识，用于持久化与筛选，例如 "deepseek" */
    val id: String

    /** 展示名称，例如 "DeepSeek" */
    val displayName: String

    /**
     * 该 Provider 是否至少有一个支持视觉的模型。
     * 注意：具体某次请求能否带图，取决于所选 [AiModel.supportsVision]。
     */
    val supportsVision: Boolean

    /** 默认接口地址（OpenAI 兼容根地址，末尾带 /） */
    val defaultBaseUrl: String

    /** 预设模型列表 */
    val models: List<AiModel>

    /** 申请 API Key 的地址，展示在设置页方便用户跳转 */
    val apiKeyUrl: String get() = ""

    /** 默认模型（列表中第一个支持视觉的模型，没有则取第一个） */
    val defaultModel: AiModel?
        get() = models.firstOrNull { it.supportsVision } ?: models.firstOrNull()

    /** 按 id 查模型，找不到返回 null */
    fun findModel(modelId: String): AiModel? = models.firstOrNull { it.id == modelId }

    /**
     * 流式对话。
     *
     * 实现约定：
     * - 必须支持协程取消：上层通过取消 [chatStream] 所在的协程来中断请求；
     * - 出错时调用 [StreamCallback.onError]，不要向上抛异常；
     * - 正常结束时依次调用 [StreamCallback.onComplete]。
     *
     * @param apiKey  用户填写的 API Key（BYOK）
     * @param baseUrl 实际使用的接口地址，已包含用户自定义覆盖
     * @param model   模型 id
     * @param messages 完整对话上下文（含历史轮次与图片）
     * @param temperature 采样温度
     * @param callback 流式回调
     */
    suspend fun chatStream(
        apiKey: String,
        baseUrl: String,
        model: String,
        messages: List<ChatMessage>,
        temperature: Double = 0.7,
        callback: StreamCallback,
    )

    /**
     * 非流式连通性测试，用于设置页「测试连接」。
     *
     * @return null 表示成功；否则返回携带结构化信息的 [AiException]。
     *         刻意不返回现成的文案字符串 —— 错误提示必须由界面层按当前语言渲染，
     *         Provider 层保持与 Android 资源解耦（也便于纯 JVM 单测）。
     */
    suspend fun testConnection(
        apiKey: String,
        baseUrl: String,
        model: String,
    ): AiException?
}
