package com.screenshot.ai.data.crypto

/**
 * API Key 安全存储。
 *
 * 安全策略（详见 docs/api-key-security.md）：
 * 1. 使用 Android Keystore 生成 / 保存主密钥（MasterKey），密钥永不离开 TEE/StrongBox；
 * 2. 使用 EncryptedSharedPreferences 以 AES256-GCM 加密文件内容；
 * 3. 每个 Provider 的 Key 独立存储，互不覆盖；
 * 4. 应用不申请存储权限，密钥不会随备份上传（manifest 中已禁用备份）。
 */
interface CryptoRepository {
    /** 读取某 Provider 的 API Key，未设置时返回空字符串 */
    fun getApiKey(providerId: String): String

    /** 保存某 Provider 的 API Key（空字符串表示清除） */
    fun setApiKey(
        providerId: String,
        apiKey: String,
    )

    /** 清除某 Provider 的 API Key */
    fun clearApiKey(providerId: String)

    /** 读取用户自定义的 Base URL，未设置返回空字符串（表示使用默认地址） */
    fun getBaseUrl(providerId: String): String

    /** 保存自定义 Base URL（空字符串表示恢复默认） */
    fun setBaseUrl(
        providerId: String,
        baseUrl: String,
    )

    /** 是否已配置 Key */
    fun hasApiKey(providerId: String): Boolean = getApiKey(providerId).isNotBlank()
}
