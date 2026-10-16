package com.example.data.source.zju

import com.example.data.calendar.AcademicCalendar
import com.example.data.calendar.EpochDays
import com.example.data.calendar.MakeupShift
import com.example.data.calendar.Semester
import com.example.data.codec.ScheduleBundle
import com.example.data.codec.TermRefBundle
import com.example.data.codec.TimetableBlockBundle
import com.example.data.codec.academicTermOf
import com.example.data.validate.ScheduleBundleValidator
import com.example.data.validate.ValidatedBundle
import com.example.data.validate.ValidationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用**上游真实形态的 config.json** 跑完整条链路：
 * 解析 → 选学期 → 校验 → 停课/补课落库 → 校历判定。
 *
 * 这里钉的是用户 2026-10-15 给的那份真实配置（2026-2027 秋冬学期 + 国庆/中秋调休）。
 *
 * 单独测各自的函数不够：出问题的恰恰是**跨层的口径**——
 * 学期该按学年学期折成 16 周、调休该按「哪天到哪天」搬，
 * 两头各自都对、拼起来仍然错。这类错没有任何一层的单测能发现。
 */
class ZjuRealConfigPipelineTest {

    /** 用户提供的真实配置，一字未改（`Term` 是中文、`TweakType` 是字符串）。 */
    private val realConfig = """
        {
          "lastUpdated": 20260917,
          "tweaks": [
            { "TweakType": "Clear", "Description": "[中秋节] 9月25日（周五）至9月27日（周日）停课", "From": 20260925, "To": 20260927 },
            { "TweakType": "Clear", "Description": "[国庆节] 10月1日（周四）停课", "From": 20261001, "To": 20261001 },
            { "TweakType": "Clear", "Description": "[国庆节] 10月3日（周六）至10月5日（周一）停课", "From": 20261003, "To": 20261005 },
            { "TweakType": "Move", "Description": "[国庆节] 10月2日（周五）停课，安排在10月17日（周六）补课", "From": 20261002, "To": 20261017 },
            { "TweakType": "Exchange", "Description": "[国庆节] 10月6日（周二）与9月20日（周日）的课对调", "From": 20261006, "To": 20260920 },
            { "TweakType": "Exchange", "Description": "[国庆节] 10月7日（周三）与10月10日（周六）的课对调", "From": 20261007, "To": 20261010 },
            { "TweakType": "Move", "Description": "[学生节] 12月31日（周四）停课，安排在1月4日（周一）补课", "From": 20261231, "To": 20270104 },
            { "TweakType": "Clear", "Description": "[元旦] 1月1日（周五）停课", "From": 20270101, "To": 20270101 }
          ],
          "termConfigs": [
            { "Year": "2026-2027", "Term": "秋", "Begin": 20260914, "End": 20261108, "FirstWeekNo": 1 },
            { "Year": "2026-2027", "Term": "冬", "Begin": 20261109, "End": 20270103, "FirstWeekNo": 1 }
          ],
          "classTerms": ["2026-2027:秋", "2026-2027:冬"]
        }
    """.trimIndent()

    private fun day(yyyymmdd: Int): Long =
        EpochDays.parse("%04d-%02d-%02d".format(yyyymmdd / 10000, yyyymmdd / 100 % 100, yyyymmdd % 100))!!

    private val validated: ValidatedBundle
        get() = runCatching {
            val source = ZjuCalendarSource(bundledText = realConfig)
            val selected = source.selectTerm(source.parse(realConfig), "2026-2027", 0)
            val result = ScheduleBundleValidator().validate(
                ScheduleBundle(
                    calendar = selected,
                    timetable = TimetableBlockBundle(termRef = TermRefBundle("2026-2027", 0))
                )
            )
            result as? ValidationResult.Valid ?: error("整条链路没走通：$result")
        }.getOrElse { error("整条链路跑不通：$it") }.bundle

    /**
     * `Term` 与 `TweakType` 都写成中文/字符串时，整份配置必须能解析。
     *
     * 之前两个字段都声明成 `Int?`，Moshi 遇到字符串直接抛 `JsonDataException`，
     * 而 `parse` 把反序列化包在 `runCatching` 里 —— 于是**整份校历配置解析失败**，
     * 学期起止日期与调休一起消失，界面上只剩「校历配置拉取失败」。
     */
    @Test
    fun `中文 Term 与字符串 TweakType 能解析成学年学期`() {
        val parsed = ZjuCalendarSource(bundledText = realConfig).parse(realConfig)

        assertEquals(listOf(0, 1), parsed.termConfigs.map { it.term })
        assertEquals(listOf("2026-2027:0", "2026-2027:1"), parsed.classTerms)

        val term = parsed.termConfigs.academicTermOf("2026-2027", 0)!!
        assertEquals("2026-2027学年 秋冬 学期", term.label)
        assertEquals(16, term.totalWeeks)
        assertEquals(day(20260914), term.startEpochDay)
        assertEquals(day(20270103), term.endEpochDay)
    }

    @Test
    fun `学期按学年学期折成秋冬 16 周`() {
        val semester = validated.semester!!

        assertEquals("2026-2027学年 秋冬 学期", semester.name)
        assertEquals(16, semester.totalWeeks)
        assertEquals(day(20260914), semester.firstMondayEpochDay)
        assertEquals(day(20270103), semester.endEpochDay)
    }

    /**
     * `Move` 搬走 From 那天的课，From 要让出课；但不额外写一条停课日。
     *
     * 10-02 是周五、10-17 是周六，两条相隔两周 —— 「哪天到哪天」不是修辞，
     * 只记周几的话，这场补课的周次就会算成 10-17 所在的那一周。
     */
    @Test
    fun `国庆那次补课是具体到 10-02 到 10-17`() {
        val b = validated
        val makeup = b.makeupDays.single { it.epochDay == day(20261017) }

        assertEquals("10-02 是周五", 5, makeup.makeupForDayOfWeek)
        assertEquals(day(20261002), makeup.sourceEpochDay)
        assertTrue(
            "10-02 让出了课，但不额外记一条停课日",
            b.noClassDays.none { it.epochDay == day(20261002) }
        )
        assertTrue("10-02 那天上不了课", b.toCalendar().let { !it.isSchoolDay(day(20261002)) })
    }

    /**
     * 补上来的那份课算**来源日**那一周。
     *
     * 09-14 是第 1 周的周一，于是 10-02 是第 3 周、10-17 是第 5 周。
     */
    @Test
    fun `补课归属来源日那一周而不是补课当天`() {
        val calendar = validated.toCalendar()

        assertEquals(5, calendar.weekOf(day(20261017)))
        assertEquals(3, calendar.teachingWeekOf(day(20261017)))
        assertEquals("周六补的是周五的课", 5, calendar.effectiveDayOfWeek(day(20261017)))
        assertTrue("周六本来不上课，补课把它变成上课日", calendar.isSchoolDay(day(20261017)))
    }

    /**
     * 两次对调都要落地，且 `Move` 不能把中间夹着的 10-06 / 10-07 一起停掉。
     *
     * `Move` 的 From→To 跨了两周（10-02 → 10-17），中间正夹着这两次对调。
     * 按 From..To 整段清课的话，这两天会被停掉，对调整个失效。
     */
    /**
     * 对调的两端顺序是**反的**（From=10-06 / To=09-20），不能当成非法区间丢掉。
     *
     * 早先对所有调休类型都查 `to >= from`，这一条直接被报成「日期无法解析」整条忽略，
     * 于是 10-06 与 09-20 两天都上不出对方星期的课，而界面上没有任何提示。
     */
    @Test
    fun `Move 不会吃掉中间夹着的对调`() {
        val b = validated
        val noClass = b.noClassDays.map { it.epochDay }.toSet()

        assertFalse("10-06 是对调的一方，不是停课日", day(20261006) in noClass)
        assertFalse("10-07 同理", day(20261007) in noClass)

        // 节假日与周末对调：周末（09-20、10-10）是补课日，上对应工作日的课
        val swap1 = b.makeupDays.single { it.epochDay == day(20260920) }
        assertEquals("9-20 补周二 10-06 的课", 2, swap1.makeupForDayOfWeek)
        assertEquals(day(20261006), swap1.sourceEpochDay)

        val swap2 = b.makeupDays.single { it.epochDay == day(20261010) }
        assertEquals("10-10 补周三 10-07 的课", 3, swap2.makeupForDayOfWeek)
        assertEquals(day(20261007), swap2.sourceEpochDay)

        // 工作日 10-06 与 10-07 借调公休放假，是让出课（Vacated），不进补课表
        assertFalse("10-06 不是补课日", b.makeupDays.any { it.epochDay == day(20261006) })
        assertFalse("10-07 不是补课日", b.makeupDays.any { it.epochDay == day(20261007) })
    }

    /**
     * 冬那半学期的调休也要进来 —— 它们落在秋那一条 `End` 之后。
     *
     * 按被选中的那一条半学期裁区间的话，元旦停课与学生节补课会被整段丢掉。
     */
    @Test
    fun `冬那半学期的调休也要进来`() {
        val b = validated
        val calendar = b.toCalendar()
        val noClass = b.noClassDays.map { it.epochDay }.toSet()
        val makeup = b.makeupDays.associateBy { it.epochDay }

        assertTrue("元旦停课（Clear）", day(20270101) in noClass)
        assertTrue("中秋停课（Clear）", day(20260925) in noClass)

        val makeUp = makeup[day(20270104)]!!
        assertEquals("12-31 是周四", 4, makeUp.makeupForDayOfWeek)
        assertEquals(day(20261231), makeUp.sourceEpochDay)
        // 学生节那天是「让出课」，不是一条停课日
        assertTrue(
            "12-31 让出了课，但不额外记一条停课日",
            day(20261231) !in noClass
        )
        assertTrue(calendar.isVacatedForShift(day(20261231)))
        assertFalse("1-01 停课，1-04 补周一", calendar.isSchoolDay(day(20270104)))
    }

    /** 停课日与补课日不能互相覆盖：国庆那天让出课，17 号才补；对调放假的那两天也让出课。 */
    @Test
    fun `让出课的那天上不了课`() {
        val calendar = validated.toCalendar()

        assertTrue(calendar.isNoClass(day(20261001)))
        assertFalse("10-01 是停课日", calendar.isSchoolDay(day(20261001)))
        assertTrue(calendar.isVacatedForShift(day(20261002)))
        assertFalse("让出课的那天上不了课", calendar.isSchoolDay(day(20261002)))
        assertTrue(calendar.isMakeup(day(20261017)))
        assertTrue("周六补课这天要上课", calendar.isSchoolDay(day(20261017)))
        // 10-06 与 10-07 借调公休放假，属于让出课，当天不上课
        assertTrue("10-06 借调公休让出课", calendar.isVacatedForShift(day(20261006)))
        assertFalse("10-06 当天不上课", calendar.isSchoolDay(day(20261006)))
        assertTrue("10-07 借调公休让出课", calendar.isVacatedForShift(day(20261007)))
        assertFalse("10-07 当天不上课", calendar.isSchoolDay(day(20261007)))
    }

    @Test
    fun `第4周周课表在10-06与10-07显示放假且没有课`() {
        val calendar = validated.toCalendar()
        val resolver = com.example.data.calendar.ScheduleResolver(calendar)
        val dummySlots = (1..7).map { dow ->
            com.example.data.local.CourseSlotEntity(id = dow.toLong(), courseId = 1L, dayOfWeek = dow, startPeriod = 1, endPeriod = 2)
        }
        val plan = resolver.weekPlan(4, dummySlots)
        assertEquals(7, plan.size)

        // 10-05（周一）国庆停课
        assertEquals(com.example.data.calendar.DayOffReason.NO_CLASS, plan[0].offReason)
        assertTrue(plan[0].slots.isEmpty())

        // 10-06（周二）调休放假（让出课）
        assertEquals(com.example.data.calendar.DayOffReason.VACATED, plan[1].offReason)
        assertTrue(plan[1].slots.isEmpty())

        // 10-07（周三）调休放假（让出课）
        assertEquals(com.example.data.calendar.DayOffReason.VACATED, plan[2].offReason)
        assertTrue(plan[2].slots.isEmpty())

        // 10-08（周四）正常上课
        org.junit.Assert.assertNull(plan[3].offReason)
        assertEquals(listOf(4L), plan[3].slots.map { it.id })

        // 10-10（周六）补周三的课
        org.junit.Assert.assertNull(plan[5].offReason)
        assertEquals(3, plan[5].makeupDayOfWeek)
        assertEquals(listOf(3L), plan[5].slots.map { it.id })
    }

    @Test
    fun `即使本地数据库包含老版本生成的双向脏补课行，周课表依然自动自愈显示放假`() {
        val s = validated.semester!!
        // 模拟本地老数据库中存在的 6 条记录（含 10-06 -> 09-20 与 10-07 -> 10-10 脏数据）
        val dirtyMakeup = mapOf(
            day(20260920) to MakeupShift(day(20261006)),
            day(20261006) to MakeupShift(day(20260920)), // 脏行
            day(20261007) to MakeupShift(day(20261010)), // 脏行
            day(20261010) to MakeupShift(day(20261007)),
            day(20261017) to MakeupShift(day(20261002)),
            day(20270104) to MakeupShift(day(20261231))
        )
        val cal = AcademicCalendar(
            semester = Semester(
                name = s.name, yearSpan = s.yearSpan, term = s.term,
                startEpochDay = s.startEpochDay, firstMondayEpochDay = s.firstMondayEpochDay,
                endEpochDay = s.endEpochDay, totalWeeks = s.totalWeeks,
                periodsPerDay = s.periodsPerDay
            ),
            noClassDays = validated.noClassDays.map { it.epochDay }.toSet(),
            makeupDays = dirtyMakeup
        )
        val resolver = com.example.data.calendar.ScheduleResolver(cal)
        // 模拟选了周日（dayOfWeek = 7）的网球课
        val tennisSlot = com.example.data.local.CourseSlotEntity(id = 999L, courseId = 99L, dayOfWeek = 7, startPeriod = 3, endPeriod = 5)
        val plan = resolver.weekPlan(4, listOf(tennisSlot))

        // 10-06（周二）必须自愈为放假让出日，周日的网球课绝不能排到周二！
        assertEquals(com.example.data.calendar.DayOffReason.VACATED, plan[1].offReason)
        assertTrue("10-06 不排课", plan[1].slots.isEmpty())

        // 10-07（周三）必须自愈为放假让出日
        assertEquals(com.example.data.calendar.DayOffReason.VACATED, plan[2].offReason)
        assertTrue("10-07 不排课", plan[2].slots.isEmpty())
    }

    private fun ValidatedBundle.toCalendar(): AcademicCalendar {
        val s = semester!!
        return AcademicCalendar(
            semester = Semester(
                name = s.name, yearSpan = s.yearSpan, term = s.term,
                startEpochDay = s.startEpochDay, firstMondayEpochDay = s.firstMondayEpochDay,
                endEpochDay = s.endEpochDay, totalWeeks = s.totalWeeks,
                periodsPerDay = s.periodsPerDay
            ),
            noClassDays = noClassDays.map { it.epochDay }.toSet(),
            makeupDays = makeupDays.associate {
                it.epochDay to MakeupShift(it.sourceEpochDay)
            }
        )
    }
}
