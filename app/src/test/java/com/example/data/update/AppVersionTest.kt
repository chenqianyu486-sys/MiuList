package com.example.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 版本号的解析与比较。
 *
 * 这些用例钉住的都是**用户看得见的后果**：
 * 版本比错了，用户明明是旧版却被告知「已是最新」，或者反过来被催着去更新。
 */
class AppVersionTest {

    @Test
    fun `认得带 v 前缀的版本号`() {
        val version = parseAppVersion("v1.2.0")
        assertEquals(listOf(1, 2, 0), version?.parts)
        assertNull(version?.prerelease)
    }

    @Test
    fun `认得不带 v 的版本号`() {
        assertEquals(listOf(1, 1, 0), parseAppVersion("1.1.0")?.parts)
    }

    @Test
    fun `认不出的时候返回 null 而不是瞎猜`() {
        assertNull(parseAppVersion(""))
        assertNull(parseAppVersion("nightly"))
        assertNull(parseAppVersion("1.2.x"))
        assertNull(parseAppVersion("v"))
    }

    @Test
    fun `预发布标记单独拎出来`() {
        val version = parseAppVersion("1.2.0-beta")
        assertEquals(listOf(1, 2, 0), version?.parts)
        assertEquals("beta", version?.prerelease)
    }

    @Test
    fun `同样的版本就是同样的版本`() {
        assertEquals(0, compareAppVersions("1.1.0", "1.1.0"))
        assertEquals(0, compareAppVersions("v1.1.0", "1.1.0"))
    }

    @Test
    fun `段数不齐时缺的段当 0`() {
        assertEquals(0, compareAppVersions("1.2", "1.2.0"))
        assertEquals(0, compareAppVersions("1", "1.0.0"))
    }

    @Test
    fun `新的版本比旧的大`() {
        assertTrue(compareAppVersions("1.1.0", "1.2.0")!! < 0)
        assertTrue(compareAppVersions("1.1.0", "1.1.1")!! < 0)
        assertTrue(compareAppVersions("1.9.0", "1.10.0")!! < 0)
    }

    @Test
    fun `版本号按数字比而不是按字符串比`() {
        // 字符串比的话 "1.10.0" < "1.9.0"（第二个字符 '1' < '9'），
        // 于是到了第十版，App 会把旧版当成新的。
        assertTrue(compareAppVersions("1.9.0", "1.10.0")!! < 0)
    }

    @Test
    fun `正式版比同号的预发布版新`() {
        assertTrue(compareAppVersions("1.2.0-beta", "1.2.0")!! < 0)
        assertTrue(compareAppVersions("1.2.0", "1.2.0-beta")!! > 0)
    }

    @Test
    fun `有一边读不出来就说读不出来`() {
        assertNull(compareAppVersions("nightly", "1.2.0"))
        assertNull(compareAppVersions("1.1.0", "nightly"))
    }
}
