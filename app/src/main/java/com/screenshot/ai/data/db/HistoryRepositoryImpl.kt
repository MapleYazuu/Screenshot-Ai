package com.screenshot.ai.data.db

import com.screenshot.ai.data.model.HistoryItem
import com.screenshot.ai.data.repo.HistoryFilter
import com.screenshot.ai.data.repo.HistoryRepository
import com.screenshot.ai.data.repo.ImageStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.util.Locale

/**
 * 历史记录仓库实现：Room 负责持久化，截图文件交给 [ImageStore] 管理。
 *
 * 线程模型：
 * - Room 的 Flow 查询本身在自身的查询线程池上执行，这里再统一加上
 *   flowOn(Dispatchers.IO)，是为了让下游的过滤/映射也不占用主线程；
 * - 挂起写操作由 Room 自动切换到 IO，无需额外 withContext。
 */
class HistoryRepositoryImpl(
    private val dao: HistoryDao,
    private val imageStore: ImageStore,
) : HistoryRepository {
    override fun observeAll(): Flow<List<HistoryItem>> =
        dao
            .observeAll()
            .flowOn(Dispatchers.IO)

    /**
     * 筛选后的记录流。
     *
     * 关键字搜索与 Provider/模型筛选全部在内存中完成：
     * 本机历史记录量级很小（个人使用通常仅数百条），内存筛选比按条件动态拼接 SQL
     * 更易维护（无需为每种条件组合写 Query、无需担心注入与 LIKE 转义），
     * 并且天然支持「搜索范围覆盖模型名」这类跨字段需求。
     *
     * 注意：这里只在上游 Flow 的 map 中过滤，**不会**因为用户每次输入而重新查询数据库，
     * 因此输入框联动是纯内存开销，不会造成 SQL 抖动。
     */
    override fun observeFiltered(filter: HistoryFilter): Flow<List<HistoryItem>> =
        dao
            .observeAll()
            .map { list -> list.filter { matches(it, filter) } }
            .flowOn(Dispatchers.IO)

    override suspend fun getById(id: Long): HistoryItem? = dao.getById(id)

    override suspend fun insert(item: HistoryItem): Long = dao.insert(item)

    override suspend fun updateReply(
        id: Long,
        reply: String,
    ) = dao.updateReply(id, reply)

    override suspend fun delete(item: HistoryItem) {
        // 先删数据库再删文件：即使文件删除失败，列表也不会残留一条指向空文件的记录；
        // 顺序反过来则可能先删掉图片、再因数据库异常留下打不开的「僵尸卡片」。
        dao.delete(item)
        imageStore.delete(item.imagePath)
    }

    override suspend fun restore(item: HistoryItem) {
        // 复用原 id 重新插入，撤销后记录回到原来的时间位置
        dao.restore(item)
    }

    override suspend fun clearAll() {
        // 只清空数据表：批量删除截图文件属于破坏性操作，交由上层在清空历史后
        // 显式调用 ImageStore.cleanUp() 统一回收，避免误删其他流程仍在引用的图片。
        dao.clearAll()
    }

    /**
     * 判断单条记录是否命中筛选条件。
     *
     * 全部比较使用 Locale.ROOT 做大小写折叠：默认 Locale（如土耳其语）下 'I' 会折叠成
     * 'ı'，用 ROOT 可以保证搜索行为与用户所在语言无关，结果稳定可预期。
     */
    private fun matches(
        item: HistoryItem,
        filter: HistoryFilter,
    ): Boolean {
        if (filter.providerId != null && item.providerId != filter.providerId) return false
        if (filter.modelId != null && item.modelId != filter.modelId) return false

        val keyword = filter.query.trim().lowercase(Locale.ROOT)
        if (keyword.isEmpty()) return true

        return item.presetName.lowercase(Locale.ROOT).contains(keyword) ||
            item.prompt.lowercase(Locale.ROOT).contains(keyword) ||
            item.aiReply.lowercase(Locale.ROOT).contains(keyword) ||
            item.modelId.lowercase(Locale.ROOT).contains(keyword)
    }
}
