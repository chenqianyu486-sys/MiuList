package com.example.data.sync.web

import androidx.room.withTransaction
import com.example.data.local.AppDatabase
import com.example.data.local.CourseEntity
import com.example.data.local.CourseSlotEntity
import com.example.data.local.CustomTaskTypeEntity
import com.example.data.local.ExamEntity
import com.example.data.local.TaskEntity
import com.example.data.model.CourseKeys
import com.example.data.model.SlotWithWeeks
import com.example.data.repository.CourseRepository

/** 同步模式。 */
enum class SyncMode(val wire: String) {
    /**
     * **增量**：只把对方给的那几条待办按「后写覆盖」并进来，其余一概不动。
     *
     * 自动同步走这条：两端都在线、且**没有冲突**（同一条待办不会两边都改过），
     * 所以只传各自改过的那几条就够了，不用传全量。
     */
    MERGE("merge"),

    /**
     * **整份替换**：本机整体换成对方那一份。
     *
     * 手动选「以对方为准」时走这条。删除能传播全靠它 ——
     * 增量路径分不清「对方没有这条」是「还没同步」还是「被删了」。
     */
    REPLACE("replace");

    companion object {
        fun of(raw: String?): SyncMode =
            entries.firstOrNull { it.wire.equals(raw, ignoreCase = true) } ?: MERGE
    }
}

/**
 * 把**远端**的快照落进本机。
 *
 * ## 两条路径
 *
 * - [SyncMode.MERGE] —— 增量 upsert。待办逐条按 `updatedAt` 仲裁（后写覆盖）；
 *   课程 / 考试 / 自定义分类整块替换（它们没有逐条版本号，且极少冲突）。
 * - [SyncMode.REPLACE] —— 全量替换。本机整体变成对方那一份。
 *
 * ## 为什么「合并」只在网页端做、手机端只做替换
 *
 * 合并要处理「离线期间攒下的改动」——那批改动在**改动方**那边，
 * 合并算法必须看得见它们。放在网页端做，一份逻辑同时服务两个方向；
 * 手机端只负责「照单全收」，职责干净。
 *
 * ## 护栏
 *
 * 课程 / 考试 / 自定义分类各自带一个 `hasXxx` 标记。
 * 网页端**还没成功拉过一次**手机数据时，它的快照里这三块是空的 ——
 * 那不是「手机上没有课程」，而是「网页端还不知道」。没有标记就照单全收，
 * 会把手机上的课表整个清空 —— 而「刚打开网页端就点同步」最容易踩这个坑。
 *
 * 整个替换在**一个事务**里。中途失败不会留下「课程换了、待办没换」的半截状态。
 */
class SnapshotApplier(
    private val db: AppDatabase,
    private val courseRepo: CourseRepository,
) {

    /** 替换了多少东西。给界面一句「同步进来 N 条待办、M 门课」。 */
    data class Report(
        val tasks: Int = 0,
        val courses: Int = 0,
        val exams: Int = 0,
        val taskTypes: Int = 0,
        /** 本机多出来的待办被删掉了几个（只有整份替换会删）。 */
        val tasksRemoved: Int = 0,
        /** 远端赢了的待办。 */
        val appliedUids: List<String> = emptyList(),
        /** **本机**赢了的待办（远端那份更旧）。这一栏非空就说明有冲突。 */
        val keptUids: List<String> = emptyList(),
    ) {
        val hasConflict: Boolean get() = keptUids.isNotEmpty()
    }

    suspend fun apply(remote: WebSnapshotDto, mode: SyncMode): Report = db.withTransaction {
        // ── 课程（先做：待办要按 courseKey 挂到新的课程 id 上） ──
        val courseIds = if (remote.hasCourses) replaceCourses(remote) else emptyMap()

        // ── 自定义分类 ──
        var taskTypeCount = 0
        if (remote.hasTaskTypes) {
            val dao = db.customTaskTypeDao()
            dao.clear()
            for (type in remote.taskTypes) {
                dao.insert(
                    CustomTaskTypeEntity(
                        id = type.id,
                        label = type.label,
                        colorHex = type.colorHex,
                        inkHex = type.inkHex,
                        sortOrder = type.sortOrder,
                    )
                )
            }
            taskTypeCount = remote.taskTypes.size
        }

        // ── 考试 ──
        var examCount = 0
        if (remote.hasExams) {
            db.examDao().clearExams()
            val entities = remote.exams.map {
                ExamEntity(
                    id = 0,
                    xkkh = "",
                    courseCode = it.courseCode,
                    courseName = it.courseName,
                    kind = it.kind,
                    startAt = it.startAt,
                    endAt = it.endAt,
                    startMinute = it.startMinute,
                    endMinute = it.endMinute,
                    dateLabel = it.dateLabel,
                    classroom = it.classroom,
                    seat = it.seat,
                    credit = it.credit,
                    termLabel = it.termLabel,
                    updatedAt = it.updatedAt,
                )
            }
            if (entities.isNotEmpty()) db.examDao().insertExams(entities)
            examCount = entities.size
        }

        // ── 待办 ──
        val tasks = remote.tasks.filter { it.syncUid.isNotBlank() }
        val report = when (mode) {
            SyncMode.MERGE -> mergeTasks(tasks, courseIds)
            SyncMode.REPLACE -> replaceTasks(tasks, courseIds)
        }

        report.copy(
            courses = courseIds.size,
            exams = examCount,
            taskTypes = taskTypeCount,
        )
    }

    /**
     * 增量：逐条并进来，本机更旧的才让位。
     *
     * 远端那份更旧时**保留本机**并把 uid 报进 [Report.keptUids] ——
     * 那一栏非空就意味着「其实有冲突」，交给用户手动选方向。
     * 自动同步路径上它应该是空的。
     */
    private suspend fun mergeTasks(
        tasks: List<WebTaskDto>,
        courseIds: Map<String, Pair<Long, CourseEntity>>,
    ): Report {
        val applied = mutableListOf<String>()
        val kept = mutableListOf<String>()
        for (task in tasks) {
            val local = db.taskDao().getBySyncUid(task.syncUid)
            if (local == null) {
                db.taskDao().insertTask(task.toEntity(courseIds, createdAt = task.createdAt))
                applied.add(task.syncUid)
                continue
            }
            if (task.updatedAt > local.updatedAt) {
                db.taskDao().updateTask(
                    task.toEntity(courseIds, createdAt = local.createdAt).copy(id = local.id)
                )
                applied.add(task.syncUid)
            } else {
                kept.add(task.syncUid)
            }
        }
        return Report(tasks = tasks.size, appliedUids = applied, keptUids = kept)
    }

    /** 整份：本机多出来的待办删掉，其余按远端重建。 */
    private suspend fun replaceTasks(
        tasks: List<WebTaskDto>,
        courseIds: Map<String, Pair<Long, CourseEntity>>,
    ): Report {
        val localCount = db.taskDao().countTasks()
        db.taskDao().clearTasks()
        val entities = tasks.map { it.toEntity(courseIds, createdAt = it.createdAt) }
        if (entities.isNotEmpty()) db.taskDao().insertTasks(entities)
        return Report(
            tasks = entities.size,
            tasksRemoved = (localCount - entities.size).coerceAtLeast(0),
            appliedUids = tasks.map { it.syncUid },
        )
    }

    /**
     * 远端的一条待办 → 本机实体。
     *
     * **远端的 `updatedAt` 原样保留**：它是下一次合并的仲裁依据。
     * 在这里盖成本机时间等于宣称「本机刚改过」，下一次合并就永远是本机赢，
     * 另一端再也没机会改回来。
     */
    private fun WebTaskDto.toEntity(
        courseIds: Map<String, Pair<Long, CourseEntity>>,
        createdAt: Long,
    ): TaskEntity {
        val course = courseIds[courseKey]
        return TaskEntity(
            id = 0,
            courseId = course?.first ?: TaskEntity.UNLINKED_COURSE_ID,
            courseName = course?.second?.name ?: TaskEntity.UNLINKED_COURSE_NAME,
            courseCategory = course?.second?.category.orEmpty(),
            courseColorHex = course?.second?.colorHex ?: TaskEntity.UNLINKED_COLOR_HEX,
            title = title,
            description = description,
            taskType = taskType,
            startTimeTimestamp = startTimeTimestamp,
            startTimeString = startTimeString,
            dueDateTimestamp = dueDateTimestamp,
            dueDateString = dueDateString,
            priority = priority,
            isCompleted = isCompleted,
            completedAt = completedAt,
            isAutoGenerated = isAutoGenerated,
            generatedDate = null,
            week = week,
            dayOfWeek = dayOfWeek,
            createdAt = if (createdAt > 0) createdAt else System.currentTimeMillis(),
            updatedAt = updatedAt,
            syncUid = syncUid,
        )
    }

    /**
     * 整档替换课程与时段。
     *
     * **先清后插**：课表是 REPLACE 语义（见 `ScheduleImportService`），
     * 留着本机独有的课程会让「以远端为准」变成「以远端为准再补上本机的」，
     * 那不是任何一种用户能选的方向。
     *
     * 课程用**自然键**（`CourseKeys.compute`）认人：两台机器的自增 id 一定不同，
     * 拿 id 认人等于把一端的主键强加给另一端。
     */
    private suspend fun replaceCourses(remote: WebSnapshotDto): Map<String, Pair<Long, CourseEntity>> {
        courseRepo.clearAllCourses()
        val result = LinkedHashMap<String, Pair<Long, CourseEntity>>()
        for (course in remote.courses) {
            val key = course.key.ifBlank {
                CourseKeys.compute(course.code, course.name, course.teacher)
            }
            val entity = CourseEntity(
                id = 0,
                name = course.name,
                code = course.code,
                category = course.category,
                teacher = course.teacher,
                classroom = course.classroom,
                colorHex = course.colorHex,
                note = course.note,
                dedupeKey = key,
            )
            val slots = course.slots.map {
                SlotWithWeeks(
                    slot = CourseSlotEntity(
                        id = 0,
                        courseId = 0,
                        dayOfWeek = it.dayOfWeek,
                        startPeriod = it.startPeriod,
                        endPeriod = it.endPeriod,
                        classroom = it.classroom,
                    ),
                    weeks = it.weeks.toSet(),
                )
            }
            val id = courseRepo.saveCourseWithSlots(entity, slots)
            result[key] = id to entity.copy(id = id)
        }
        return result
    }
}
