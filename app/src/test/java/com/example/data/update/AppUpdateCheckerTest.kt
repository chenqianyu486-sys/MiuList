package com.example.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 检查更新的判定与取号。
 *
 * 这里钉的是**三条不能搞错的界线**：
 *
 * - 有新版 → 告诉用户有；
 * - 没查成 → **绝不能**说成「已是最新」；
 * - 版本号读不出来 → 宁可说读不出来，也不猜一个。
 */
class AppUpdateCheckerTest {

    @Test
    fun `远端更新就是有新版`() {
        val result = decide("1.1.0", "v1.2.0")
        assertTrue(result is AppUpdateChecker.Result.UpdateAvailable)
        assertEquals("1.2.0", (result as AppUpdateChecker.Result.UpdateAvailable).latestVersion)
    }

    @Test
    fun `版本一样就是已是最新`() {
        val result = decide("1.1.0", "v1.1.0")
        assertTrue(result is AppUpdateChecker.Result.UpToDate)
        assertEquals("1.1.0", (result as AppUpdateChecker.Result.UpToDate).latestVersion)
    }

    @Test
    fun `本地比线上还新时也算已是最新`() {
        // 装了开发版的人看到「有新版」只会莫名其妙。
        val result = decide("1.2.0", "v1.1.0")
        assertTrue(result is AppUpdateChecker.Result.UpToDate)
    }

    @Test
    fun `版本号读不出来就是失败 不是已是最新`() {
        val result = decide("1.1.0", "nightly")
        assertTrue(result is AppUpdateChecker.Result.Failed)
    }

    @Test
    fun `当前版本号读不出来也是失败`() {
        val result = decide("nightly", "v1.2.0")
        assertTrue(result is AppUpdateChecker.Result.Failed)
    }

    @Test
    fun `从版本清单里取版本号`() {
        assertEquals("1.2.0", parseManifestVersion("""{"version":"1.2.0"}"""))
    }

    @Test
    fun `清单里没有版本号就取不到`() {
        assertNull(parseManifestVersion("""{"foo":1}"""))
        assertNull(parseManifestVersion("not json"))
    }

    @Test
    fun `从发布页里认出版本号`() {
        // 页面上的几处版本号：下载链接、版本徽标、事实列表。
        val html = """
            <a href="https://github.com/x/MiuList/releases/download/v1.2.0/MiuList-v1.2.0.apk">下载</a>
            <span class="version-badge">v1.2.0 · 正式版</span>
        """.trimIndent()
        assertEquals("1.2.0", extractVersionFromPage(html))
    }

    @Test
    fun `页面上认不出版本号就返回 null`() {
        assertNull(extractVersionFromPage("<html><body>你好</body></html>"))
    }
}
