package com.screenshot.ai.example

import android.util.Log
import com.screenshot.ai.ServiceLocator
import com.screenshot.ai.ai.AiException
import com.screenshot.ai.ai.AiModel
import com.screenshot.ai.ai.AiProvider
import com.screenshot.ai.ai.AiProviderRegistry
import com.screenshot.ai.ai.ChatMessage
import com.screenshot.ai.ai.StreamCallback
import com.screenshot.ai.util.OcrUtils
import com.screenshot.ai.util.PromptTemplates
import kotlinx.coroutines.flow.first

/**
 * ============================================================================
 * 最小可运行示例 ③：AI 调用（多 Provider + SSE 流式）
 * ============================================================================
 *
 * 这个示例演示「不经过任何 UI，直接调一次模型并把增量文本打出来」的最小闭环，
 * 适合用来：
 *   - 验证自己的 API Key / Base URL 是否可用；
 *   - 新增 Provider 时先在这里跑通，再接入 [com.screenshot.ai.ai.AiProviderRegistry]；
 *   - 排查是「网络问题」还是「UI 问题」。
 *
 * 三个关键点：
 *   1. **BYOK**：Key 由 [ServiceLocator.cryptoRepository] 加密存储，本示例只读取；
 *   2. **视觉能力**：只有 [AiModel.supportsVision] 为 true 的模型才能直接传图，
 *      否则先用 [com.screenshot.ai.util.OcrUtils] 本地 OCR 降级成文字；
 *   3. **流式**：通过 [StreamCallback] 逐段接收，UI 才能做到「逐字输出」。
 */
object AiCallExample {
    private const val TAG = "AiCallExample"

    /**
     * 一次性问答（无图片），返回完整回复。
     *
     * @param providerId 留空使用设置中的默认 Provider
     * @param modelId    留空使用该 Provider 的默认模型
     * @param onChunk    每收到一段增量文本回调一次（可用于逐字渲染）
     */
    suspend fun askText(
        prompt: String,
        providerId: String? = null,
        modelId: String? = null,
        onChunk: (String) -> Unit = {},
    ): String {
        val settings = ServiceLocator.settingsRepository.settings.first()
        val (provider, model) =
            AiProviderRegistry.resolve(
                providerId ?: settings.providerId,
                modelId ?: settings.modelId,
            )
        val apiKey = ServiceLocator.cryptoRepository.getApiKey(provider.id)
        if (apiKey.isBlank()) {
            return "未配置 ${provider.displayName} 的 API Key，请先在设置中填写"
        }
        val baseUrl =
            ServiceLocator.cryptoRepository
                .getBaseUrl(provider.id)
                .ifBlank { provider.defaultBaseUrl }

        val builder = StringBuilder()
        provider.chatStream(
            apiKey = apiKey,
            baseUrl = baseUrl,
            model = model.id,
            messages =
                listOf(
                    ChatMessage.system(PromptTemplates.SYSTEM_PROMPT),
                    ChatMessage.user(prompt),
                ),
            callback = collectingCallback(builder, onChunk),
        )
        return builder.toString()
    }

    /**
     * 带图片的问答（截图场景）。
     *
     * @param imageBase64 不含 `data:` 前缀的 Base64；用
     *        [ScreenCaptureExample.captureAsBase64] 获取
     */
    suspend fun askWithImage(
        prompt: String,
        imageBase64: String,
        lowQuality: Boolean = false,
        onChunk: (String) -> Unit = {},
    ): String {
        val settings = ServiceLocator.settingsRepository.settings.first()
        val (provider, model) = AiProviderRegistry.resolve(settings.providerId, settings.modelId)

        // 不支持视觉的模型：走本地 OCR 降级，把图片换成文字。
        // 注意：只有 supportsVision=false 的模型才需要这条路径，
        // 对支持视觉的模型强行 OCR 会白白损失图片里的排版与图形信息。
        if (!model.supportsVision) {
            val tempPath = saveTempImage(imageBase64)
            val ocrText = OcrUtils.recognizeText(tempPath)
            Log.i(TAG, "当前模型不支持视觉，已降级为 OCR，识别到 ${ocrText.length} 字")
            java.io.File(tempPath).delete()
            return askText(prompt = "$prompt\n\n【截图中的文字】\n$ocrText", onChunk = onChunk)
        }

        val apiKey = ServiceLocator.cryptoRepository.getApiKey(provider.id)
        if (apiKey.isBlank()) {
            return "未配置 ${provider.displayName} 的 API Key，请先在设置中填写"
        }
        val baseUrl =
            ServiceLocator.cryptoRepository
                .getBaseUrl(provider.id)
                .ifBlank { provider.defaultBaseUrl }

        val builder = StringBuilder()
        provider.chatStream(
            apiKey = apiKey,
            baseUrl = baseUrl,
            model = model.id,
            messages =
                listOf(
                    ChatMessage.system(PromptTemplates.SYSTEM_PROMPT),
                    ChatMessage.userWithImage(
                        text = prompt,
                        imageBase64 = imageBase64,
                        mimeType = "image/jpeg",
                    ),
                ),
            callback = collectingCallback(builder, onChunk),
        )
        return builder.toString()
    }

    /** 统一构造「累积全文 + 透传增量」的流式回调，避免每个方法重复写匿名对象 */
    private fun collectingCallback(
        builder: StringBuilder,
        onChunk: (String) -> Unit,
    ): StreamCallback =
        object : StreamCallback {
            override fun onChunk(text: String) {
                builder.append(text)
                onChunk(text)
            }

            override fun onComplete() = Unit

            override fun onError(
                error: Throwable,
                partialText: String,
            ) {
                Log.w(TAG, "请求失败：${describe(error)}", error)
            }
        }

    /**
     * 把 Base64 落成临时文件后交给 [OcrUtils] 识别。
     *
     * ML Kit 也可以直接接收 Bitmap，这里走文件是为了演示
     * 「历史记录里保存的截屏原图同样能走 OCR」这条真实路径。
     */
    private fun saveTempImage(base64: String): String {
        val file =
            java.io.File(
                ServiceLocator.applicationContext().cacheDir,
                "screenshot_ai_example_${System.currentTimeMillis()}.jpg",
            )
        file.writeBytes(android.util.Base64.decode(base64, android.util.Base64.DEFAULT))
        return file.absolutePath
    }

    /**
     * 测试连接：设置页「测试连接」按钮走的就是这条路径。
     *
     * @return null 表示成功，否则返回携带结构化信息的 [AiException]，
     *         由调用方按当前界面语言渲染（本示例只取英文兜底文本）
     */
    suspend fun testConnection(
        provider: AiProvider,
        model: AiModel,
    ): AiException? {
        val apiKey = ServiceLocator.cryptoRepository.getApiKey(provider.id)
        if (apiKey.isBlank()) return AiException.MissingApiKey(provider.displayName)
        val baseUrl =
            ServiceLocator.cryptoRepository
                .getBaseUrl(provider.id)
                .ifBlank { provider.defaultBaseUrl }
        return provider.testConnection(apiKey, baseUrl, model.id)
    }

    /**
     * 示例场景下的简易错误描述。
     *
     * 正式界面请用 [com.screenshot.ai.util.AiErrorMessages.localize] 按语言渲染；
     * 这里只是调试示例，因此直接取异常的英文兜底文本。
     */
    fun describe(error: Throwable): String =
        when (error) {
            is AiException -> error.fallbackMessage
            else -> error.message ?: "Unknown error"
        }

    /** 当前默认 Provider + 模型，供示例界面展示 */
    suspend fun currentTarget(): Pair<AiProvider, AiModel> {
        val settings = ServiceLocator.settingsRepository.settings.first()
        return AiProviderRegistry.resolve(settings.providerId, settings.modelId)
    }
}
