package com.example.data.repository

import androidx.room.withTransaction
import com.example.data.generation.GenerationRuleResolver
import com.example.data.local.AppDatabase
import com.example.data.local.CategoryRuleDao
import com.example.data.local.CourseDao
import com.example.data.local.CourseEntity
import com.example.data.local.CourseSlotDao
import com.example.data.local.CourseSlotEntity
import com.example.data.local.CourseSlotWeekDao
import com.example.data.local.CourseSlotWeekEntity
import com.example.data.local.CourseWithSlots
import com.example.data.local.TaskDao
import com.example.data.local.TaskEntity
import com.example.data.local.toDomain
import com.example.data.model.SlotWithWeeks
import com.example.data.sample.LegacySampleData
import com.example.data.timetable.WeekSet
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 课程、时段与开课周次的读写。
 *
 * 从改造前那个 386 行的 `CourseTodoRepository` 拆出来。多表写入一律走
 * `withTransaction` —— 原来是裸写（insert course → delete slots → insert slots
 * 三步独立提交），中途失败会留下「有课程、无时段」的半截状态。
 *
 * 持有 [TaskDao] 只为一处用途：删课程时把它的待办改挂到「未关联课程」（见 [deleteCourse]）。
 * 跨聚合的写入都在同一个事务里，拆到两个 Repository 再想办法合事务只会更绕。
 */
class CourseRepository(
    private val db: AppDatabase,
    private val courseDao: CourseDao,
    private val slotDao: CourseSlotDao,
    private val weekDao: CourseSlotWeekDao,
    private val taskDao: TaskDao,
    /** 只为新建课程时读类别规则的默认值（见 [saveCourseWithSlots]）。 */
    private val categoryRuleDao: CategoryRuleDao
) {

    fun getAllCourses(): Flow<List<CourseEntity>> = courseDao.getAllCourses()

    fun getAllCoursesWithSlots(): Flow<List<CourseWithSlots>> = courseDao.getAllCoursesWithSlots()

    fun getAllSlots(): Flow<List<CourseSlotEntity>> = slotDao.getAllSlots()

    fun getAllSlotWeeks(): Flow<List<CourseSlotWeekEntity>> = weekDao.getAll()

    /** 时段 id → 开课周次集合。缺 key = 未指定 = 每周。 */
    fun getWeeksBySlotId(): Flow<Map<Long, Set<Int>>> =
        weekDao.getAll().map { rows ->
            rows.groupBy { it.courseSlotId }
                .mapValues { (_, list) -> list.map { it.week }.toSet() }
        }

    suspend fun getCourseById(id: Long): CourseEntity? = courseDao.getCourseById(id)

    /**
     * 只改课程类别。
     *
     * **不走 [saveCourseWithSlots]**：那条路会把时段整体删光重建，
     * 而这里只该动一个字符串字段。顺手重建时段等于把「改类别」变成
     * 「把这门课重新保存一遍」，开课周次都要跟着重来。
     */
    suspend fun updateCourseCategory(id: Long, category: String) =
        courseDao.updateCategory(id, category)

    /** 同 key 可能返回多条（dedupeKey 是非唯一索引，历史数据可能已经重复）。 */
    suspend fun getByDedupeKey(key: String): List<CourseEntity> = courseDao.getByDedupeKey(key)

    suspend fun getSlotsForCourseSync(courseId: Long): List<CourseSlotEntity> =
        slotDao.getSlotsForCourseSync(courseId)

    suspend fun getAllSlotsOnce(): List<CourseSlotEntity> = slotDao.getAllSlotsOnce()

    suspend fun getAllCoursesOnce(): List<CourseEntity> = courseDao.getAllCoursesOnce()

    suspend fun getWeeksBySlotIdOnce(): Map<Long, Set<Int>> =
        weekDao.getAllOnce()
            .groupBy { it.courseSlotId }
            .mapValues { (_, list) -> list.map { it.week }.toSet() }

    suspend fun getAllSlotWeeksOnce(): List<CourseSlotWeekEntity> = weekDao.getAllOnce()

    suspend fun countCourses(): Int = courseDao.getAllCoursesCount()

    /**
     * 保存课程及其全部时段（含开课周次）。
     *
     * 时段一律整体重建：update 分支先删光旧时段再插新的，避免旧时段残留。
     *
     * **只有新建分支会按类别规则初始化三个待办开关**（见 `GenerationRuleResolver.applySwitches`）。
     * 放在这里而不是界面上，是因为导课表 / 浙大同步都走这一条路 ——
     * 类别规则之后要对这个类别下的所有课程说了算，新课一进来就得是对齐的，
     * 否则新建的那门课要等到下次在弹层里应用才被纠正。
     * 编辑分支不动开关：那时这些值是用户自己在课程编辑里给的，不替他改。
     */
    suspend fun saveCourseWithSlots(course: CourseEntity, slots: List<SlotWithWeeks>): Long =
        db.withTransaction {
            val prepared = course.withDedupeKey()
            if (prepared.id == 0L) {
                val categoryRule = categoryRuleDao.getByCategory(prepared.category)?.toDomain()
                val seeded = GenerationRuleResolver.applySwitches(prepared, categoryRule)
                val courseId = courseDao.insertCourse(seeded)
                insertSlotsWithWeeksLocked(courseId, slots)
                courseId
            } else {
                courseDao.updateCourse(prepared)
                slotDao.deleteSlotsByCourseId(prepared.id)
                insertSlotsWithWeeksLocked(prepared.id, slots)
                prepared.id
            }
        }

    /** 只能在事务内调用（供 [saveCourseWithSlots] 复用同一事务）。 */
    private suspend fun insertSlotsWithWeeksLocked(courseId: Long, slots: List<SlotWithWeeks>) {
        if (slots.isEmpty()) return
        val newIds = slotDao.insertSlots(slots.map { it.slot.copy(id = 0, courseId = courseId) })
        val rows = newIds.flatMapIndexed { index, slotId ->
            val weeks = slots.getOrNull(index)?.weeks ?: emptySet<Int>()
            weeks.map { CourseSlotWeekEntity(slotId, it) }
        }
        if (rows.isNotEmpty()) weekDao.insertAll(rows)
    }

    /**
     * 删除课程及其时段，**它的待办不删**：原本挂在上面的一律移入「未关联课程」。
     *
     * 返回被移入「未关联课程」的待办条数，让调用方能如实告诉用户动了多少条 ——
     * 静默改挂一批待办的归属，不说出来就等于让人自己怀疑数据是不是坏了。
     *
     * 待办不属于课表这个聚合根（与 [clearAllCourses] 同一个理由），所以这里只改归属：
     * 课程行消失后，它们原本指向一个已不存在的 id，不改写就会变成既不在任何课程下、
     * 也进不了「未关联课程」分组的悬空行 —— 等于凭空消失，虽然形式上比 DELETE 隐蔽。
     */
    suspend fun deleteCourse(courseId: Long): Int = db.withTransaction {
        val unlinked = taskDao.unlinkTasksByCourseId(
            courseId = courseId,
            unlinkedId = TaskEntity.UNLINKED_COURSE_ID,
            unlinkedName = TaskEntity.UNLINKED_COURSE_NAME,
            unlinkedColor = TaskEntity.UNLINKED_COLOR_HEX
        )
        slotDao.deleteSlotsByCourseId(courseId)
        courseDao.deleteCourseById(courseId)
        unlinked
    }

    /**
     * 清空全部课程与时段（导入 REPLACE 策略用）。
     *
     * **待办不在清空范围内**，这一点是刻意的：
     * 待办是用户自己的数据（手写的、勾选状态、截止日期），换一份课表不该把它清掉。
     * REPLACE 删掉课程后，原本挂在这些课上的待办会因为课程行消失而失去归属，
     * 由调用方在同一个事务里接着处理（见 `ScheduleImportService.applyTaskLinks`）：
     * 课程还在的改挂到新 id 上，不在的移入「未关联课程」。
     *
     * 真要连待办一起清，请显式调 [TaskRepository] / `taskDao` 的清空方法 ——
     * 这个方法的名字里没有 tasks，就不要让它替用户做这个决定。
     */
    suspend fun clearAllCourses() = db.withTransaction {
        slotDao.clearSlots()
        courseDao.clearCourses()
    }

    /**
     * 清掉旧版本自动播种的示例课表与示例待办。
     *
     * 2026-10-20 起 App 不再播种示例数据、也不再提供「恢复示例数据」的入口。
     * 但存量安装里那份示例数据还在，而界面上已经没有任何东西说明它是假的 ——
     * 用户会把「高等数学(上)」当成自己的课，把示例作业当成真作业。
     * 识别判据见 [LegacySampleData]（**宁可漏清、不可误删**）。
     *
     * **用户挂在示例课程上的手写待办不删**，只跟着课程一起失去归属
     * （移入「未关联课程」），与 [deleteCourse] 的既有约定一致。
     *
     * @return 清掉的课程数
     */
    suspend fun clearLegacySampleData(): Int = db.withTransaction {
        // 示例待办先删，**且不依赖示例课程还在不在**。
        //
        // 判据是标题而不是归属：用户删过示例课程的话，那 7 条示例待办早就挂到
        // 「未关联课程」上了（删课程只改归属、不删待办），courseId 已经不是示例课程，
        // 只按 courseId 认就漏掉了 —— 而「未关联课程」里挂着七条假作业，
        // 恰恰是最容易被当成真待办的地方。
        taskDao.getAllTasksOnce()
            .filter { LegacySampleData.isSampleTask(it) }
            .forEach { taskDao.deleteTaskById(it.id) }

        val sampleCourses = courseDao.getAllCoursesOnce().filter { LegacySampleData.isSampleCourse(it) }
        // 剩下的待办（用户自己写的）跟着课程走 unlink，留在「未关联课程」里。
        sampleCourses.forEach { course ->
            taskDao.unlinkTasksByCourseId(
                courseId = course.id,
                unlinkedId = TaskEntity.UNLINKED_COURSE_ID,
                unlinkedName = TaskEntity.UNLINKED_COURSE_NAME,
                unlinkedColor = TaskEntity.UNLINKED_COLOR_HEX
            )
            slotDao.deleteSlotsByCourseId(course.id)
            courseDao.deleteCourseById(course.id)
        }
        sampleCourses.size
    }

    /**
     * 学期周数变化后裁掉越界的开课周次。
     *
     * 缩小总周数时，若某时段的周次被清空，退回「每周」——空集合在 resolver 里
     * 表示每周；让它变成「这学期一次都不上」显然不符合直觉。
     */
    suspend fun clampSlotWeeksTo(totalWeeks: Int) = db.withTransaction {
        weekDao.deleteWeeksAbove(totalWeeks)
        val slotIds = slotDao.getAllSlotsOnce().map { it.id }
        if (slotIds.isEmpty()) return@withTransaction
        val emptied = weekDao.findSlotsMissingFrom(slotIds)
        if (emptied.isEmpty()) return@withTransaction
        val allWeeks = WeekSet.all(totalWeeks)
        val rows = emptied.flatMap { slotId -> allWeeks.map { CourseSlotWeekEntity(slotId, it) } }
        weekDao.insertAll(rows)
    }
}