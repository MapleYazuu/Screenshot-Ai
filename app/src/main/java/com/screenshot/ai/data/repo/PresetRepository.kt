package com.screenshot.ai.data.repo

import com.screenshot.ai.data.model.Preset
import kotlinx.coroutines.flow.Flow

/**
 * 预设指令仓库。
 *
 * 约定（3.1.0 起）：
 * - **没有任何内置指令**：列表初始化时为空，全部由用户自行创建；
 * - 列表顺序即优先级：排在最前面的指令就是截图时默认使用的指令，
 *   拖拽排序 [reorder] 后首项自动成为默认；
 * - 历史版本遗留的内置指令由 [migrateLegacyBuiltins] 在启动时自动
 *   「转正」为普通用户指令（可删除）。
 */
interface PresetRepository {
    /** 全部指令，按用户排列的顺序返回（首项为默认） */
    fun observeAll(): Flow<List<Preset>>

    /** 当前默认指令；列表为空时返回 null */
    fun observeDefault(): Flow<Preset?>

    /** 同步读取当前默认指令（悬浮窗截图时立即需要）；列表为空时返回 null */
    suspend fun getDefault(): Preset?

    /** 新增或更新一条指令 */
    suspend fun upsert(preset: Preset)

    /** 删除指令 */
    suspend fun delete(id: String)

    /** 批量删除（多选模式）；删除默认项后自动补位 */
    suspend fun deleteMany(ids: Set<String>)

    /** 设为默认指令 */
    suspend fun setDefault(id: String)

    /**
     * 拖拽排序：把 fromIndex 的元素移动到 toIndex。
     * 顺序即优先级：移动完成后列表首项自动成为默认指令。
     */
    suspend fun reorder(
        fromIndex: Int,
        toIndex: Int,
    )

    /**
     * 数据迁移：把历史版本遗留的内置指令标记清除（转正为普通用户指令）。
     * 幂等，由 ServiceLocator 在启动时调用一次。
     */
    suspend fun migrateLegacyBuiltins()
}
