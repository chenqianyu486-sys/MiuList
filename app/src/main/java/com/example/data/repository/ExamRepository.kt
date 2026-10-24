package com.example.data.repository

import androidx.room.withTransaction
import com.example.data.calendar.AppZone
import com.example.data.local.AppDatabase
import com.example.data.local.CourseDao
import com.example.data.local.CourseEntity
import com.example.data.local.ExamDao
import com.example.data.local.ExamEntity
import com.example.data.model.CourseKeys
import com.example.data.model.ExamEntry
import com.example.data.model.ExamKind
import com.example.data.source.zju.ZjuExamMapper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.Instant

/**
 * 考试的读写。
 *
 * ## 考试是独立的一份数据
 *
 * 它不参与课表的导入 / 导出 / 快照那条管线（那是 `ScheduleBundle` 的地盘），
 * 也不随课程增删而增删 —— 考试是学校已经公布的事实，
 * 用户删掉一门课不等于那场考试不存在了。
 *
 * 但它**和课程有关联**：界面上要继承课程的颜色、能点进课程详情。
 * 这个关联在**读时**解析（见 [getExamEntries]），而不是靠外键：
 * 外键会让「删课」连带「删考试」，那是错的。
 */
class ExamRepository(
    private val db: AppDatabase,
    private val examDao: ExamDao,
    private val courseDao: CourseDao
) {

    fun getAllExams(): Flow<List<ExamEntity>> = examDao.getAllExams()

    suspend fun countExams(): Int = examDao.countExams()

    /**
     * 考试 + 它对应的那门课。
     *
     * 关联按可靠性排两级：**课程代码**优先，**课程名**兜底。
     * 两级都对不上时 [ExamEntry.courseId] 为 null ——
     * 考试照常显示，只是没有颜色可继承、也不能点进课程详情。
     */
    fun getExamEntries(): Flow<List<ExamEntry>> =
        combine(examDao.getAllExams(), courseDao.getAllCourses()) { exams, courses ->
            link(exams, courses)
        }

    /**
     * 用一次抓取的结果替换全部考试。
     *
     * ## 空结果**不覆盖**
     *
     * 学校还没安排考试时接口会回空（`items` 为空数组）。
     * 那时如果照着「服务端即真相」把本地清掉，用户在界面上会看到
     * 「刚同步完，考试全没了」—— 而实际上学校只是**还没公布**，
     * 上一份数据才是此刻唯一有用的信息。
     *
     * 这与本项目在课表上的既有约定是同一条：**空结果不覆盖缓存**。
     *
     * @return 实际写入的条数；空结果时返回 0 且**不动**已有的数据。
     */
    suspend fun replaceAll(rows: List<ZjuExamMapper.ExamRow>): Int = db.withTransaction {
        if (rows.isEmpty()) return@withTransaction 0
        val now = System.currentTimeMillis()
        val entities = rows.map { it.toEntity(now) }
        examDao.clearExams()
        examDao.insertExams(entities)
        entities.size
    }

    /**
     * 本地改一场考试：考场、座位、考试时刻。
     *
     * ## 改完留到下一次教务网同步为止
     *
     * [replaceAll] 是**整表覆盖**：同步时服务端那份会把本地的一并盖掉。
     * 这正是「本地可编辑、但以教务网为准」的意思 —— 本地编辑是**临时补丁**
     * （比如学校还没公布座位号，学生从通知里抄一个），不是对学校数据的修改。
     *
     * 所以界面上必须把这句话说清楚，否则用户会以为改了就稳了。
     *
     * @return 是否真的改到了（id 不存在时返回 false）。
     */
    suspend fun updateLocal(
        entry: ExamEntry,
        classroom: String,
        seat: String,
        startMinute: Int,
        endMinute: Int
    ): Boolean = db.withTransaction {
        if (entry.id == 0L) return@withTransaction false
        // 绝对时刻要跟着新时刻重算：换的是**当天的时间**，
        // 日期不变（考试换到另一天是另一件事，不在这次编辑的范围内）。
        val startAt = if (entry.startAt > 0L) {
            millisOfDay(entry.startAt, startMinute)
        } else {
            0L
        }
        val endAt = if (startAt > 0L) millisOfDay(entry.startAt, endMinute) else 0L
        examDao.updateExam(
            id = entry.id,
            classroom = classroom.trim(),
            seat = seat.trim(),
            startAt = startAt,
            endAt = endAt,
            startMinute = startMinute,
            endMinute = endMinute,
            updatedAt = System.currentTimeMillis()
        )
        true
    }

    /** 某个绝对时刻所在的那一天 + 当天第几分钟 → 绝对毫秒。 */
    private fun millisOfDay(dayAnchorMillis: Long, minuteOfDay: Int): Long {
        val date = Instant.ofEpochMilli(dayAnchorMillis)
            .atZone(AppZone.ZONE)
            .toLocalDate()
        return date.atStartOfDay(AppZone.ZONE).toInstant().toEpochMilli() +
            minuteOfDay * 60_000L
    }

    private fun link(exams: List<ExamEntity>, courses: List<CourseEntity>): List<ExamEntry> {
        val byCode = courses
            .filter { it.code.isNotBlank() }
            .associateBy { it.code.trim() }
        val byName = courses
            .filter { it.name.isNotBlank() }
            .groupBy { normalizeName(it.name) }

        return exams.map { exam ->
            val matched = byCode[exam.courseCode.trim()]
                ?: byName[normalizeName(exam.courseName)]?.firstOrNull()
            ExamEntry(
                id = exam.id,
                // **显示用课程表里的名字**（对得上的话）。
                //
                // 教务网两个接口对同一门课的写法可能不一致：
                // 课表里是「大学物理（甲）II」（全角括号），考试里是「大学物理(甲)II」（半角）。
                // 两张卡并排出现在同一屏上时，名字写法不一样会让人以为是两门课。
                // 对不上课时退回考试自己那份 —— 那时它多半还没进用户的课表。
                courseName = matched?.name?.takeIf { it.isNotBlank() } ?: exam.courseName,
                kind = ExamKind.of(exam.kind),
                startAt = exam.startAt,
                endAt = exam.endAt,
                startMinute = exam.startMinute,
                endMinute = exam.endMinute,
                dateLabel = exam.dateLabel,
                classroom = exam.classroom,
                seat = exam.seat,
                credit = exam.credit,
                termLabel = exam.termLabel,
                courseId = matched?.id,
                courseColorHex = matched?.colorHex?.takeIf { it.isNotBlank() }
                    ?: ExamEntry.UNLINKED_COLOR_HEX
            )
        }
    }

    /**
     * 课程名比对用的归一化。
     *
     * 括号要一起归一：教务网在课表与考试两个接口里对同一门课的写法可能不一致
     * （「大学物理(甲)II」与「大学物理（甲）II」），而归一后就能对上。
     */
    private fun normalizeName(value: String): String =
        CourseKeys.normalize(value.replace('(', '（').replace(')', '）'))
}

/** 解析出来的一场考试 → 落库实体。 */
private fun ZjuExamMapper.ExamRow.toEntity(updatedAt: Long): ExamEntity = ExamEntity(
    xkkh = xkkh,
    courseCode = courseCode,
    courseName = courseName,
    kind = kind,
    startAt = startAt,
    endAt = endAt,
    startMinute = startMinute,
    endMinute = endMinute,
    dateLabel = dateLabel,
    classroom = classroom,
    seat = seat,
    credit = credit,
    termLabel = termLabel,
    updatedAt = updatedAt
)
