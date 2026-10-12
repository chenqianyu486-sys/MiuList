package com.example.data.source.zju

import com.example.data.calendar.ZjuCalendarAdapter
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * 真联网冒烟测试：只回答「现在这套网络能不能跑通爬取链路」，不做业务逻辑断言。
 *
 * 默认**不跑**（[liveNet] 关着），因为：
 * · 它依赖外网与浙大服务是否在线，CI 上必然时好时坏；
 * · CAS 有失败锁号的风险，不该在每次单测里被真实账号触发。
 *
 * 调试爬取 / 校历时手动开：
 *   gradlew :app:testDebugUnitTest --tests "*ZjuNetworkSmokeTest*" -Pmiulist.liveNet=1
 *
 * 代理由 app/build.gradle.kts 从 https_proxy 注入到 test JVM，本机直连不通的域名
 * （例如 raw.githubusercontent.com）要靠它出去。
 */
class ZjuNetworkSmokeTest {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    @Before
    fun requireLiveNetwork() {
        assumeTrue(
            "未开启真联网测试：加 -Pmiulist.liveNet=1",
            System.getProperty("miulist.liveNet") == "1"
        )
    }

    /**
     * 公共校历：不需要账号，先确认这条链是通的。
     *
     * 断言刻意查到字段级：上游把 camelCase 和 PascalCase 混着用，DTO 少写一个
     * [@Json] 就会解析出「条数对、字段全 null」的空壳，只断言非空根本发现不了。
     */
    @Test
    fun fetchPublicCalendar() = runBlocking {
        val bundle = ZjuCalendarSource(client).fetch()
        println("lastUpdated=${bundle.lastUpdated} terms=${bundle.termConfigs.size}")
        bundle.termConfigs.forEach { println("  ${it.year} term=${it.term} ${it.begin}..${it.end}") }
        bundle.tweaks.forEach { println("  tweak ${it.description} ${it.from}..${it.to}") }
        assertTrue("校历里一个学期都没有", bundle.termConfigs.isNotEmpty())
        assertTrue("作息时间表没填上", bundle.periods.isNotEmpty())

        val first = bundle.termConfigs.first()
        assertTrue("学期年份解析不到，上游字段名变了", !first.year.isNullOrBlank())
        assertTrue("学期序号解析不到，上游字段名变了", first.term != null)
        assertTrue("起始日期解析不到，上游字段名变了", first.begin != null)
        assertTrue("结束日期解析不到，上游字段名变了", first.end != null)
        assertTrue(
            "起止日期转不成 epochDay",
            ZjuCalendarAdapter.yyyymmddToEpochDay(first.begin) != null &&
                ZjuCalendarAdapter.yyyymmddToEpochDay(first.end) != null
        )
        assertTrue(
            "调休/停课日解析不到，上游字段名变了",
            bundle.tweaks.isEmpty() || !bundle.tweaks.first().description.isNullOrBlank()
        )
    }

    /** CAS 登录页 + 公钥接口：爬课表的第一步，页面结构一变这里先炸。 */
    @Test
    fun casLoginPageAndPubKey() {
        val page = client.newCall(
            Request.Builder()
                .url("https://zjuam.zju.edu.cn/cas/login?service=http%3A%2F%2Fappservice.zju.edu.cn%2F")
                .get()
                .build()
        ).execute().use { it.body?.string().orEmpty() }
        assertTrue("登录页里找不到 execution，页面结构变了", page.contains("execution"))

        val pubKey = client.newCall(
            Request.Builder().url("https://zjuam.zju.edu.cn/cas/v2/getPubKey").get().build()
        ).execute().use { it.body?.string().orEmpty() }
        println("pubKey=$pubKey")
        assertTrue("公钥里没有 modulus", pubKey.contains("modulus"))
        assertTrue("公钥里没有 exponent", pubKey.contains("exponent"))
    }

    /** 课表接口本身：HTTP 明文，只验证「连得上」，不携带登录态。 */
    @Test
    fun scheduleEndpointReachable() {
        val response = client.newCall(
            Request.Builder()
                .url("http://appservice.zju.edu.cn/zju-smartcampus/zdydjw/api/kbdy_cxXsZKbxx")
                .get()
                .build()
        ).execute()
        println("schedule endpoint -> HTTP ${response.code}")
        response.close()
        assertTrue("课表接口连不上", response.code > 0)
    }
}
