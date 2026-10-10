package com.screenshot.ai.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import com.screenshot.ai.data.model.HistoryItem
import kotlinx.coroutines.flow.Flow

/**
 * 历史记录 DAO。
 *
 * 设计说明：
 * - 列表查询返回 Flow：Room 会监听 history 表并在数据变化时自动重查推送，
 *   UI 无需手动刷新，从根本上避免「删除后列表不更新」这类状态不一致；
 * - 排序固定 timestamp DESC，与界面「最新在最上」的预期一致，排序放在 SQL 里
 *   可以让 Room 提前完成，仓库层不必再维护顺序；
 * - DAO 只提供最小粒度能力，「先删后插」的撤销语义由仓库层组合调用实现。
 */
@Dao
interface HistoryDao {
    /** 全部记录，按提问时间倒序（最新在前） */
    @Query("SELECT * FROM history ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<HistoryItem>>

    /** 按主键查询单条记录，不存在返回 null（用于「重新提问」还原上下文） */
    @Query("SELECT * FROM history WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): HistoryItem?

    /** 插入新记录并返回自增主键；id 传 0 时由 SQLite 分配 */
    @Insert
    suspend fun insert(item: HistoryItem): Long

    /**
     * 回写 AI 回复全文。
     * 单独用 UPDATE 只改 aiReply 一列，避免流式过程中整行覆盖导致其他字段被旧值冲掉。
     */
    @Query("UPDATE history SET aiReply = :reply WHERE id = :id")
    suspend fun updateReply(
        id: Long,
        reply: String,
    )

    /** 删除单条记录（截图文件由仓库层负责回收） */
    @Delete
    suspend fun delete(item: HistoryItem)

    /**
     * 恢复一条被删除的记录（撤销删除）。
     *
     * 直接复用原对象的 id 再次插入：Room 允许为 autoGenerate 主键显式赋值，
     * 这样恢复后记录在「时间倒序」列表中的位置与外部持有的 id 引用都不会错乱。
     */
    @Insert
    suspend fun restore(item: HistoryItem): Long

    /** 清空全部记录 */
    @Query("DELETE FROM history")
    suspend fun clearAll()

    /** 记录条数（设置页展示存储占用时使用） */
    @Query("SELECT COUNT(*) FROM history")
    suspend fun count(): Int
}
