package com.example.data.source.zju

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ZjuCoursesClientTest {

    @Test
    fun cookieJar_domainAndPathMatching_isolatesHostSpecificCookies() {
        val jar = CoursesInMemoryCookieJar()
        val now = System.currentTimeMillis()

        val ssoCookie = Cookie.Builder()
            .name("iPlanetDirectoryPro")
            .value("sso_val_123")
            .domain("zju.edu.cn")
            .path("/")
            .expiresAt(now + 3600_000L)
            .build()

        val coursesCookie = Cookie.Builder()
            .name("session")
            .value("session_abc")
            .domain("courses.zju.edu.cn")
            .path("/")
            .expiresAt(now + 3600_000L)
            .build()

        val identityCookie = Cookie.Builder()
            .name("AUTH_SESSION_ID")
            .value("auth_session_xyz")
            .domain("identity.zju.edu.cn")
            .path("/auth/realms/zju/")
            .expiresAt(now + 3600_000L)
            .build()

        jar.addCookie(ssoCookie)
        jar.addCookie(coursesCookie)
        jar.addCookie(identityCookie)

        // 1. courses.zju.edu.cn/api/todos 应该匹配 ssoCookie 和 coursesCookie，但不应包含 identityCookie
        val coursesUrl = "https://courses.zju.edu.cn/api/todos".toHttpUrl()
        val cookiesForCourses = jar.loadForRequest(coursesUrl)
        val namesForCourses = cookiesForCourses.map { it.name }.toSet()
        assertTrue(namesForCourses.contains("iPlanetDirectoryPro"))
        assertTrue(namesForCourses.contains("session"))
        assertFalse(namesForCourses.contains("AUTH_SESSION_ID"))

        // 2. identity.zju.edu.cn/auth/realms/zju/login 应该包含 ssoCookie 和 identityCookie，但不包含 coursesCookie
        val identityUrl = "https://identity.zju.edu.cn/auth/realms/zju/login".toHttpUrl()
        val cookiesForIdentity = jar.loadForRequest(identityUrl)
        val namesForIdentity = cookiesForIdentity.map { it.name }.toSet()
        assertTrue(namesForIdentity.contains("iPlanetDirectoryPro"))
        assertTrue(namesForIdentity.contains("AUTH_SESSION_ID"))
        assertFalse(namesForIdentity.contains("session"))
    }

    @Test
    fun cookieJar_expiredCookie_isFilteredOut() {
        val jar = CoursesInMemoryCookieJar()
        val now = System.currentTimeMillis()

        val expiredCookie = Cookie.Builder()
            .name("iPlanetDirectoryPro")
            .value("old_sso")
            .domain("zju.edu.cn")
            .path("/")
            .expiresAt(now - 1000L)
            .build()

        jar.addCookie(expiredCookie)
        assertNull(jar.findCookie("iPlanetDirectoryPro"))

        val url = "https://courses.zju.edu.cn/user/index".toHttpUrl()
        assertTrue(jar.loadForRequest(url).isEmpty())
    }

    @Test
    fun loginPageParser_errorDivReason_extractsErrorText() {
        val parser = LoginPageParser()
        val html = """
            <!DOCTYPE html>
            <html>
            <body>
                <div class="error text-left" id="errormsg" role="heading">
                    <span id="msg">用户名或密码错误</span>
                </div>
            </body>
            </html>
        """.trimIndent()

        val reason = parser.errorDivReason(html)
        assertEquals("用户名或密码错误", reason)
    }

    @Test
    fun loginPageParser_execution_extractsToken() {
        val parser = LoginPageParser()
        val html = """
            <form id="loginForm">
                <input type="hidden" name="execution" value="e1s1_token_value" />
                <input type="hidden" name="_eventId" value="submit" />
            </form>
        """.trimIndent()

        val execution = parser.execution(html)
        assertEquals("e1s1_token_value", execution)
    }
}
