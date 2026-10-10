package com.screenshot.ai.ai.net

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 接口地址拼装。
 *
 * 用户既可能只填厂商域名，也可能把完整接口地址整段粘进来（还常带多余斜杠），
 * 因此这里用一组确定性的字符串规则统一收敛：同一份配置永远拼出同一个 URL，
 * 便于单元测试断言的同时，也避免「同一配置时好时坏」的诡异问题。
 */
object UrlBuilder {
    /** OpenAI 兼容的补全路径 */
    private const val CHAT_COMPLETIONS = "/chat/completions"

    /** Anthropic 默认域名（用户未填写时的兜底） */
    private const val ANTHROPIC_DEFAULT_BASE = "https://api.anthropic.com"

    /** 版本段标记 */
    private const val V1_MARKER = "/v1"

    /** 协议分隔符 */
    private const val SCHEME_SEPARATOR = "://"

    /**
     * 规范化 baseUrl：去掉首尾空白与路径末尾多余的斜杠。
     *
     * 例如 " https://api.deepseek.com// " -> "https://api.deepseek.com"。
     * 仅含协议的地址（如 "https://"）会原样保留，避免把协议分隔符本身裁掉。
     */
    fun normalize(baseUrl: String): String {
        val trimmed = baseUrl.trim()
        if (trimmed.isEmpty()) return ""
        val schemeEnd = trimmed.indexOf(SCHEME_SEPARATOR)
        // 协议分隔符以斜杠结尾，裁剪必须停在这之后
        val minLength = if (schemeEnd >= 0) schemeEnd + SCHEME_SEPARATOR.length else 0
        var end = trimmed.length
        while (end > minLength && trimmed[end - 1] == '/') {
            end--
        }
        return trimmed.substring(0, end)
    }

    /**
     * OpenAI 兼容的 chat completions 地址。
     *
     * 规则（按顺序判断）：
     * 1. 空地址 -> 抛 [IllegalArgumentException]；
     * 2. 已以 "/chat/completions" 结尾 -> 原样返回（用户直接粘了完整地址）；
     * 3. 路径中已含 "/v1"（/v1、/v1beta、/v1/openai 等）-> 追加 "/chat/completions"；
     * 4. 其余情况 -> 追加 "/v1/chat/completions"。
     */
    fun chatCompletions(baseUrl: String): String {
        val base = normalize(baseUrl)
        require(base.isNotEmpty()) { "接口地址不能为空，请在设置中填写 Base URL" }
        if (base.endsWith(CHAT_COMPLETIONS)) return base
        // 地址里已经带版本段时不再重复追加，否则会拼出 /v1/v1/chat/completions
        return if (pathOf(base).contains(V1_MARKER, ignoreCase = true)) {
            base + CHAT_COMPLETIONS
        } else {
            base + V1_MARKER + CHAT_COMPLETIONS
        }
    }

    /**
     * Anthropic 的 messages 地址。
     *
     * 空地址回退到官方域名；最终形态为 "$base/v1/messages"，
     * 若 base 已以 "/v1" 结尾则只追加 "/messages"。
     */
    fun anthropicMessages(baseUrl: String): String {
        val base = normalize(baseUrl).ifEmpty { ANTHROPIC_DEFAULT_BASE }
        return when {
            base.endsWith("$V1_MARKER/messages") -> base
            base.endsWith(V1_MARKER) -> base + "/messages"
            else -> base + V1_MARKER + "/messages"
        }
    }

    /**
     * 取 URL 的路径部分做版本判断。
     *
     * 用 [okhttp3.HttpUrl] 解析只为了更准确地拿到 path（避免把域名里的字样误判成版本段）；
     * 解析失败（用户填了非法地址）时退化为整串比较，保证不会因为解析失败而崩溃。
     */
    private fun pathOf(url: String): String = url.toHttpUrlOrNull()?.encodedPath ?: url
}
