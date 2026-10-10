package com.screenshot.ai.data

import com.screenshot.ai.data.model.CustomModelConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自定义模型配置的边界条件测试：
 * 重点验证 Base URL 的协议自动补全与留空语义（对应设置页的输入校验）。
 */
class CustomModelConfigTest {

    @Test
    fun `缺少协议的URL自动补全https`() {
        val config = CustomModelConfig(baseUrl = "192.168.1.100:11434/v1")
        assertEquals("https://192.168.1.100:11434/v1", config.normalizedBaseUrl())
    }

    @Test
    fun `已有https或http协议时保持不变`() {
        assertEquals(
            "https://api.openai.com/v1",
            CustomModelConfig(baseUrl = "https://api.openai.com/v1").normalizedBaseUrl(),
        )
        assertEquals(
            "http://10.0.0.5:8080/v1",
            CustomModelConfig(baseUrl = "http://10.0.0.5:8080/v1").normalizedBaseUrl(),
        )
    }

    @Test
    fun `空白URL返回空表示使用标准OpenAI地址`() {
        assertEquals("", CustomModelConfig(baseUrl = "   ").normalizedBaseUrl())
    }

    @Test
    fun `URL首尾空白被清理`() {
        val config = CustomModelConfig(baseUrl = "  https://api.openai.com/v1  ")
        assertEquals("https://api.openai.com/v1", config.normalizedBaseUrl())
    }

    @Test
    fun `模型ID与显示名规范化`() {
        val config = CustomModelConfig(displayName = "  我的 Ollama  ", modelId = "  llama3 ")
        assertEquals("我的 Ollama", config.normalizedDisplayName())
        assertEquals("llama3", config.normalizedModelId())
        assertTrue(config.isConfigured)
    }

    @Test
    fun `模型ID为空时视为未配置`() {
        assertFalse(CustomModelConfig(modelId = "  ").isConfigured)
    }
}
