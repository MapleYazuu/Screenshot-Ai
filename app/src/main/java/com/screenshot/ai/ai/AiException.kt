package com.screenshot.ai.ai

/**
 * AI 调用相关异常统一封装。
 *
 * ## 为什么用「错误类型 + 参数」而不是直接带一段人话
 *
 * 错误提示要能被翻译，就不能在异常里写死某种语言的句子
 * （否则日文界面会弹出中文错误）。因此这里只携带**结构化信息**
 * （HTTP 状态码、Provider 名、原始原因），由 UI 层通过
 * [com.screenshot.ai.util.AiErrorMessages.localize] 渲染成当前界面语言的文案。
 *
 * [fallbackMessage] 是英文兜底文本，仅在拿不到 Context（单元测试、后台日志）时使用。
 */
sealed class AiException(
    val fallbackMessage: String,
    cause: Throwable? = null,
) : Exception(fallbackMessage, cause) {

    /** 未配置 API Key */
    class MissingApiKey(
        val providerName: String,
    ) : AiException("Please fill in the API key for $providerName in Settings")

    /** HTTP 状态码异常；具体文案由界面层按状态码渲染 */
    class HttpError(
        val code: Int,
        val body: String?,
    ) : AiException("Request failed ($code)")

    /** 网络层错误（超时、DNS、断网等） */
    class NetworkError(
        val reason: Throwable,
    ) : AiException("Network error: ${reason.message ?: "please check your connection"}", reason)

    /** 没有可用模型 / 模型不支持当前输入 */
    class UnsupportedModel(
        message: String,
    ) : AiException(message)

    /** 响应解析失败（返回体结构不符合预期） */
    class ParseError(
        val detail: String,
        cause: Throwable? = null,
    ) : AiException("Failed to parse the response: $detail", cause)

    /** 请求被用户取消 */
    data object Cancelled : AiException("Cancelled")

    /** 其他未知错误 */
    class Unknown(
        val reason: Throwable,
    ) : AiException(reason.message ?: "Unknown error", reason)
}
