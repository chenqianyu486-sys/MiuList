package com.example.data.source.zju

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * CAS 登录 + 课表抓取的真联网探针，**不依赖公共校历配置**。
 *
 * 存在的理由：完整链路里 [ZjuCalendarSource] 走的是 GitHub 系镜像，
 * 那条路在本机网络环境下未必通。这个用例把校历摘出去，单独回答
 * 「教务网这一侧（登录 + 取课表 + 映射）到底通不通」——
 * 两件事要能分开定位，否则一个镜像挂了会把整个后端的问题搅在一起。
 *
 * 这个类还负责**把会话状态打印出来**。之前卡在「登录成功但课表接口 504」，
 * 而登录接口在失败时会静默返回（只报 200），光看异常文案根本判断不出
 * 到底是「没登上」还是「登上了但接口认不出这个会话」。所以这里显式打印：
 * 最终地址、每个 Cookie 的名字与域名、以及课表请求实际带上了哪些 Cookie。
 *
 * 只打 Cookie 的**名字**，不打印值 —— TGC 是会话令牌，不该进日志。
 *
 * 凭据同样只从环境变量读，见 [ZjuLiveEndToEndTest] 的说明。
 */
class ZjuCasLiveProbeTest {

    private val studentId: String? = System.getenv("MIULIST_ZJU_STUDENT_ID")
    private val password: String? = System.getenv("MIULIST_ZJU_PASSWORD")

    @Before
    fun requireLiveNetworkAndCredentials() {
        assumeTrue("未开启真联网测试：设 MIULIST_LIVE_NET=1", System.getProperty("miulist.liveNet") == "1")
        assumeTrue("未提供学号：设 MIULIST_ZJU_STUDENT_ID", !studentId.isNullOrBlank())
        assumeTrue("未提供密码：设 MIULIST_ZJU_PASSWORD", !password.isNullOrBlank())
    }

    @Test
    fun loginAndFetchRawSchedule() = runBlocking {
        // 只打印方法/地址/状态与 Cookie 名字，不打印任何 Cookie 的值。
        val logger = Interceptor { chain ->
            val req = chain.request()
            val names = req.headers("Cookie")
                .flatMap { it.split(';') }
                .mapNotNull { it.substringBefore('=').trim().takeIf { n -> n.isNotEmpty() } }
            val resp = chain.proceed(req)
            println("[probe] ${req.method} ${req.url} -> ${resp.code}  cookies=[${names.joinToString(",")}]")
            resp
        }

        // 用生产默认客户端（内含同一个 InMemoryCookieJar），只额外挂一个观察用的
        // interceptor；CookieJar 必须复用生产的那个，否则会话传不下去、
        // 观察到的现象就成了探针自己造出来的。
        val cas = ZjuCasClient(
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .cookieJar(InMemoryCookieJar())
                .addInterceptor(logger)
                .build()
        )

        try {
            cas.login(studentId!!, password!!)
        } catch (e: ZjuException) {
            println("[probe] 登录失败：${e.message}")
            throw e
        }
        println("[probe] CAS 登录成功（最终地址已离开 /cas/login）")

        // 2026-2027 秋冬的第一段 = 教务网 xqm 查询串 "1|秋"。
        // 注意 zdbk 这条接口**不传学号**：它靠 CAS 会话识别学生。
        val raw = cas.fetchSchedule("2026-2027", ZjuTerms.queryOf(0))
        println("[probe] 课表接口返回 ${raw.length} 字节")
        println("[probe] 原文前 600 字：${raw.take(600)}")
        assertTrue("课表接口返回空", raw.isNotBlank())
        assertTrue("返回的不是 JSON", raw.trimStart().startsWith("{"))

        val service = ZjuImportService()
        val rows = service.parseRows(raw)
        println("[probe] 解析出 ${rows.size} 条 kbList")
        rows.take(30).forEach {
            println(
                "[probe]   课号=${it.xkkh} kcb=${it.kcb} " +
                    "星期=${it.xqj}(${it.xqj?.javaClass?.simpleName}) 起始节=${it.djj} 连上=${it.skcd} " +
                    "单双周=${it.dsz} 学期=${it.xxq} 确认=${it.sfqd}"
            )
        }
        assertTrue("kbList 一条都没解析出来", rows.isNotEmpty())

        val mapped = ZjuScheduleMapper.toCourses(rows, totalWeeks = 16, termCode = 0)
        println("[probe] 映射出 ${mapped.courses.size} 门课，跳过 ${mapped.skipped} 条")
        mapped.skipReason?.let { println("[probe] 跳过原因：$it") }
        mapped.courses.forEach {
            println("[probe] · ${it.name} ${it.slots.size}个时段")
            it.slots.forEach { s ->
                println("[probe]     周${s.dayOfWeek} ${s.startPeriod}-${s.endPeriod}节 @${s.classroom} weeks=${s.weeks}")
            }
        }
        assertTrue("映射后没有课程", mapped.courses.isNotEmpty())
    }
}