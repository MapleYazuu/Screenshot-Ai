package com.screenshot.ai.ai.net

import com.screenshot.ai.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * AI 网络层共享的 HTTP 资源。
 *
 * 设计要点：
 * - 全局单例 [client]：复用连接池与内部线程池，避免每次请求都新建客户端，
 *   否则连接与线程会持续泄漏（尤其悬浮窗场景下请求频繁）；
 * - 读超时放宽到 120 秒：流式响应在两个 token 之间可能长时间静默，
 *   沿用默认 10 秒会把正常的「长思考」误判为超时；
 * - 日志拦截器只在 Debug 构建挂载：Release 包既不把请求头里的 API Key
 *   写进 logcat，也省掉日志拼装的额外开销。
 */
object HttpClient {
    /** 建立连接（含 TLS 握手）的超时，单位秒 */
    private const val CONNECT_TIMEOUT_SECONDS = 30L

    /** 读取超时，单位秒；流式场景必须足够长 */
    private const val READ_TIMEOUT_SECONDS = 120L

    /** 写入超时，单位秒；请求体可能包含 Base64 图片 */
    private const val WRITE_TIMEOUT_SECONDS = 30L

    /** 流式线程数：同一时刻只有一次对话在跑，2 个线程足够且不会无限增长 */
    private const val STREAM_THREADS = 2

    /** 全局共享的 OkHttp 客户端 */
    val client: OkHttpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .apply {
                if (BuildConfig.DEBUG) {
                    // BASIC 只记录请求行与响应行：既能定位 4xx/5xx，又不会打印 API Key
                    addInterceptor(
                        HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC },
                    )
                }
            }.build()

    /**
     * 驱动流式读写的守护线程池。
     *
     * 单独抽出来，是为了让需要「OkHttp 回调风格」的调用方复用同一组线程，
     * 而不是各自 new Thread；使用守护线程可避免未结束的任务阻塞 JVM 退出。
     */
    val streamExecutor: ExecutorService =
        Executors.newFixedThreadPool(STREAM_THREADS, StreamThreadFactory())

    /** 给线程命名，便于抓线程转储时快速定位网络层问题 */
    private class StreamThreadFactory : ThreadFactory {
        private val counter = AtomicInteger(1)

        override fun newThread(runnable: Runnable): Thread =
            Thread(runnable, "ai-stream-${counter.getAndIncrement()}").apply { isDaemon = true }
    }
}
