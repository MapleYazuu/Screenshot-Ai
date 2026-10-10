package com.screenshot.ai.data.preset

import android.util.Log
import com.screenshot.ai.data.model.Preset
import com.screenshot.ai.data.repo.PresetRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * 预设指令仓库实现。
 *
 * 存储形态：整份列表序列化成 JSON，作为单个值写进 kv 表（key = "presets"）。
 * 这样做的好处是读写都是「一份完整列表」，仓库层可以在写入前统一做一致性约束
 * （默认项唯一、顺序即优先级），不必依赖数据库约束或跨行事务。
 *
 * 3.1.0 起：
 * - **不再内置任何指令**：新安装初始化即为空列表，用户自行创建；
 * - 历史版本写入的内置指令由 [migrateLegacyBuiltins] 一次性「转正」
 *   （builtin 置 false），之后可正常删除 / 排序 / 多选删除。
 *
 * 并发：所有写操作都串行化在 [mutex] 上。整表覆盖写的模式下，两个并发 upsert
 * 会互相覆盖（读-改-写竞态），加锁是最简单可靠的解法；预设写入频率极低，
 * 锁竞争可以忽略。
 */
class PresetRepositoryImpl(
    private val dao: PresetDao,
) : PresetRepository {
    private val json =
        Json {
            // 旧版本写入的字段被移除后仍能读出其余字段，避免升级即「配置丢失」
            ignoreUnknownKeys = true
            // builtin / isDefault 等默认值必须落盘，否则下次启动时语义丢失
            encodeDefaults = true
        }

    private val serializer = ListSerializer(Preset.serializer())

    private val mutex = Mutex()

    /** 迁移是否已执行过（进程内幂等标志） */
    @Volatile
    private var migrated = false

    /**
     * 全部指令流。
     *
     * 在 flow 构建器里先做一次初始化：**首次安装（数据为空）时写入 3 条初始模板指令**
     * （与用户自建指令完全相同：无内置标记、可删除/修改/排序），并打上「已初始化」标记 ——
     * 用户把它们全部删掉之后**不会**再被重新写入。
     */
    override fun observeAll(): Flow<List<Preset>> {
        val source =
            flow<String?> {
                mutex.withLock { ensureInitialPresetsLocked() }
                emitAll(dao.observe(KEY))
            }
        return source
            .map { raw -> decode(raw) }
            .flowOn(Dispatchers.IO)
    }

    /** 当前默认指令：列表为空时返回 null */
    override fun observeDefault(): Flow<Preset?> =
        observeAll().map { list -> Preset.defaultOf(list) }

    /** 同步读取当前默认指令；列表为空时返回 null */
    override suspend fun getDefault(): Preset? {
        ensureInitialPresets()
        return Preset.defaultOf(decode(dao.get(KEY)))
    }

    override suspend fun upsert(preset: Preset) {
        mutex.withLock {
            ensureInitialPresetsLocked()
            val list = decode(dao.get(KEY)).toMutableList()

            // id 为空说明是「新增」：id 由仓库层生成，调用方不必关心 UUID 规则
            val id = preset.id.ifBlank { UUID.randomUUID().toString() }
            val index = list.indexOfFirst { it.id == id }
            val previous = list.getOrNull(index)

            // 3.1.0 起没有内置指令：所有条目都是普通用户指令
            val builtin = false

            // 编辑默认指令时 UI 通常只回传名称与 Prompt，若直接用 preset.isDefault 覆盖，
            // 会把默认标记抹掉，因此这里做「保持」处理；真正切换默认请走 setDefault()。
            val isDefault = preset.isDefault || previous?.isDefault == true
            if (isDefault) {
                // 全局最多一条默认指令：先把所有标记清掉，再写入当前这条
                for (i in list.indices) {
                    list[i] = list[i].copy(isDefault = false)
                }
            }

            val entry = preset.copy(id = id, builtin = builtin, isDefault = isDefault)
            if (index >= 0) {
                list[index] = entry
            } else {
                list.add(entry)
            }
            dao.put(KvRow(KEY, encode(list)))
        }
    }

    override suspend fun delete(id: String) {
        mutex.withLock {
            ensureInitialPresetsLocked()
            val list = decode(dao.get(KEY)).toMutableList()
            val target = list.firstOrNull { it.id == id } ?: return@withLock
            list.remove(target)

            if (target.isDefault && list.isNotEmpty()) {
                // 被删掉的正好是默认项：立即把首项补位为默认
                list[0] = list[0].copy(isDefault = true)
            }
            dao.put(KvRow(KEY, encode(list)))
        }
    }

    override suspend fun setDefault(id: String) {
        mutex.withLock {
            ensureInitialPresetsLocked()
            val list = decode(dao.get(KEY)).toMutableList()
            // 传入失效 id（例如目标刚被删除）时保持原状：
            // 若照常清空全部标记，会出现「一个默认项都没有」的状态。
            if (list.none { it.id == id }) {
                return@withLock
            }
            for (i in list.indices) {
                list[i] = list[i].copy(isDefault = list[i].id == id)
            }
            dao.put(KvRow(KEY, encode(list)))
        }
    }

    /**
     * 拖拽排序：把 fromIndex 的元素移动到 toIndex。
     *
     * 排序即优先级：排在最前面的指令就是截图时默认使用的指令，
     * 因此移动完成后会把新列表首项设为默认（与列表视觉顺序保持一致）。
     */
    override suspend fun reorder(
        fromIndex: Int,
        toIndex: Int,
    ) {
        if (fromIndex == toIndex) return
        mutex.withLock {
            ensureInitialPresetsLocked()
            val list = decode(dao.get(KEY)).toMutableList()
            if (fromIndex !in list.indices) return@withLock
            val target = (toIndex.coerceIn(list.indices))
            val item = list.removeAt(fromIndex)
            list.add(target, item)

            // 顺序即优先级：首项成为默认
            if (list.isNotEmpty() && !list.first().isDefault) {
                for (i in list.indices) {
                    list[i] = list[i].copy(isDefault = i == 0)
                }
            }
            dao.put(KvRow(KEY, encode(list)))
        }
    }

    /**
     * 批量删除（多选模式）。
     *
     * 3.1.0 起没有任何「不可删除」的条目：ids 里的全部删除；
     * 被删集合里有默认项时，剩余列表首项立即补位默认。
     * 整个「读-过滤-写」在 mutex 内一次完成。
     */
    override suspend fun deleteMany(ids: Set<String>) {
        if (ids.isEmpty()) return
        mutex.withLock {
            ensureInitialPresetsLocked()
            val list = decode(dao.get(KEY)).toMutableList()
            val toRemove = list.filter { preset -> preset.id in ids }
            if (toRemove.isEmpty()) return@withLock

            val remaining = list.filterNot { it in toRemove }.toMutableList()
            if (toRemove.any { it.isDefault } && remaining.isNotEmpty()) {
                remaining[0] = remaining[0].copy(isDefault = true)
            }
            dao.put(KvRow(KEY, encode(remaining)))
        }
    }

    /**
     * 数据迁移（幂等）：历史版本写入的内置指令「转正」为普通用户指令。
     * 与 [ensureInitialPresetsLocked] 共用同一把锁与幂等流程。
     */
    override suspend fun migrateLegacyBuiltins() {
        mutex.withLock { ensureInitialPresetsLocked() }
    }

    /** 外部入口（getDefault 等无锁路径）：串行化后确保初始化完成 */
    private suspend fun ensureInitialPresets() {
        mutex.withLock { ensureInitialPresetsLocked() }
    }

    /**
     * 初始化 / 迁移（调用方必须已持有 [mutex]，进程内幂等）。
     *
     * 规则：
     * 1. **首次安装**（未打过初始化标记且列表为空）→ 写入 3 条初始模板指令
     *    （与用户自建指令完全相同，可删除/修改/排序，第一条为默认）；
     * 2. 打过初始化标记后**绝不再写入** —— 用户把指令全删掉也不会复现；
     * 3. 历史版本遗留的 builtin 标记一律清除（转正为普通指令）。
     */
    private suspend fun ensureInitialPresetsLocked() {
        if (migrated) return
        migrated = true
        val seeded = dao.get(SEED_FLAG_KEY)
        val raw = dao.get(KEY)
        var list = decode(raw)

        if (seeded.isNullOrBlank()) {
            if (list.isEmpty()) {
                list = INITIAL_PRESETS
                Log.i(TAG, "首次初始化：写入 ${INITIAL_PRESETS.size} 条初始模板指令")
            }
            dao.put(KvRow(SEED_FLAG_KEY, "1"))
        }

        if (list.any { it.builtin }) {
            list =
                list.map { preset ->
                    if (preset.builtin) {
                        preset.copy(builtin = false, builtinNameRes = 0, builtinPromptRes = 0)
                    } else {
                        preset
                    }
                }
            Log.i(TAG, "已把遗留内置指令转为普通用户指令")
        }

        if (list != decode(raw)) {
            dao.put(KvRow(KEY, encode(list)))
        }
    }

    private fun decode(raw: String?): List<Preset> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            json.decodeFromString(serializer, raw)
        } catch (e: SerializationException) {
            // 降级为空列表而不是向上抛：保证设置页永远可用，不会因为一份损坏的配置直接崩溃。
            Log.w(TAG, "预设指令 JSON 解析失败", e)
            emptyList()
        }
    }

    private fun encode(list: List<Preset>): String = json.encodeToString(serializer, list)

    companion object {
        /** kv 表中的键名 */
        private const val KEY = "presets"

        /** 初始化标记键：打过标记后不再自动写入初始指令（用户删光不会复现） */
        private const val SEED_FLAG_KEY = "presets_seeded"

        /**
         * 首次安装写入的初始模板指令。
         *
         * 与用户自建指令完全相同（builtin = false）：可删除、可修改、可拖拽排序；
         * 第一条「翻译」为初始默认指令。
         */
        private val INITIAL_PRESETS: List<Preset> =
            listOf(
                Preset(
                    id = "seed_translate",
                    name = "翻译",
                    prompt = "请识别截图中的全部文字，并翻译成中文。",
                    icon = "🌐",
                    isDefault = true,
                ),
                Preset(
                    id = "seed_summary",
                    name = "总结",
                    prompt = "请总结这张截图的主要内容，提取关键信息。",
                    icon = "📝",
                ),
                Preset(
                    id = "seed_explain_code",
                    name = "解释代码",
                    prompt = "截图中包含源代码，请说明它的功能与实现方式。",
                    icon = "💻",
                ),
            )

        /** 日志标签 */
        private const val TAG = "PresetRepository"
    }
}
