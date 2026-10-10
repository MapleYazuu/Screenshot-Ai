package com.screenshot.ai.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 应用更新检查（基于 GitHub Releases）。
 *
 * 设计取舍：
 * - 只依赖 GitHub 公开 API，不需要自建服务端；
 * - 失败时直接抛异常，由「关于」页 catch 后展示 about_check_fail，
 *   这样调用方一句 try/catch 就能同时处理网络错误、404（还没有 Release）与解析错误；
 * - 版本比较是纯函数，可完全用 JVM 单测覆盖，不需要网络。
 *
 * @throws IOException 网络不可达、HTTP 非 2xx（例如仓库还没有任何 Release 时返回 404）
 * @throws org.json.JSONException 返回体不是合法 JSON
 */
object UpdateChecker {
    /** GitHub 仓库所有者 */
    const val GITHUB_OWNER = "MapleYazuu"

    /** GitHub 仓库名 */
    const val GITHUB_REPO = "Screenshot-AI"

    /** 最新 Release 接口地址 */
    const val RELEASES_API =
        "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"

    /** 应用当前版本（与 BuildConfig 解耦，便于单测与灰度） */
    data class ReleaseInfo(
        val version: String,
        val tagName: String,
        val body: String,
        val htmlUrl: String,
        val apkUrl: String?,
    )

    /** 检查结果 */
    data class UpdateResult(
        val hasUpdate: Boolean,
        val latest: ReleaseInfo,
        val currentVersion: String,
    )

    /**
     * 检查是否有新版本。
     *
     * 不做异常包装：GitHub 不可达、被限流（403）、仓库还没有 Release（404）、
     * 返回体不是合法 JSON 都会原样抛出，调用方据此提示用户「检查更新失败」。
     *
     * @param currentVersion 当前版本名，例如 BuildConfig.VERSION_NAME
     * @throws IOException 网络或 HTTP 状态异常
     * @throws org.json.JSONException 返回体不是合法 JSON
     */
    suspend fun check(currentVersion: String): UpdateResult =
        withContext(Dispatchers.IO) {
            val request =
                Request
                    .Builder()
                    .url(RELEASES_API)
                    .header("User-Agent", userAgent())
                    // GitHub API 推荐显式声明接受的媒体类型，同时避免中间代理返回旧格式
                    .header("Accept", "application/vnd.github+json")
                    .get()
                    .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("更新检查失败: HTTP ${response.code}")
                }
                val payload =
                    response.body?.string()
                        ?: throw IOException("更新检查失败: 响应体为空")
                val latest = parseRelease(payload)
                UpdateResult(
                    hasUpdate = compareVersions(latest.version, currentVersion) > 0,
                    latest = latest,
                    currentVersion = currentVersion,
                )
            }
        }

    /**
     * 解析 GitHub Release 返回体（internal 以便单测直接喂 JSON）。
     *
     * 只取我们真正需要的字段，多余字段一律忽略，避免接口加字段就打挂。
     */
    internal fun parseRelease(json: String): ReleaseInfo {
        val root = JSONObject(json)
        val tagName = root.getString("tag_name")
        return ReleaseInfo(
            // tag 习惯写成 v1.2.3，对外暴露的 version 去掉前缀，方便直接展示与比较
            version = normalizeVersion(tagName),
            tagName = tagName,
            body = root.optString("body", ""),
            htmlUrl = root.optString("html_url", ""),
            apkUrl = firstApkUrl(root),
        )
    }

    /**
     * 取第一个 .apk 资产的下载地址。
     *
     * Release 里通常还会带源码 zip、校验和等资产，只有 .apk 才是可安装产物；
     * 找不到时返回 null，UI 退化为「打开 Release 页面」。
     */
    private fun firstApkUrl(root: JSONObject): String? {
        val assets = root.optJSONArray("assets") ?: return null
        for (index in 0 until assets.length()) {
            val asset = assets.optJSONObject(index) ?: continue
            val name = asset.optString("name", "")
            if (name.endsWith(".apk", ignoreCase = true)) {
                val url = asset.optString("browser_download_url", "")
                if (url.isNotEmpty()) {
                    return url
                }
            }
        }
        return null
    }

    /**
     * 版本号比较（纯函数，必须可测）。
     *
     * 算法：
     * 1. 去掉前导 'v'/'V'（tag 与 BuildConfig 两种写法混用）；
     * 2. 以第一个 '-' 或 '+' 切成「数字段」与「后缀段」，后缀段即 prerelease/build 标记；
     * 3. 数字段按 '.' 切分，逐段转 Int 做数值比较（不能用字符串比较，
     *    否则 "1.10.0" 会被判定小于 "1.9.9"）；缺失的段补 0（"2.0" == "2.0.0"）；
     * 4. 数字完全相同的情况下：没有后缀的正式版更大（1.0.0 > 1.0.0-beta），
     *    都有后缀则按字符串比较（1.0.0-alpha < 1.0.0-beta）；
     * 5. 出现非数字段（如 "1.0.x"）时退化为该段的字符串比较，保证不抛异常。
     *
     * @return a > b 返回正数，a < b 返回负数，相等返回 0
     */
    fun compareVersions(
        a: String,
        b: String,
    ): Int {
        val (aCore, aSuffix) = splitVersion(a)
        val (bCore, bSuffix) = splitVersion(b)

        val coreResult = compareNumericSegments(aCore, bCore)
        if (coreResult != 0) {
            return coreResult
        }
        // 数字部分相同，再比较 prerelease 后缀
        return when {
            aSuffix.isEmpty() && bSuffix.isEmpty() -> 0
            // 正式版永远大于同号的预发布版
            aSuffix.isEmpty() -> 1
            bSuffix.isEmpty() -> -1
            else -> aSuffix.compareTo(bSuffix)
        }
    }

    /** GitHub API 要求带 User-Agent，否则返回 403 */
    fun userAgent(): String = USER_AGENT

    /**
     * 拆版本号：返回 (数字段列表, 后缀原文)。
     *
     * 后缀保留原始比较语义（不做语义化解析），因为这里只需要区分「正式版 / 预发布版」。
     */
    private fun splitVersion(raw: String): Pair<List<String>, String> {
        val trimmed = raw.trim().removePrefix("v").removePrefix("V")
        // 兼容 1.0.0+build 与 1.0.0-beta.1 两种后缀写法
        val cutIndex = trimmed.indexOfAny(charArrayOf('-', '+'))
        val core = if (cutIndex >= 0) trimmed.substring(0, cutIndex) else trimmed
        val suffix = if (cutIndex >= 0) trimmed.substring(cutIndex + 1) else ""
        return core.split('.') to suffix
    }

    /** 逐段数值比较，缺失段补 0；某段不是数字时退化为字符串比较 */
    private fun compareNumericSegments(
        a: List<String>,
        b: List<String>,
    ): Int {
        val size = maxOf(a.size, b.size)
        for (index in 0 until size) {
            val aSegment = a.getOrElse(index) { "0" }.trim()
            val bSegment = b.getOrElse(index) { "0" }.trim()

            val aNumber = aSegment.toIntOrNull()
            val bNumber = bSegment.toIntOrNull()
            if (aNumber == null || bNumber == null) {
                // 非法段（如 "x"）不应导致崩溃，退化为字符串比较
                val stringResult = aSegment.compareTo(bSegment)
                if (stringResult != 0) {
                    return if (stringResult > 0) 1 else -1
                }
                continue
            }
            if (aNumber != bNumber) {
                return if (aNumber > bNumber) 1 else -1
            }
        }
        return 0
    }

    /** 去掉 v 前缀，供展示使用 */
    private fun normalizeVersion(tag: String): String = tag.trim().removePrefix("v").removePrefix("V")

    /** GitHub API 要求的 User-Agent 标识 */
    private const val USER_AGENT = "ScreenshotAI-Android"

    /**
     * 复用的 HTTP 客户端。
     *
     * 单独设置短超时：更新检查是「用户点了才发生」的轻量请求，
     * 让用户干等半分钟体验很差，失败也比长等待好。
     */
    private val client: OkHttpClient by lazy {
        OkHttpClient
            .Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
