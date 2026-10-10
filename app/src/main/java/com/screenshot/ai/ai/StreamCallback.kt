package com.screenshot.ai.ai

/**
 * 流式回调集合。
 *
 * 相比三个独立的 lambda 参数，使用一个接口便于：
 * 1. 在 UI 层实现「思考中 / 流式输出 / 出错」三态；
 * 2. 单元测试中用 Fake 实现记录调用序列；
 * 3. 在回调中随时通过 [ChatSession.cancel] 中断。
 */
interface StreamCallback {
    /** 收到一段增量文本（可能是一个字，也可能是一句话） */
    fun onChunk(text: String)

    /** 正常结束 */
    fun onComplete()

    /**
     * 出错。
     *
     * @param error 具体异常，通常是 [AiException] 或其子类
     * @param partialText 出错前已经收到的部分文本，便于 UI 保留已有内容
     */
    fun onError(
        error: Throwable,
        partialText: String,
    ) = Unit

    /** 请求已发出、等待首个 token（可用于展示 loading 态） */
    fun onStart() = Unit
}

/**
 * 简易 StreamCallback 工厂，便于在 Kotlin 中直接用 lambda 调用。
 */
inline fun streamCallback(
    crossinline onChunk: (String) -> Unit,
    crossinline onComplete: () -> Unit = {},
    crossinline onError: (Throwable, String) -> Unit = { _, _ -> },
    crossinline onStart: () -> Unit = {},
): StreamCallback =
    object : StreamCallback {
        override fun onChunk(text: String) = onChunk(text)

        override fun onComplete() = onComplete()

        override fun onError(
            error: Throwable,
            partialText: String,
        ) = onError(error, partialText)

        override fun onStart() = onStart()
    }
