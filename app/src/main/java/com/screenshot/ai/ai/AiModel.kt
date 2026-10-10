package com.screenshot.ai.ai

/**
 * AI 模型描述。
 *
 * @param id            调用 API 时使用的模型标识，例如 "deepseek-chat"
 * @param displayName   界面上展示的名称
 * @param supportsVision 是否支持图片输入；为 false 时截图会先走本地 OCR 降级
 * @param contextWindow 上下文窗口大小（token），未知时可为 null
 */
data class AiModel(
    val id: String,
    val displayName: String,
    val supportsVision: Boolean,
    val contextWindow: Int? = null,
)
