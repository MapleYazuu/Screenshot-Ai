package com.screenshot.ai.data.prefs

import com.screenshot.ai.ai.AiModel
import com.screenshot.ai.ai.AiProvider
import com.screenshot.ai.ai.AiProviderRegistry

/**
 * 应用全局配置。
 *
 * 存放在 DataStore 中；API Key 不放这里，而是单独走 [com.screenshot.ai.data.crypto.CryptoRepository]
 * 加密存储，避免明文密钥进入普通偏好文件。
 *
 * @param providerId        当前选中的 Provider
 * @param modelId           当前选中的模型
 * @param defaultPresetId   默认预设指令 id
 * @param floatingEnabled   悬浮工具栏是否开启（用于服务被回收后开关回弹）
 * @param floatingAlpha     悬浮球透明度 0.3 ~ 1.0
 * @param floatingX         悬浮球 x 坐标（px，-1 表示未初始化）
 * @param floatingY         悬浮球 y 坐标（px，-1 表示未初始化）
 * @param snapToEdge        是否贴边自动半隐藏
 * @param lowQuality        低质量模式：压缩到 512px，节省 token
 * @param autoStartOnBoot   开机自启
 * @param themeMode         主题模式：system / light / dark
 * @param languageTag       语言：system / zh / en
 * @param previewPinned     截图预览窗口是否固定（固定后不可拖动）
 * @param previewX          截图预览窗口最后位置 x（px，-1 表示未保存过、使用居中）
 * @param previewY          截图预览窗口最后位置 y（px，-1 表示未保存过、使用居中）
 * @param resultPinned      AI 回复窗口是否固定
 * @param resultX           AI 回复窗口最后位置 x（px，-1 表示未保存过）
 * @param resultY           AI 回复窗口最后位置 y（px，-1 表示未保存过）
 * @param historyX          历史/选择指令窗口最后位置 x（px，-1 表示未保存过、使用居中）
 * @param historyY          历史/选择指令窗口最后位置 y（px，-1 表示未保存过、使用居中）
 */
data class AppSettings(
    val providerId: String = AiProviderRegistry.default.id,
    val modelId: String = AiProviderRegistry.default.defaultModel?.id ?: "deepseek-chat",
    val defaultPresetId: String = "",
    val floatingEnabled: Boolean = false,
    val floatingAlpha: Float = 1.0f,
    val floatingX: Int = -1,
    val floatingY: Int = -1,
    val snapToEdge: Boolean = true,
    val lowQuality: Boolean = false,
    val autoStartOnBoot: Boolean = false,
    val themeMode: String = THEME_SYSTEM,
    val languageTag: String = LANGUAGE_SYSTEM,
    val previewPinned: Boolean = false,
    val previewX: Int = -1,
    val previewY: Int = -1,
    val resultPinned: Boolean = false,
    val resultX: Int = -1,
    val resultY: Int = -1,

    val historyX: Int = -1,
    val historyY: Int = -1,
    val previewW: Int = -1,
    val previewH: Int = -1,
    val resultW: Int = -1,
    val resultH: Int = -1,
) {
    /** 当前选中的 Provider 对象 */
    val provider: AiProvider get() = AiProviderRegistry.findOrDefault(providerId)

    /** 当前选中的模型对象 */
    val model: AiModel
        get() =
            provider.findModel(modelId)
                ?: provider.defaultModel
                ?: AiModel(modelId, modelId, supportsVision = false)

    companion object {
        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"

        const val LANGUAGE_SYSTEM = "system"

        /** 受支持的界面语言码；新增语言时同步更新 res/values-<code>/ */
        val SUPPORTED_LANGUAGE_TAGS = listOf("zh", "en", "ja", "ko", "ru")
    }
}
