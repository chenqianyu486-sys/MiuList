package com.example.data.repository

import androidx.room.withTransaction
import com.example.data.calendar.AcademicCalendar
import com.example.data.calendar.EpochDays
import com.example.data.calendar.MakeupShift
import com.example.data.calendar.Semester
import com.example.data.local.AppDatabase
import com.example.data.local.CalendarDao
import com.example.data.local.MakeupDayEntity
import com.example.data.local.NoClassDayEntity
import com.example.data.local.SemesterEntity
import com.example.data.local.TimetablePeriodEntity
import com.example.data.timetable.PeriodSlot
import com.example.data.timetable.PeriodTable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * 学期、每日作息节数、停课日与补课日。
 *
 * 四张表总是被一起读取与一起替换，所以共用一个 DAO 和一个 Repository。
 */
class AcademicCalendarRepository(
    private val db: AppDatabase,
    private val calendarDao: CalendarDao
) {

    val semester: Flow<SemesterEntity?> = calendarDao.semester()
    val timetablePeriods: Flow<List<TimetablePeriodEntity>> = calendarDao.timetablePeriods()
    val noClassDays: Flow<List<NoClassDayEntity>> = calendarDao.noClassDays()
    val makeupDays: Flow<List<MakeupDayEntity>> = calendarDao.makeupDays()

    /** 把四张表合成一个 [AcademicCalendar] + 一个 [PeriodTable]。 */
    val calendarState: Flow<CalendarState> =
        combine(semester, timetablePeriods, noClassDays, makeupDays) { sem, periods, noClass, makeup ->
            CalendarState(
                calendar = AcademicCalendar(
                    semester = sem?.toDomain(),
                    noClassDays = noClass.map { it.epochDay }.toSet(),
                    // 只搬来源日：补周几由它算，不搬实体里那个派生列
                    makeupDays = makeup.associate {
                        it.epochDay to MakeupShift(it.sourceEpochDay)
                    }
                ),
                periodTable = PeriodTable.from(
                    periods.map { PeriodSlot(it.period, it.startMinute, it.endMinute, it.section) }
                )
            )
        }

    suspend fun semesterOnce(): SemesterEntity? = calendarDao.semesterOnce()

    suspend fun periodsOnce(): List<TimetablePeriodEntity> = calendarDao.timetablePeriodsOnce()

    suspend fun noClassDaysOnce(): List<NoClassDayEntity> = calendarDao.noClassDaysOnce()

    suspend fun makeupDaysOnce(): List<MakeupDayEntity> = calendarDao.makeupDaysOnce()

    /**
     * 首次启动时写入内置作息。
     *
     * 必须做这一步：`timetable_periods` 只有两个写入点 —— v3→v4 迁移（老用户）
     * 和导入带作息表的文件。全新安装两条都不走，表就是空的，于是
     * `PeriodTable.from(emptyList())` 得到 `periodsPerDay = 0`，
     * 课表栅格一节都画不出来 —— 新用户第一次打开看到的是空课表。
     *
     * 默认值给的是**浙大作息**而不是通用表：新装用户装的就是浙大课表，
     * 给错表等于让他们先看到一屏时间全错的课再自己改。两张表节数同为 13，
     * 但时刻不同 —— 分清它们要看第 5 节的起始，不是看节数。
     * v3→v4 迁移仍然写 [PeriodTable.DEFAULT_PERIODS]，那是历史值不能动。
     *
     * 与 [ScheduleSettingsRepository.ensureDefaultRules] 同形：只在表为空时写，
     * 已经配置过作息的用户不受影响。
     */
    suspend fun ensureDefaultPeriods(
        periods: List<PeriodSlot> = PeriodTable.ZJU_PERIODS
    ) = db.withTransaction {
        if (calendarDao.timetablePeriodsOnce().isNotEmpty()) return@withTransaction
        if (periods.isNotEmpty()) calendarDao.insertPeriods(periods.map { toEntity(it) })
    }

    /**
     * 清理补课日中的非法/脏数据（如老版本对调逻辑残留的「工作日补周末课」或来源日为空的行）。
     *
     * 节假日调休只会在周末补工作日课（或工作日间互换），工作日作为让出日绝不应该是补课日。
     * 本方法在启动时自动执行自愈，消除历史残留脏数据。
     */
    suspend fun sanitizeMakeupDays() = db.withTransaction {
        val all = calendarDao.makeupDaysOnce()
        val invalidDays = all.filter {
            it.sourceEpochDay > 0L && EpochDays.isWeekend(it.sourceEpochDay) && !EpochDays.isWeekend(it.epochDay)
        }.map { it.epochDay }
        if (invalidDays.isNotEmpty()) {
            calendarDao.deleteMakeupDays(invalidDays)
        }
    }

    /** 替换整个校历配置。作息表传空列表表示「不动」。 */
    suspend fun saveCalendar(
        semester: SemesterEntity?,
        periods: List<TimetablePeriodEntity>,
        noClass: List<NoClassDayEntity>,
        makeup: List<MakeupDayEntity>,
        replacePeriods: Boolean,
        replaceNoClass: Boolean,
        replaceMakeup: Boolean
    ) = db.withTransaction {
        if (semester != null) {
            calendarDao.upsertSemester(semester)
        } else if (replacePeriods || replaceNoClass || replaceMakeup) {
            // REPLACE 策略下文件没带校历 = 清空为未配置
            calendarDao.clearSemester()
        }
        if (replacePeriods) {
            calendarDao.clearPeriods()
            if (periods.isNotEmpty()) calendarDao.insertPeriods(periods)
        }
        if (replaceNoClass) {
            calendarDao.clearNoClassDays()
            if (noClass.isNotEmpty()) calendarDao.insertNoClassDays(noClass)
        }
        if (replaceMakeup) {
            calendarDao.clearMakeupDays()
            val validMakeup = makeup.filterNot {
                it.sourceEpochDay > 0L && EpochDays.isWeekend(it.sourceEpochDay) && !EpochDays.isWeekend(it.epochDay)
            }
            if (validMakeup.isNotEmpty()) calendarDao.insertMakeupDays(validMakeup)
        }
    }

    data class CalendarState(
        val calendar: AcademicCalendar,
        val periodTable: PeriodTable
    )

    companion object {
        /**
         * 学期名兜底：`20xx-20xx学年 秋冬学期` / `春夏学期`。
         *
         * 浙大的学期单位是**学年学期**（秋冬 / 春夏，各 16 周，下设秋(春)1–8 周、
         * 冬(夏)9–16 周），不是「秋季学期 / 春季学期」——后者会把 16 周的学期
         * 说成 8 周的那一半。
         *
         * 学年以**秋天**为界：8–12 月与次年 1 月都算同一个学年的秋冬学期。
         */
        fun defaultName(nowEpochDay: Long = EpochDays.today()): String {
            val date = java.time.LocalDate.ofEpochDay(nowEpochDay)
            val startYear = when {
                date.monthValue >= 8 -> date.year      // 8–12 月：学年刚开始
                date.monthValue == 1 -> date.year - 1  // 次年 1 月：仍是上一个学年
                else -> date.year - 1                  // 2–7 月：学年过半
            }
            val season = if (date.monthValue >= 8 || date.monthValue == 1) "秋冬" else "春夏"
            return "$startYear-${startYear + 1}学年 $season 学期"
        }

        /**
         * 学期名 → [SemesterEntity.term]，按**学年学期**口径（秋冬 / 春夏）。
         *
         * 「秋冬 / 春夏」必须判在单字前面：「秋冬」里也有个「秋」，先判单字会把一个
         * 16 周的学年学期记成 8 周的半学期，导出时 `termConfigs` 就只写得出秋那一条。
         * 旧名字（「…学年 秋季 学期」）仍按原样存回去 —— 不能因为换了口径就让已有学期读丢。
         *
         * 放在这里而不是各自写一份：弹层手填学期名时要用它，
         * 「应用官方校历」直接写官方那份 label 时也要用它，两边算出来的
         * `term` 必须一致 —— 不一致会让同一条学期在导出里一会儿 8 周一会儿 16 周。
         */
        fun seasonOf(name: String): String = when {
            name.contains("秋冬") -> "秋冬"
            name.contains("春夏") -> "春夏"
            name.contains("秋") -> "秋季"
            name.contains("春") -> "春季"
            else -> ""
        }

        fun toEntity(s: Semester): SemesterEntity = SemesterEntity(
            id = SemesterEntity.SINGLETON_ID,
            name = s.name,
            yearSpan = s.yearSpan,
            term = s.term,
            startEpochDay = s.startEpochDay,
            firstMondayEpochDay = s.firstMondayEpochDay,
            endEpochDay = s.endEpochDay,
            totalWeeks = s.totalWeeks,
            periodsPerDay = s.periodsPerDay
        )

        fun SemesterEntity.toDomain() = Semester(
            name = name,
            yearSpan = yearSpan,
            term = term,
            startEpochDay = startEpochDay,
            firstMondayEpochDay = firstMondayEpochDay,
            endEpochDay = endEpochDay,
            totalWeeks = totalWeeks,
            periodsPerDay = periodsPerDay
        )

        fun toEntity(p: PeriodSlot) = TimetablePeriodEntity(p.period, p.startMinute, p.endMinute, p.section)
    }
}