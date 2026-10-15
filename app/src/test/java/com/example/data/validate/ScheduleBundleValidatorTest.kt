package com.example.data.validate

import com.example.data.calendar.TweakKind
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

    /**
     * termRef 指向哪一半都行，落地的学期都是**那个学年学期**。
     *
     * 浙大一个学期是 16 周，由秋(1–8 周)与冬(9–16 周)两条 `termConfigs` 拼成。
     * 之前按被选中的那一条算，学期只剩 8 周，冬那 8 周的日期全部越界，
     * 课程周号已经按 16 周平移过，两端口径对不上，后半学期整段没课。
     *
     * 这里的 termConfigs 按真实形态摆：秋冬用 Term 0/1，春夏用 Term 4/5，
     * 同一个 Year "2026-2027" 下挂两个学年学期。
     */
    @Test
    fun `termRef 指向任意一半都展开成整个学年学期`() {
        val cal = CalendarBundle(
            termConfigs = listOf(
                TermConfigBundle("2026-2027", 0, 20260914, 20261108, 1),
                TermConfigBundle("2026-2027", 1, 20261109, 20270103, 1),
                TermConfigBundle("2026-2027", 4, 20270222, 20270418, 1),
                TermConfigBundle("2026-2027", 5, 20270419, 20270613, 1)
            )
        )

        val autumn = valid(
            ScheduleBundle(calendar = cal, timetable = timetable(course(), ref = TermRefBundle("2026-2027", 0)))
        ).semester!!
        val byWinter = valid(
            ScheduleBundle(calendar = cal, timetable = timetable(course(), ref = TermRefBundle("2026-2027", 1)))
        ).semester!!
        val spring = valid(
            ScheduleBundle(calendar = cal, timetable = timetable(course(), ref = TermRefBundle("2026-2027", 4)))
        ).semester!!

        assertEquals("2026-2027学年 秋冬 学期", autumn.name)
        assertEquals("秋冬", autumn.term)
        // 秋 8 周 + 冬 8 周 = 16 周，而不是只按秋那一条算出来的 8 周
        assertEquals(16, autumn.totalWeeks)
        assertEquals(day(20260914), autumn.firstMondayEpochDay)
        assertEquals(day(20270103), autumn.endEpochDay)
        // 指秋与指冬得到同一个学期
        assertEquals(autumn.name, byWinter.name)
        assertEquals(autumn.totalWeeks, byWinter.totalWeeks)
        assertEquals(autumn.endEpochDay, byWinter.endEpochDay)

        // 春夏是另一个学年学期，不能与秋冬混成一条
        assertEquals("2026-2027学年 春夏 学期", spring.name)
        assertEquals(day(20270222), spring.firstMondayEpochDay)
        assertEquals(day(20270613), spring.endEpochDay)
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
    fun `clear wipes every day in its range`() {
        val b = valid(
            ScheduleBundle(
                calendar = CalendarBundle(
                    termConfigs = listOf(TermConfigBundle("2026-2027", 0, 20260907, 20270124, 1)),
                    tweaks = listOf(TweakBundle(TweakKind.CLEAR, "国庆", 20261001, 20261007))
                ),
                timetable = timetable(course(), ref = null)
            )
        )
        assertEquals(7, b.noClassDays.size)
        assertTrue(b.noClassDays.all { it.note == "国庆" })
    }

    /**
     * `Move` 搬走 From 那天的课，From 必须**让出课**；但不额外写一条停课日。
     *
     * 「让出」由 `makeupDays[To].sourceEpochDay` 完整表达（见
     * `AcademicCalendar.isVacatedForShift`）。再抄一份进 `noClassDays` 就是同一事实
     * 存两处：导出会合成一条 CLEAR 加一条 MOVE，导回来凭空多一个停课日。
     */
    @Test
    fun `move vacates the from-day without duplicating a no-class row`() {
        val b = valid(
            ScheduleBundle(
                calendar = CalendarBundle(
                    termConfigs = listOf(TermConfigBundle("2026-2027", 0, 20260907, 20270124, 1)),
                    tweaks = listOf(TweakBundle(TweakKind.MOVE, "补周五", 20261002, 20261017))
                ),
                timetable = timetable(course(), ref = null)
            )
        )
        assertEquals("不额外写停课日", emptyList<Long>(), b.noClassDays.map { it.epochDay })
        val makeup = b.makeupDays.single()
        assertEquals(day(20261017), makeup.epochDay)
        assertEquals("To 那天上 From 那天的课 = 周五的课", 5, makeup.makeupForDayOfWeek)
        assertEquals("来源日要一并记下，排课才知道算第几周", day(20261002), makeup.sourceEpochDay)
    }

    @Test
    fun `exchange swaps both days`() {
        val b = valid(
            ScheduleBundle(
                calendar = CalendarBundle(
                    termConfigs = listOf(TermConfigBundle("2026-2027", 0, 20260907, 20270124, 1)),
                    tweaks = listOf(TweakBundle(TweakKind.EXCHANGE, "对调", 20260907, 20260910))
                ),
                timetable = timetable(course(), ref = null)
            )
        )
        assertEquals(2, b.makeupDays.size)
        // 09-10 是周四，周四上 09-07（周一）的课；09-07 上 09-10（周四）的课
        assertEquals(day(20260910) to 1, b.makeupDays.first().epochDay to b.makeupDays.first().makeupForDayOfWeek)
        assertEquals(day(20260907) to 4, b.makeupDays[1].epochDay to b.makeupDays[1].makeupForDayOfWeek)
        // 两侧各自记下来源日：09-10 那场来自 09-07（第 1 周），09-07 那场来自 09-10（第 1 周）
        assertEquals(day(20260907), b.makeupDays.first().sourceEpochDay)
        assertEquals(day(20260910), b.makeupDays[1].sourceEpochDay)
        assertTrue("对调不是停课", b.noClassDays.isEmpty())
    }

    @Test
    fun `a tweak entirely outside the term is ignored`() {
        val b = valid(
            ScheduleBundle(
                calendar = CalendarBundle(
                    termConfigs = listOf(TermConfigBundle("2026-2027", 0, 20260907, 20270124, 1)),
                    // 整段落在学期结束之后（寒假）
                    tweaks = listOf(TweakBundle(TweakKind.CLEAR, "寒假", 20270125, 20270201))
                ),
                timetable = timetable(course(), ref = null)
            )
        )
        assertTrue("整段在学期区间外，应被忽略", b.noClassDays.isEmpty())
        assertTrue(b.issues.any { it.detail.contains("不在学期区间内") })
    }

    /**
     * 认不出来的类型必须**报出来并整条忽略**，不能默认成停课。
     *
     * 默认成 CLEAR 会把一条看不懂的规则变成「这周有几天没课」，用户完全不知道。
     */
    @Test
    fun `an unrecognised tweak type is reported rather than silently applied`() {
        val b = valid(
            ScheduleBundle(
                calendar = CalendarBundle(
                    termConfigs = listOf(TermConfigBundle("2026-2027", 0, 20260907, 20270124, 1)),
                    tweaks = listOf(TweakBundle(null, "?", 20261001, 20261002))
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
    fun `a course with no category but a lab name gets the lab one`() {
        // 文件导入也走同一套判定：教务系统导出的课表不带类别，
        // 名字带「实验」的课不该顶着一个「公共基础」的名字混在基础课里。
        val b = valid(ScheduleBundle(timetable = timetable(course(name = "大学物理实验"))))
        assertEquals("实验实践", b.courses.first().category)
    }

    @Test
    fun `a category written in the document wins over the guess`() {
        // 文档显式写了类别就照用 —— 导入不能把用户已经整理好的类别改回去。
        val explicit = course().copy(category = "通识公共")
        val b = valid(ScheduleBundle(timetable = timetable(explicit)))
        assertEquals("通识公共", b.courses.first().category)
    }

    // ---------------------------------------------------------------- 相连时段合并

    @Test
    fun `同一门课同一天同一地点的相连时段被合并`() {
        // 教务系统常把连堂课拆成多行发；不合并的话课表上是两块挤在一起的卡。
        val c = course(
            slots = listOf(
                com.example.data.codec.SlotBundle(1, 6, 6, "教二-301", null),
                com.example.data.codec.SlotBundle(1, 7, 7, "教二-301", null)
            )
        )
        val b = valid(ScheduleBundle(timetable = timetable(c)))
        val slots = b.courses.first().slots
        assertEquals(1, slots.size)
        assertEquals(6, slots.first().startPeriod)
        assertEquals(7, slots.first().endPeriod)
        assertTrue(b.issues.any { it.detail.contains("已合并") })
    }

    @Test
    fun `三段连排合并成一段`() {
        val c = course(
            slots = listOf(
                com.example.data.codec.SlotBundle(1, 1, 1, "教二-301", null),
                com.example.data.codec.SlotBundle(1, 2, 2, "教二-301", null),
                com.example.data.codec.SlotBundle(1, 3, 3, "教二-301", null)
            )
        )
        val b = valid(ScheduleBundle(timetable = timetable(c)))
        val slot = b.courses.first().slots.first()
        assertEquals(1, b.courses.first().slots.size)
        assertEquals(1, slot.startPeriod)
        assertEquals(3, slot.endPeriod)
        // issue 要说清并成了什么，而不是只丢一句「已处理」
        assertTrue(b.issues.any { it.detail.contains("第1-3节") })
    }

    @Test
    fun `中间空一节的不合并`() {
        val c = course(
            slots = listOf(
                com.example.data.codec.SlotBundle(1, 1, 2, "教二-301", null),
                com.example.data.codec.SlotBundle(1, 4, 5, "教二-301", null)
            )
        )
        val b = valid(ScheduleBundle(timetable = timetable(c)))
        assertEquals(2, b.courses.first().slots.size)
    }

    @Test
    fun `地点不同的两段不合并`() {
        // 同一节课换了教室就是两件事，并起来会把其中一个地点吞掉。
        val c = course(
            slots = listOf(
                com.example.data.codec.SlotBundle(1, 6, 6, "教二-301", null),
                com.example.data.codec.SlotBundle(1, 7, 7, "紫金港西2-101", null)
            )
        )
        val b = valid(ScheduleBundle(timetable = timetable(c)))
        assertEquals(2, b.courses.first().slots.size)
    }

    @Test
    fun `星期不同的两段不合并`() {
        val c = course(
            slots = listOf(
                com.example.data.codec.SlotBundle(1, 6, 6, "教二-301", null),
                com.example.data.codec.SlotBundle(2, 7, 7, "教二-301", null)
            )
        )
        val b = valid(ScheduleBundle(timetable = timetable(c)))
        assertEquals(2, b.courses.first().slots.size)
    }

    @Test
    fun `周次不同的两段不合并`() {
        // 这条最容易写错：同一地点的第1-8节与第9-16节是学期前后两段，
        // 并起来周次就变成 1-16 —— 凭空多出一半课，比不合并错得离谱。
        val c = course(
            slots = listOf(
                com.example.data.codec.SlotBundle(1, 1, 8, "教二-301", listOf(1, 2, 3, 4, 5, 6, 7, 8)),
                com.example.data.codec.SlotBundle(1, 9, 16, "教二-301", listOf(9, 10, 11, 12, 13, 14, 15, 16))
            )
        )
        val b = valid(ScheduleBundle(timetable = timetable(c)))
        assertEquals(2, b.courses.first().slots.size)
    }

    @Test
    fun `每周与整学期全周是同一组周次 可以并`() {
        // 「每周」(null) 与「第1-20周」经 WeekSet.parse 之后都是 {1..20}，
        // 语义相同就该并；分组键用归一化后的集合，正好自动满足这一条。
        val b = valid(
            ScheduleBundle(
                calendar = term(),
                timetable = timetable(
                    course(
                        slots = listOf(
                            com.example.data.codec.SlotBundle(1, 1, 2, "教二-301", null),
                            com.example.data.codec.SlotBundle(1, 3, 4, "教二-301", "1-20周")
                        )
                    )
                )
            )
        )
        val slots = b.courses.first().slots
        assertEquals(1, slots.size)
        assertEquals(1, slots.first().startPeriod)
        assertEquals(4, slots.first().endPeriod)
    }

    @Test
    fun `没有可合并的时段就不产生合并 issue`() {
        val b = valid(ScheduleBundle(timetable = timetable(course())))
        assertTrue(b.issues.none { it.detail.contains("已合并") })
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
                        TweakBundle(TweakKind.CLEAR, "国庆", 20261001, 20261007),
                        TweakBundle(TweakKind.MOVE, "补周一", 20260907, 20261010)
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
        // 国庆 7 天。Move 的 From（09-07）不额外记停课日，它由 sourceEpochDay 表达
        assertEquals(7, b.noClassDays.size)
        assertEquals(1, b.makeupDays.size)
        assertEquals(1, b.courses.size)
        assertEquals(13, b.semester!!.periodsPerDay)
        // 课程自动化段里的截止天数必须原样带过来
        assertEquals(6, b.courses.first().automation!!.defaultPostDueDays)
    }
}