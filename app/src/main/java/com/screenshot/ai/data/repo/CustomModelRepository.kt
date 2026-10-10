package com.screenshot.ai.data.repo

import com.screenshot.ai.data.model.CustomModelConfig
import kotlinx.coroutines.flow.Flow

/**
 * 自定义模型（OpenAI 兼容第三方接口）仓库。
 *
 * 分工：
 * - 配置字段（名称 / Base URL / 模型 ID / 视觉开关）存 DataStore；
 * - API Key 存加密存储（providerId = "custom"）；
 * - [cachedConfig] 供 AI 层同步读取（AiProviderRegistry 的自定义 Provider 在
 *   主线程同步构造 AiModel 时使用），由 ServiceLocator 的收集器持续刷新。
 */
interface CustomModelRepository {
    /** 配置流（UI 绑定） */
    fun observe(): Flow<CustomModelConfig>

    /** 当前配置（挂起读取，保证拿到最新值） */
    suspend fun current(): CustomModelConfig

    /** 保存配置（已规范化） */
    suspend fun save(config: CustomModelConfig)

    /** 最近一次缓存的配置（同步读取，供 Provider 构造模型列表） */
    fun cachedConfig(): CustomModelConfig

    /** 更新缓存（由 ServiceLocator 的收集器调用） */
    fun updateCache(config: CustomModelConfig)

    /** 自定义模型的 API Key（加密存储） */
    fun apiKey(): String

    /** 保存自定义模型的 API Key（空字符串表示清除） */
    fun setApiKey(key: String)
}
