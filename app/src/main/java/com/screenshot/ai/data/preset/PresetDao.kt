package com.screenshot.ai.data.preset

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 单槽位键值行。
 *
 * 预设指令条数少、需要整体读写、并且带默认值，因此序列化成 JSON 后作为**一个值**存取，
 * 而不是为每条预设建一张表：这样读取时天然是一份完整、自洽的列表，不会出现
 * 「读到一半被其他事务改写」的中间态，也省去了跨表事务的复杂度。
 *
 * 放在本文件是为了让「表结构 + 访问它的 DAO」始终一起演进，避免两处定义漂移。
 */
@Entity(tableName = "kv")
data class KvRow(
    /** 键（例如 "presets"），作为主键保证同键只有一行 */
    @PrimaryKey val k: String,
    /** 值（预设列表的 JSON 文本） */
    val value: String,
)

/**
 * 预设指令 DAO：一个薄薄的单槽位键值存储。
 *
 * 之所以不直接使用 DataStore：
 * - 预设与历史记录同属业务数据，集中放在 Room 里便于后续做跨表一致性处理与备份；
 * - Room 的 Flow 查询可与历史记录共享同一套响应式机制，UI 侧心智模型统一。
 *
 * 之所以不按字段建表：预设包含 icon/isDefault/builtin 等语义字段，且未来还会扩展，
 * 每加一个字段都要改 schema 并写迁移；用 JSON 承载则只关心「值的读写」。
 */
@Dao
interface PresetDao {
    /** 读取原始 JSON，未写入过返回 null */
    @Query("SELECT value FROM kv WHERE k = :key LIMIT 1")
    suspend fun get(key: String): String?

    /** 写入/覆盖某个键（REPLACE 保证同键只有一行，不会因并发插入主键冲突而抛异常） */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(row: KvRow)

    /** 观察某个键的变化，首次写入后即可收到推送 */
    @Query("SELECT value FROM kv WHERE k = :key LIMIT 1")
    fun observe(key: String): Flow<String?>

    /** 删除某个键（恢复出厂设置/清空数据时使用） */
    @Query("DELETE FROM kv WHERE k = :key")
    suspend fun remove(key: String)
}
