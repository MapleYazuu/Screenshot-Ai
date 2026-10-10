package com.screenshot.ai.data.repo

import com.screenshot.ai.data.model.HistoryItem
import kotlinx.coroutines.flow.Flow

/**
 * 历史记录筛选条件。
 * 搜索范围：指令名 + 提问文本 + AI 回复全文 + 模型名；
 * 叠加 Provider / 模型筛选。
 */
data class HistoryFilter(
    val query: String = "",
    val providerId: String? = null,
    val modelId: String? = null,
) {
    /** 是否为空条件（无搜索、无筛选） */
    val isEmpty: Boolean
        get() = query.isBlank() && providerId == null && modelId == null
}

/**
 * 历史记录仓库。Room 负责持久化，筛选在内存中完成（本地数据量小，无需 SQL 动态拼接）。
 */
interface HistoryRepository {
    /** 全部记录，按时间倒序 */
    fun observeAll(): Flow<List<HistoryItem>>

    /** 按条件筛选后的记录流 */
    fun observeFiltered(filter: HistoryFilter): Flow<List<HistoryItem>>

    /** 单条记录（用于重新提问） */
    suspend fun getById(id: Long): HistoryItem?

    /** 插入并返回自增 id */
    suspend fun insert(item: HistoryItem): Long

    /** 更新回复内容（流式结束后回写） */
    suspend fun updateReply(
        id: Long,
        reply: String,
    )

    /** 删除单条（返回被删除的对象，供撤销 Snackbar 恢复） */
    suspend fun delete(item: HistoryItem)

    /** 恢复一条被删除的记录 */
    suspend fun restore(item: HistoryItem)

    /** 清空全部 */
    suspend fun clearAll()
}
