package com.screenshot.ai.util

import android.content.Context
import android.text.method.LinkMovementMethod
import android.widget.TextView
import com.screenshot.ai.ServiceLocator
import io.noties.markwon.Markwon
import io.noties.markwon.core.CorePlugin
import io.noties.markwon.ext.tables.TablePlugin
import kotlinx.coroutines.launch

/**
 * Markdown 渲染统一出口（View 体系）。
 *
 * ## 技术选型
 * 悬浮窗是传统 View/XML 架构、提问历史是 Compose —— 混合架构。
 * 选择 **io.noties.markwon:core 4.6.2 + ext-tables**：
 * - View 侧（AI 回复悬浮窗 / 悬浮历史面板）直接 `renderAsync(textView, markdown)`；
 * - Compose 侧（提问历史展开详情）用 AndroidView 内嵌同一套 TextView 渲染，
 *   全应用共用一种 Markdown 方言与样式，不会出现两处渲染不一致。
 *
 * ## 渲染细节
 * - 标题（层级字号加粗）、加粗 / 斜体、列表（缩进项目符号）、引用、
 *   行内代码与代码块（等宽字体）均由 Markwon core 默认样式提供；
 * - 表格由 ext-tables 插件支持；链接（http/https）可点击跳转浏览器，
 *   需配合 [enableLinkClicks] 启用 movementMethod。
 *
 * ## 流式输出兼容（重点）
 * 流式期间**不渲染 Markdown**：调用方在 streaming 状态只显示纯文本
 * （半截语法不会闪烁/错位）；整段输出结束才调用 [renderAsync] 一次性渲染。
 * 解析在 IO 线程、setText 回主线程，不阻塞 UI。
 */
object MarkdownRenderer {

    @Volatile
    private var markwon: Markwon? = null

    /** 进程内单例（配置一次，避免每次渲染重复创建解析器） */
    private fun instance(context: Context): Markwon {
        markwon?.let { return it }
        synchronized(this) {
            markwon?.let { return it }
            val built =
                Markwon
                    .builder(context)
                    .usePlugin(CorePlugin.create())
                    .usePlugin(TablePlugin.create(context))
                    .build()
            markwon = built
            return built
        }
    }

    /**
     * 异步渲染：IO 线程解析 Markdown → 主线程 setText。
     * 多次调用按到达顺序执行；调用方保证流式期间不调用（半截语法由纯文本兜底）。
     */
    fun renderAsync(
        view: TextView,
        markdown: String,
    ) {
        if (markdown.isBlank()) {
            view.post { view.text = markdown }
            return
        }
        val mw = instance(view.context)
        ServiceLocator.appScope.launch {
            val parsed = runCatching { mw.toMarkdown(markdown) }.getOrNull()
            view.post {
                if (parsed != null) {
                    mw.setParsedMarkdown(view, parsed)
                }
            }
        }
    }

    /** 让链接可点击（Markwon 默认 LinkResolver 会打开 http/https 链接） */
    fun enableLinkClicks(view: TextView) {
        view.movementMethod = LinkMovementMethod.getInstance()
    }
}
