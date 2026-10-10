package com.screenshot.ai.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 配置读写仓库。
 *
 * 全部配置以 Flow 暴露，UI 侧 collectAsStateWithLifecycle 即可自动刷新；
 * 写入操作使用 DataStore 的原子 edit，避免并发覆盖。
 * DataStore 单例统一由 [appDataStore] 提供（见 AppDataStore.kt）。
 */
class SettingsRepository(
    private val context: Context,
) {
    private object Keys {
        val PROVIDER_ID = stringPreferencesKey("provider_id")
        val MODEL_ID = stringPreferencesKey("model_id")
        val DEFAULT_PRESET_ID = stringPreferencesKey("default_preset_id")
        val FLOATING_ENABLED = booleanPreferencesKey("floating_enabled")
        val FLOATING_ALPHA = floatPreferencesKey("floating_alpha")
        val FLOATING_X = intPreferencesKey("floating_x")
        val FLOATING_Y = intPreferencesKey("floating_y")
        val SNAP_TO_EDGE = booleanPreferencesKey("snap_to_edge")
        val LOW_QUALITY = booleanPreferencesKey("low_quality")
        val AUTO_START = booleanPreferencesKey("auto_start_on_boot")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val LANGUAGE = stringPreferencesKey("language_tag")
        val PREVIEW_PINNED = booleanPreferencesKey("preview_pinned")
        val PREVIEW_X = intPreferencesKey("preview_x")
        val PREVIEW_Y = intPreferencesKey("preview_y")
        val RESULT_PINNED = booleanPreferencesKey("result_pinned")
        val RESULT_X = intPreferencesKey("result_x")
        val RESULT_Y = intPreferencesKey("result_y")
        val HISTORY_X = intPreferencesKey("history_x")
        val HISTORY_Y = intPreferencesKey("history_y")
        val PREVIEW_W = intPreferencesKey("preview_w")
        val PREVIEW_H = intPreferencesKey("preview_h")
        val RESULT_W = intPreferencesKey("result_w")
        val RESULT_H = intPreferencesKey("result_h")
    }

    /** 全部配置流 */
    val settings: Flow<AppSettings> =
        context.appDataStore.data.map { p ->
            val defaults = AppSettings()
            AppSettings(
                providerId = p[Keys.PROVIDER_ID] ?: defaults.providerId,
                modelId = p[Keys.MODEL_ID] ?: defaults.modelId,
                defaultPresetId = p[Keys.DEFAULT_PRESET_ID] ?: defaults.defaultPresetId,
                floatingEnabled = p[Keys.FLOATING_ENABLED] ?: defaults.floatingEnabled,
                floatingAlpha = p[Keys.FLOATING_ALPHA] ?: defaults.floatingAlpha,
                floatingX = p[Keys.FLOATING_X] ?: defaults.floatingX,
                floatingY = p[Keys.FLOATING_Y] ?: defaults.floatingY,
                snapToEdge = p[Keys.SNAP_TO_EDGE] ?: defaults.snapToEdge,
                lowQuality = p[Keys.LOW_QUALITY] ?: defaults.lowQuality,
                autoStartOnBoot = p[Keys.AUTO_START] ?: defaults.autoStartOnBoot,
                themeMode = p[Keys.THEME_MODE] ?: defaults.themeMode,
                languageTag = p[Keys.LANGUAGE] ?: defaults.languageTag,
                previewPinned = p[Keys.PREVIEW_PINNED] ?: defaults.previewPinned,
                previewX = p[Keys.PREVIEW_X] ?: defaults.previewX,
                previewY = p[Keys.PREVIEW_Y] ?: defaults.previewY,
                resultPinned = p[Keys.RESULT_PINNED] ?: defaults.resultPinned,
                resultX = p[Keys.RESULT_X] ?: defaults.resultX,
                resultY = p[Keys.RESULT_Y] ?: defaults.resultY,
                historyX = p[Keys.HISTORY_X] ?: defaults.historyX,
                historyY = p[Keys.HISTORY_Y] ?: defaults.historyY,
                previewW = p[Keys.PREVIEW_W] ?: defaults.previewW,
                previewH = p[Keys.PREVIEW_H] ?: defaults.previewH,
                resultW = p[Keys.RESULT_W] ?: defaults.resultW,
                resultH = p[Keys.RESULT_H] ?: defaults.resultH,
            )
        }

    /** 切换 Provider，同时把模型切到该 Provider 的默认模型，避免出现「模型与 Provider 不匹配」 */
    suspend fun setProvider(
        providerId: String,
        modelId: String,
    ) {
        context.appDataStore.edit { p ->
            p[Keys.PROVIDER_ID] = providerId
            p[Keys.MODEL_ID] = modelId
        }
    }

    /** 仅切换模型 */
    suspend fun setModel(modelId: String) {
        context.appDataStore.edit { p -> p[Keys.MODEL_ID] = modelId }
    }

    /** 设置默认预设指令 */
    suspend fun setDefaultPreset(presetId: String) {
        context.appDataStore.edit { p -> p[Keys.DEFAULT_PRESET_ID] = presetId }
    }

    /** 悬浮工具栏开关 */
    suspend fun setFloatingEnabled(enabled: Boolean) {
        context.appDataStore.edit { p -> p[Keys.FLOATING_ENABLED] = enabled }
    }

    /** 悬浮球透明度（0.3 ~ 1.0） */
    suspend fun setFloatingAlpha(alpha: Float) {
        context.appDataStore.edit { p -> p[Keys.FLOATING_ALPHA] = alpha.coerceIn(0.3f, 1f) }
    }

    /** 记忆悬浮球位置 */
    suspend fun setFloatingPosition(
        x: Int,
        y: Int,
    ) {
        context.appDataStore.edit { p ->
            p[Keys.FLOATING_X] = x
            p[Keys.FLOATING_Y] = y
        }
    }

    /** 截图预览窗口固定状态 */
    suspend fun setPreviewPinned(pinned: Boolean) {
        context.appDataStore.edit { p -> p[Keys.PREVIEW_PINNED] = pinned }
    }

    /** 截图预览窗口最后位置（拖动/固定时写入，下次打开沿用） */
    suspend fun setPreviewPosition(
        x: Int,
        y: Int,
    ) {
        context.appDataStore.edit { p ->
            p[Keys.PREVIEW_X] = x
            p[Keys.PREVIEW_Y] = y
        }
    }

    /** AI 回复独立卡片固定状态 */
    suspend fun setResultPinned(pinned: Boolean) {
        context.appDataStore.edit { p -> p[Keys.RESULT_PINNED] = pinned }
    }

    /** AI 回复独立卡片最后位置 */
    suspend fun setResultPosition(
        x: Int,
        y: Int,
    ) {
        context.appDataStore.edit { p ->
            p[Keys.RESULT_X] = x
            p[Keys.RESULT_Y] = y
        }
    }

    /** 历史/选择指令窗口最后位置 */
    suspend fun setHistoryPosition(
        x: Int,
        y: Int,
    ) {
        context.appDataStore.edit { p ->
            p[Keys.HISTORY_X] = x
            p[Keys.HISTORY_Y] = y
        }
    }

    /** 贴边自动半隐藏 */
    suspend fun setSnapToEdge(snap: Boolean) {
        context.appDataStore.edit { p -> p[Keys.SNAP_TO_EDGE] = snap }
    }

    /** 低质量模式（512px 压缩） */
    suspend fun setLowQuality(low: Boolean) {
        context.appDataStore.edit { p -> p[Keys.LOW_QUALITY] = low }
    }

    /** 开机自启 */
    suspend fun setAutoStart(enabled: Boolean) {
        context.appDataStore.edit { p -> p[Keys.AUTO_START] = enabled }
    }

    /** 主题模式 */
    suspend fun setThemeMode(mode: String) {
        context.appDataStore.edit { p -> p[Keys.THEME_MODE] = mode }
    }

    /** 语言 */
    suspend fun setLanguage(tag: String) {
        context.appDataStore.edit { p -> p[Keys.LANGUAGE] = tag }
    }

    /** 截图预览窗口尺寸（px；-1 = 未调整过、使用默认） */
    suspend fun setPreviewSize(
        width: Int,
        height: Int,
    ) {
        context.appDataStore.edit { p ->
            p[Keys.PREVIEW_W] = width
            p[Keys.PREVIEW_H] = height
        }
    }

    /** AI 回复窗口尺寸 */
    suspend fun setResultSize(
        width: Int,
        height: Int,
    ) {
        context.appDataStore.edit { p ->
            p[Keys.RESULT_W] = width
            p[Keys.RESULT_H] = height
        }
    }}
