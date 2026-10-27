package com.example.data.calendar

import com.example.data.local.CourseEntity
import com.example.data.local.CourseSlotEntity
import com.example.data.model.ExamEntry
import com.example.data.model.ExamKind
import com.example.data.model.WeekPlanDto
import com.example.data.model.WeekPlanSlotDto
import com.example.data.timetable.PeriodTable
import com.squareup.moshi.Moshi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * 周课表快照。纯 JUnit（不碰 Room / Android）。
 *
 * ## 这里守的是什么
 *
 * 网页端**不自己算**周课表 —— 手机算好发过去（理由见 [WeekPlanBuilder]）。
 * 于是「网页端那张表对不对」这件事，就落在这份数据对不对上。
 *
 * 守的正是那些**只有周课表这一层才表达得出来**的语义：
 * - 补课：周六出周五的课，而周五自己反而空着（让出）；
 * - 停课：那天一节都不上，且要说出「为什么空」；
 * - 单双周：只在第 1、3 周开的课，第 2 周不能出现；
 * - 考试：只有钟点，要按作息表换算成节次才能落进格子。
 *
 * 任何一条退化，用户看到的都是「课表错了」，而网页端无从自查。
 */
class WeekPlanBuilderTest {

    private val firstMonday = EpochDays.parse("2026-09-07")!!   // 周一

    private fun semester(totalWeeks: Int = 20) = Semester(
        name = "2026-2027学年 秋冬学期",
        yearSpan = "2026-2027",
        term = "秋冬",
        startEpochDay = firstMonday,
        firstMondayEpochDay = firstMonday,
        endEpochDay = firstMonday + (totalWeeks - 1) * 7L + 6,
        totalWeeks = totalWeeks,
        periodsPerDay = 13,
    )

    private val course = CourseEntity(
        id = 1L,
        name = "大学物理",
        code = "PHYS1001",
        category = "公共基础",
        teacher = "王芳",
        classroom = "教十 201",
        colorHex = "#f0b429",
        dedupeKey = "PHYS1001|大学物理|王芳",
    )

    private fun slot(id: Long, dayOfWeek: Int, start: Int, end: Int = start, courseId: Long = 1L) =
        CourseSlotEntity(
            id = id,
            courseId = courseId,
            dayOfWeek = dayOfWeek,
            startPeriod = start,
            endPeriod = end,
        )

    private fun build(
        calendar: AcademicCalendar,
        slots: List<CourseSlotEntity> = listOf(slot(1, 5, 3, 4)),
        weeksBySlotId: Map<Long, Set<Int>> = emptyMap(),
        exams: List<ExamEntry> = emptyList(),
        totalWeeks: Int = 20,
    ): WeekPlanDto = WeekPlanBuilder.build(
        calendar = calendar,
        slots = slots,
        weeksBySlotId = weeksBySlotId,
        coursesById = mapOf(1L to course),
        exams = exams,
        periodTable = PeriodTable.DEFAULT,
    )

    private fun WeekPlanDto.week(n: Int) = weeks.first { it.week == n }

    /** 那一天下标指向的时段，按 [WeekPlanDto.slotList] 展开。 */
    private fun WeekPlanDto.slotsOf(week: Int, dayIndex: Int): List<WeekPlanSlotDto> =
        week(week).days[dayIndex].slotIndexes.map { slotList[it] }

    // ---------------------------------------------------------------- 基本形状

    @Test
    fun `每一周都是七列 - 少一列整张表会左右错位`() {
        val table = build(AcademicCalendar(semester()))
        assertEquals(20, table.weeks.size)
        table.weeks.forEach { week ->
            assertEquals("第 ${week.week} 周不是七列", 7, week.days.size)
        }
    }

    @Test
    fun `周次条要用得到的东西都在 - 总周数与当前周`() {
        val table = build(AcademicCalendar(semester(totalWeeks = 16)))
        assertEquals(16, table.totalWeeks)
        assertEquals(13, table.periodsPerDay)
        assertEquals(13, table.periods.size)
        assertEquals(1, table.periods.first().period)
        assertEquals(8 * 60, table.periods.first().startMinute)
    }

    @Test
    fun `时段只存一份 - 周计划里存下标`() {
        val table = build(
            AcademicCalendar(semester()),
            slots = listOf(slot(1, 1, 3, 4), slot(2, 5, 3, 4)),
        )
        assertEquals("两个时段就是两条", 2, table.slotList.size)
        // 第 1 周的周一有第 1 条、周五有第 2 条
        assertEquals(listOf(0), table.week(1).days[0].slotIndexes)
        assertEquals(listOf(1), table.week(1).days[4].slotIndexes)
        // 下标展开后要能拿到课程与节次
        val monday = table.slotsOf(1, 0).single()
        assertEquals("PHYS1001|大学物理|王芳", monday.courseKey)
        assertEquals(3, monday.startPeriod)
        assertEquals(4, monday.endPeriod)
        assertEquals("时段没写教室时要落回课程自己的教室", "教十 201", monday.classroom)
    }

    // ---------------------------------------------------------------- 补课

    /**
     * 补课只有「按真实日期分列」才表达得出来。
     *
     * 第 3 周的周五（09-25）让出课，第 5 周的周六（10-10）补它。
     * 于是：第 3 周的周五那一列**空着**（VACATED），第 5 周的周六那一列
     * **出周五的课**。
     *
     * 网页端若按星期几分列，周六那一列永远是空的 —— 用户看到的是「周六没课」。
     */
    @Test
    fun `补课落在它真正发生的那一天 - 周五让出 周六出周五的课`() {
        val from = firstMonday + 18      // 第 3 周周五 09-25
        val to = firstMonday + 33        // 第 5 周周六 10-10
        val calendar = AcademicCalendar(semester(), makeupDays = mapOf(to to MakeupShift(from)))
        val table = build(calendar)

        val vacated = table.week(3).days[4]
        assertEquals("让出课那天是周五", 5, vacated.dayOfWeek)
        assertEquals("VACATED", vacated.offReason)
        assertTrue("让出课那天一节都不上", vacated.slotIndexes.isEmpty())

        val makeup = table.week(5).days[5]
        assertEquals("补课日是周六", 6, makeup.dayOfWeek)
        assertEquals("补课日要说明它补的是星期几", 5, makeup.makeupDayOfWeek)
        assertNull("补课日不是休息日", makeup.offReason)
        assertEquals("周六要出周五那门课", 1, makeup.slotIndexes.size)
    }

    /**
     * 补课那一列的课，用**来源日**的开课周次筛，不是补课当天那一周。
     *
     * 来源日 09-25 在第 3 周，补课日 10-10 在第 5 周。
     * 只在第 3 周开的课必须出现，只在第 5 周开的必须不出现 ——
     * 用错周次会凭空多出一次课，或者一次都不出。
     */
    @Test
    fun `补上来的课按来源日那一周筛开课周次`() {
        val from = firstMonday + 18
        val to = firstMonday + 33
        val calendar = AcademicCalendar(semester(), makeupDays = mapOf(to to MakeupShift(from)))
        val slot = slot(1, 5, 3, 4)

        val odd = build(calendar, slots = listOf(slot), weeksBySlotId = mapOf(1L to setOf(3)))
        assertEquals(
            "只在来源日那一周开的课要出来",
            1,
            odd.week(5).days[5].slotIndexes.size,
        )

        val even = build(calendar, slots = listOf(slot), weeksBySlotId = mapOf(1L to setOf(5)))
        assertEquals(
            "第 5 周不是这节课的身份",
            0,
            even.week(5).days[5].slotIndexes.size,
        )
    }

    // ---------------------------------------------------------------- 停课

    @Test
    fun `停课日空着并且说得出为什么`() {
        val wednesday = firstMonday + 2
        val calendar = AcademicCalendar(semester(), noClassDays = setOf(wednesday))
        val table = build(calendar, slots = listOf(slot(1, 3, 3, 4)))

        val day = table.week(1).days[2]
        assertEquals("NO_CLASS", day.offReason)
        assertTrue("停课那天一节都不上", day.slotIndexes.isEmpty())
    }

    /**
     * 周末是**软**理由：用户自己排了课就要出。
     *
     * 而且那一列的 `offReason` 是**空**，不是 `WEEKEND` ——
     * 「排了课」之后再说它是休息日就是在讲一件没发生的事（App 的 `weekPlan`
     * 同一口径：周末排了课 → 那天不是「没课日」）。
     */
    @Test
    fun `周末排了课照样出 - 周末只是默认没课`() {
        val table = build(
            AcademicCalendar(semester()),
            slots = listOf(slot(1, 6, 3, 4)),
        )
        val saturday = table.week(1).days[5]
        assertNull("排了课的那天就不该再被说成休息日", saturday.offReason)
        assertEquals("周六排的课要出来", 1, saturday.slotIndexes.size)
    }

    // ---------------------------------------------------------------- 开课周次

    @Test
    fun `只在开课周次里的时段才出现`() {
        val table = build(
            AcademicCalendar(semester()),
            slots = listOf(slot(1, 1, 3, 4)),
            weeksBySlotId = mapOf(1L to setOf(1, 3, 5)),
        )
        assertEquals(1, table.week(1).days[0].slotIndexes.size)    // 第 1 周
        assertEquals(0, table.week(2).days[0].slotIndexes.size)    // 第 2 周
        assertEquals(1, table.week(3).days[0].slotIndexes.size)    // 第 3 周
    }

    // ---------------------------------------------------------------- 考试

    /**
     * 考试没有节号，只有钟点：09:00–11:00 要落成跨三节的那一格。
     *
     * 作息表是 08:00–08:45 / 08:55–09:40 / 10:00–10:45 / 10:55–11:40，
     * 于是 09:00 落在第 2 节里；结束时刻 11:00 之前**已经开课**的有第 3 节（10:00）
     * 和第 4 节（10:55）→ 占第 2、3、4 节。
     */
    @Test
    fun `考试按钟点换算成节次并落到那一天`() {
        val thursday = firstMonday + 3
        val exam = examOn(thursday, 9 * 60, 11 * 60)
        val table = build(AcademicCalendar(semester()), exams = listOf(exam))

        val day = table.week(1).days[3]
        assertEquals("考试要落在这天", 1, day.exams.size)
        val cell = day.exams.single()
        assertEquals("大学物理", cell.courseName)
        assertEquals(2, cell.startPeriod)
        assertEquals(4, cell.endPeriod)
        assertEquals(9 * 60, cell.startMinute)
    }

    /** 日期还没公布的考试换不出节次，进不了栅格（但仍在「考试」列表里）。 */
    @Test
    fun `没公布时刻的考试不进栅格`() {
        val exam = ExamEntry(
            id = 1L,
            courseName = "大学物理",
            kind = ExamKind.FINAL,
            startAt = 0L,
            endAt = 0L,
            startMinute = 0,
            endMinute = 0,
            dateLabel = "考试周第 3 天",
            classroom = "",
            seat = "",
            credit = 3.0,
            termLabel = "2026-2027 秋冬",
            courseId = 1L,
            courseColorHex = "#f0b429",
        )
        val table = build(AcademicCalendar(semester()), exams = listOf(exam))
        table.weeks.forEach { week ->
            week.days.forEach { day ->
                assertTrue("没时刻的考试不该落进格子", day.exams.isEmpty())
            }
        }
    }

    /** 一场考试只落在一天上，整张表里只出现一次。 */
    @Test
    fun `一场考试只出现一次`() {
        val thursday = firstMonday + 3
        val exam = examOn(thursday, 9 * 60, 11 * 60)
        val table = build(AcademicCalendar(semester()), exams = listOf(exam))

        var seen = 0
        table.weeks.forEach { week -> week.days.forEach { day -> seen += day.exams.size } }
        assertEquals(1, seen)
    }

    // ---------------------------------------------------------------- 线上格式

    /**
     * 周课表要能**编成 JSON 再解回来**，而且解回来的内容一样。
     *
     * 这一条不是凑数：网页端就是按这份 JSON 解析的，格式坏了它不会报错 ——
     * 只会「没找到 timetable 字段」，于是周视图那一档悄悄消失，
     * 用户看到的是「网页端没有课表」，而不是「解析失败」。
     */
    @Test
    fun `周课表能编成 JSON 再解回来 - 网页端要靠它解`() {
        val table = build(
            AcademicCalendar(semester(), noClassDays = setOf(firstMonday + 2)),
            slots = listOf(slot(1, 5, 3, 4)),
            exams = listOf(examOn(firstMonday + 3, 9 * 60, 11 * 60)),
        )
        val adapter = Moshi.Builder().build().adapter(WeekPlanDto::class.java)
        val back = adapter.fromJson(adapter.toJson(table))
        assertNotNull(back)
        assertEquals(table.totalWeeks, back!!.totalWeeks)
        assertEquals(table.currentWeek, back.currentWeek)
        assertEquals(table.periodsPerDay, back.periodsPerDay)
        assertEquals(table.slotList.size, back.slotList.size)
        assertEquals(table.weeks.size, back.weeks.size)
        assertEquals("每一周都是七列", 7, back.weeks.first().days.size)
        assertEquals("停课理由要传过去", "NO_CLASS", back.weeks.first().days[2].offReason)
        assertEquals("考试要落在那一天", 1, back.weeks.first().days[3].exams.size)
        assertEquals(2, back.weeks.first().days[3].exams.single().startPeriod)
    }

    // ---------------------------------------------------------------- 没学期

    /**
     * 没配置学期时降级：周次从**今天所在周**起算，开课周次不参与筛选。
     *
     * 与 App 的降级路径一致（见 [com.example.data.calendar.ScheduleResolver]）。
     */
    @Test
    fun `没配置学期时也出得了课表`() {
        val table = build(
            AcademicCalendar.EMPTY,
            slots = listOf(slot(1, 1, 3, 4)),
            weeksBySlotId = mapOf(1L to setOf(2, 4)),
        )
        assertNotNull(table)
        assertTrue("没学期也要有周", table.weeks.isNotEmpty())
        val monday = table.week(1).days[0]
        assertEquals("没学期时开课周次不筛选", 1, monday.slotIndexes.size)
    }

    // ---------------------------------------------------------------- 工具

    /** 造一场「某天的某段时间开考」的考试。 */
    private fun examOn(epochDay: Long, startMinute: Int, endMinute: Int) = ExamEntry(
        id = 9L,
        courseName = "大学物理",
        kind = ExamKind.MIDTERM,
        startAt = atBeijing(epochDay, startMinute),
        endAt = atBeijing(epochDay, endMinute),
        startMinute = startMinute,
        endMinute = endMinute,
        dateLabel = "",
        classroom = "教十 201",
        seat = "12",
        credit = 3.0,
        termLabel = "2026-2027 秋冬",
        courseId = 1L,
        courseColorHex = "#f0b429",
    )

    /** 北京时间某天的第几分钟 → epoch 毫秒。 */
    private fun atBeijing(epochDay: Long, minuteOfDay: Int): Long =
        LocalDate.ofEpochDay(epochDay)
            .atStartOfDay(ZoneId.of("Asia/Shanghai"))
            .plusMinutes(minuteOfDay.toLong())
            .toInstant()
            .toEpochMilli()
}
