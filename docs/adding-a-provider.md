# 新增一个 AI Provider

本文档手把手说明如何为 Screenshot AI 增加一个新的模型厂商。以**假想的 `MoonshotProvider`** 为例，
给出两条路线：

- **路线 A：继承 `OpenAiCompatibleProvider`** —— 厂商接口兼容 OpenAI `/chat/completions` 时使用，
  只需声明元数据，**不写任何协议代码**。
- **路线 B：完全自定义协议** —— 厂商使用私有协议时使用，需要自己拼请求体、写鉴权头、解析 SSE。
  内置的 `AnthropicProvider` 就是这条路线唯一的真实例子。

> 📌 本文中的所有代码都与仓库当前实现保持一致，可直接参照
> `app/src/main/java/com/screenshot/ai/ai/AiProvider.kt`、
> `ai/providers/OpenAiCompatibleProvider.kt` 与 `ai/providers/AnthropicProvider.kt`。

- [先搞清楚两件事](#先搞清楚两件事)
- [接口全貌](#接口全貌)
- [路线 A：继承 OpenAiCompatibleProvider](#路线-a继承-openaicompatibleprovider)
- [路线 A+：需要微调时](#路线-a需要微调时)
- [路线 B：完全自定义协议](#路线-b完全自定义协议)
- [如何声明视觉能力](#如何声明视觉能力)
- [注册到 AiProviderRegistry](#注册到-aiproviderregistry)
- [如何补测试](#如何补测试)
- [注意事项](#注意事项)
- [提交前检查清单](#提交前检查清单)

---

## 先搞清楚两件事

### 1. 厂商属于哪一类

| 判断依据 | 结论 |
| --- | --- |
| 接口路径是 `/chat/completions`，鉴权用 `Authorization: Bearer <key>` | ✅ 走**路线 A** |
| 官方文档写着「OpenAI 兼容 / OpenAI-compatible」 | ✅ 走**路线 A** |
| 接口路径是别的东西（如 `/v1/messages`），或有自定义鉴权头 | ⚠️ 走**路线 B** |

本项目内置的 6 家厂商中，**只有 Anthropic 走路线 B**，其余 5 家（DeepSeek / OpenAI / Gemini /
通义千问 / 豆包）都继承自 `OpenAiCompatibleProvider`。

### 2. Provider 是 `open class`，不是 `object`

内置 Provider 全部声明为 **`open class`**（例如 `open class DeepSeekProvider : OpenAiCompatibleProvider()`），
注册表里用 `DeepSeekProvider()` 实例化。这样做是为了让**自建网关/代理可以继承内置 Provider**
覆写地址与模型清单，无需修改仓库代码。新增 Provider 请沿用这个约定。

---

## 接口全貌

```kotlin
// app/src/main/java/com/screenshot/ai/ai/AiProvider.kt（节选）
interface AiProvider {

    /** 唯一标识，用于持久化与筛选，例如 "deepseek" */
    val id: String

    /** 展示名称，例如 "DeepSeek" */
    val displayName: String

    /** 该 Provider 是否至少有一个支持视觉的模型 */
    val supportsVision: Boolean

    /** 默认接口地址（OpenAI 兼容根地址，末尾带 /） */
    val defaultBaseUrl: String

    /** 预设模型列表 */
    val models: List<AiModel>

    /** 申请 API Key 的地址，展示在设置页方便用户跳转 */
    val apiKeyUrl: String get() = ""

    /** 默认模型（第一个支持视觉的模型，没有则取第一个）—— 已有默认实现 */
    val defaultModel: AiModel?
        get() = models.firstOrNull { it.supportsVision } ?: models.firstOrNull()

    /** 按 id 查模型 —— 已有默认实现 */
    fun findModel(modelId: String): AiModel? = models.firstOrNull { it.id == modelId }

    /** 流式对话：失败走 callback.onError，不要向上抛异常 */
    suspend fun chatStream(
        apiKey: String,
        baseUrl: String,
        model: String,
        messages: List<ChatMessage>,
        temperature: Double = 0.7,
        callback: StreamCallback
    )

    /** 非流式连通性测试；返回 null 表示成功，否则返回可直接展示的中文描述 */
    suspend fun testConnection(apiKey: String, baseUrl: String, model: String): String?
}
```

配套类型：

```kotlin
data class AiModel(
    val id: String,                 // 调用 API 用的模型标识，如 "deepseek-chat"
    val displayName: String,        // 界面展示名
    val supportsVision: Boolean,    // false 时截图先走本地 ML Kit OCR 降级
    val contextWindow: Int? = null  // 上下文窗口，未知可省略
)

data class ChatMessage(
    val role: Role,                              // SYSTEM / USER / ASSISTANT
    val text: String,
    val imageBase64: String? = null,             // 不含 "data:" 前缀
    val imageMimeType: String = "image/jpeg"
) {
    val hasImage: Boolean get() = !imageBase64.isNullOrEmpty()
    companion object {
        fun system(text: String) = ChatMessage(Role.SYSTEM, text)
        fun user(text: String) = ChatMessage(Role.USER, text)
        fun userWithImage(text: String, imageBase64: String, mimeType: String = "image/jpeg")
        fun assistant(text: String) = ChatMessage(Role.ASSISTANT, text)
    }
}

interface StreamCallback {
    fun onChunk(text: String)                                  // 一段增量文本
    fun onComplete()                                           // 正常结束
    fun onError(error: Throwable, partialText: String) = Unit   // 出错（带已收到的部分文本）
    fun onStart() = Unit                                       // 已发出请求，等待首个 token
}
```

**两条硬性约定**（内置实现都遵守，新 Provider 也必须遵守）：

1. **任何失败都通过 `callback.onError(...)` 交付**，不要向上抛异常 —— 上层 UI 只处理回调；
2. **唯一例外是协程取消**：先 `callback.onError(AiException.Cancelled, partial)`，
   再把 `CancellationException` **继续抛出**，以维持结构化并发语义。

---

## 路线 A：继承 `OpenAiCompatibleProvider`

[`OpenAiCompatibleProvider`](../app/src/main/java/com/screenshot/ai/ai/providers/OpenAiCompatibleProvider.kt)
已经把 OpenAI 兼容协议的公共流程全部收敛好了：

```text
拼 URL → 组装 JSON 请求体 → extraHeaders 挂鉴权 → OkHttp execute
      → 校验响应码（非 2xx 抛 AiException.HttpError）
      → SseReader.readSse 逐条读取 → parseChunk 解析增量 → callback.onChunk
      → callback.onComplete
```

其中已经替你处理了这些**踩坑点**：

- 请求体固定带 `charset=utf-8`，否则部分网关按 ISO-8859-1 解析导致中文乱码；
- `parseChunk` 兼容 `delta.content` / `delta.reasoning_content` / `message.content` /
  旧版 `choices[0].text` / 顶层 `delta.text` / Gemini 兼容层的 `content` 数组；
- 网关把错误塞进 200 响应时（`{"error": {...}}`）会被识别并抛 `AiException.ParseError`；
- `data.isBlank()` 的心跳帧会被跳过而不是报错；
- 错误响应体只截取前 500 字符，避免整页 HTML 被塞进异常信息。

### 于是实现只需要声明元数据

新建 `app/src/main/java/com/screenshot/ai/ai/providers/MoonshotProvider.kt`：

```kotlin
package com.screenshot.ai.ai.providers

import com.screenshot.ai.ai.AiModel

/**
 * Moonshot（Kimi）。
 *
 * 接口与 OpenAI /chat/completions 完全兼容，鉴权同为 Bearer，
 * 因此直接继承 [OpenAiCompatibleProvider]，不需要写任何协议代码。
 *
 * 标记为 open：自建网关或代理可继承本类覆写地址与模型清单，无需改动本文件。
 */
open class MoonshotProvider : OpenAiCompatibleProvider() {

    override val id: String = "moonshot"

    override val displayName: String = "Moonshot (Kimi)"

    override val supportsVision: Boolean get() = models.any { it.supportsVision }

    override val defaultBaseUrl: String = "https://api.moonshot.cn/v1/"

    override val apiKeyUrl: String = "https://platform.moonshot.cn/console/api-keys"

    /** moonshot-v1-8k-vision-preview 可读图；其余为纯文本模型 */
    override val models: List<AiModel> = listOf(
        AiModel(
            id = "moonshot-v1-8k-vision-preview",
            displayName = "Moonshot v1 8k Vision",
            supportsVision = true,
            contextWindow = 8_000,
        ),
        AiModel(
            id = "moonshot-v1-8k",
            displayName = "Moonshot v1 8k",
            supportsVision = false,
            contextWindow = 8_000,
        ),
        AiModel(
            id = "moonshot-v1-32k",
            displayName = "Moonshot v1 32k",
            supportsVision = false,
            contextWindow = 32_000,
        ),
    )
}
```

**协议逻辑为零。** 真正必须写的只有 6 个属性：`id`、`displayName`、`supportsVision`、
`defaultBaseUrl`、`apiKeyUrl`、`models`。

---

## 路线 A+：需要微调时

少数「号称兼容」的厂商会有一两处偏差。`OpenAiCompatibleProvider` 提供了以下钩子，
**按需覆写即可，不要复制整份实现**：

| 钩子 | 默认行为 | 什么时候覆写 |
| --- | --- | --- |
| `protected open val maxTokens: Int?` | `null`（不写 `max_tokens` 字段） | 厂商要求显式限制输出长度时 |
| `protected open fun authHeaderName(): String` | `"Authorization"` | 换头名（如 Azure 的 `api-key`） |
| `protected open fun requiresApiKeyHeader(): Boolean` | `true` | 本地部署（Ollama 代理）不需要 Key 时改为 `false` |
| `protected open fun extraHeaders(builder: Request.Builder, apiKey: String)` | 写 `Authorization: Bearer $apiKey` | 鉴权方式完全不同（自定义签名、双头） |
| `protected open fun buildChatUrl(baseUrl: String): String` | `UrlBuilder.chatCompletions(baseUrl)` | 路径规则不同（参见 `DoubaoProvider`） |
| `protected open fun buildRequestBody(model, messages, temperature, stream): JSONObject` | `model`/`messages`/`stream`/`temperature` + 可选 `max_tokens` | 需要额外字段或字段名不同 |
| `protected open fun buildTestRequestBody(model: String): JSONObject` | 非流式 + 1 个 token | 推理模型不接受 `max_tokens`，要换字段名 |
| `protected open fun buildMessages(messages): JSONArray` | 带图走 `text` + `image_url` 内容数组 | 多模态字段结构不同 |
| `protected open fun parseChunk(json: JSONObject): String?` | 兼容 6 种增量形态 | 网关的增量字段不在兼容列表里 |

### 例子：鉴权头换名 + 限制输出长度

```kotlin
open class AzureStyleProvider : OpenAiCompatibleProvider() {

    /** 该厂商要求显式限制输出长度 */
    override val maxTokens: Int = 4096

    /** 鉴权头不是 Authorization */
    override fun authHeaderName(): String = "api-key"

    override val id: String = "azure-style"
    override val displayName: String = "Azure 风格网关"
    override val supportsVision: Boolean get() = models.any { it.supportsVision }
    override val defaultBaseUrl: String = "https://gw.example.com/openai/"
    override val models: List<AiModel> = listOf(
        AiModel("gpt-4o", "gpt-4o", supportsVision = true),
    )
}
```

### 例子：地址规则不同（参照 `DoubaoProvider`）

```kotlin
open class DoubaoLikeProvider : OpenAiCompatibleProvider() {

    /** 默认 base 自带 /api/v3，直接追加 /chat/completions，不能再补 /v1 */
    override fun buildChatUrl(baseUrl: String): String {
        val base = UrlBuilder.normalize(baseUrl)
        if (base.isEmpty() || base.contains("/v1", ignoreCase = true)) {
            return UrlBuilder.chatCompletions(baseUrl)   // 用户粘的是 OpenAI 风格地址
        }
        return if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
    }

    override val id: String = "doubao-like"
    override val displayName: String = "豆包风格网关"
    override val supportsVision: Boolean get() = models.any { it.supportsVision }
    override val defaultBaseUrl: String = "https://ark.cn-beijing.volces.com/api/v3/"
    override val models: List<AiModel> = listOf(
        AiModel("doubao-vision-pro-32k", "Doubao Vision Pro 32k", supportsVision = true),
    )
}
```

> 💡 `UrlBuilder` 已经把「末尾多余斜杠 / 缺版本段 / 已粘完整地址」三种情况都规整好了，
> 覆写 `buildChatUrl` 前先想清楚是不是真的需要。

---

## 路线 B：完全自定义协议

当厂商不使用 OpenAI 协议时，直接实现 `AiProvider`。下面这份实现**与仓库中真实的
`AnthropicProvider` 结构一致**（仅精简了注释），以此说明自定义协议要处理哪些事。

以 Anthropic 的 `/v1/messages` 为例，它与 OpenAI 协议有四处根本差异：

1. 路径是 `/v1/messages`，不是 `/chat/completions`；
2. 鉴权用 `x-api-key` + `anthropic-version`，**没有** `Authorization: Bearer`；
3. `system` 提示词是**顶层字段**，不放进 `messages` 数组；
4. 图片用 `{type:"image", source:{type:"base64", ...}}`，不是 `image_url`；
5. 流式以 `message_stop` 结束，**没有** `data: [DONE]` 哨兵。

```kotlin
package com.screenshot.ai.ai.providers

import com.screenshot.ai.ai.AiException
import com.screenshot.ai.ai.AiModel
import com.screenshot.ai.ai.AiProvider
import com.screenshot.ai.ai.ChatMessage
import com.screenshot.ai.ai.StreamCallback
import com.screenshot.ai.ai.net.HttpClient
import com.screenshot.ai.ai.net.SseReader
import com.screenshot.ai.ai.net.UrlBuilder
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** 必须带 charset，否则部分网关按 ISO-8859-1 解析导致中文乱码 */
private val JSON_MEDIA_TYPE: MediaType? = "application/json; charset=utf-8".toMediaTypeOrNull()

private const val ANTHROPIC_VERSION = "2023-06-01"
private const val MAX_TOKENS = 4096
private const val TEST_MAX_TOKENS = 1
private const val TEST_PROMPT = "ping"
private const val ERROR_BODY_LIMIT = 500
private const val DATA_PREVIEW_LIMIT = 200
private const val ACCEPT_SSE = "text/event-stream"
private const val ACCEPT_JSON = "application/json"

/**
 * 自定义协议 Provider 范例（/v1/messages 风格）。
 *
 * 与 [OpenAiCompatibleProvider] 的区别：鉴权头、system 顶层字段、图片结构、
 * SSE 结束事件全部不同，因此不继承它，直接实现 [AiProvider]。
 */
open class CustomProtocolProvider : AiProvider {

    override val id: String = "custom-protocol"

    override val displayName: String = "Custom Protocol"

    override val supportsVision: Boolean get() = models.any { it.supportsVision }

    override val defaultBaseUrl: String = "https://api.example.com/"

    override val apiKeyUrl: String = ""

    override val models: List<AiModel> = listOf(
        AiModel("custom-sonnet-latest", "Custom Sonnet", supportsVision = true, contextWindow = 200_000),
        AiModel("custom-haiku-latest", "Custom Haiku", supportsVision = true, contextWindow = 200_000),
    )

    /** 与 OpenAI 兼容实现保持同一套约定：失败走 onError，只有取消信号继续上抛 */
    override suspend fun chatStream(
        apiKey: String,
        baseUrl: String,
        model: String,
        messages: List<ChatMessage>,
        temperature: Double,
        callback: StreamCallback
    ) {
        val partial = StringBuilder()
        try {
            if (apiKey.isBlank()) throw AiException.MissingApiKey(displayName)
            val payload = buildPayload(model, messages, stream = true, maxTokens = MAX_TOKENS)
            val request = newRequest(UrlBuilder.anthropicMessages(baseUrl), payload, apiKey, ACCEPT_SSE)

            withContext(Dispatchers.IO) {
                // use 保证异常路径上也会关闭响应体（进而释放连接）
                HttpClient.client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val errorBody = response.body?.string()?.take(ERROR_BODY_LIMIT)
                        throw AiException.HttpError(response.code, errorBody)
                    }
                    callback.onStart()
                    val body = response.body
                        ?: throw AiException.ParseError("响应体为空，无法读取流式数据")

                    // 该协议不发送 [DONE]，收到 message_stop 后置位，忽略其后的残留事件
                    var finished = false
                    SseReader.readSse(body.source()) { data ->
                        if (finished || data.isBlank()) return@readSse
                        val json = parseJson(data)
                        when (json.optString("type")) {
                            "message_stop" -> finished = true
                            "error" -> throw AiException.ParseError(describeError(json))
                            else -> {
                                val text = json.optJSONObject("delta")?.opt("text") as? String
                                if (!text.isNullOrEmpty()) {
                                    partial.append(text)
                                    callback.onChunk(text)
                                }
                            }
                        }
                    }
                }
            }
            callback.onComplete()
        } catch (e: CancellationException) {
            // 用户主动中断：先告知上层，再继续抛出取消信号以维持结构化并发
            callback.onError(AiException.Cancelled, partial.toString())
            throw e
        } catch (e: Throwable) {
            callback.onError(mapError(e), partial.toString())
        }
    }

    /** 非流式连通性测试：返回 null 表示成功，否则返回可直接展示的中文描述 */
    override suspend fun testConnection(apiKey: String, baseUrl: String, model: String): String? {
        return try {
            if (apiKey.isBlank()) {
                AiException.MissingApiKey(displayName).userMessage
            } else {
                val payload = buildPayload(model, listOf(ChatMessage.user(TEST_PROMPT)), false, TEST_MAX_TOKENS)
                val request = newRequest(UrlBuilder.anthropicMessages(baseUrl), payload, apiKey, ACCEPT_JSON)
                withContext(Dispatchers.IO) {
                    HttpClient.client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            null
                        } else {
                            val errorBody = response.body?.string()?.take(ERROR_BODY_LIMIT)
                            AiException.HttpError(response.code, errorBody).userMessage
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            AiException.NetworkError(e).userMessage
        } catch (e: AiException) {
            e.userMessage
        } catch (e: Throwable) {
            "测试失败：${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** 组装 /v1/messages 请求体：system 提到顶层，不写空 system 字段 */
    private fun buildPayload(
        model: String,
        messages: List<ChatMessage>,
        stream: Boolean,
        maxTokens: Int
    ): JSONObject {
        val payload = JSONObject()
        payload.put("model", model)
        payload.put("max_tokens", maxTokens)
        payload.put("stream", stream)
        // 多条 system 按空行拼接，避免混进 messages 触发角色校验错误
        messages.filter { it.role == ChatMessage.Role.SYSTEM && it.text.isNotBlank() }
            .joinToString("\n\n") { it.text }
            .takeIf { it.isNotEmpty() }
            ?.let { payload.put("system", it) }
        payload.put("messages", buildMessages(messages))
        return payload
    }

    /** 只保留 user / assistant；system 已提升到顶层 */
    private fun buildMessages(messages: List<ChatMessage>): JSONArray {
        val array = JSONArray()
        for (message in messages) {
            val role = when (message.role) {
                ChatMessage.Role.USER -> "user"
                ChatMessage.Role.ASSISTANT -> "assistant"
                ChatMessage.Role.SYSTEM -> null   // 已提升到顶层，跳过
            } ?: continue

            val item = JSONObject()
            item.put("role", role)
            item.put("content", buildContent(message))
            array.put(item)
        }
        return array
    }

    /**
     * user 消息的 content：文本块 + 可选的 base64 图片块。
     * 注意 base64 数据**不带** "data:image/jpeg;base64," 前缀 —— 这与 image_url 写法不同，
     * 写错通常会收到 400 / 422。
     */
    private fun buildContent(message: ChatMessage): Any {
        if (message.role != ChatMessage.Role.USER) return message.text

        val content = JSONArray()
        content.put(JSONObject().apply {
            put("type", "text")
            put("text", message.text)
        })

        if (message.hasImage) {
            val source = JSONObject()
            source.put("type", "base64")
            source.put("media_type", message.imageMimeType)
            source.put("data", message.imageBase64)
            content.put(JSONObject().apply {
                put("type", "image")
                put("source", source)
            })
        }
        return content
    }

    /** 自定义协议使用 x-api-key，而不是 Bearer */
    private fun newRequest(url: String, payload: JSONObject, apiKey: String, accept: String): Request =
        Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .header("Accept", accept)
            .header("x-api-key", apiKey)
            .header("anthropic-version", ANTHROPIC_VERSION)
            .build()

    private fun describeError(json: JSONObject): String {
        val error = json.optJSONObject("error") ?: return "服务端返回了错误事件"
        return listOfNotNull(error.opt("type") as? String, error.opt("message") as? String)
            .joinToString("：")
            .ifEmpty { "服务端返回了错误事件" }
    }

    /** SSE 数据必须是合法 JSON；不合法时报解析错误，而不是悄悄跳过 */
    private fun parseJson(data: String): JSONObject = try {
        JSONObject(data)
    } catch (e: JSONException) {
        throw AiException.ParseError("SSE 数据不是合法 JSON：${data.take(DATA_PREVIEW_LIMIT)}", e)
    }

    /** 把底层异常收敛成 [AiException]，让 UI 只需处理一种异常类型 */
    private fun mapError(error: Throwable): AiException = when (error) {
        is AiException -> error
        is IOException -> AiException.NetworkError(error)
        else -> AiException.Unknown(error)
    }
}
```

### 关于 `SseReader`

`SseReader.readSse(source) { data -> ... }` **只回传 `data:` 后面的内容**，不暴露事件名。
它已经处理好了：跨 TCP 分片截断、`\r\n`、空行分隔、`:` 开头的心跳/注释行。

因此对于**具名事件**协议（`event: xxx` + `data: {...}`），要像上面那样
**读取 JSON 内部的 `type` 字段**来判断事件类型 —— 这是最容易写错的地方。

---

## 如何声明视觉能力

`AiModel.supportsVision` 是**整个多模态分流逻辑的开关**，务必如实填写：

```kotlin
AiModel(id = "qwen-vl-max", displayName = "Qwen VL Max", supportsVision = true)
AiModel(id = "qwen-plus",   displayName = "Qwen Plus",   supportsVision = false)
```

| 取值 | 应用行为 | 后果 |
| --- | --- | --- |
| `true` | 图片压缩到长边 1024px（低质量模式 512px）→ Base64 JPEG → 作为图片块发给模型 | 正确的多模态体验 |
| `false` | **不发送图片**，改用 ML Kit 中文 OCR 提取文字，按纯文本发送 | 成本极低，但丢失版式与图形信息 |

`AiProvider.supportsVision` 是 **Provider 级**的汇总，内置实现的统一写法是：

```kotlin
override val supportsVision: Boolean get() = models.any { it.supportsVision }
```

它驱动设置页的提示与「默认模型」的选择（`defaultModel` = 第一个支持视觉的模型），
所以**不要**硬编码成 `true`。

### 判断依据

- 厂商文档里模型能接受 `image_url` / `image` / `input_image` 类型输入 → `true`
- 只接受纯文本 → `false`
- **文档没写清楚** → 保守填 `false`（填 `true` 会让用户拿到 400/422 报错，填 `false` 只是效果打折）
- 同一厂商不同模型能力不同，**逐个确认**，不要整家厂商一刀切

> `AiProviderRegistry.resolve()` 在遇到用户手填的未知模型名时，会回退到该 Provider 的
> `defaultModel`，并**按纯文本模型处理**（`supportsVision = false`），这是有意的安全兜底。

---

## 注册到 `AiProviderRegistry`

在 `app/src/main/java/com/screenshot/ai/ai/AiProviderRegistry.kt` 的列表里加一行：

```kotlin
object AiProviderRegistry {

    /** 内置 Provider 列表（顺序即设置页展示顺序） */
    private val providers: List<AiProvider> by lazy {
        listOf(
            com.screenshot.ai.ai.providers.DeepSeekProvider(),
            com.screenshot.ai.ai.providers.OpenAiProvider(),
            com.screenshot.ai.ai.providers.GeminiProvider(),
            com.screenshot.ai.ai.providers.AnthropicProvider(),
            com.screenshot.ai.ai.providers.QwenProvider(),
            com.screenshot.ai.ai.providers.DoubaoProvider(),
            com.screenshot.ai.ai.providers.MoonshotProvider(),   // ← 新增这一行
        )
    }

    fun all(): List<AiProvider> = providers
    fun find(id: String): AiProvider? = providers.firstOrNull { it.id == id }
    fun findOrDefault(id: String?): AiProvider = providers.firstOrNull { it.id == id } ?: providers.first()
    val default: AiProvider get() = providers.first()
    fun resolve(providerId: String?, modelId: String?): Pair<AiProvider, AiModel> { /* ... */ }
}
```

注册后确认三件事：

1. 设置页的 Provider 下拉能列出新厂商，且**顺序**符合预期（列表顺序即展示顺序）；
2. 选中后模型下拉能列出你声明的所有模型，视觉模型有相应标记；
3. 切换 Provider 后 API Key 与 Base URL 是**独立保存**的（切回去不丢）。

> ⚠️ **不要调整已有 Provider 的顺序**。`ProviderRegistryTest` 把 id 与顺序钉死了：
> 顺序变化会影响 `AiProviderRegistry.default`，进而改变新用户的初始模型。
> 新增厂商请**追加到列表末尾**。

---

## 如何补测试

测试放在 `app/src/test/java/com/screenshot/ai/ai/` 下。**不得发起真实网络请求**。

### 1. 元数据契约测试（必需）

`ProviderRegistryTest` 已经覆盖了「所有 Provider」的通用的契约。新增 Provider 后，
它应当**自动被覆盖**（因为测试遍历 `AiProviderRegistry.all()`）。请确认以下断言对新 Provider 也成立：

```kotlin
@Test
fun `每个Provider都有展示名与默认接口地址`() {
    AiProviderRegistry.all().forEach { provider ->
        assertTrue("展示名不能为空: ${provider.id}", provider.displayName.isNotBlank())
        assertTrue(
            "接口地址必须是 https: ${provider.id} -> ${provider.defaultBaseUrl}",
            provider.defaultBaseUrl.startsWith("https://")
        )
    }
}

@Test
fun `每个Provider的模型id唯一`() {
    AiProviderRegistry.all().forEach { provider ->
        val ids = provider.models.map { it.id }
        assertEquals("模型 id 不能重复: ${provider.id}", ids.size, ids.toSet().size)
    }
}
```

### 2. 专属测试（推荐）

如果新 Provider 覆写了任何钩子，请为**被覆写的逻辑**单独补测试：

```kotlin
package com.screenshot.ai.ai.providers

import com.screenshot.ai.ai.AiModel
import com.screenshot.ai.ai.ChatMessage
import com.screenshot.ai.ai.net.UrlBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Moonshot Provider 契约测试。
 *
 * 构造 Provider 只涉及纯 JVM 类，不触发 Android 框架调用，可在普通 JVM 单测中运行。
 */
class MoonshotProviderTest {

    private val provider = MoonshotProvider()

    @Test
    fun `id与展示名符合约定`() {
        assertEquals("moonshot", provider.id)
        assertTrue(provider.displayName.isNotBlank())
    }

    @Test
    fun `默认地址以斜杠结尾且拼出的chat地址唯一`() {
        val base = provider.defaultBaseUrl
        assertTrue("Base URL 应以 / 结尾：$base", base.endsWith("/"))
        assertEquals(
            "https://api.moonshot.cn/v1/chat/completions",
            UrlBuilder.chatCompletions(base),
        )
        // 同一份配置必须永远拼出同一个 URL
        assertEquals(UrlBuilder.chatCompletions(base), UrlBuilder.chatCompletions("$base//"))
    }

    @Test
    fun `视觉模型被正确标注`() {
        val visionIds = provider.models.filter { it.supportsVision }.map { it.id }.toSet()
        assertEquals(setOf("moonshot-v1-8k-vision-preview"), visionIds)
        // Provider 级标记由模型列表汇总而来
        assertTrue("Provider 必须声明为支持视觉", provider.supportsVision)
    }

    @Test
    fun `默认模型是第一个支持视觉的模型`() {
        val expected = provider.models.first { it.supportsVision }
        assertEquals(expected.id, provider.defaultModel?.id)
    }

    @Test
    fun `findModel按id精确查找`() {
        assertEquals("moonshot-v1-8k", provider.findModel("moonshot-v1-8k")?.id)
        assertEquals(null, provider.findModel("不存在的模型"))
    }
}
```

### 3. 自定义协议额外要测的

路线 B 的风险集中在解析与拼装上，**每个分支都要有测试**。可参照 `SseParserTest`：

```kotlin
@Test
fun `跨分片截断的SSE事件能被正确拼接`() { /* ... */ }

@Test
fun `message_stop事件之后的残留数据被忽略`() { /* ... */ }

@Test
fun `网关把error塞进200响应时抛出解析异常`() { /* ... */ }

@Test
fun `system消息被提升到顶层而不出现在messages中`() { /* ... */ }

@Test
fun `图片base64不带dataURI前缀`() { /* ... */ }
```

运行：

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:testDebugUnitTest --tests "com.screenshot.ai.ai.providers.MoonshotProviderTest"
```

---

## 注意事项

### SSE 格式差异

各家虽然都叫「流式」，细节差别很大，**照抄 OpenAI 的解析逻辑一定会踩坑**：

| 差异点 | 表现 | 处理方式 |
| --- | --- | --- |
| 结束哨兵 | OpenAI 发 `data: [DONE]`；Anthropic 用具名事件 `message_stop` | `[DONE]` 不能被当成 JSON 解析；自定义协议用 `finished` 标志位忽略后续事件 |
| 具名事件 | 出现 `event: content_block_delta` 行 | `SseReader` **不暴露事件名**，需读取 JSON 内的 `type` 字段 |
| 心跳/注释行 | `: keep-alive`、空行、`data: `（空数据） | `SseReader` 已过滤注释行；空 data 用 `data.isBlank()` 跳过 |
| 换行符 | 少数网关用 `\r\n` | `SseReader` 已统一处理 |
| 跨分片截断 | 一条 JSON 被切成两个 TCP 分片 | `SseReader` 已按 buffer 累积再切分 |
| 思维链字段 | DeepSeek reasoner 返回 `delta.reasoning_content` | 静默消费、不展示（`parseChunk` 已处理） |
| 错误混在流里 | 已返回 200，之后用事件推错误 | 检测 `error` 事件/字段并抛 `AiException.ParseError` |

### 鉴权头差异

| 写法 | 厂商 |
| --- | --- |
| `Authorization: Bearer <key>` | OpenAI 及绝大多数兼容厂商（`extraHeaders` 默认行为） |
| `x-api-key: <key>` + `anthropic-version: 2023-06-01` | Anthropic（`AnthropicProvider` 手写） |
| `api-key: <key>` | Azure OpenAI 风格（覆写 `authHeaderName()`） |
| `?key=<key>` 查询参数 | Gemini **原生**协议（本项目走其 OpenAI 兼容端点，仍用 Bearer） |

写完**先用「测试连接」验证鉴权**（它走非流式、只申请 1 个 token，非常便宜），再去调流式解析。

### 模型名即 endpoint 的厂商（火山方舟）

豆包（火山方舟）这类厂商，**真正要填的 `model` 参数可能不是模型名，而是控制台创建的
「推理接入点 ID」**（形如 `ep-2024xxxxxx-xxxxx`）。适配要点：

1. 内置模型清单里给出**模型名**作为默认值（用户开箱可用）；
2. 但设置页必须允许用户**手动改写模型名**填入接入点 ID；
   `AiProviderRegistry.resolve()` 对未知模型名会回退到 `defaultModel`，
   因此同时要保证「用户自定义模型名」这条路径能走通；
3. 错误提示要写清楚：「若返回 404 / ModelNotFound，请到厂商控制台复制推理接入点 ID 填入模型名」；
4. 地址形态也要注意：方舟的 base 自带 `/api/v3`，不能再补 `/v1`（见 `DoubaoProvider.buildChatUrl`）。

### 其他易错点

- **请求体一定要带 `charset=utf-8`**，否则中文可能乱码。统一用文件顶部的 `JSON_MEDIA_TYPE`。
- **错误响应体要截断**（内置实现取前 500 字符），否则整页 HTML 会被塞进异常信息。
- **不要吞掉解析失败**：SSE 数据不是合法 JSON 时应抛 `AiException.ParseError`，而不是静默跳过。
- **图片 base64 不带 `data:` 前缀**：`ChatMessage.imageBase64` 存的是裸 base64，
  OpenAI 兼容实现才在拼 `image_url` 时自己加上 `data:${mime};base64,` 前缀。
- **不同厂商 `max_tokens` 上限不同**：设太大可能直接被拒。Anthropic 的 `max_tokens` 还是必填项。
- **不要发送 `temperature` 给推理型模型**：部分模型要求温度必须为默认值，会直接报错。
- **模型 id 与展示名分开**：`id` 必须是厂商接口认的字符串（不要「美化」），`displayName` 才是给人看的。
- **不要在 Provider 里做 UI 操作**：Provider 只负责网络与解析，UI 更新一律通过 `StreamCallback`。
- **不要在 Provider 里 `new OkHttpClient()`**：统一走 `HttpClient.client`，保证超时与连接池策略一致。
- **不要打印敏感信息**：日志里**禁止**输出 API Key 与截图 Base64。
- **记得标记 `open`**：便于自建网关继承覆写。

---

## 提交前检查清单

- [ ] 新建了 `ai/providers/XxxProvider.kt`，声明为 **`open class`** 并继承 `OpenAiCompatibleProvider` 或实现 `AiProvider`
- [ ] `id` 全小写、无空格，且与注册表、文档三处一致
- [ ] `defaultBaseUrl` 为 https 地址，并以 `/` 结尾
- [ ] `supportsVision` 用 `models.any { it.supportsVision }` 汇总，**没有**硬编码
- [ ] 每个 `AiModel` 的 `supportsVision` 都经过文档确认，拿不准就填 `false`
- [ ] 填了 `apiKeyUrl`（设置页的「获取 Key」跳转按钮会用到）
- [ ] 已在 `AiProviderRegistry` 中**追加**（未打乱既有顺序）注册
- [ ] `chatStream` 遵守约定：失败走 `callback.onError`，取消信号继续上抛
- [ ] `testConnection` 在所有路径上都返回「中文描述或 null」，不抛异常
- [ ] 已补单元测试，`./gradlew :app:testDebugUnitTest` 全绿
- [ ] 已更新 [README 的 Provider 表格](../README.md#api-key-配置说明-byok)
      （申请地址 / 默认 Base URL / 是否支持视觉）
- [ ] 已在真机上用真实 Key 跑通「测试连接」与一次完整的截图问答
- [ ] 已在 PR 描述中说明**是否需要自定义 Base URL 以及原因**
- [ ] 代码含**中文注释**，`./gradlew ktlintCheck` 通过

---

[← 返回 README](../README.md) · [BYOK 模式详解](byok.md) · [API Key 安全策略](api-key-security.md)
