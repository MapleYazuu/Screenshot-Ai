package com.screenshot.ai.data.crypto

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * API Key 安全存储实现（EncryptedSharedPreferences + Android Keystore）。
 *
 * 键名约定（与 Provider 一一对应，互不覆盖）：
 * - `api_key_<providerId>`：该 Provider 的 API Key；
 * - `base_url_<providerId>`：用户自定义的 Base URL。
 *
 * 健壮性设计（重点）：
 * 主密钥由 Keystore 持有且永不导出。设备恢复出厂设置、刷机、或用户在系统里清除凭据后，
 * Keystore 中的密钥会被销毁，而 shared_prefs 里仍是旧的密文——此时
 * EncryptedSharedPreferences.create() 甚至单次 getString() 都会抛异常
 * （GeneralSecurityException / IOException / InvalidProtocolBufferException）。
 * 若不做处理，用户会遭遇「一启动就闪退、且清数据才能恢复」的恶性体验。
 * 因此这里对所有访问统一走 [guarded]：捕获异常 → 记录中文日志 → 删除损坏的偏好文件
 * → 重建一次；仍然失败就按「未配置」降级返回，绝不把异常抛给启动流程。
 */
class CryptoRepositoryImpl(
    context: Context,
) : CryptoRepository {
    /** 只持有 Application Context，避免设置页销毁后泄漏 Activity */
    private val appContext: Context = context.applicationContext

    /** 初始化锁：Keystore 首次访问较慢，避免多线程同时初始化出两份实例 */
    private val initLock = Any()

    /** 已初始化的偏好实例；重建时会被整体替换，因此需要 volatile 保证可见性 */
    @Volatile
    private var cached: SharedPreferences? = null

    override fun getApiKey(providerId: String): String =
        guarded("") { store -> store.getString(apiKeyKey(providerId), "").orEmpty() }

    override fun setApiKey(
        providerId: String,
        apiKey: String,
    ) {
        // 接口约定：空字符串表示清除。这里顺带把「只输入了空格」也当成清除，
        // 否则存储里会留下一个空白 Key，让 hasApiKey() 误判为已配置。
        if (apiKey.isBlank()) {
            clearApiKey(providerId)
            return
        }
        // trim 的原因：用户从网页复制 Key 常带首尾换行/空格，原样发出会被服务端判为无效 Key，
        // 而报错信息只会显示「鉴权失败」，排查成本很高。
        putString(apiKeyKey(providerId), apiKey.trim())
    }

    override fun clearApiKey(providerId: String) {
        remove(apiKeyKey(providerId))
    }

    override fun getBaseUrl(providerId: String): String =
        guarded("") { store -> store.getString(baseUrlKey(providerId), "").orEmpty() }

    override fun setBaseUrl(
        providerId: String,
        baseUrl: String,
    ) {
        // 接口约定：空字符串表示恢复默认地址，因此删除条目而不是写入空值
        if (baseUrl.isBlank()) {
            remove(baseUrlKey(providerId))
            return
        }
        putString(baseUrlKey(providerId), baseUrl.trim())
    }

    /**
     * 统一的容错入口。
     *
     * @param fallback 自愈失败时返回的兜底值，保证调用方（网络层/设置页）永远拿到可用数据
     * @param block    真正的读写动作；可能抛出解密相关异常
     */
    private fun <T> guarded(
        fallback: T,
        block: (SharedPreferences) -> T,
    ): T {
        return try {
            block(prefs())
        } catch (first: Exception) {
            // 走到这里通常是主密钥失效（同一次失败会连续出现，日志容易定位）。
            // 先尝试删除损坏文件并重建，再重试一次；仍失败则降级，绝不向上抛。
            Log.w(TAG, "加密偏好访问失败，疑似主密钥失效，尝试重建后重试", first)
            if (!recreate()) {
                return fallback
            }
            try {
                block(prefs())
            } catch (second: Exception) {
                Log.e(TAG, "重建加密偏好后仍无法访问，本次按未配置处理", second)
                fallback
            }
        }
    }

    /** 双重检查锁获取实例：只有第一次访问才会触碰 Keystore */
    private fun prefs(): SharedPreferences {
        cached?.let { return it }
        synchronized(initLock) {
            cached?.let { return it }
            val created = openOrRecreate()
            cached = created
            return created
        }
    }

    /** 首次创建；失败时删除损坏的偏好文件再建一次（仍失败则由 [guarded] 兜底） */
    private fun openOrRecreate(): SharedPreferences =
        try {
            createEncrypted()
        } catch (e: Exception) {
            // 典型场景：设备恢复出厂 / 换 ROM / 用户清除凭据后 Keystore 主密钥已被销毁，
            // 旧密文无法解密。此时旧文件已无任何价值，删除并重建，让用户重新填写 Key。
            Log.w(TAG, "加密偏好初始化失败，删除损坏文件后重建", e)
            appContext.deleteSharedPreferences(FILE_NAME)
            createEncrypted()
        }

    /** 清空缓存、删除文件并重建；返回是否成功（内部消化异常，避免崩溃传染给调用方） */
    private fun recreate(): Boolean {
        cached = null
        return try {
            appContext.deleteSharedPreferences(FILE_NAME)
            cached = createEncrypted()
            true
        } catch (e: Exception) {
            Log.e(TAG, "重建加密偏好失败，本次按未配置处理", e)
            false
        }
    }

    private fun createEncrypted(): SharedPreferences {
        val masterKey =
            MasterKey
                .Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
        return EncryptedSharedPreferences.create(
            appContext,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /**
     * 同步写入。
     *
     * 用 commit() 而不是 apply()：设置页保存后常会立刻发起「测试连接」，
     * apply() 的异步落盘存在读到旧值的极小窗口，会让用户看到莫名其妙的鉴权失败；
     * 这里数据量极小，同步写入的耗时可忽略。commit() 返回 false 表示写盘失败，
     * 只记日志不抛异常（磁盘满属于环境问题，不应让设置页崩溃）。
     */
    @SuppressLint("ApplySharedPref")
    private fun putString(
        key: String,
        value: String,
    ) {
        guarded(Unit) { store ->
            if (!store.edit().putString(key, value).commit()) {
                Log.w(TAG, "加密偏好写入失败（commit 返回 false），可能是磁盘空间不足")
            }
        }
    }

    /** 删除键本身而不是写入空串：残留空值条目会让「是否已配置」的判断变复杂 */
    @SuppressLint("ApplySharedPref")
    private fun remove(key: String) {
        guarded(Unit) { store ->
            if (!store.edit().remove(key).commit()) {
                Log.w(TAG, "加密偏好删除失败（commit 返回 false）")
            }
        }
    }

    private fun apiKeyKey(providerId: String): String = KEY_PREFIX_API_KEY + providerId

    private fun baseUrlKey(providerId: String): String = KEY_PREFIX_BASE_URL + providerId

    companion object {
        private const val TAG = "CryptoRepository"

        /** 加密偏好文件名（shared_prefs 下的实际文件名） */
        private const val FILE_NAME = "screenshot_ai_secrets"

        private const val KEY_PREFIX_API_KEY = "api_key_"

        private const val KEY_PREFIX_BASE_URL = "base_url_"
    }
}
