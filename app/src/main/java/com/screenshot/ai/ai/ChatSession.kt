package com.screenshot.ai.ai

import com.screenshot.ai.util.PromptTemplates
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext

/** 默认采样温度：截图分析既要稳定又不宜过于死板 */
private const val DEFAULT_TEMPERATURE = 0.7

/** 单张图片按 1024 token 估算（各家视觉编码器的常见量级） */
private const val IMAGE_TOKENS = 1024

/** 非中文（英文/数字/符号）字符的估算比例：约 4 个字符 1 个 token */
private const val CHARS_PER_TOKEN = 4

/** 中日韩文字与全角符号的起始码点：之后的字符按「1 字 1 token」估算 */
private const val CJK_START_CODE = 0x2E80

/**
 * 一轮对话会话：持有完整上下文（含原图），供首轮提问与后续追问复用。
 *
 * 上下文策略：
 * - 首条 user 消息里始终保留原图 Base64，追问时不重新上传、也不丢弃它，
 *   因此模型在追问阶段依然能「看见」这张截图，视觉上下文不会丢失；
 * - 系统提示词固定在 messages 首位（Anthropic 会把它提取到顶层 system 字段）；
 * - [start] 重置上下文，[followUp] 追加提问，[regenerate] 丢弃上一条回复后重发。
 *
 * @param provider     实际执行请求的 Provider
 * @param model        选中的模型，决定能否携带图片
 * @param baseUrl      用户配置的接口地址
 * @param apiKey       用户配置的 API Key
 * @param systemPrompt 系统提示词，默认使用内置预设
 */
class ChatSession(
    val provider: AiProvider,
    val model: AiModel,
    val baseUrl: String,
    val apiKey: String,
    private val systemPrompt: String = PromptTemplates.SYSTEM_PROMPT,
) {
    /** 完整上下文：system + 交替出现的 user / assistant 消息 */
    private val messages = mutableListOf<ChatMessage>()

    /** 上下文快照；修改返回列表不会影响会话内部状态 */
    val history: List<ChatMessage> get() = messages.toList()

    /** 当前模型是否支持图片；为 false 时上层应先做本地 OCR 降级 */
    val isVisionAvailable: Boolean get() = model.supportsVision

    /** 采样温度，可按需调整（例如需要更稳定输出时调低） */
    var temperature: Double = DEFAULT_TEMPERATURE

    /** 最近一次 [send] 所在协程，供 [cancel] 中断 */
    private var activeJob: Job? = null

    /**
     * 开始一轮新会话：写入首条用户消息（可带原图）。
     *
     * 会清空已有上下文（包括上一次的截图），因此重新发起截图分析时必须调用它，
     * 否则旧截图会作为历史继续参与推理。
     */
    fun start(
        prompt: String,
        imageBase64: String?,
        mimeType: String = "image/jpeg",
    ) {
        messages.clear()
        if (systemPrompt.isNotBlank()) {
            messages += ChatMessage.system(systemPrompt)
        }
        messages +=
            if (imageBase64.isNullOrEmpty()) {
                ChatMessage.user(prompt)
            } else {
                ChatMessage.userWithImage(prompt, imageBase64, mimeType)
            }
    }

    /** 追问：追加一条用户消息后立即发起请求 */
    suspend fun followUp(
        prompt: String,
        callback: StreamCallback,
    ) {
        ensureSystemPrompt()
        messages += ChatMessage.user(prompt)
        send(callback)
    }

    /**
     * 把流式输出的最终回复写回上下文。
     *
     * 由调用方在 [StreamCallback.onComplete] 时传入完整文本：
     * 会话层不缓存流式分片，避免与 UI 各自的累积逻辑产生两份「真相」。
     */
    fun appendAssistant(reply: String) {
        if (reply.isBlank()) return
        messages += ChatMessage.assistant(reply)
    }

    /** 重新生成：丢弃末尾的 assistant 回复后重发，保留用户消息与原图 */
    suspend fun regenerate(callback: StreamCallback) {
        while (messages.isNotEmpty() && messages.last().role == ChatMessage.Role.ASSISTANT) {
            messages.removeAt(messages.size - 1)
        }
        send(callback)
    }

    /** 用当前上下文发起一次流式请求 */
    suspend fun send(callback: StreamCallback) {
        ensureSystemPrompt()
        // 记录当前协程，便于在回调（非挂起上下文）中调用 cancel()
        activeJob = currentCoroutineContext()[Job]
        try {
            provider.chatStream(
                apiKey = apiKey,
                baseUrl = baseUrl,
                model = model.id,
                messages = messages.toList(),
                temperature = temperature,
                callback = callback,
            )
        } finally {
            activeJob = null
        }
    }

    /** 中断正在进行的请求；在 [StreamCallback] 回调里随时可调用 */
    fun cancel() {
        activeJob?.cancel()
    }

    /**
     * 估算当前上下文的 token 数，用于超限保护。
     *
     * 估算规则：中文约 1 token/字，英文约 1 token/4 字符（向上取整，宁可高估），
     * 图片按 [IMAGE_TOKENS] 计。真实分词器依赖各厂商实现，这里只求量级正确。
     */
    fun estimateTokens(): Int {
        ensureSystemPrompt()
        var total = 0
        for (message in messages) {
            total += estimateTextTokens(message.text)
            if (message.hasImage) total += IMAGE_TOKENS
        }
        return total
    }

    /** 首次使用前补齐系统提示词；空提示词则不写入，避免发送无意义的系统消息 */
    private fun ensureSystemPrompt() {
        if (messages.isEmpty() && systemPrompt.isNotBlank()) {
            messages += ChatMessage.system(systemPrompt)
        }
    }

    private fun estimateTextTokens(text: String): Int {
        var cjk = 0
        var others = 0
        for (ch in text) {
            if (ch.code >= CJK_START_CODE) cjk++ else others++
        }
        return cjk + (others + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN
    }
}
