package com.example.data.transfer

import androidx.room.withTransaction
import com.example.data.calendar.EpochDays
import com.example.data.codec.CourseBundle
import com.example.data.codec.DecodeResult
import com.example.data.codec.ScheduleCodec
import com.example.data.codec.SlotBundle
import com.example.data.local.AppDatabase
import com.example.data.local.CourseEntity
import com.example.data.local.CourseSlotEntity
import com.example.data.local.TaskEntity
import com.example.data.model.CourseKeys
import com.example.data.model.CategoryRule
import com.example.data.model.SlotWithWeeks
import com.example.data.repository.AcademicCalendarRepository
import com.example.data.repository.CourseRepository
import com.example.data.repository.ScheduleSettingsRepository
import com.example.data.timetable.WeekSet
import com.example.data.validate.ScheduleBundleValidator
import com.example.data.validate.ValidatedBundle
import com.example.data.validate.ValidationResult

sealed interface PreviewResult {
    data class Failed(val message: String) : PreviewResult
    data class Ready(
        val validated: ValidatedBundle,
        val preview: ImportPreview
    ) : PreviewResult
}

/**
 * 课表导入编排：**先预览、用户确认、再单事务提交**。
 *
 * 解析 → 校验 → 与库内数据比对，三步都**不写库**；只有 [commit] 会写，
 * 且全程一个事务：任一步抛异常就整体回滚，DB 回到导入前，
 * 不存在「导了一半」的中间态。
 *
 * 待办**不属于课表**。整表替换只换课表，不动用户自己的待办：课程还在的
 * 继续挂在那门课上，不在了的移入「未关联课程」，本来就未关联的原样不动。
 * 见 [captureTaskLinks] / [planTaskLinks] / [applyTaskLinks]。
 */
class ScheduleImportService(
    private val db: AppDatabase,
    private val codec: ScheduleCodec,
    private val validator: ScheduleBundleValidator,
    private val courseRepo: CourseRepository,
    private val calendarRepo: AcademicCalendarRepository,
    private val settingsRepo: ScheduleSettingsRepository
) {

    suspend fun preview(text: String, strategy: ImportStrategy): PreviewResult {
        val decoded = when (val r = codec.decode(text)) {
            is DecodeResult.Failure -> return PreviewResult.Failed(r.message)
            is DecodeResult.Success -> r.bundle
        }
        val validated = when (val v = validator.validate(decoded)) {
            is ValidationResult.Invalid -> return PreviewResult.Failed(v.message)
            is ValidationResult.Valid -> v.bundle
        }

        val existingCourses = courseRepo.getAllCoursesOnce()
        val existingByKey = existingCourses.associateBy { it.dedupeKey.ifBlank { CourseKeys.compute(it.code, it.name, it.teacher) } }

        val newCourses = mutableListOf<CourseBundle>()
        val updated = mutableListOf<Pair<String, Long>>()
        var unchanged = 0
        for (c in validated.courses) {
            val key = c.key ?: CourseKeys.compute(c.code.orEmpty(), c.name.orEmpty(), c.teacher.orEmpty())
            val hit = existingByKey[key]
            if (hit == null) newCourses += c else updated += (c.name ?: "") to hit.id
        }
        if (strategy == ImportStrategy.MERGE) {
            unchanged = (existingCourses.size - updated.size).coerceAtLeast(0)
        }

        val existingSemester = calendarRepo.semesterOnce()
        val semesterChanged = validated.semester != null &&
            (existingSemester?.startEpochDay != validated.semester.startEpochDay ||
                existingSemester?.totalWeeks != validated.semester.totalWeeks ||
                existingSemester?.firstMondayEpochDay != validated.semester.firstMondayEpochDay)

        // 只有 REPLACE 会让课程消失，也只有它需要预告「有多少条待办会失去归属」。
        // 计划与提交走同一个 [planTaskLinks]，免得预览说的和实际做的不一致。
        val taskLinkPlan = if (strategy == ImportStrategy.REPLACE) {
            planTaskLinks(
                captureTaskLinks(),
                validated.courses.map { course ->
                    CourseRef(
                        key = course.key ?: CourseKeys.compute(
                            course.code.orEmpty(), course.name.orEmpty(), course.teacher.orEmpty()
                        ),
                        name = course.name.orEmpty(),
                        id = 0L
                    )
                }
            )
        } else {
            emptyMap()
        }

        return PreviewResult.Ready(
            validated = validated,
            preview = ImportPreview(
                strategy = strategy,
                fileCourseCount = validated.courses.size,
                newCourses = newCourses,
                updatedCourses = updated,
                unchangedCount = unchanged,
                issues = validated.issues,
                semesterChanged = semesterChanged,
                semesterSummary = validated.semester?.let {
                    "${it.name} · 第 1-${it.totalWeeks} 周 · ${EpochDays.formatCn(it.startEpochDay)} 起"
                },
                timetableChanged = validated.timetable != null,
                noClassDayCount = validated.noClassDays.size,
                makeupDayCount = validated.makeupDays.size,
                existingCourseCount = existingCourses.size,
                existingTaskCount = existingTaskCount(),
                tasksLosingCourse = taskLinkPlan.count { it.value == null },
                tasksKeepingCourse = taskLinkPlan.count { it.value != null }
            )
        )
    }

    /** 确认后落库。整段在一个事务里。 */
    suspend fun commit(ready: PreviewResult.Ready): ImportReport {
        val v = ready.validated
        val strategy = ready.preview.strategy

        return db.withTransaction {
            // 必须在清课程**之前**记下待办挂在谁身上：清完就再也认不出谁是谁了。
            val taskLinks = if (strategy == ImportStrategy.REPLACE) captureTaskLinks() else emptyList()

            if (strategy == ImportStrategy.REPLACE) {
                courseRepo.clearAllCourses()
                calendarRepo.saveCalendar(
                    semester = v.semester?.toEntity(),
                    periods = v.timetable.orEmpty().map(AcademicCalendarRepository::toEntity),
                    noClass = v.noClassDays,
                    makeup = v.makeupDays,
                    replacePeriods = v.timetable != null,
                    replaceNoClass = true,
                    replaceMakeup = true
                )
            }

            var inserted = 0
            var updatedCount = 0

            for (course in v.courses) {
                val key = course.key ?: CourseKeys.compute(
                    course.code.orEmpty(), course.name.orEmpty(), course.teacher.orEmpty()
                )
                val entity = course.toEntity()
                val hits = courseRepo.getByDedupeKey(key)
                val existing = if (strategy == ImportStrategy.MERGE) hits.firstOrNull() else null
                val slots = course.slots.map { it.toSlotWithWeeks(v) }

                if (existing != null) {
                    courseRepo.saveCourseWithSlots(entity.copy(id = existing.id), slots)
                    updatedCount++
                } else {
                    courseRepo.saveCourseWithSlots(entity.copy(id = 0L), slots)
                    inserted++
                }
            }

            // 课程落库之后才有新 id，待办要挂到这些新 id 上 —— 顺序反了就全挂不上。
            val taskLinkPlan = applyTaskLinks(taskLinks)

            if (strategy == ImportStrategy.MERGE) {
                val semesterChanged = v.semester != null
                val noClassChanged = v.noClassDays.isNotEmpty()
                val makeupChanged = v.makeupDays.isNotEmpty()
                val periodsChanged = v.timetable != null
                if (semesterChanged || periodsChanged || noClassChanged || makeupChanged) {
                    calendarRepo.saveCalendar(
                        semester = v.semester?.toEntity(),
                        periods = v.timetable.orEmpty().map(AcademicCalendarRepository::toEntity),
                        noClass = v.noClassDays,
                        makeup = v.makeupDays,
                        replacePeriods = periodsChanged,
                        replaceNoClass = noClassChanged,
                        replaceMakeup = makeupChanged
                    )
                    if (v.semester != null) courseRepo.clampSlotWeeksTo(v.semester.totalWeeks)
                }
            }

            applyImportedRules(v.categoryRules)

            val removed = if (strategy == ImportStrategy.REPLACE) ready.preview.existingCourseCount else 0

            ImportReport(
                inserted = inserted,
                updated = updatedCount,
                removed = removed,
                issues = v.issues,
                semesterUpdated = v.semester != null,
                timetableUpdated = v.timetable != null,
                noClassDayCount = v.noClassDays.size,
                makeupDayCount = v.makeupDays.size,
                tasksUnlinked = taskLinkPlan.count { it.value == null },
                tasksRelinked = taskLinkPlan.count { it.value != null }
            )
        }
    }

    private suspend fun existingTaskCount(): Int = db.taskDao().countTasks()

    // ------------------------------------------------------------------ 待办与课程的对应关系

    /**
     * 一条待办原来挂在哪门课上。
     *
     * [courseKey] 优先取课程行的自然键；老数据可能没键（`dedupeKey` 为空），
     * 这时退回按 [courseName] 认人。
     */
    private data class TaskLink(
        val task: TaskEntity,
        val courseKey: String?,
        val courseName: String
    )

    /** 导入侧的课程身份。[id] 在预览阶段还没落库，用 0 占位。 */
    private data class CourseRef(val key: String, val name: String, val id: Long)

    /**
     * 记下当前所有**已关联课程**的待办，归属于谁。
     *
     * 原本就「未关联课程」的待办（`courseId = 0`）不收进来 —— 它们与课表无关，
     * 导入怎么折腾都不该动它们。
     */
    private suspend fun captureTaskLinks(): List<TaskLink> {
        val tasks = db.taskDao().getAllTasksOnce()
        if (tasks.none { it.courseId != TaskEntity.UNLINKED_COURSE_ID }) return emptyList()

        val coursesById = courseRepo.getAllCoursesOnce().associateBy { it.id }
        return tasks.mapNotNull { task ->
            if (task.courseId == TaskEntity.UNLINKED_COURSE_ID) {
                null
            } else {
                val old = coursesById[task.courseId]
                TaskLink(
                    task = task,
                    courseKey = old?.dedupeKey?.takeIf { it.isNotBlank() },
                    courseName = old?.name ?: task.courseName
                )
            }
        }
    }

    /**
     * 算出每条待办该挂到新表的哪门课，返回 `待办 id → 命中的课程`（null = 该门课没了）。
     *
     * **返回命中的课程而不是 id**，因为预览阶段这些课还没落库、没有 id 可言；
     * 早先这里直接返回 `id`，预览只能拿 0 顶上，于是「匹配上了」和「移入未关联」
     * 在预览里长得一模一样（都等于 0），预告的数字会整个反过来。
     *
     * 判定顺序：**先认自然键**（`code|name|teacher`，和课程本身的去重口径一致），
     * 认不到再按课名兜底，且**只在课名唯一时才认** —— 同名的两门课（不同老师）
     * 挑一门挂上去是纯猜，猜错比移进「未关联」更糟。
     *
     * 预览和提交共用这一个函数：预览承诺的数字必须就是提交后的结果。
     */
    private fun planTaskLinks(links: List<TaskLink>, courses: List<CourseRef>): Map<Long, CourseRef?> {
        if (links.isEmpty()) return emptyMap()
        val byKey = courses.associateBy { it.key }
        val byName = courses.groupBy { it.name }.filterValues { it.size == 1 }
            .mapValues { it.value.single() }

        return links.associate { link ->
            link.task.id to (link.courseKey?.let { byKey[it] } ?: byName[link.courseName])
        }
    }

    /**
     * 按 [planTaskLinks] 的结论真正改写待办的归属，返回这份计划。
     *
     * 改的只有 `courseId` 和随之而来的那三个快照字段（课程名 / 类别 / 颜色）：
     * 待办卡上显示的课程色就是从任务行上读的快照，不跟着新课程走的话，
     * 一门课改了配色之后它的待办还会挂着旧颜色。标题、截止、勾选状态一律不碰。
     */
    private suspend fun applyTaskLinks(links: List<TaskLink>): Map<Long, CourseRef?> {
        if (links.isEmpty()) return emptyMap()

        val courses = courseRepo.getAllCoursesOnce()
        val plan = planTaskLinks(links, courses.map { CourseRef(it.dedupeKey, it.name, it.id) })
        val coursesById = courses.associateBy { it.id }
        val taskDao = db.taskDao()

        for (link in links) {
            if (link.task.id !in plan) continue
            val target = plan[link.task.id]?.let { coursesById[it.id] }
            val updated = if (target == null) {
                link.task.copy(
                    courseId = TaskEntity.UNLINKED_COURSE_ID,
                    courseName = TaskEntity.UNLINKED_COURSE_NAME,
                    courseCategory = "",
                    courseColorHex = TaskEntity.UNLINKED_COLOR_HEX
                )
            } else {
                link.task.copy(
                    courseId = target.id,
                    courseName = target.name,
                    courseCategory = target.category,
                    courseColorHex = target.colorHex
                )
            }
            // 值没真的变就别写：一次无谓的 UPDATE 会让 Room 白写、Flow 多发一次。
            if (updated != link.task) taskDao.updateTask(updated)
        }
        return plan
    }

    /**
     * 导入包里带来的类别规则，**两条导入策略共用这一个写入点**。
     *
     * 空列表**什么都不做**，这是这个函数存在的全部理由：
     * 浙大教务系统只给课程名 / 教师 / 教室，**从不提供类别规则**（见
     * `ZjuScheduleMapper`），所以每次浙大同步包里这一节都是空的。而
     * `replaceAll` 的语义是「整表替换」= 先 `clear()` 再插，空列表进它等于
     * **把用户配好的规则整表抹掉**。
     *
     * 抹掉之后是两处同时坏、而且都不报错：
     * 1. 「课程联动规则」弹层读的是 `category_rules`，表空 = 一张类别卡都不渲染，
     *    弹层只剩标题和两个按钮。
     * 2. 课程上的三个开关是联动的真相源。它拿不到规则行时就退回 `CourseEntity`
     *    的实体默认值（预习开 + 作业开），于是刚按用户规格配好的默认值被悄悄改回
     *    「一律预习 + 作业」。
     *
     * 两条策略各写一份守卫过一次，REPLACE 那份漏了 —— 所以这里收敛成一个函数：
     * 「包里没有就别动」这条判断只允许存在一份。
     *
     * 顺带解决的顺序问题：这个调用现在在**课程落库之后**。原先放在 REPLACE 的开头时，
     * `replaceAll` 内部的写穿面对的是刚被 `clearAllCourses` 清空的课程表，
     * 恒定返回 0 门，看起来"生效了"其实什么都没做。
     */
    private suspend fun applyImportedRules(rules: List<CategoryRule>) {
        if (rules.isEmpty()) return
        settingsRepo.replaceAll(rules)
    }


    private fun CourseBundle.toEntity(): CourseEntity {
        val a = automation
        return CourseEntity(
            id = 0L,
            name = name.orEmpty(),
            code = code.orEmpty(),
            category = category.orEmpty(),
            teacher = teacher.orEmpty(),
            classroom = classroom.orEmpty(),
            colorHex = colorHex.orEmpty(),
            dedupeKey = key.orEmpty(),
            autoPreStudy = a?.preStudy ?: true,
            autoPostHomework = a?.postHomework ?: true,
            autoLabReport = a?.labReport ?: false,
            autoReview = a?.review ?: false,
            defaultPostDueDays = a?.defaultPostDueDays ?: 3,
            preStudyEstimatedMinutes = a?.preStudyMinutes ?: 30,
            postHomeworkEstimatedMinutes = a?.postHomeworkMinutes ?: 60,
            labReportEstimatedMinutes = a?.labReportMinutes ?: 90
        )
    }

    private fun SlotBundle.toSlotWithWeeks(v: ValidatedBundle): SlotWithWeeks {
        // 校验阶段已经把 weeks 归一化成 Set<Int>。这里仍然走一遍 WeekSet.parse
        // 而不是 filterIsInstance<Int>()：JSON 反序列化给出来的是 List<Number>
        // （内置 ObjectJsonAdapter 把数字读成 Double），一旦哪天有代码绕过校验器
        // 直接构造 bundle，filterIsInstance<Int>() 会安静地把整组周次清空，
        // 结果是「这门课整个学期都在上」——正好是最不容易被发现的错法。
        val weeks = WeekSet.parse(weeks, v.totalWeeks).weeks
        val bound = weeks.filter { it in 1..v.totalWeeks }.toSet()
        return SlotWithWeeks(
            slot = CourseSlotEntity(
                courseId = 0L,
                dayOfWeek = dayOfWeek ?: 1,
                startPeriod = startPeriod ?: 1,
                endPeriod = endPeriod ?: startPeriod ?: 1,
                classroom = classroom.orEmpty()
            ),
            weeks = if (bound.isEmpty()) WeekSet.all(v.totalWeeks) else bound
        )
    }
}