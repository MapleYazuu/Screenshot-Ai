package com.screenshot.ai.ai.providers

import com.screenshot.ai.ai.AiException
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

/** 请求体类型：必须带 charset，否则部分网关按 ISO-8859-1 解析导致中文乱码 */
private val JSON_MEDIA_TYPE: MediaType? = "application/json; charset=utf-8".toMediaTypeOrNull()

/** 连通性测试的极短提示词 */
private const val TEST_PROMPT = "ping"

/** 连通性测试的输出上限：只要能返回就说明链路可用，没必要真的生成内容 */
private const val TEST_MAX_TOKENS = 1

/** 错误响应体只保留前 500 个字符，避免把整页 HTML 错误页塞进异常信息 */
private const val ERROR_BODY_LIMIT = 500

/** 解析失败时回显的数据片段长度 */
private const val DATA_PREVIEW_LIMIT = 200

/**
 * OpenAI 兼容协议的 Provider 基类。
 *
 * DeepSeek / OpenAI / Gemini（OpenAI 兼容层）/ 通义千问 / 豆包的差异只有
 * baseUrl、模型清单、少量请求体字段与鉴权头，流程完全一致：
 * 拼 URL -> 组装 JSON -> SSE 流式读取 -> 解析增量文本 -> 回调。
 * 因此公共流程收敛在这里，子类只覆写真正不同的部分。
 *
 * 实现取舍：使用同步 `execute()` 配合 `Dispatchers.IO` 读取流，
 * 而不是 OkHttp 的 `enqueue` 回调 —— 协程取消会直接关闭连接，
 * 中断更及时，同时便于用 MockWebServer 编写单元测试。
 */
abstract class OpenAiCompatibleProvider : AiProvider {
    /**
     * 输出上限字段的值；为 null 时不写入请求体。
     *
     * 默认 null，让各家模型使用服务端默认上限；需要限制长度的厂商
     * （或使用其它字段名的推理模型）在子类里覆写。
     */
    protected open val maxTokens: Int? = null

    /**
     * 挂载额外请求头，默认写入 Bearer 鉴权。
     *
     * 需要完全自定义鉴权方式的厂商覆写本方法；
     * 只是换头名或关闭鉴权的厂商，覆写 [authHeaderName] / [requiresApiKeyHeader] 即可。
     */
    protected open fun extraHeaders(
        builder: Request.Builder,
        apiKey: String,
    ) {
        if (requiresApiKeyHeader()) {
            builder.header(authHeaderName(), "Bearer $apiKey")
        }
    }

    /** 鉴权头名称 */
    protected open fun authHeaderName(): String = "Authorization"

    /** 是否必须携带 API Key；本地部署（如 Ollama 代理）可覆写为 false */
    protected open fun requiresApiKeyHeader(): Boolean = true

    /** chat 接口地址，默认走 OpenAI 兼容规则 */
    protected open fun buildChatUrl(baseUrl: String): String = UrlBuilder.chatCompletions(baseUrl)

    /**
     * 组装请求体：model / messages / stream / temperature，可选 max_tokens。
     *
     * 键的写入顺序即序列化顺序（JSONObject 内部用 LinkedHashMap），
     * 固定顺序便于日志比对与单元测试断言。
     */
    protected open fun buildRequestBody(
        model: String,
        messages: List<ChatMessage>,
        temperature: Double,
        stream: Boolean,
    ): JSONObject {
        val body = JSONObject()
        body.put("model", model)
        body.put("messages", buildMessages(messages))
        body.put("stream", stream)
        body.put("temperature", temperature)
        maxTokens?.let { body.put("max_tokens", it) }
        return body
    }

    /**
     * 组装连通性测试请求体：非流式，且只申请 1 个 token。
     *
     * 单独开一个钩子，是为了让「不接受 max_tokens 字段」的推理模型
     * 能在子类里替换成自己的字段名。
     */
    protected open fun buildTestRequestBody(model: String): JSONObject {
        val body = buildRequestBody(model, listOf(ChatMessage.user(TEST_PROMPT)), 0.0, false)
        body.put("max_tokens", TEST_MAX_TOKENS)
        return body
    }

    /**
     * 组装 messages 数组。
     *
     * 带图消息使用多模态内容数组（text + image_url），
     * 图片以 data URL 形式内联，无需先上传到图床。
     */
    protected open fun buildMessages(messages: List<ChatMessage>): JSONArray {
        val array = JSONArray()
        for (message in messages) {
            val item = JSONObject()
            item.put("role", message.role.wireName)
            if (message.hasImage) {
                item.put("content", buildMultimodalContent(message))
            } else {
                item.put("content", message.text)
            }
            array.put(item)
        }
        return array
    }

    /**
     * 从单个 SSE data JSON 中解析增量文本；没有可展示内容时返回 null。
     *
     * 兼容以下形态（不同网关实现差异较大）：
     * - choices[0].delta.content：OpenAI / DeepSeek / 通义千问 / 豆包；
     * - choices[0].delta.reasoning_content：DeepSeek reasoner 的思维链，不展示但需要正常消费；
     * - choices[0].message.content：部分网关忽略 stream 参数，直接返回完整消息；
     * - choices[0].text：旧版 completions 形态；
     * - 顶层 delta.text：Anthropic 风格，做防御式兼容；
     * - content 为 JSONArray（每项形如 {"type":"text","text":"..."}）：Gemini 的 OpenAI 兼容层；
     * - 非 [DONE] 的结束标记（finish_reason、message_stop 等）：无文本，返回 null。
     *
     * 若 JSON 里带非空 error 对象，说明网关把错误塞进了 200 响应，这里直接抛出解析异常。
     */
    protected open fun parseChunk(json: JSONObject): String? {
        if (json.has("error") && !json.isNull("error")) {
            throw AiException.ParseError(describeError(json.opt("error")))
        }

        val choice = json.optJSONArray("choices")?.optJSONObject(0)
        if (choice != null) {
            val delta = choice.optJSONObject("delta")
            if (delta != null) {
                val content = textOf(delta.opt("content"))
                if (!content.isNullOrEmpty()) return content
                // delta 里只有 reasoning_content 时属于思维链，静默消费即可
            }
            val message = choice.optJSONObject("message")
            if (message != null) {
                val content = textOf(message.opt("content"))
                if (!content.isNullOrEmpty()) return content
            }
            val legacy = textOf(choice.opt("text"))
            if (!legacy.isNullOrEmpty()) return legacy
        }

        // 防御式兜底：部分网关会把 Anthropic 风格的 delta 混在 OpenAI 结构里
        val deltaNode = json.optJSONObject("delta")
        if (deltaNode != null) {
            val text = textOf(deltaNode.opt("text"))
            if (!text.isNullOrEmpty()) return text
        }
        val topLevel = textOf(json.opt("content"))
        return topLevel?.takeIf { it.isNotEmpty() }
    }

    /**
     * 流式对话。
     *
     * 约定：任何失败都通过 [StreamCallback.onError] 交付（携带已收到的部分文本），
     * 不向调用方抛异常，取消也不例外（见下方 catch 分支的说明）。
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
            if (requiresApiKeyHeader() && apiKey.isBlank()) {
                throw AiException.MissingApiKey(displayName)
            }
            val payload = buildRequestBody(model, messages, temperature, stream = true)
            val request = newRequest(buildChatUrl(baseUrl), payload, apiKey, ACCEPT_SSE)

            executeCall(request) { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string()?.take(ERROR_BODY_LIMIT)
                    throw AiException.HttpError(response.code, errorBody)
                }
                // 已确认拿到 200 响应，之后才可能出现首个 token
                callback.onStart()
                val body =
                    response.body
                        ?: throw AiException.ParseError("响应体为空，无法读取流式数据")
                SseReader.readSse(body.source()) { data ->
                    // 心跳帧（如 "data: "）没有可解析内容，跳过而不是报错
                    if (data.isBlank()) return@readSse
                    val delta = parseChunk(parseJson(data))
                    if (!delta.isNullOrEmpty()) {
                        partial.append(delta)
                        callback.onChunk(delta)
                    }
                }
            }
            callback.onComplete()
        } catch (e: CancellationException) {
            // 取消属于失败的一种：按接口约定只回调 onError，不向上抛异常。
            // 协程自身的取消状态并未被清除，调用方后续的挂起点仍会照常收到取消信号。
            callback.onError(AiException.Cancelled, partial.toString())
        } catch (e: Throwable) {
            callback.onError(deliverableError(e), partial.toString())
        }
    }

    /**
     * 非流式连通性测试。
     *
     * 返回 **null 表示成功**，否则返回携带结构化信息的 [AiException]。
     * 刻意不返回「一句话」：错误文案必须由界面层按当前语言渲染，
     * Provider 层不应该依赖 Android 资源。
     */
    override suspend fun testConnection(
        apiKey: String,
        baseUrl: String,
        model: String,
    ): AiException? =
        try {
            if (requiresApiKeyHeader() && apiKey.isBlank()) {
                AiException.MissingApiKey(displayName)
            } else {
                val payload = buildTestRequestBody(model)
                val request = newRequest(buildChatUrl(baseUrl), payload, apiKey, ACCEPT_JSON)
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
     * 仅靠 [SseReader] 的 ensureActive 无法中断阻塞中的 socket 读：取消后若服务端长时间
     * 不再发数据，读操作要等到读超时（120 秒）才返回。这里在协程被取消时主动 cancel 连接，
     * 让阻塞的读立即以 IOException 结束，再由上层还原成「已取消」。
     */
    private suspend fun <T> executeCall(
        request: Request,
        block: suspend (Response) -> T,
    ): T =
        withContext(Dispatchers.IO) {
            val call = HttpClient.client.newCall(request)
            // invokeOnCompletion 在正常结束时 cause 为 null，只有取消才需要真的中断连接
            val handle =
                currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
                    if (cause != null) call.cancel()
                }
            try {
                // use 保证异常路径上也会关闭响应体（进而释放连接）
                call.execute().use { response -> block(response) }
            } finally {
                handle?.dispose()
            }
        }

    /**
     * 归类最终交付给 UI 的异常。
     *
     * 先判断协程是否已取消：主动取消时 OkHttp 抛出的是 IOException("Canceled")，
     * 若当作普通 IO 异常处理，用户点了「停止」却会看到「网络异常」，与实际原因不符。
     */
    private suspend fun deliverableError(error: Throwable): AiException =
        if (currentCoroutineContext().isActive) mapError(error) else AiException.Cancelled

    /** 组装 POST 请求：JSON 请求体 + Accept + 厂商自定义头 */
    private fun newRequest(
        url: String,
        payload: JSONObject,
        apiKey: String,
        accept: String,
    ): Request {
        val builder =
            Request
                .Builder()
                .url(url)
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .header("Accept", accept)
        extraHeaders(builder, apiKey)
        return builder.build()
    }

    /** 带图消息的多模态内容数组 */
    private fun buildMultimodalContent(message: ChatMessage): JSONArray {
        val content = JSONArray()

        val textPart = JSONObject()
        textPart.put("type", "text")
        textPart.put("text", message.text)
        content.put(textPart)

        val imageUrl = JSONObject()
        imageUrl.put("url", "data:${message.imageMimeType};base64,${message.imageBase64}")
        val imagePart = JSONObject()
        imagePart.put("type", "image_url")
        imagePart.put("image_url", imageUrl)
        content.put(imagePart)

        return content
    }

    /**
     * 把任意 JSON 值转成文本。
     *
     * - String 直接返回；
     * - JSONArray 视为内容分片数组（Gemini 兼容层），按序拼接其中的 text 片段；
     * - JSONObject 取其中的 "text" / "content" 字段；
     * 注意不能用 optString：字段缺失时它会返回空串，JSONObject.NULL 还会被转成 "null"。
     */
    private fun textOf(value: Any?): String? =
        when (value) {
            null, JSONObject.NULL -> null
            is String -> value
            is JSONArray -> {
                val builder = StringBuilder()
                for (index in 0 until value.length()) {
                    builder.append(textOf(value.opt(index)) ?: "")
                }
                builder.toString().takeIf { it.isNotEmpty() }
            }
            is JSONObject -> textOf(value.opt("text")) ?: textOf(value.opt("content"))
            else -> value.toString()
        }

    /** 提取 error 对象里的可读信息，兼容字符串与对象两种写法 */
    private fun describeError(error: Any?): String =
        when (error) {
            null, JSONObject.NULL -> "未知错误"
            is String -> error
            is JSONObject -> textOf(error.opt("message")) ?: error.toString()
            else -> error.toString()
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
        const val ACCEPT_SSE = "text/event-stream"
        const val ACCEPT_JSON = "application/json"
    }
}
