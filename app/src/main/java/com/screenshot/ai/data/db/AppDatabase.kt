package com.screenshot.ai.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.screenshot.ai.data.model.HistoryItem
import com.screenshot.ai.data.preset.KvRow
import com.screenshot.ai.data.preset.PresetDao

/**
 * 应用数据库。
 *
 * 表结构：
 * - history：提问历史（见 [HistoryItem]），记录量级为「本机个人使用」，无需分页索引；
 * - kv：单槽位键值表（见 [KvRow]），承载以 JSON 整体读写的预设指令等配置型数据。
 *
 * 迁移策略（重要）：
 * 这里刻意 **不** 调用 fallbackToDestructiveMigration()。破坏性迁移会在升级时静默
 * 清空用户的历史记录与预设，属于不可接受的数据丢失；因此后续任何 schema 变更都必须
 * 把 version 加 1 并同时提交对应的 Migration，编译期/运行期都不会「悄悄降级」。
 */
@Database(
    entities = [HistoryItem::class, KvRow::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    /** 历史记录访问入口 */
    abstract fun historyDao(): HistoryDao

    /** 预设指令访问入口（预设整体以 JSON 存放在 kv 表中） */
    abstract fun presetDao(): PresetDao

    companion object {
        /** 数据库文件名 */
        private const val DB_NAME = "screenshot_ai.db"

        /**
         * 单例缓存。
         * @Volatile 保证多线程可见性，配合 synchronized 的双重检查可避免重复建库
         * （重复建库会各自持有连接，导致写入互相不可见这类难以排查的问题）。
         */
        @Volatile
        private var instance: AppDatabase? = null

        /** 获取数据库单例，传入的 Context 会自动转为 Application Context，不会泄漏 Activity */
        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room
                    .databaseBuilder(
                        context.applicationContext,
                        AppDatabase::class.java,
                        DB_NAME,
                    ).build()
                    .also { instance = it }
            }
    }
}
