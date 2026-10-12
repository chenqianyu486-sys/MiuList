package com.example.data.repository

import androidx.room.withTransaction
import com.example.data.local.AppDatabase
import com.example.data.local.CourseDao
import com.example.data.local.CourseEntity
import com.example.data.local.CourseSlotDao
import com.example.data.local.CourseSlotEntity
import com.example.data.local.CourseSlotWeekDao
import com.example.data.local.CourseSlotWeekEntity
import com.example.data.local.CourseWithSlots
import com.example.data.local.TaskDao
import com.example.data.model.SlotWithWeeks
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
 * 持有 [TaskDao] 只为一处用途：删课程时级联清掉它的待办。跨聚合的写入都在
 * 同一个事务里，拆到两个 Repository 再想办法合事务只会更绕。
 */
class CourseRepository(
    private val db: AppDatabase,
    private val courseDao: CourseDao,
    private val slotDao: CourseSlotDao,
    private val weekDao: CourseSlotWeekDao,
    private val taskDao: TaskDao
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
     */
    suspend fun saveCourseWithSlots(course: CourseEntity, slots: List<SlotWithWeeks>): Long =
        db.withTransaction {
            val prepared = course.withDedupeKey()
            if (prepared.id == 0L) {
                val courseId = courseDao.insertCourse(prepared)
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

    /** 删除课程及其时段与待办。 */
    suspend fun deleteCourse(courseId: Long) = db.withTransaction {
        taskDao.deleteTasksByCourseId(courseId)
        slotDao.deleteSlotsByCourseId(courseId)
        courseDao.deleteCourseById(courseId)
    }

    /** 清空全部课表内容（导入 REPLACE 策略 / 重置示例数据用）。待办由调用方在同一事务清。 */
    suspend fun clearAllCourses() = db.withTransaction {
        taskDao.clearTasks()
        slotDao.clearSlots()
        courseDao.clearCourses()
    }

    suspend fun resetToDemoData(
        courses: List<CourseEntity>,
        slots: List<CourseSlotEntity>,
        tasks: List<com.example.data.local.TaskEntity>
    ) = db.withTransaction {
        taskDao.clearTasks()
        slotDao.clearSlots()
        courseDao.clearCourses()
        weekDao.clear()
        courseDao.insertCourses(courses.map { it.withDedupeKey() })
        slotDao.insertSlots(slots)
        // 示例数据的时段一律按每周处理：开课周次表留空 = 每周
        taskDao.insertTasks(tasks)
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