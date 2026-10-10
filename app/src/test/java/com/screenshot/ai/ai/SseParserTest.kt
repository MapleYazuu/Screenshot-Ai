package com.screenshot.ai.ai

import com.screenshot.ai.ai.net.SseReader
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.BufferedSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SSE 流式响应解析测试（[SseReader]）。
 *
 * 数据源用 okio.Buffer 直接在内存里构造，不依赖网络与端口：
 * SSE 解析是纯字符串处理逻辑，用真实 socket 只会让测试变慢、变脆。
 */
class SseParserTest {
    @Test
    fun `解析普通data行`() =
        runTest {
            val events =
                readSse(
                    """
            |data: {"choices":[{"delta":{"content":"你"}}]}
            |
            |data: {"choices":[{"delta":{"content":"好"}}]}
            |
            |
                    """.trimMargin(),
                )

            assertEquals(2, events.size)
            assertEquals("""{"choices":[{"delta":{"content":"你"}}]}""", events[0])
            assertEquals("""{"choices":[{"delta":{"content":"好"}}]}""", events[1])
        }

    @Test
    fun `DONE标记终止解析且不回调`() =
        runTest {
            val events =
                readSse(
                    """
            |data: {"a":1}
            |
            |data: [DONE]
            |
            |data: {"a":2}
            |
            |
                    """.trimMargin(),
                )

            // [DONE] 是控制标记，不能当数据抛给上层；其后内容也不应再解析
            assertEquals(listOf("""{"a":1}"""), events)
        }

    @Test
    fun `跳过注释与心跳行`() =
        runTest {
            val events =
                readSse(
                    """
            |: keep-alive
            |
            |: ping
            |data: {"a":1}
            |
            |
                    """.trimMargin(),
                )

            assertEquals(listOf("""{"a":1}"""), events)
        }

    @Test
    fun `多行data按SSE规范用换行拼接`() =
        runTest {
            val events =
                readSse(
                    """
            |data: 第一行
            |data: 第二行
            |
            |
                    """.trimMargin(),
                )

            assertEquals(1, events.size)
            assertEquals("第一行\n第二行", events[0])
        }

    @Test
    fun `忽略空行与无data前缀的行`() =
        runTest {
            val events =
                readSse(
                    """

            |event: message
            |id: 42
            |retry: 1000
            |data: {"a":1}

            |
            |
                    """.trimMargin(),
                )

            assertEquals(listOf("""{"a":1}"""), events)
        }

    @Test
    fun `data字段前后的空白被清理`() =
        runTest {
            // 两个事件之间必须有空行，否则按规范它们属于同一个事件
            val events = readSse("data:{\"a\":1}\n\n  data:  {\"b\":2}  \n\n")

            // 前缀与 payload 的空白都会被清理，因此两种写法结果一致
            assertEquals(2, events.size)
            assertEquals("""{"a":1}""", events[0])
            assertEquals("""{"b":2}""", events[1])
        }

    @Test
    fun `空响应体不产生任何事件`() =
        runTest {
            val events = readSse("")
            assertTrue("空响应不应有事件", events.isEmpty())
        }

    @Test
    fun `只有DONE标记时不产生事件`() =
        runTest {
            val events = readSse("data: [DONE]\n\n")
            assertTrue(events.isEmpty())
        }

    @Test
    fun `兼容CRLF换行`() =
        runTest {
            val events = readSse("data: {\"a\":1}\r\n\r\ndata: {\"a\":2}\r\n\r\n")
            assertEquals(2, events.size)
            assertEquals("""{"a":1}""", events[0])
            assertEquals("""{"a":2}""", events[1])
        }

    @Test
    fun `末尾没有空行的最后一个事件也会回调`() =
        runTest {
            // 部分服务端在最后一个事件后不发空行就关闭连接；
            // 这里两个 data 之间没有空行，按 SSE 规范属于**同一个**事件，
            // 因此期望得到一条用换行拼接的载荷，而不是两条。
            val events = readSse("data: {\"a\":1}\ndata: {\"a\":2}")
            assertEquals(1, events.size)
            assertEquals("{\"a\":1}\n{\"a\":2}", events[0])
        }

    @Test
    fun `末尾无空行时用空行分隔的两个事件都能收到`() =
        runTest {
            // 结尾缺空行不能导致最后一个事件被丢掉
            val events = readSse("data: {\"a\":1}\n\ndata: {\"a\":2}")
            assertEquals(2, events.size)
            assertEquals("""{"a":2}""", events[1])
        }

    @Test
    fun `多行JSON被拼成一条完整载荷`() =
        runTest {
            // 真实场景：服务端把一个 JSON 分多行发送，按行回调会得到两段非法 JSON
            val events = readSse("data: {\"choices\":[\ndata: {\"delta\":{\"content\":\"你好\"}}]}\n\n")

            assertEquals(1, events.size)
            assertEquals("{\"choices\":[\n{\"delta\":{\"content\":\"你好\"}}]}", events[0])
        }

    // ---------------- 测试辅助 ----------------

    /** 用内存 Buffer 构造响应体，收集解析出的所有 data 载荷 */
    private suspend fun readSse(body: String): List<String> {
        val source: BufferedSource = Buffer().writeUtf8(body)
        val events = mutableListOf<String>()
        SseReader.readSse(source) { data ->
            events.add(data)
        }
        return events
    }
}
