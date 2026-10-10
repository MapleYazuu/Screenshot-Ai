package com.screenshot.ai.ai.net

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okio.BufferedSource

/**
 * SSE（text/event-stream）逐行解析。
 *
 * 只实现实际用到的最小语义：识别 data 字段、忽略注释行与其它字段、按规范拼接多行 data。
 * 之所以不使用 okhttp3.sse：本项目只依赖 OkHttp 核心包，
 * 且各厂商在标准之外还有各自约定（[DONE]、message_stop 等），交给上层解析更清晰。
 */
object SseReader {
    /** data 字段前缀 */
    private const val DATA_PREFIX = "data:"

    /** OpenAI 风格的流结束标记 */
    const val DONE = "[DONE]"

    /** 注释行前缀（服务端心跳形如 ": keep-alive"） */
    private const val COMMENT_PREFIX = ":"

    /**
     * 读取 [responseBody] 中的 SSE 事件。
     *
     * 严格遵守 SSE 规范中「一个事件由若干字段行组成、空行代表事件结束」的约定：
     * - 同一事件内的多条 `data:` 行必须用 `\n` 拼接后**一次性**交给 [onData]，
     *   而不是每行回调一次。这条规则在正常流式输出里看不出来（每个事件只有一行 data），
     *   但一旦服务端把一个 JSON 分多行发送，按行回调会得到两段非法 JSON，
     *   上层解析直接失败。
     * - `:` 开头的注释行（心跳）与 event / id / retry 等字段按规范忽略；
     * - 空行触发派发；流结束时若缓冲区仍有未派发的数据，也要补发一次，
     *   否则最后一条没有以空行结尾的事件会被丢掉；
     * - 读到 [DONE] 立即返回，剩余读取与响应体关闭由调用方负责。
     *
     * 循环内显式检查协程取消状态：底层 readUtf8Line 是阻塞调用，
     * 上层取消协程时会关闭连接，这里保证取消后第一时间退出而不是继续解析。
     */
    suspend fun readSse(
        responseBody: BufferedSource,
        onData: suspend (String) -> Unit,
    ) {
        // 当前事件已累积的 data 行
        val dataLines = mutableListOf<String>()

        while (true) {
            currentCoroutineContext().ensureActive()
            val line = responseBody.readUtf8Line()
            if (line == null) {
                // 流意外结束：补发尚未派发的事件，避免丢失最后一条数据
                if (dataLines.isNotEmpty()) {
                    dispatch(dataLines, onData)
                }
                return
            }

            val trimmed = line.trim()

            if (trimmed.isEmpty()) {
                // 空行 = 事件边界
                if (dataLines.isEmpty()) continue
                val payload = dataLines.joinToString("\n")
                dataLines.clear()
                if (payload == DONE) return
                onData(payload)
                continue
            }

            if (trimmed.startsWith(COMMENT_PREFIX)) continue
            if (!trimmed.startsWith(DATA_PREFIX)) continue

            // 规范允许 "data:value" 与 "data: value" 两种写法，实际上各家厂商两种都在用，
            // 因此统一 trim 掉首尾空白：每个事件的 JSON 都是自包含的，
            // 去掉行首行尾空白不会破坏结构，但能避免 " data: {...}" 这类写法带来的解析失败。
            val payload = trimmed.substring(DATA_PREFIX.length).trim()
            if (payload == DONE) return
            dataLines += payload
        }
    }

    /** 派发一个已累积的完整事件 */
    private suspend fun dispatch(
        dataLines: List<String>,
        onData: suspend (String) -> Unit,
    ) {
        val payload = dataLines.joinToString("\n")
        if (payload.isNotEmpty()) {
            onData(payload)
        }
    }
}
