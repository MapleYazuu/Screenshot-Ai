package com.screenshot.ai.util

import android.content.Context
import com.screenshot.ai.R
import com.screenshot.ai.ai.AiException
import java.io.IOException

/**
 * 把 [AiException] 翻译成当前界面语言的用户可读提示。
 *
 * 为什么需要这一层：
 * Provider 层属于「领域逻辑」，不应该依赖 Android 资源（否则单元测试要跑 Robolectric）；
 * 但它又是错误信息的产生地。折中做法是让异常只携带结构化信息，
 * 在**展示的最后一刻**（悬浮窗、设置页）再按界面语言渲染成句子。
 *
 * 所有返回文案都可以直接展示给用户。
 */
object AiErrorMessages {

    /**
     * 渲染错误提示。
     *
     * @param error 任意异常；非 [AiException] 的异常会被归入「未知错误」并附带原始 message
     */
    fun localize(context: Context, error: Throwable): String = when (error) {
        is AiException.MissingApiKey ->
            context.getString(R.string.error_missing_api_key, error.providerName)

        is AiException.HttpError -> httpErrorMessage(context, error.code)

        is AiException.NetworkError ->
            context.getString(
                R.string.error_network,
                error.reason.message ?: context.getString(R.string.error_network_hint),
            )

        is AiException.UnsupportedModel -> error.fallbackMessage

        is AiException.ParseError ->
            context.getString(R.string.error_parse, error.detail)

        AiException.Cancelled -> context.getString(R.string.error_cancelled)

        is AiException.Unknown ->
            context.getString(R.string.error_unknown_detail, error.fallbackMessage)

        is IOException ->
            context.getString(
                R.string.error_network,
                error.message ?: context.getString(R.string.error_network_hint),
            )

        else -> error.message ?: context.getString(R.string.common_unknown)
    }

    /** HTTP 状态码 → 针对性提示（区分鉴权、额度、服务端故障，便于用户自助排查） */
    fun httpErrorMessage(context: Context, code: Int): String = when (code) {
        401, 403 -> context.getString(R.string.error_http_auth, code)
        404 -> context.getString(R.string.error_http_not_found, code)
        429 -> context.getString(R.string.error_http_rate_limit, code)
        in 500..599 -> context.getString(R.string.error_http_server, code)
        else -> context.getString(R.string.error_http_generic, code)
    }
}
