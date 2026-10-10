package com.screenshot.ai.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.screenshot.ai.data.crypto.CryptoRepositoryImpl
import com.screenshot.ai.data.model.CustomModelConfig
import com.screenshot.ai.data.repo.CustomModelRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * 自定义模型仓库实现。
 *
 * - 配置字段存共享 DataStore（[appDataStore]），写入走原子 edit；
 * - API Key 走 [CryptoRepositoryImpl] 的 providerId = "custom"，
 *   与内置 Provider 的加密存储策略完全一致。
 */
class CustomModelRepositoryImpl(
    context: Context,
) : CustomModelRepository {
    private val appContext = context.applicationContext

    /** 加密存储：自定义模型固定使用 "custom" 这个 providerId 命名空间 */
    private val crypto = CryptoRepositoryImpl(appContext)

    private object Keys {
        val DISPLAY_NAME = stringPreferencesKey("custom_display_name")
        val BASE_URL = stringPreferencesKey("custom_base_url")
        val MODEL_ID = stringPreferencesKey("custom_model_id")
        val SUPPORTS_VISION = booleanPreferencesKey("custom_supports_vision")
    }

    @Volatile
    private var cached: CustomModelConfig = CustomModelConfig()

    override fun observe(): Flow<CustomModelConfig> =
        appContext.appDataStore.data.map { p ->
            CustomModelConfig(
                displayName = p[Keys.DISPLAY_NAME].orEmpty(),
                baseUrl = p[Keys.BASE_URL].orEmpty(),
                modelId = p[Keys.MODEL_ID].orEmpty(),
                supportsVision = p[Keys.SUPPORTS_VISION] ?: false,
            )
        }

    override suspend fun current(): CustomModelConfig = observe().first()

    override suspend fun save(config: CustomModelConfig) {
        val normalized =
            CustomModelConfig(
                displayName = config.normalizedDisplayName(),
                baseUrl = config.normalizedBaseUrl(),
                modelId = config.normalizedModelId(),
                supportsVision = config.supportsVision,
            )
        appContext.appDataStore.edit { p ->
            p[Keys.DISPLAY_NAME] = normalized.displayName
            p[Keys.BASE_URL] = normalized.baseUrl
            p[Keys.MODEL_ID] = normalized.modelId
            p[Keys.SUPPORTS_VISION] = normalized.supportsVision
        }
        updateCache(normalized)
    }

    override fun cachedConfig(): CustomModelConfig = cached

    override fun updateCache(config: CustomModelConfig) {
        cached = config
    }

    override fun apiKey(): String = crypto.getApiKey(CUSTOM_PROVIDER_ID)

    override fun setApiKey(key: String) {
        crypto.setApiKey(CUSTOM_PROVIDER_ID, key.trim())
    }

    companion object {
        /** 自定义模型的 Provider id：与内置 Provider 共用同一套命名空间 */
        const val CUSTOM_PROVIDER_ID = "custom"
    }
}
