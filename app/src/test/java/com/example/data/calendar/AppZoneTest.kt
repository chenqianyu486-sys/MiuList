package com.example.data.calendar

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Calendar
import java.util.TimeZone

/**
 * 「全 App 统一北京时间」的守门测试。
 *
 * ## 为什么必须真的改 JVM 默认时区来测
 *
 * 只在默认时区已经是 Asia/Shanghai 的机器上跑，这些断言永远是绿的 ——
 * 而一个**用系统时区**的实现（`Calendar.getInstance()` / `LocalDate.now()` 无参）
 * 在北京用户手机上同样永远是绿的。绿灯证明不了任何事。
 *
 * 所以这里在 `@Before` 里把 `TimeZone.setDefault()` 真的改成 GMT、
 * `ZoneId.systemDefault()` 跟着变，然后断言 AppZone 的一切**不受影响**。
 * 这才是把「北京用户看到的没变」这句话变成可执行断言的方式。
 *
 * 测完必须还原（`@After`）：默认时区是 JVM 全局状态，漏还原会污染同一进程里的
 * 其它测试，让它们的失败原因指向一个毫不相干的地方。
 */
class AppZoneTest {

    private var originalZone: TimeZone = TimeZone.getDefault()

    @Before
    fun forceDeviceIntoUtc() {
        originalZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("GMT"))
    }

    @After
    fun restoreDeviceZone() {
        TimeZone.setDefault(originalZone)
    }

    /** 确认前提成立：这个测试必须真的跑在非北京时区下，否则它什么也没验。 */
    @Test
    fun deviceZoneIsActuallyUtc() {
        assertEquals("GMT", TimeZone.getDefault().id)
        assertEquals(
            "AppZone 必须固定 Asia/Shanghai，不能读系统时区",
            "Asia/Shanghai",
            AppZone.ZONE.id
        )
    }

    /**
     * 同一个 epochMillis，无论设备在哪，翻译出的墙上时间都必须是北京那一版。
     *
     * 2026-10-19 20:00 北京 = 12:00 UTC。设备时区若生效，UTC 机器会读出「12:00」，
     * 这就是用户在错误时区上会看到的界面。
     */
    @Test
    fun calendarReadsBeijingWallClockRegardlessOfDeviceZone() {
        val beijing8pm = LocalDate.of(2026, 10, 19)
            .atTime(20, 0)
            .atZone(AppZone.ZONE)
            .toInstant()
            .toEpochMilli()

        val cal = AppZone.calendar(beijing8pm)
        assertEquals(2026, cal.get(Calendar.YEAR))
        assertEquals(Calendar.OCTOBER, cal.get(Calendar.MONTH))
        assertEquals(19, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(20, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, cal.get(Calendar.MINUTE))
    }

    /** 同一件事的 java.time 那一侧。两种 API 给的是同一个答案，否则界面自己会打架。 */
    @Test
    fun formatUsesBeijingWallClockRegardlessOfDeviceZone() {
        val beijing8pm = LocalDate.of(2026, 10, 19)
            .atTime(20, 0)
            .atZone(AppZone.ZONE)
            .toInstant()
            .toEpochMilli()

        assertEquals("20:00", AppZone.format("HH:mm", beijing8pm))
        assertEquals("2026-10-19", AppZone.format("yyyy-MM-dd", beijing8pm))
    }

    /**
     * 「北京 00:30」这个最阴的时刻。
     *
     * 北京当天 00:30 = UTC 前一天 16:30。设备时区若生效，此刻会被认成**前一天**，
     * 于是「今天的课」整块消失，一键生成把昨天的课当成本周预习。
     * 这是整套改造要解决的主要症状，钉在测试里。
     */
    @Test
    fun beijingHalfPastMidnightIsNotYesterday() {
        val beijing0030 = LocalDate.of(2026, 10, 19)
            .atTime(0, 30)
            .atZone(AppZone.ZONE)
            .toInstant()
            .toEpochMilli()

        val oct19 = LocalDate.of(2026, 10, 19).toEpochDay()

        assertEquals(
            "北京 00:30 必须算作 10/19，而不是 10/18",
            oct19,
            Instant.ofEpochMilli(beijing0030).atZone(AppZone.ZONE).toLocalDate().toEpochDay()
        )

        // 确认这个用例真的踩中了跨日窗口：系统时区此刻确实还是前一天。
        // 没有这条，测试在「时区恰好没跨日」的时候也会绿，而那证明不了任何事。
        assertEquals(
            "这个用例必须在 UTC 下仍然跨日才有效",
            oct19 - 1L,
            Instant.ofEpochMilli(beijing0030).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()
        )
    }

    /**
     * 今天 00:00 必须落在同一个 epochDay 上。
     *
     * 这一条最容易悄悄错一天：东八区 00:00 对应 UTC 前一天 16:00，
     * 拿 UTC 直接整除 86400000 就会退回前一天，于是「今天」比所有别的判据早一天。
     */
    @Test
    fun todayStartLandsOnSameEpochDay() {
        val start = AppZone.todayStart()

        assertEquals(
            "todayStart() 必须与 todayEpochDay() 指向同一天",
            AppZone.todayEpochDay(),
            Instant.ofEpochMilli(start.timeInMillis).atZone(AppZone.ZONE).toLocalDate().toEpochDay()
        )
        assertEquals("今天 00:00", 0, start.get(Calendar.HOUR_OF_DAY))
    }
}
