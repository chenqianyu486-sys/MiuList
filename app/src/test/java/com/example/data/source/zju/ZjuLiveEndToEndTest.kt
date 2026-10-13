package com.example.data.source.zju

import com.example.data.calendar.ZjuCalendarAdapter
import com.example.data.codec.DecodeResult
import com.example.data.codec.JsonScheduleCodec
import com.example.data.codec.ScheduleBundle
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * **真联网端到端测试**：CAS 登录 → 取课表 → 映射 → 编码成 v2 文档，一条龙走完
 * [ZjuImportService.fetchScheduleJson]，也就是 App 里点「获取课表」实际执行的那条路径。
 *
 * ## 凭据不进版本库
 *
 * 学号与密码只从环境变量读，测试文件里没有任何字面量：
 * ```
 * $env:MIULIST_ZJU_STUDENT_ID = "..."
 * $env:MIULIST_ZJU_PASSWORD   = "..."
 * ```
 * 两个开关缺一个就整类跳过，所以别人 clone 下来直接 `test` 也是绿的，
 * 不需要也不可能从源码里拿到任何人的凭据。
 *
 * ## 为什么默认关闭
 *
 * 这是**唯一**一个会用真实账号反复敲 CAS 登录的用例。CAS 有失败次数限制、
 * 连续失败会锁号，所以它必须显式开：
 * ```
 * $env:MIULIST_LIVE_NET = "1"
 * gradlew :app:testDebugUnitTest --tests "*ZjuLiveEndToEndTest*"
 * ```
 *
 * 注意它在 `test`（JVM）源集里而不是 `androidTest`：宿主 JVM 直连，
 * 能跑完整的 CAS → zdbk 会话链（zdbk 是 HTTPS，不再依赖任何明文 HTTP 的特殊放行）。
 */
class ZjuLiveEndToEndTest {

    private val studentId: String? = System.getenv("MIULIST_ZJU_STUDENT_ID")
    private val password: String? = System.getenv("MIULIST_ZJU_PASSWORD")

    @Before
    fun requireLiveNetworkAndCredentials() {        assumeTrue("未开启真联网测试：设 MIULIST_LIVE_NET=1", System.getProperty("miulist.liveNet") == "1")
        assumeTrue("未提供学号：设 MIULIST_ZJU_STUDENT_ID", !studentId.isNullOrBlank())
        assumeTrue("未提供密码：设 MIULIST_ZJU_PASSWORD", !password.isNullOrBlank())
    }

    /**
     * 读仓库里的内置兜底校历。
     *
     * 单元测试的工作目录是 `app/`，所以从那里往上找 `src/main/assets`。
     * 读不到就跳过整个用例而不是失败 —— 校历文件缺失是环境问题，
     * 不该被记成「爬课表功能有问题」。
     */
    private fun bundledFallbackJson(): String? {
        val candidates = listOf(
            java.io.File("src/main/assets", ZjuCalendarSource.FALLBACK_ASSET),
            java.io.File("../src/main/assets", ZjuCalendarSource.FALLBACK_ASSET)
        )
        val found = candidates.firstOrNull { it.isFile }
        // assumeTrue 返回 Unit 而不是它的第二个参数，所以不能写成
        // `?: assumeTrue(..., false)` —— 那样整个函数会推成 Any。
        assumeTrue("找不到内置兜底校历 ${ZjuCalendarSource.FALLBACK_ASSET}", found != null)
        return found!!.readText(Charsets.UTF_8)
    }

    @Test
    fun fetchScheduleAndProduceImportableV2Document() = runBlocking {
        // 校历源必须显式注入内置兜底文本：这条测试跑在 JVM 源集里，没有
        // Android 的 Context，拿不到 `Context.assets`；而联网的三个 config.json
        // 源（GitHub / jsDelivr / gh-proxy）在开发机网络下一律连不上。
        //
        // App 真实运行时走的是 `ZjuImportService(cas, ZjuCalendarSource(assets = ctx.assets))`，
        // 那条路另有冒烟测试覆盖，这里只验证「拿到校历之后」的编排与编码。
        val service = ZjuImportService(
            calendarSource = ZjuCalendarSource(bundledText = bundledFallbackJson())
        )

        // 先确认公共校历能拉到，并定位到目标学期。
        // 秋冬学期 = 浙大的 term 0（秋冬连读），对应教务网的 "1|秋"。
        val terms = service.availableTerms()
        assertTrue("校历里一个学期都没有", terms.isNotEmpty())

        val target = terms.firstOrNull { it.year == "2026-2027" && it.term == 0 }
        assertTrue(
            "校历里没有 2026-2027 秋冬学期，实际有：" +
                terms.joinToString(", ") { it.label },
            target != null
        )

        println("[e2e] 目标学期：${target!!.label}")
        println("[e2e] 全部可选学期：${terms.joinToString(" | ") { it.label }}")

        // 走完整的生产路径。
        val json = service.fetchScheduleJson(
            studentId = studentId!!,
            password = password!!,
            option = target
        )

        assertTrue("产出的 v2 文档是空的", json.isNotBlank())

        // 产出的必须是能被自己的解码器读回去的合法 v2 文档 —— 这是「端到端」的关键：
        // 只要这里能 round-trip，说明接下来 App 里的解码 → 校验 → 预览 → 落库
        // 拿到的是与手工文件导入完全同形的数据。
        val decoded = JsonScheduleCodec().decode(json)
        val bundle: ScheduleBundle = when (decoded) {
            is DecodeResult.Success -> decoded.bundle
            is DecodeResult.Failure -> throw AssertionError("产出的文档连自己的解码器都读不回去：${decoded.message}")
        }

        assertEquals("来源标记不是 zju", "zju", bundle.provenance.source)

        val timetable = requireNotNull(bundle.timetable) { "文档里没有 timetable 块" }
        val termRef = requireNotNull(timetable.termRef) { "timetable 里没有 termRef" }
        assertEquals("学期年份对不上", "2026-2027", termRef.year)
        assertEquals("学期序号对不上", 0, termRef.term)

        val courses = timetable.courses
        assertTrue("一门课都没抓到", courses.isNotEmpty())

        val slots = courses.sumOf { it.slots.size }
        assertTrue("抓到了课但一个时段都没有", slots > 0)

        // 每个时段都得能定位到「星期几 + 起止节次」，否则画到网格上是空的。
        courses.forEach { c ->
            c.slots.forEach { s ->
                assertTrue("${c.name} 的时段缺星期几", s.dayOfWeek != null)
                assertTrue("${c.name} 的时段缺起始节次", s.startPeriod != null)
                assertTrue("${c.name} 的时段缺结束节次", s.endPeriod != null)
                assertTrue("${c.name} 的时段起止颠倒", s.startPeriod!! <= s.endPeriod!!)
            }
        }

        // 校历那几块也要真的有内容，否则导入后会画出空的网格。
        val calendar = requireNotNull(bundle.calendar) { "文档里没有 calendar 块" }
        assertTrue("校历里没有学期配置", calendar.termConfigs.isNotEmpty())
        assertTrue("作息表是空的", calendar.periods.isNotEmpty())

        println("[e2e] ===== 抓取成功 =====")
        println("[e2e] 课程 ${courses.size} 门 / 时段 $slots 条 / 作息 ${calendar.periods.size} 节")
        calendar.termConfigs.forEach {
            val begin = ZjuCalendarAdapter.yyyymmddToEpochDay(it.begin)
            val end = ZjuCalendarAdapter.yyyymmddToEpochDay(it.end)
            println("[e2e] 学期 ${it.year}:${it.term} 起止 $begin..$end 首周=${it.firstWeekNo}")
        }
        courses.forEach { c ->
            println(
                "[e2e] · ${c.name} (${c.code}) ${c.teacher} " +
                    "共${c.slots.size}个时段 " +
                    c.slots.joinToString("; ") { s ->
                        "周${s.dayOfWeek} 第${s.startPeriod}-${s.endPeriod}节@${s.classroom}" +
                            " 周次=${s.weeks ?: "每周"}"
                    }
            )
        }
    }
}