package com.screenshot.ai.util

import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [UpdateChecker] 单元测试。
 *
 * 只覆盖纯逻辑（版本比较 + Release 解析），不发起真实网络请求：
 * 更新检查依赖 GitHub 可用性，测试必须离线可跑、结果确定。
 */
class UpdateCheckerTest {
    // ---------------- compareVersions ----------------

    @Test
    fun `v前缀被忽略`() {
        assertEquals(0, UpdateChecker.compareVersions("1.0.0", "v1.0.0"))
        assertEquals(0, UpdateChecker.compareVersions("v1.0.0", "1.0.0"))
        assertEquals(0, UpdateChecker.compareVersions("V2.3.1", "v2.3.1"))
    }

    @Test
    fun `相同版本返回0`() {
        assertEquals(0, UpdateChecker.compareVersions("1.0.0", "1.0.0"))
        assertEquals(0, UpdateChecker.compareVersions("2.0", "2.0.0"))
    }

    @Test
    fun `按数值而非字符串比较段`() {
        // 字符串比较会认为 "1.10.0" < "1.9.9"，这是最典型的版本比较 bug
        assertTrue(
            "1.10.0 必须大于 1.9.9",
            UpdateChecker.compareVersions("1.10.0", "1.9.9") > 0,
        )
        assertTrue(
            "1.9.9 必须小于 1.10.0",
            UpdateChecker.compareVersions("1.9.9", "1.10.0") < 0,
        )
    }

    @Test
    fun `段数不同时缺失段按0处理`() {
        assertTrue("2.0 > 1.9.9", UpdateChecker.compareVersions("2.0", "1.9.9") > 0)
        assertTrue("1.0.1 > 1.0", UpdateChecker.compareVersions("1.0.1", "1.0") > 0)
        assertTrue("1.0 < 1.0.1", UpdateChecker.compareVersions("1.0", "1.0.1") < 0)
        assertEquals(0, UpdateChecker.compareVersions("1.0.0.0", "1.0"))
    }

    @Test
    fun `主次版本相同时比较后续段`() {
        assertTrue(UpdateChecker.compareVersions("1.0.10", "1.0.9") > 0)
        assertTrue(UpdateChecker.compareVersions("1.0.9", "1.0.10") < 0)
    }

    @Test
    fun `预发布版本小于同号正式版`() {
        assertTrue(
            "1.0.0-beta 应小于正式版 1.0.0",
            UpdateChecker.compareVersions("1.0.0-beta", "1.0.0") < 0,
        )
        assertTrue(
            "正式版 1.0.0 应大于 1.0.0-beta",
            UpdateChecker.compareVersions("1.0.0", "1.0.0-beta") > 0,
        )
        assertTrue(
            "1.2.3-rc.1 应小于 1.2.3",
            UpdateChecker.compareVersions("v1.2.3-rc.1", "1.2.3") < 0,
        )
    }

    @Test
    fun `数字部分不同时先比数字`() {
        // 预发布标记不能盖过数字段的差异：1.0.1-alpha 依然大于 1.0.0
        assertTrue(
            "1.0.1-alpha > 1.0.0",
            UpdateChecker.compareVersions("1.0.1-alpha", "1.0.0") > 0,
        )
    }

    @Test
    fun `同级预发布版本按后缀字符串比较`() {
        assertTrue(
            "1.0.0-alpha < 1.0.0-beta",
            UpdateChecker.compareVersions("1.0.0-alpha", "1.0.0-beta") < 0,
        )
        assertEquals(0, UpdateChecker.compareVersions("1.0.0-beta", "1.0.0-beta"))
    }

    @Test
    fun `比较满足反对称性`() {
        val versions = listOf("1.0.0", "v1.0.0", "1.10.0", "1.9.9", "2.0", "1.0.0-beta")
        for (left in versions) {
            for (right in versions) {
                val result = UpdateChecker.compareVersions(left, right)
                val reversed = UpdateChecker.compareVersions(right, left)
                assertEquals(
                    "$left 与 $right 的比较结果应互为相反数",
                    Integer.signum(result),
                    -Integer.signum(reversed),
                )
            }
        }
    }

    @Test
    fun `垃圾输入不抛异常`() {
        val garbage = listOf("", " ", "abc", "v", "1..2", "1.0.x", "-beta", "1.0.0-", "...")
        for (left in garbage) {
            for (right in garbage) {
                // 只要不抛异常即可，返回值语义不作保证
                UpdateChecker.compareVersions(left, right)
            }
        }
        // 正常的版本号永远大于空串
        assertTrue(UpdateChecker.compareVersions("1.0.0", "") > 0)
    }

    // ---------------- 常量与请求头 ----------------

    @Test
    fun `API地址与仓库常量一致`() {
        assertEquals("MapleYazuu", UpdateChecker.GITHUB_OWNER)
        assertEquals("Screenshot-AI", UpdateChecker.GITHUB_REPO)
        assertEquals(
            "https://api.github.com/repos/MapleYazuu/Screenshot-AI/releases/latest",
            UpdateChecker.RELEASES_API,
        )
    }

    @Test
    fun `UserAgent非空且不含空格`() {
        // GitHub API 对缺失或非法 User-Agent 直接返回 403
        val userAgent = UpdateChecker.userAgent()
        assertTrue("User-Agent 不能为空", userAgent.isNotBlank())
        assertTrue("User-Agent 不能含空格", !userAgent.contains(" "))
    }

    // ---------------- parseRelease ----------------

    @Test
    fun `解析Release字段并去掉tag前缀`() {
        val json =
            """
            {
              "tag_name": "v1.2.0",
              "name": "1.2.0",
              "body": "修复若干问题",
              "html_url": "https://github.com/MapleYazuu/Screenshot-AI/releases/tag/v1.2.0",
              "assets": [
                {
                  "name": "source.zip",
                  "browser_download_url": "https://example.com/source.zip"
                },
                {
                  "name": "app-release.apk",
                  "browser_download_url": "https://example.com/app-release.apk"
                }
              ]
            }
            """.trimIndent()

        val info = UpdateChecker.parseRelease(json)
        assertEquals("1.2.0", info.version)
        assertEquals("v1.2.0", info.tagName)
        assertEquals("修复若干问题", info.body)
        assertTrue(info.htmlUrl.endsWith("v1.2.0"))
        assertEquals("必须选中第一个 apk 资产", "https://example.com/app-release.apk", info.apkUrl)
    }

    @Test
    fun `没有apk资产时apkUrl为null`() {
        val json =
            """
            {
              "tag_name": "1.3.0",
              "body": "",
              "html_url": "https://example.com",
              "assets": [
                { "name": "checksums.txt", "browser_download_url": "https://example.com/a.txt" }
              ]
            }
            """.trimIndent()

        val info = UpdateChecker.parseRelease(json)
        assertEquals("无 v 前缀时原样保留", "1.3.0", info.version)
        assertNull(info.apkUrl)
    }

    @Test
    fun `缺少assets或body字段时使用默认值`() {
        val info = UpdateChecker.parseRelease("""{"tag_name":"v1.0.1","html_url":"https://example.com"}""")
        assertEquals("1.0.1", info.version)
        assertEquals("", info.body)
        assertNull(info.apkUrl)
        assertNotNull(info.htmlUrl)
    }

    @Test
    fun `缺少tag_name时抛JSONException`() {
        // 调用方（关于页）依赖异常来展示「检查更新失败」
        assertThrows(JSONException::class.java) {
            UpdateChecker.parseRelease("""{"html_url":"https://example.com"}""")
        }
    }

    @Test
    fun `非JSON响应抛JSONException`() {
        assertThrows(JSONException::class.java) {
            UpdateChecker.parseRelease("<html>404 Not Found</html>")
        }
    }

    // ---------------- UpdateResult 语义 ----------------

    @Test
    fun `UpdateResult按版本比较决定hasUpdate`() {
        val latest =
            UpdateChecker.ReleaseInfo(
                version = "1.1.0",
                tagName = "v1.1.0",
                body = "",
                htmlUrl = "https://example.com",
                apkUrl = null,
            )
        val hasUpdate =
            UpdateChecker.UpdateResult(
                hasUpdate = UpdateChecker.compareVersions(latest.version, "1.0.0") > 0,
                latest = latest,
                currentVersion = "1.0.0",
            )
        assertTrue(hasUpdate.hasUpdate)

        val noUpdate =
            UpdateChecker.UpdateResult(
                hasUpdate = UpdateChecker.compareVersions(latest.version, "1.1.0") > 0,
                latest = latest,
                currentVersion = "1.1.0",
            )
        assertEquals(false, noUpdate.hasUpdate)
    }
}
