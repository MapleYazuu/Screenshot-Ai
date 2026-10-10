package com.screenshot.ai.data.model

import android.content.Context
import androidx.annotation.StringRes
import kotlinx.serialization.Serializable

/**
 * 预设指令。
 *
 * 序列化成 JSON 后存入 kv 表（见 [com.screenshot.ai.data.preset.PresetRepositoryImpl]），
 * 相比 Room 更适合「条数少、整体读写、需要默认值」的配置型数据。
 *
 * ## 3.1.0：内置指令已全部移除
 *
 * 不再内置任何预设指令：
 * - 列表初始化时为空，用户通过「新建指令」自行创建；
 * - 历史版本写入的内置指令会在首次读取时被自动「转正」为普通用户指令
 *   （[builtin] 置 false，可长按删除 / 拖拽排序 / 多选删除），见
 *   PresetRepositoryImpl 的迁移逻辑；
 * - [builtin] 字段保留在数据模型中仅为兼容旧 JSON，新数据恒为 false。
 *
 * @param id        唯一标识
 * @param name      指令名称
 * @param prompt    指令内容（发送给 AI 的 Prompt）
 * @param icon      可选图标（单个 Emoji）
 * @param isDefault 是否为默认指令（全局最多一条，由仓库层保证）
 * @param builtin   历史遗留的内置标记（3.1.0 起迁移后恒为 false，仅兼容旧数据）
 */
@Serializable
data class Preset(
    val id: String,
    val name: String,
    val prompt: String,
    val icon: String? = null,
    val isDefault: Boolean = false,
    val builtin: Boolean = false,
    @StringRes val builtinNameRes: Int = 0,
    @StringRes val builtinPromptRes: Int = 0,
) {
    /** 展示用名称（3.1.0 起所有指令都是用户指令，直接返回自身字段） */
    fun displayName(context: Context): String = name

    companion object {

        /** 历史内置指令 id 前缀（仅用于识别旧数据，不再新增） */
        const val BUILTIN_PREFIX = "builtin_"

        /** 取默认指令，若列表中没有标记默认的，则回退到第一条（顺序即优先级） */
        fun defaultOf(list: List<Preset>): Preset? = list.firstOrNull { it.isDefault } ?: list.firstOrNull()
    }
}
