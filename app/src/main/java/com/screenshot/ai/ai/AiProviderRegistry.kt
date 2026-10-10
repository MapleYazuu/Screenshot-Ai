package com.screenshot.ai.ai

/**
 * Provider 注册表。
 *
 * 所有内置 Provider 在此集中登记；UI 只依赖本注册表，
 * 因此新增厂商时不需要修改任何界面代码。
 *
 * 此外支持**自定义模型**：外部（ServiceLocator）可注入一个
 * [com.screenshot.ai.ai.providers.CustomProvider] 实例，
 * 它由用户配置驱动，追加在内置 Provider 之后。
 */
object AiProviderRegistry {
    /** 内置 Provider 列表（顺序即设置页展示顺序） */
    private val providers: List<AiProvider> by lazy {
        listOf(
            com.screenshot.ai.ai.providers
                .DeepSeekProvider(),
            com.screenshot.ai.ai.providers
                .OpenAiProvider(),
            com.screenshot.ai.ai.providers
                .GeminiProvider(),
            com.screenshot.ai.ai.providers
                .AnthropicProvider(),
            com.screenshot.ai.ai.providers
                .QwenProvider(),
            com.screenshot.ai.ai.providers
                .DoubaoProvider(),
        )
    }

    /**
     * 自定义模型 Provider（可空）。
     *
     * 由 ServiceLocator 在初始化时注入；为空时（例如单元测试环境）
     * [all] 仍只返回 6 个内置 Provider，保持既有行为不变。
     */
    @Volatile
    var customProvider: AiProvider? = null

    /** 全部 Provider（内置 + 自定义，自定义排在最后） */
    fun all(): List<AiProvider> = providers + listOfNotNull(customProvider)

    /** 按 id 查找，找不到返回 null */
    fun find(id: String): AiProvider? = all().firstOrNull { it.id == id }

    /**
     * 按 id 查找，找不到时回退到第一个内置 Provider。
     * 用户切换过 Provider 或数据被清理后仍能正常发请求。
     */
    fun findOrDefault(id: String?): AiProvider = all().firstOrNull { it.id == id } ?: providers.first()

    /** 默认 Provider（内置列表第一个） */
    val default: AiProvider get() = providers.first()

    /**
     * 根据 providerId / modelId 解析出实际可用的 (Provider, Model) 组合。
     * 若模型不在预设列表中（例如自定义模型改名后），则以纯文本模型兜底处理。
     */
    fun resolve(
        providerId: String?,
        modelId: String?,
    ): Pair<AiProvider, AiModel> {
        val provider = findOrDefault(providerId)
        val model =
            modelId?.let { provider.findModel(it) }
                ?: provider.defaultModel
                ?: AiModel(
                    id = modelId ?: "unknown",
                    displayName = modelId ?: "unknown",
                    supportsVision = false,
                )
        return provider to model
    }
}
