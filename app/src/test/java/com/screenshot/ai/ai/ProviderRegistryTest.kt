package com.screenshot.ai.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Provider 注册表契约测试。
 *
 * UI 完全依赖注册表来渲染设置页，一旦某个 Provider 的 id、展示名或 baseUrl 被改错，
 * 用户会直接看到空白项或请求打到错误地址，因此这里把这些「对外契约」钉死。
 *
 * 注意：构造 Provider 只涉及 okhttp/okio 等纯 JVM 类，不触发 Android 框架调用，
 * 因此可以在普通 JVM 单测中运行（android.util.Log 在 returnDefaultValues 下返回 0）。
 */
class ProviderRegistryTest {
    /** 期望的内置 Provider id 及其顺序（顺序即设置页展示顺序） */
    private val expectedIds = listOf("deepseek", "openai", "gemini", "anthropic", "qwen", "doubao")

    @Test
    fun `内置6个Provider且id与顺序固定`() {
        val ids = AiProviderRegistry.all().map { it.id }
        assertEquals("Provider 数量必须是 6", expectedIds.size, ids.size)
        assertEquals(expectedIds, ids)
    }

    @Test
    fun `每个Provider都有展示名与默认接口地址`() {
        AiProviderRegistry.all().forEach { provider ->
            assertTrue("展示名不能为空: ${provider.id}", provider.displayName.isNotBlank())
            assertTrue(
                "接口地址必须是 https: ${provider.id} -> ${provider.defaultBaseUrl}",
                provider.defaultBaseUrl.startsWith("https://"),
            )
        }
    }

    @Test
    fun `每个Provider至少有一个模型且默认模型可用`() {
        AiProviderRegistry.all().forEach { provider ->
            assertTrue("模型列表不能为空: ${provider.id}", provider.models.isNotEmpty())
            assertNotNull("默认模型不能为空: ${provider.id}", provider.defaultModel)
        }
    }

    @Test
    fun `每个Provider的模型id唯一`() {
        AiProviderRegistry.all().forEach { provider ->
            val ids = provider.models.map { it.id }
            assertEquals("模型 id 不能重复: ${provider.id}", ids.size, ids.toSet().size)
        }
    }

    @Test
    fun `默认Provider拥有可用的视觉模型`() {
        // 截图分析是本应用的核心能力，默认 Provider 必须能直接收图
        assertTrue(
            "默认 Provider 必须至少有一个支持视觉的模型: ${AiProviderRegistry.default.id}",
            AiProviderRegistry.default.supportsVision,
        )
    }

    @Test
    fun `find按id精确查找`() {
        expectedIds.forEach { id ->
            assertEquals("find($id) 应命中自身", id, AiProviderRegistry.find(id)?.id)
        }
        assertEquals(null, AiProviderRegistry.find("不存在的厂商"))
    }

    @Test
    fun `resolve未知provider回退到默认`() {
        val (provider, model) = AiProviderRegistry.resolve("unknown", null)
        assertEquals("未知 provider 应回退到默认", AiProviderRegistry.default.id, provider.id)
        assertNotNull("回退后必须有可用模型", model)
        assertEquals(expectedIds.first(), provider.id)
    }

    @Test
    fun `resolve未指定模型时使用默认模型`() {
        val (provider, model) = AiProviderRegistry.resolve("deepseek", null)
        assertEquals("deepseek", provider.id)
        // AiProvider.defaultModel 的约定：优先返回第一个支持视觉的模型
        assertEquals(
            "未指定模型时必须与默认模型一致",
            provider.defaultModel?.id,
            model.id,
        )
        // AppSettings 依赖该回退值，改坏会让设置页与请求参数不一致
        assertEquals("deepseek-flash", model.id)
    }

    @Test
    fun `resolve指定模型时返回该模型`() {
        val (provider, model) = AiProviderRegistry.resolve("deepseek", "deepseek-flash")
        assertEquals("deepseek", provider.id)
        assertEquals("deepseek-flash", model.id)
        assertTrue("deepseek-flash 必须支持视觉输入", model.supportsVision)
    }

    @Test
    fun `resolve未知模型名时回退到默认模型`() {
        // 用户手填了预设列表里没有的模型名：此时按 provider.defaultModel 处理，
        // 但仍然必须返回一个可用的模型对象，不能返回 null 或空 id
        val (provider, model) = AiProviderRegistry.resolve("openai", "my-custom-model")
        assertEquals("openai", provider.id)
        assertTrue("回退后的模型 id 不能为空", model.id.isNotBlank())
        assertTrue("回退后的模型展示名不能为空", model.displayName.isNotBlank())
    }

    @Test
    fun `resolve空providerId时回退到默认`() {
        val (provider, _) = AiProviderRegistry.resolve(null, null)
        assertEquals(AiProviderRegistry.default.id, provider.id)
    }

    @Test
    fun `每个Provider都能查到自己的默认模型`() {
        AiProviderRegistry.all().forEach { provider ->
            val defaultModel = provider.defaultModel
            assertNotNull("默认模型不能为空: ${provider.id}", defaultModel)
            assertNotNull(
                "默认模型必须能通过 findModel 查回: ${provider.id}",
                provider.findModel(defaultModel!!.id),
            )
        }
    }
}
