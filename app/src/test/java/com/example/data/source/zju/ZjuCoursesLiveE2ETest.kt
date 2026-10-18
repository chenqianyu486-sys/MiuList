package com.example.data.source.zju

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * **学在浙大待办的真联网端到端测试**：CAS 密码登录 → 换 TGC → 走完整条 SSO 链
 * → 调 `/api/todos` → 解析成 [CoursesTodoItem]，也就是 App 里点「刷新学在浙大」执行的那条路径。
 *
 * ## 为什么需要它
 *
 * 前面那些测试全都能离线跑，因为它们验证的是**规则**；而这条链真正会坏的地方
 * 是「规则和现网对不上」——上游改了跳转形状、换了身份源、换了字段口径。
 * 2026-10-18 就是靠无凭据探针才发现学在浙大已经改走 Keycloak
 * （`identity.zju.edu.cn/auth/realms/zju`，client_id=TronClass）。
 *
 * 无凭据探针能回答「链路长什么样」，回答不了「我这份凭据能不能走通」。
 * 后者只有真跑一次才知道。
 *
 * ## 凭据不进版本库
 *
 * 学号与密码只从环境变量读，本文件里没有任何字面量：
 * ```
 * $env:MIULIST_ZJU_STUDENT_ID = "..."
 * $env:MIULIST_ZJU_PASSWORD   = "..."
 * ```
 * 开关或凭据缺一个就整类跳过，所以别人 clone 下来直接 `test` 也是绿的。
 *
 * ## 怎么跑
 *
 * ```
 * $env:MIULIST_LIVE_NET        = "1"
 * $env:MIULIST_ZJU_STUDENT_ID = "你的学号"
 * $env:MIULIST_ZJU_PASSWORD   = "你的密码"
 * .\gradlew.bat :app:testDebugUnitTest "-Pmiulist.liveNet=1" "--tests=*ZjuCoursesLiveE2ETest*"
 * ```
 *
 * ⚠️ CAS 有失败次数限制、连续失败会锁号 —— 确认密码没打错再跑。
 */
class ZjuCoursesLiveE2ETest {

    private val studentId: String? = System.getenv("MIULIST_ZJU_STUDENT_ID")
    private val password: String? = System.getenv("MIULIST_ZJU_PASSWORD")

    @Before
    fun requireLiveNetworkAndCredentials() {
        assumeTrue("未开启真联网测试：加 -Pmiulist.liveNet=1", System.getProperty("miulist.liveNet") == "1")
        assumeTrue("未提供学号：设 MIULIST_ZJU_STUDENT_ID", !studentId.isNullOrBlank())
        assumeTrue("未提供密码：设 MIULIST_ZJU_PASSWORD", !password.isNullOrBlank())
    }

    @Test
    fun fetchTodosThroughFullSsoChain() = runBlocking {
        val client = ZjuCoursesClient()

        val todos = try {
            client.fetchTodos(studentId!!, password!!)
        } catch (e: ZjuException) {
            println("[courses-e2e] 失败分类：${e.kind}")
            // 失败消息里带着逐跳轨迹 —— 卡在第几跳就是问题定位的全部。
            println("[courses-e2e] ${e.message}")
            throw AssertionError(
                "学在浙大待办拉取失败（${e.kind}）：${e.message}",
                e
            )
        }

        println("[courses-e2e] 成功，共 ${todos.size} 条")
        todos.take(30).forEach {
            println("[courses-e2e] · ${it.courseName} / ${it.title} / ${it.dueFormatted} / ${it.daysRemainingText}")
        }

        // 「一条都没有」是合法状态（真的全交清了），所以这里**不**断言非空。
        // 认证失败已经在上面被转成 AssertionError 了 —— 那才是这条链真正要防的退化。
        todos.forEach {
            assertTrue("课程名为空：$it", it.courseName.isNotBlank())
            assertTrue("标题为空：$it", it.title.isNotBlank())
            assertTrue("截止时间展示缺失：$it", it.dueFormatted.isNotBlank())
        }

        val withDeadline = todos.filter { it.dueTimestamp != null }
        assertTrue(
            "有截止时间的条目没有按时间升序排列",
            withDeadline.zipWithNext().all { (a, b) -> a.dueTimestamp!! <= b.dueTimestamp!! }
        )
    }
}
