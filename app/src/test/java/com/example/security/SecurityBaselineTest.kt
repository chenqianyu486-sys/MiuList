package com.example.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 清单与资源里的**安全基线**。
 *
 * ## 为什么要有这一层
 *
 * 前面那些改动（凭据用 KeyStore、备份排除凭据、禁止明文）都有一个共同点：
 * 它们是**声明式**的 —— 写在 XML 里，靠「没人去改它」维持。
 *
 * 而这类声明最常见的失效方式不是被人改坏，而是**被人顺手加一行**：
 * - 为了让某个接口能用，把 `cleartextTrafficPermitted="true"` 加回去；
 * - 为了让别的 App 能调某个组件，加一个 `android:exported="true"`；
 * - 备份规则改了格式，凭据那份 exclude 悄悄没了。
 *
 * 这些改动都能编译通过、App 照跑，**没有任何测试会红**。
 * 所以这里把它们钉成断言：一旦有人动到这条线，测试立刻拦下。
 */
class SecurityBaselineTest {

    // ------------------------------------------------------------------ 工具

    /** 从模块目录往上找资源文件，兼容不同的测试工作目录。 */
    private fun locate(relativePath: String): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relativePath)
            if (candidate.exists()) return candidate
            dir = dir.parentFile
        }
        throw AssertionError("找不到资源文件：$relativePath")
    }

    private fun readText(relativePath: String): String = locate(relativePath).readText()

    private fun parse(relativePath: String): Element =
        DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(locate(relativePath))
            .documentElement

    private fun Element.childElements(tag: String): List<Element> {
        val out = mutableListOf<Element>()
        val nodes = getElementsByTagName(tag)
        for (i in 0 until nodes.length) out += nodes.item(i) as Element
        return out
    }

    // ------------------------------------------------------- 明文传输：全禁

    @Test
    fun `网络配置不允许任何明文流量`() {
        val xml = readText("src/main/res/xml/network_security_config.xml")

        assertFalse(
            "出现了 cleartextTrafficPermitted=\"true\"：本 App 的链路全部走 HTTPS，" +
                "真要接只支持 HTTP 的第三方接口，先想清楚能不能走网关。",
            xml.contains("cleartextTrafficPermitted=\"true\"")
        )
        assertTrue(
            "应当显式声明全局禁明文",
            xml.contains("cleartextTrafficPermitted=\"false\"")
        )
    }

    @Test
    fun `清单也显式禁用明文流量`() {
        val manifest = readText("src/main/AndroidManifest.xml")

        assertTrue(
            "application 上应当有 android:usesCleartextTraffic=\"false\"（纵深防御）",
            manifest.contains("android:usesCleartextTraffic=\"false\"")
        )
    }

    // --------------------------------------------------------- 导出组件

    @Test
    fun `只有启动器是导出组件`() {
        val manifest = parse("src/main/AndroidManifest.xml")

        val exported = mutableListOf<Element>()
        for (tag in listOf("activity", "service", "receiver", "provider")) {
            for (element in manifest.childElements(tag)) {
                if (element.getAttribute("android:exported") == "true") exported += element
            }
        }

        assertEquals(
            "导出组件应当只有启动器那一个；多出来的任何一个都是能被外部调起的入口",
            1,
            exported.size
        )

        val launcher = exported.single()
        val hasLauncher = launcher.getElementsByTagName("category").let { categories ->
            (0 until categories.length).any {
                (categories.item(it) as Element).getAttribute("android:name") ==
                    "android.intent.category.LAUNCHER"
            }
        }
        assertTrue("导出组件必须是启动器", hasLauncher)
    }

    @Test
    fun `清单只申请联网相关的两个权限`() {
        val manifest = parse("src/main/AndroidManifest.xml")

        val permissions = manifest.childElements("uses-permission")
            .map { it.getAttribute("android:name") }
            .sorted()

        assertEquals(
            "权限只应包含联网与读网络状态；多一个都意味着多一份被索取的权限",
            listOf(
                "android.permission.ACCESS_NETWORK_STATE",
                "android.permission.INTERNET"
            ),
            permissions
        )
    }

    @Test
    fun `发布构建不允许调试`() {
        val manifest = readText("src/main/AndroidManifest.xml")

        assertFalse(
            "清单里不能出现 android:debuggable=\"true\"",
            manifest.contains("android:debuggable=\"true\"")
        )
    }

    // ------------------------------------------------------------- 备份

    @Test
    fun `凭据文件永远不进云备份`() {
        for (path in listOf(
            "src/main/res/xml/backup_rules.xml",
            "src/main/res/xml/data_extraction_rules.xml"
        )) {
            val xml = readText(path)
            assertTrue(
                "$path 必须排除凭据文件",
                xml.contains("miulist_secure_credentials.xml")
            )
        }
    }

    /**
     * 课表、偏好、文件**全部**不进系统备份与设备迁移。
     *
     * 私人目录里装的是课表、考场座位、待办与作业标题、用户挑的壁纸照片 ——
     * 用户没有一条是打算公开的。而 Auto Backup 是**默认开启**的：
     * 不显式排除，这些数据就会跟着 Google 账号走，换机时还会自动搬过去。
     *
     * 排除之后，跨设备只剩 App 自带的「导出快照 / 导入快照」这一条路，
     * 这是有意的取舍（见两个规则文件里的注释）。
     */
    @Test
    fun `课表偏好文件都不进系统备份`() {
        for (path in listOf(
            "src/main/res/xml/backup_rules.xml",
            "src/main/res/xml/data_extraction_rules.xml"
        )) {
            val excludedDomains = parse(path).childElements("exclude")
                .map { it.getAttribute("domain") }
                .toSet()

            for (domain in listOf("root", "file", "database", "sharedpref", "external")) {
                assertTrue(
                    "$path 必须排除 $domain 域（路径用 \".\" 表示整个目录）",
                    domain in excludedDomains
                )
            }
        }
    }
}
