package com.screenshot.ai.ai.providers

import com.screenshot.ai.ai.AiException
import com.screenshot.ai.ai.AiModel
import com.screenshot.ai.ai.AiProvider
import com.screenshot.ai.ai.ChatMessage
import com.screenshot.ai.ai.StreamCallback
import com.screenshot.ai.ai.net.HttpClient
import com.screenshot.ai.ai.net.SseReader
import com.screenshot.ai.ai.net.UrlBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

/** 请求体类型：Anthropic 对非 UTF-8 声明同样敏感 */
private val JSON_MEDIA_TYPE: MediaType? = "application/json; charset=utf-8".toMediaTypeOrNull()

/** Anthropic 官方要求的版本头，缺失会直接 400 */
private const val ANTHROPIC_VERSION = "2023-06-01"

/** 流式请求的输出上限：Anthropic 的 max_tokens 是必填字段 */
private const val ANTHROPIC_MAX_TOKENS = 4096

/** 连通性测试的输出上限 */
private const val TEST_MAX_TOKENS = 1

/** 连通性测试的极短提示词 */
private const val TEST_PROMPT = "ping"

/** 错误响应体只保留前 500 个字符 */
private const val ERROR_BODY_LIMIT = 500

/** 解析失败时回显的数据片段长度 */
private const val DATA_PREVIEW_LIMIT = 200

private const val ROLE_USER = "user"
private const val ROLE_ASSISTANT = "assistant"
private const val ACCEPT_SSE = "text/event-stream"
private const val ACCEPT_JSON = "application/json"

/**
 * Anthropic Claude。
 *
 * /v1/messages 与 OpenAI 协议差异较大，因此不继承
 * [OpenAiCompatibleProvider]，而是单独实现：
 * - 鉴权用 x-api-key 头（不是 Bearer），并必须带 anthropic-version；
 * - system 提示词是顶层字段，不放进 messages；
 * - 图片用 {type:"image", source:{type:"base64", ...}} 结构，而不是 image_url；
 * - 流式事件是 content_block_delta，且以 message_stop 结束（没有 [DONE]）。
 *
 * 标记为 open：自建网关或代理可继承本类覆写地址与模型清单，无需改动本文件。
 */
open class AnthropicProvider : AiProvider {
    override val id: String = "anthropic"

    override val displayName: String = "Anthropic Claude"

    override val supportsVision: Boolean get() = models.any { it.supportsVision }

    override val defaultBaseUrl: String = "https://api.anthropic.com/"

    override val apiKeyUrl: String = "https://console.anthropic.com/settings/keys"

    /** Claude 3 系列均可读图（截图分析场景可直接使用多模态） */
    override val models: List<AiModel> =
        listOf(
            AiModel(
                id = "claude-3-5-sonnet-20241022",
                displayName = "Claude 3.5 Sonnet",
                supportsVision = true,
                contextWindow = 200_000,
            ),
            AiModel(
                id = "claude-3-5-haiku-20241022",
                displayName = "Claude 3.5 Haiku",
                supportsVision = true,
                contextWindow = 200_000,
            ),
            AiModel(
                id = "claude-3-opus-20240229",
                displayName = "Claude 3 Opus",
                supportsVision = true,
                contextWindow = 200_000,
            ),
        )

    /**
     * 流式对话。
     *
     * 与 OpenAI 兼容实现保持同一套约定：失败一律走 [StreamCallback.onError]，
     * 包括用户取消（取消也不会向上抛异常）。
     */
    override suspend fun chatStream(
        apiKey: String,
        baseUrl: String,
        model: String,
        messages: List<ChatMessage>,
        temperature: Double,
        callback: StreamCallback,
    ) {
        val partial = StringBuilder()
        try {
            if (apiKey.isBlank()) throw AiException.MissingApiKey(displayName)
            val payload = buildPayload(model, messages, stream = true, maxTokens = ANTHROPIC_MAX_TOKENS)
            val request = newRequest(UrlBuilder.anthropicMessages(baseUrl), payload, apiKey, ACCEPT_SSE)

            executeCall(request) { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string()?.take(ERROR_BODY_LIMIT)
                    throw AiException.HttpError(response.code, errorBody)
                }
                callback.onStart()
                val body =
                    response.body
                        ?: throw AiException.ParseError("响应体为空，无法读取流式数据")
                // Anthropic 不发送 [DONE]，收到 message_stop 后置位，忽略其后的残留事件
                var finished = false
                SseReader.readSse(body.source()) { data ->
                    if (finished || data.isBlank()) return@readSse
                    val json = parseJson(data)
                    when (json.optString(TYPE_FIELD)) {
                        TYPE_MESSAGE_STOP -> finished = true
                        TYPE_ERROR -> throw AiException.ParseError(describeError(json))
                        else -> emitDelta(json.optJSONObject("delta")?.opt("text"), partial, callback)
                    }
                }
            }
            callback.onComplete()
        } catch (e: CancellationException) {
            // 取消属于失败的一种：按接口约定只回调 onError，不向上抛异常
            callback.onError(AiException.Cancelled, partial.toString())
        } catch (e: Throwable) {
            callback.onError(deliverableError(e), partial.toString())
        }
    }

    /**
     * 非流式连通性测试。
     *
     * 返回 null 表示成功，否则返回携带结构化信息的 [AiException]，
     * 由界面层按当前语言渲染文案。
     */
    override suspend fun testConnection(
        apiKey: String,
        baseUrl: String,
        model: String,
    ): AiException? =
        try {
            if (apiKey.isBlank()) {
                AiException.MissingApiKey(displayName)
            } else {
                val messages = listOf(ChatMessage.user(TEST_PROMPT))
                val payload = buildPayload(model, messages, stream = false, maxTokens = TEST_MAX_TOKENS)
                val request = newRequest(UrlBuilder.anthropicMessages(baseUrl), payload, apiKey, ACCEPT_JSON)
                executeCall(request) { response ->
                    if (response.isSuccessful) {
                        null
                    } else {
                        val errorBody = response.body?.string()?.take(ERROR_BODY_LIMIT)
                        AiException.HttpError(response.code, errorBody)
                    }
                }
            }
        } catch (e: CancellationException) {
            // 没有回调可交付，只能把取消信号继续向上传播
            throw e
        } catch (e: IOException) {
            AiException.NetworkError(e)
        } catch (e: AiException) {
            e
        } catch (e: Throwable) {
            AiException.Unknown(e)
        }

    /**
     * 在 IO 线程执行请求，并把协程取消转成对底层连接的取消。
     *
     * 只靠 ensureActive 无法中断阻塞中的 socket 读，取消后若服务端长时间静默，
     * 读操作要等到读超时才返回；这里直接 cancel 连接，让读立即结束。
     */
    private suspend fun <T> executeCall(
        request: Request,
        block: suspend (Response) -> T,
    ): T =
        withContext(Dispatchers.IO) {
            val call = HttpClient.client.newCall(request)
            val handle =
                currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
                    if (cause != null) call.cancel()
                }
            try {
                call.execute().use { response -> block(response) }
            } finally {
                handle?.dispose()
            }
        }

    /**
     * 归类最终交付给 UI 的异常。
     *
     * 先判断协程是否已取消：主动取消时 OkHttp 抛出的是 IOException("Canceled")，
     * 若当作普通 IO 异常处理，用户点了「停止」却会看到「网络异常」。
     */
    private suspend fun deliverableError(error: Throwable): AiException =
        if (currentCoroutineContext().isActive) mapError(error) else AiException.Cancelled

    /**
     * 组装 /v1/messages 请求体。
     *
     * 刻意不发送 temperature：截图分析追求稳定复现，使用服务端默认温度即可，
     * 也避免与「必须为 1.0」的推理型模型冲突。
     */
    private fun buildPayload(
        model: String,
        messages: List<ChatMessage>,
        stream: Boolean,
        maxTokens: Int,
    ): JSONObject {
        val payload = JSONObject()
        payload.put("model", model)
        payload.put("max_tokens", maxTokens)
        payload.put("stream", stream)
        // system 是顶层字段：多条系统消息按空行拼接，避免混进 messages 触发角色校验错误
        systemText(messages)?.let { payload.put("system", it) }
        payload.put("messages", buildMessages(messages))
        return payload
    }

    /** 拼接所有系统消息；没有则返回 null（不写空 system 字段） */
    private fun systemText(messages: List<ChatMessage>): String? =
        messages
            .filter { it.role == ChatMessage.Role.SYSTEM && it.text.isNotBlank() }
            .joinToString("\n\n") { it.text }
            .takeIf { it.isNotEmpty() }

    /**
     * 组装 messages 数组，只保留 user / assistant 两种角色。
     *
     * user 消息的 content 统一用内容块数组，便于在文本之外附带图片；
     * assistant 消息不可能是多模态，直接用字符串，保持请求体简洁。
     */
    private fun buildMessages(messages: List<ChatMessage>): JSONArray {
        val array = JSONArray()
        for (message in messages) {
            val role =
                when (message.role) {
                    ChatMessage.Role.USER -> ROLE_USER
                    ChatMessage.Role.ASSISTANT -> ROLE_ASSISTANT
                    // system 已提升到顶层字段，这里跳过
                    ChatMessage.Role.SYSTEM -> null
                } ?: continue

            val item = JSONObject()
            item.put("role", role)
            item.put("content", buildContent(message))
            array.put(item)
        }
        return array
    }

    /** user 消息的 content：文本块 + 可选的 base64 图片块 */
    private fun buildContent(message: ChatMessage): Any {
        if (message.role != ChatMessage.Role.USER) return message.text

        val content = JSONArray()
        val textPart = JSONObject()
        textPart.put("type", "text")
        textPart.put("text", message.text)
        content.put(textPart)

        if (message.hasImage) {
            val source = JSONObject()
            source.put("type", "base64")
            source.put("media_type", message.imageMimeType)
            source.put("data", message.imageBase64)
            val imagePart = JSONObject()
            imagePart.put("type", "image")
            imagePart.put("source", source)
            content.put(imagePart)
        }
        return content
    }

    /** 组装 POST 请求：Anthropic 使用 x-api-key 而非 Bearer */
    private fun newRequest(
        url: String,
        payload: JSONObject,
        apiKey: String,
        accept: String,
    ): Request =
        Request
            .Builder()
            .url(url)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .header("Accept", accept)
            .header("x-api-key", apiKey)
            .header("anthropic-version", ANTHROPIC_VERSION)
            .build()

    /** 输出增量文本；空片段（如 content_block_start 的空文本）直接忽略 */
    private fun emitDelta(
        value: Any?,
        partial: StringBuilder,
        callback: StreamCallback,
    ) {
        val text = value as? String
        if (text.isNullOrEmpty()) return
        partial.append(text)
        callback.onChunk(text)
    }

    /** 提取 error 事件里的可读信息：形如 {"type":"error","error":{"type":..,"message":..}} */
    private fun describeError(json: JSONObject): String {
        val error = json.optJSONObject("error") ?: return "服务端返回了错误事件"
        val type = error.opt("type") as? String
        val message = error.opt("message") as? String
        return listOfNotNull(type, message).joinToString("：").ifEmpty { "服务端返回了错误事件" }
    }

    /** SSE 数据必须是合法 JSON；不合法时报解析错误，而不是悄悄跳过 */
    private fun parseJson(data: String): JSONObject =
        try {
            JSONObject(data)
        } catch (e: JSONException) {
            throw AiException.ParseError("SSE 数据不是合法 JSON：${data.take(DATA_PREVIEW_LIMIT)}", e)
        }

    /** 把底层异常收敛成 [AiException]，让 UI 只需处理一种异常类型 */
    private fun mapError(error: Throwable): AiException =
        when (error) {
            is AiException -> error
            is IOException -> AiException.NetworkError(error)
            else -> AiException.Unknown(error)
        }

    private companion object {
        const val TYPE_FIELD = "type"
        const val TYPE_MESSAGE_STOP = "message_stop"
        const val TYPE_ERROR = "error"
    }
}
