package com.example.data.validate

import com.example.data.codec.AutomationBundle
import com.example.data.codec.CalendarBundle
import com.example.data.codec.CourseBundle
import com.example.data.codec.MakeupDayBundle
import com.example.data.codec.NoClassRangeBundle
import com.example.data.codec.PeriodBundle
import com.example.data.codec.ScheduleBundle
import com.example.data.codec.TermConfigBundle
import com.example.data.codec.TermRefBundle
import com.example.data.codec.TimetableBlockBundle
import com.example.data.codec.TweakBundle
import com.example.data.local.CourseSlotEntity
import com.example.data.timetable.PeriodTable
import com.example.data.timetable.WeekSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 归一化与判错。
 *
 * 这层**不认识 JSON**：手动编辑、文件导入、App 内浙大导入三条路都先变成
 * [ScheduleBundle] 再过这里，所以它必须是唯一的把关口。
 *
 * v2 的形状是「单文件、顶层分块」：学期从 `calendar.termConfigs` 里选、
 * 课表块带 `termRef` 指回那一期、停课与补课从 `calendar.tweaks` 展开。
 */
class ScheduleBundleValidatorTest {

    private val validator = ScheduleBundleValidator()

    private fun valid(bundle: ScheduleBundle): ValidatedBundle {
        val r = validator.validate(bundle)
        assertTrue("本该校验通过：$r", r is ValidationResult.Valid)
        return (r as ValidationResult.Valid).bundle
    }

    private fun invalid(bundle: ScheduleBundle): String {
        val r = validator.validate(bundle)
        assertTrue("本该整档失败：$r", r is ValidationResult.Invalid)
        return (r as ValidationResult.Invalid).message
    }

    // ---------------------------------------------------------------- 夹具

    /** 2026-09-07 周一 → 2027-01-24，共 20 周（首周周一算第 1 周）。 */
    private fun term(year: String = "2026-2027", term: Int = 0) =
        CalendarBundle(termConfigs = listOf(TermConfigBundle(year, term, 20260907, 20270124, 1)))

    /** tweak 里是 YYYYMMDD，落库的是 epochDay；断言时两边必须换到同一个刻度。 */
    private fun day(yyyymmdd: Int): Long =
        com.example.data.calendar.ZjuCalendarAdapter.yyyymmddToEpochDay(yyyymmdd)!!

    private fun timetable(vararg courses: CourseBundle, ref: TermRefBundle? = null) =
        TimetableBlockBundle(termRef = ref, courses = courses.toList())

    private fun course(
        name: String = "高等数学",
        code: String = "MATH101",
        teacher: String = "王教授",
        color: String? = "#93C5FD",
        slots: List<com.example.data.codec.SlotBundle> = listOf(
            com.example.data.codec.SlotBundle(1, 1, 2, "教二", listOf(1, 2, 3))
        ),
        automation: AutomationBundle? = null
    ) = CourseBundle(
        key = com.example.data.model.CourseKeys.compute(code, name, teacher),
        name = name, code = code, teacher = teacher,
        category = null, classroom = null, colorHex = color,
        automation = automation, slots = slots
    )

    // ---------------------------------------------------------------- 缺段与兜底

    @Test
    fun `a completely empty document is still importable`() {
        val b = valid(ScheduleBundle())
        assertNull("没校历就是没学期", b.semester)
        assertNull("没作息就是沿用库里的", b.timetable)
        assertEquals(WeekSet.FALLBACK_TOTAL_WEEKS, b.totalWeeks)
        assertEquals(PeriodTable.DEFAULT_PERIODS.size, b.periodsPerDay)
        assertTrue(b.issues.isEmpty())
    }

    @Test
    fun `a document with courses but no calendar imports the courses`() {
        val b = valid(ScheduleBundle(timetable = timetable(course())))
        assertNull(b.semester)
        assertEquals(1, b.courses.size)
    }

    // ---------------------------------------------------------------- 学期选择

    @Test
    fun `a termRef that does not match any term config refuses the whole document`() {
        val b = ScheduleBundle(
            calendar = term(year = "2026-2027", term = 0),
            timetable = timetable(course(), ref = TermRefBundle("2020-2021", 4))
        )
        val msg = invalid(b)
        assertTrue("要指出对不上的是哪一期：$msg", msg.contains("2020-2021:4"))
        assertTrue("并列出可用的：$msg", msg.contains("2026-2027:0"))
    }

    @Test
    fun `a termRef with no calendar at all is a hard error, not a silent pass`() {
        // 对照 `a document with courses but no calendar imports the courses`：
        // 区别只在这一个带了 termRef —— 指着某学期却根本没写学期，就是自相矛盾。
        val msg = invalid(
            ScheduleBundle(timetable = timetable(course(), ref = TermRefBundle("2026-2027", 0)))
        )
        assertTrue(msg.contains("学期"))
    }

    @Test
    fun `a termRef that matches picks that exact term`() {
        val cal = CalendarBundle(
            termConfigs = listOf(
                TermConfigBundle("2026-2027", 0, 20260907, 20270124, 1),
                TermConfigBundle("2026-2027", 1, 20270222, 20270704, 1)
            )
        )
        val b = valid(
            ScheduleBundle(
                calendar = cal,
                timetable = timetable(course(), ref = TermRefBundle("2026-2027", 1))
            )
        )
        assertNotNull(b.semester)
        // Term=1 是冬学期（zju-ical 的枚举：0 秋 / 1 冬 / 4 春 / 5 夏）
        assertEquals("冬学期", b.semester!!.term)
    }

    @Test
    fun `with one term and no termRef that term is used`() {
        val b = valid(
            ScheduleBundle(calendar = term(), timetable = timetable(course(), ref = null))
        )
        assertEquals("2026-2027", b.semester!!.yearSpan)
        assertEquals(20, b.semester!!.totalWeeks)
        assertEquals(20, b.totalWeeks)
    }

    @Test
    fun `a term with no usable dates is reported but does not void the document`() {
        val b = valid(
            ScheduleBundle(
                calendar = CalendarBundle(
                    termConfigs = listOf(TermConfigBundle("2026-2027", 0, null, null, 1))
                ),
                timetable = timetable(course(), ref = null)
            )
        )
        assertNull(b.semester)
        assertTrue(b.issues.any { it.scope == "校历" })
        assertEquals(1, b.courses.size)
    }

    @Test
    fun `a term that ends before it starts refuses the whole document`() {
        // 这和「日期解析不出来」是两回事：解析不出来是**缺信息**，按未配置学期降级；
        // 起止颠倒是**自相矛盾**，没有任何可以推定的取值，硬猜会让整个学期的周次全错。
        val msg = invalid(
            ScheduleBundle(
                calendar = CalendarBundle(
                    termConfigs = listOf(TermConfigBundle("2026-2027", 0, 20270124, 20260907, 1))
                ),
                timetable = timetable(course(), ref = null)
            )
        )
        assertTrue("要指出是学期起止的问题：$msg", msg.contains("起始日期晚于结束日期"))
    }

    // ---------------------------------------------------------------- 作息表

    @Test
    fun `a timetable section drives periodsPerDay for the whole import`() {
        val b = valid(
            ScheduleBundle(
                calendar = CalendarBundle(
                    termConfigs = listOf(TermConfigBundle("2026-2027", 0, 20260907, 20270124, 1)),
                    periods = (1..10).map {
                        PeriodBundle(it, "08:00", "08:45", "上午")
                    }
                ),
                timetable = timetable(course(), ref = null)
            )
        )
        assertEquals(10, b.periodsPerDay)
        assertEquals(10, b.timetable!!.size)
    }

    @Test
    fun `a duplicated period number keeps the first and says so`() {
        val b = valid(
            ScheduleBundle(
                calendar = CalendarBundle(
                    periods = listOf(
                        PeriodBundle(1, "08:00", "08:45", "上午"),
                        PeriodBundle(1, "09:00", "09:45", "上午")
                    )
                )
            )
        )
        assertEquals(1, b.timetable!!.size)
        assertTrue(b.issues.any { it.detail.contains("重复") })
    }

    @Test
    fun `an unparseable time drops that period instead of the whole timetable`() {
        val b = valid(
            ScheduleBundle(
                calendar = CalendarBundle(
                    periods = listOf(
                        PeriodBundle(1, "08:00", "08:45", "上午"),
                        PeriodBundle(2, "八点", "八点五十", "上午")
                    )
                )
            )
        )
        assertEquals(1, b.timetable!!.size)
        assertTrue(b.issues.any { it.detail.contains("起止时间非法") })
    }

    @Test
    fun `a period that ends before it starts is dropped`() {
        val b = valid(
            ScheduleBundle(
                calendar = CalendarBundle(periods = listOf(PeriodBundle(1, "09:00", "08:00", "上午")))
            )
        )
        assertNull(b.timetable)
        assertTrue(b.issues.any { it.detail.contains("结束时间不晚于开始时间") })
    }

    @Test
    fun `gaps in the period numbering are renumbered so the grid lines up`() {
        val b = valid(
            ScheduleBundle(
                calendar = CalendarBundle(
                    periods = listOf(
                        PeriodBundle(1, "08:00", "08:45", "上午"),
                        PeriodBundle(3, "10:00", "10:45", "上午")
                    )
                )
            )
        )
        assertEquals(listOf(1, 2), b.timetable!!.map { it.period })
        assertTrue(b.issues.any { it.detail.contains("缺口") })
    }

    // ---------------------------------------------------------------- 调休展开

    @Test
    fun `tweak type zero clears every day in its range`() {
        val b = valid(
            ScheduleBundle(
                calendar = CalendarBundle(
                    termConfigs = listOf(TermConfigBundle("2026-2027", 0, 20260907, 20270124, 1)),
                    tweaks = listOf(TweakBundle(0, "国庆", 20261001, 20261007))
                ),
                timetable = timetable(course(), ref = null)
            )
        )
        assertEquals(7, b.noClassDays.size)
        assertTrue(b.noClassDays.all { it.note == "国庆" })
    }

    @Test
    fun `tweak type one copies the from-day classes onto the to-day`() {
        val b = valid(
            ScheduleBundle(
                calendar = CalendarBundle(
                    termConfigs = listOf(TermConfigBundle("2026-2027", 0, 20260907, 20270124, 1)),
                    tweaks = listOf(TweakBundle(1, "补周一", 20260907, 20261010))
                ),
                timetable = timetable(course(), ref = null)
            )
        )
        assertEquals(1, b.makeupDays.size)
        val makeup = b.makeupDays.first()
        // tweak 里存的是 YYYYMMDD，落库的是 epochDay —— 断言必须换算，不能直接比
        assertEquals(day(20261010), makeup.epochDay)
        assertEquals("To 那天上 From 那天的课 = 周一的课", 1, makeup.makeupForDayOfWeek)
    }

    @Test
    fun `tweak type two exchanges both days`() {
        val b = valid(
            ScheduleBundle(
                calendar = CalendarBundle(
                    termConfigs = listOf(TermConfigBundle("2026-2027", 0, 20260907, 20270124, 1)),
                    tweaks = listOf(TweakBundle(2, "对调", 20260907, 20260910))
                ),
                timetable = timetable(course(), ref = null)
            )
        )
        assertEquals(2, b.makeupDays.size)
        // 09-10 是周四，周四上 09-07（周一）的课；09-07 上 09-10（周四）的课
        assertEquals(day(20260910) to 1, b.makeupDays.first().epochDay to b.makeupDays.first().makeupForDayOfWeek)
        assertEquals(day(20260907) to 4, b.makeupDays[1].epochDay to b.makeupDays[1].makeupForDayOfWeek)
    }

    @Test
    fun `a tweak entirely outside the term is ignored`() {
        val b = valid(
            ScheduleBundle(
                calendar = CalendarBundle(
                    termConfigs = listOf(TermConfigBundle("2026-2027", 0, 20260907, 20270124, 1)),
                    // 整段落在学期结束之后（寒假）
                    tweaks = listOf(TweakBundle(0, "寒假", 20270125, 20270201))
                ),
                timetable = timetable(course(), ref = null)
            )
        )
        assertTrue("整段在学期区间外，应被忽略", b.noClassDays.isEmpty())
        assertTrue(b.issues.any { it.detail.contains("不在学期区间内") })
    }

    @Test
    fun `an unrecognised tweak type is reported rather than silently applied`() {
        val b = valid(
            ScheduleBundle(
                calendar = CalendarBundle(
                    termConfigs = listOf(TermConfigBundle("2026-2027", 0, 20260907, 20270124, 1)),
                    tweaks = listOf(TweakBundle(9, "?", 20261001, 20261002))
                ),
                timetable = timetable(course(), ref = null)
            )
        )
        assertTrue(b.noClassDays.isEmpty() && b.makeupDays.isEmpty())
        assertTrue(b.issues.any { it.detail.contains("TweakType") })
    }

    @Test
    fun `v1 legacy no-class ranges still expand`() {
        val b = valid(
            ScheduleBundle(
                calendar = CalendarBundle(
                    legacyNoClassRanges = listOf(
                        NoClassRangeBundle("2026-10-01", "2026-10-03", "国庆"),
                        NoClassRangeBundle("2026-10-02", "2026-10-05", "")
                    )
                )
            )
        )
        // 2026-10-01 … 10-05 合并去重后是 5 天
        assertEquals(5, b.noClassDays.size)
    }

    @Test
    fun `a legacy makeup day with an out-of-range weekday is dropped`() {
        val b = valid(
            ScheduleBundle(
                calendar = CalendarBundle(
                    legacyMakeupDays = listOf(
                        MakeupDayBundle("2026-10-10", 1, "补周一"),
                        MakeupDayBundle("2026-10-11", 9, "补周日"),
                        MakeupDayBundle("2026-10-12", null, "")
                    )
                )
            )
        )
        assertEquals(1, b.makeupDays.size)
        assertEquals(1, b.makeupDays.first().makeupForDayOfWeek)
    }

    // ---------------------------------------------------------------- 课程

    @Test
    fun `a course with no name is skipped`() {
        val b = valid(ScheduleBundle(timetable = timetable(course(name = ""), course(name = "线性代数"))))
        assertEquals(1, b.courses.size)
        assertEquals("线性代数", b.courses.first().name)
    }

    @Test
    fun `two rows for the same course merge their slots`() {
        val duplicated = course(
            slots = listOf(com.example.data.codec.SlotBundle(3, 3, 4, "教一", null))
        )
        val b = valid(ScheduleBundle(timetable = timetable(course(), duplicated)))
        assertEquals(1, b.courses.size)
        assertEquals(2, b.courses.first().slots.size)
        assertTrue(b.issues.any { it.detail.contains("出现两次") })
    }

    @Test
    fun `an invalid colour falls back to the palette`() {
        val b = valid(ScheduleBundle(timetable = timetable(course(color = "不是颜色"))))
        assertTrue(b.courses.first().colorHex!!.startsWith("#"))
        assertTrue(b.issues.any { it.detail.contains("非法") })
    }

    @Test
    fun `a course with no category gets the default one`() {
        val b = valid(ScheduleBundle(timetable = timetable(course())))
        assertEquals(com.example.data.model.DEFAULT_CATEGORY, b.courses.first().category)
    }

    @Test
    fun `a slot whose weekday is out of range is skipped`() {
        val bad = course(slots = listOf(com.example.data.codec.SlotBundle(0, 1, 2, "", null)))
        val b = valid(ScheduleBundle(timetable = timetable(bad)))
        assertTrue(b.courses.first().slots.isEmpty())
        assertTrue(b.issues.any { it.detail.contains("星期非法") })
    }

    @Test
    fun `a slot whose period exceeds the timetable is skipped`() {
        // 起始节要真的超出节数才有意义：越界的**结束**节会被夹到最后一节而不是丢弃。
        val bad = course(slots = listOf(com.example.data.codec.SlotBundle(1, 99, 99, "", null)))
        val b = valid(ScheduleBundle(timetable = timetable(bad)))
        assertTrue(b.courses.first().slots.isEmpty())
        assertTrue(b.issues.any { it.detail.contains("起始节超出") })
    }

    @Test
    fun `a missing end period defaults to the start`() {
        val half = course(slots = listOf(com.example.data.codec.SlotBundle(1, 3, null, "", null)))
        val b = valid(ScheduleBundle(timetable = timetable(half)))
        val slot = b.courses.first().slots.first()
        assertEquals(3, slot.startPeriod)
        assertEquals(3, slot.endPeriod)
    }

    // ---------------------------------------------------------------- 周次

    @Test
    fun `a compact weeks string is normalised into a set`() {
        val b = valid(
            ScheduleBundle(
                calendar = term(),
                timetable = timetable(
                    course(slots = listOf(com.example.data.codec.SlotBundle(1, 1, 2, "", "1-16周(单)")))
                )
            )
        )
        @Suppress("UNCHECKED_CAST")
        val weeks = b.courses.first().slots.first().weeks as Set<Int>
        assertEquals((1..16).filter { it % 2 == 1 }.toSet(), weeks)
    }

    @Test
    fun `weeks are clamped to the total of the term`() {
        val b = valid(
            ScheduleBundle(
                calendar = term(),
                timetable = timetable(
                    course(slots = listOf(com.example.data.codec.SlotBundle(1, 1, 2, "", listOf(1, 2, 30))))
                )
            )
        )
        @Suppress("UNCHECKED_CAST")
        val weeks = b.courses.first().slots.first().weeks as Set<Int>
        assertTrue("超出学期总周数的要裁掉", weeks.all { it in 1..20 })
    }

    @Test
    fun `an unparseable weeks string falls back to every week and is reported`() {
        val b = valid(
            ScheduleBundle(
                calendar = term(),
                timetable = timetable(
                    course(slots = listOf(com.example.data.codec.SlotBundle(1, 1, 2, "", "看心情")))
                )
            )
        )
        assertTrue(b.issues.isNotEmpty())
        @Suppress("UNCHECKED_CAST")
        val weeks = b.courses.first().slots.first().weeks as Set<Int>
        assertEquals(20, weeks.size)
    }

    // ---------------------------------------------------------------- 整体

    @Test
    fun `a complete document comes out with everything the database needs`() {
        val b = valid(
            ScheduleBundle(
                calendar = CalendarBundle(
                    termConfigs = listOf(TermConfigBundle("2026-2027", 0, 20260907, 20270124, 1)),
                    tweaks = listOf(
                        TweakBundle(0, "国庆", 20261001, 20261007),
                        TweakBundle(1, "补周一", 20260907, 20261010)
                    ),
                    periods = (1..13).map { PeriodBundle(it, "08:00", "08:45", "上午") }
                ),
                timetable = timetable(
                    course(
                        automation = AutomationBundle(
                            preStudy = true, postHomework = false, labReport = false,
                            review = true, defaultPostDueDays = 6,
                            preStudyMinutes = 30, postHomeworkMinutes = 60, labReportMinutes = 90
                        )
                    )
                )
            )
        )
        assertNotNull(b.semester)
        assertEquals(13, b.timetable!!.size)
        assertEquals(7, b.noClassDays.size)
        assertEquals(1, b.makeupDays.size)
        assertEquals(1, b.courses.size)
        assertEquals(13, b.semester!!.periodsPerDay)
        // 课程自动化段里的截止天数必须原样带过来
        assertEquals(6, b.courses.first().automation!!.defaultPostDueDays)
    }
}