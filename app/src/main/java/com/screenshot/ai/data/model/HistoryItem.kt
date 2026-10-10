package com.screenshot.ai.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 提问历史记录。
 *
 * 设计说明：
 * - [imagePath] 保存截图的本地私有文件路径（filesDir/screenshots/），不进入相册；
 * - prompt / aiReply 保存完整文本，历史页展开时展示全文；
 * - providerId + modelId 用于筛选与「重新提问」时还原上下文。
 */
@Entity(tableName = "history")
data class HistoryItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 提问时间戳（毫秒） */
    val timestamp: Long,
    /** 截图原图本地路径，文件不存在时历史页会显示占位 */
    val imagePath: String,
    /** 使用的预设指令名称，便于列表展示 */
    val presetName: String,
    /** Provider id，例如 "deepseek" */
    val providerId: String,
    /** 模型 id，例如 "deepseek-chat" */
    val modelId: String,
    /** 实际发送给模型的提问文本（可能包含 OCR 降级后的文字） */
    val prompt: String,
    /** AI 回复全文 */
    val aiReply: String,
) {
    /** 展示用标题：指令名，为空时回退到 modelId */
    val title: String get() = presetName.ifBlank { modelId }
}
