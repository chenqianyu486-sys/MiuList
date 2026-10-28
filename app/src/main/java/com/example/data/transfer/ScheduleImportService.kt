package com.example.data.transfer

import androidx.room.withTransaction
import com.example.data.calendar.EpochDays
import com.example.data.codec.CourseBundle
import com.example.data.codec.DecodeResult
import com.example.data.codec.ExamBundle
import com.example.data.codec.ScheduleCodec
import com.example.data.codec.SlotBundle
import com.example.data.codec.TaskBundle
import com.example.data.codec.TaskRuleBundle
import com.example.data.codec.TaskTypeBundle
import com.example.data.generation.CourseRuleSpec
import com.example.data.generation.TaskCadence
import com.example.data.local.AppDatabase
import com.example.data.local.CourseEntity
import com.example.data.local.CourseSlotEntity
import com.example.data.local.CustomTaskTypeEntity
import com.example.data.local.ExamEntity
import com.example.data.local.TaskEntity
import com.example.data.model.CategoryRule
import com.example.data.model.CourseCategory
import com.example.data.model.CourseKeys
import com.example.data.model.SlotWithWeeks
import com.example.data.model.TaskStar
import com.example.data.model.TaskType
import com.example.data.repository.AcademicCalendarRepository
import com.example.data.repository.CourseRepository
import com.example.data.repository.ScheduleSettingsRepository
import com.example.data.repository.TaskTypeRepository
import com.example.data.timetable.WeekSet
import com.example.data.validate.ScheduleBundleValidator
import com.example.data.validate.SnapshotValidator
import com.example.data.validate.ValidatedBundle
import com.example.data.validate.ValidatedSnapshot
import com.example.data.validate.ValidationResult

/**
 * 「这条待办，文件里的版本是不是比本机的新」。
 *
 * **抽成顶层函数而不是两个方法各自判一次**：预览与提交对「谁赢」的判断必须
 * 是同一处实现，否则会出现「预览说覆盖 3 条、实际覆盖了 5 条」，
 * 而界面上两处数字都显得合理，没有任何矛盾可查。
 *
 * 相等时**文件赢**。毫秒级撞车概率极低，但真撞上时行为是确定的（不会静默错乱）；
 * 「本机赢」的话，用户会看到自己刚在另一台设备上勾的待办在这台上莫名其妙退回未完成。
 */
private fun taskWinsFile(localUpdatedAt: Long, fileUpdatedAt: Long): Boolean =
    fileUpdatedAt >= localUpdatedAt

/**
 * 一门课在跨设备语境下的自然键。
 *
 * **空 `dedupeKey` 必须现算，不能直接拿空串去匹配。**
 * `MIGRATION_3_4` 给老数据补过这一列，但补的是补不上的地方就留空 —— 而文件里
 * 的 `courseKey` 是按算法算出来的，不是空串。于是拿空串当键去匹配，
 * 结果是「这门课找不到」，待办静默变成「未关联课程」，而界面上看不出任何异常。
 *
 * 口径必须与 `CourseKeys.compute` 一致，也与本文件 [preview] 里既有的
 * `existingByKey` 一致 —— 同一个键有三处算法，迟早会漂移出一处。
 */
private fun CourseEntity.naturalKey(): String =
    dedupeKey.ifBlank { CourseKeys.compute(code, name, teacher) }

/** 本机课程的自然键 → id。供待办与生成规则共用。 */
private fun List<CourseEntity>.courseIdByNaturalKey(): Map<String, Long> =
    associate { it.naturalKey() to it.id }

/**
 * 一场考试跨设备的自然键。**口径必须与 [ExamBundle.naturalKey] 一致**，
 * 否则文件里的那场考试在本机认不出来，会被当成一场新的插进去 ——
 * 界面上同一门课的两场考试变成三场，而且没有任何报错。
 */
private fun ExamEntity.naturalKey(): String {
    val code = xkkh.trim()
    if (code.isNotEmpty()) return code
    return listOf(
        courseCode.trim(),
        courseName.trim(),
        startAt.toString(),
        startMinute.toString()
    ).joinToString("|")
}

sealed interface PreviewResult {
    data class Failed(val message: String) : PreviewResult
    data class Ready(
        val validated: ValidatedBundle,
        val preview: ImportPreview,
        /** v3 的三块用户数据。非 SNAPSHOT 策略下是 [ValidatedSnapshot.EMPTY]。 */
        val snapshot: ValidatedSnapshot = ValidatedSnapshot.EMPTY
    ) : PreviewResult
}

/**
 * 课表导入编排：**先预览、用户确认、再单事务提交**。
 *
 * 解析 → 校验 → 与库内数据比对，三步都**不写库**；只有 [commit] 会写，
 * 且全程一个事务：任一步抛异常就整体回滚，DB 回到导入前，
 * 不存在「导了一半」的中间态。
 *
 * ## 三种策略各管各的
 *
 * - [ImportStrategy.MERGE] / [ImportStrategy.REPLACE] 是**课表**的两种合并法，
 *   按 v2 起的既定设计**都不动待办**：课程还在的继续挂着，不在的移入「未关联课程」
 *   （见 [captureTaskLinks] / [planTaskLinks] / [applyTaskLinks]）。浙大同步走的就是
 *   REPLACE —— 服务端只管课表，它根本不知道本机的待办勾没勾。
 * - [ImportStrategy.SNAPSHOT] 才是整机同步：课表部分按 MERGE（**不** REPLACE，
 *   清空课程会毁掉刚同步来的课表），待办按 `syncUid` 逐条后写覆盖。
 *
 * 两条链路共用同一份 Bundle 与同一个 Validator，但**写库的覆盖面刻意不重叠**。
 */
class ScheduleImportService(
    private val db: AppDatabase,
    private val codec: ScheduleCodec,
    private val validator: ScheduleBundleValidator,
    private val courseRepo: CourseRepository,
    private val calendarRepo: AcademicCalendarRepository,
    private val settingsRepo: ScheduleSettingsRepository,
    private val snapshotValidator: SnapshotValidator = SnapshotValidator(),
    private val taskTypeRepo: TaskTypeRepository = TaskTypeRepository(db, db.customTaskTypeDao())
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
        val snapshot = when (strategy) {
            ImportStrategy.SNAPSHOT -> snapshotValidator.validate(decoded)
            // 课表导入不带用户数据。浙大同步走的就是这条路，
            // 所以这里**必须**不让它碰到待办 —— 它产出的文档三块本来就是空的。
            else -> ValidatedSnapshot.EMPTY
        }

        val existingCourses = courseRepo.getAllCoursesOnce()
        val existingByKey = existingCourses.associateBy { it.naturalKey() }

        // 本机每门课的类别。**只读，不写。**
        val localCategories = existingCourses.toLocalCategories()

        // 类别冲突要在**预览**这一层就报出来：这一份文件注定要被拒绝，
        // 那就不该先给用户一个「可以导入」的预览、让他点完确认才被拦下。
        val categoryConflict = categoryConflictMessage(validated.courses, localCategories)
        if (categoryConflict != null) return PreviewResult.Failed(categoryConflict)

        val newCourses = mutableListOf<CourseBundle>()
        val updated = mutableListOf<Pair<String, Long>>()
        var unchanged = 0
        for (c in validated.courses) {
            val key = c.key ?: CourseKeys.compute(c.code.orEmpty(), c.name.orEmpty(), c.teacher.orEmpty())
            val hit = existingByKey[key]
            if (hit == null) newCourses += c else updated += (c.name ?: "") to hit.id
        }
        if (strategy == ImportStrategy.MERGE || strategy == ImportStrategy.SNAPSHOT) {
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

        // 快照的逐条合并结论。与提交走同一个 [planTaskMerge]：
        // 预览承诺「新增 12 条、覆盖 3 条、保留 5 条」就必须真的是这个数。
        val taskMerge = if (strategy == ImportStrategy.SNAPSHOT) {
            planTaskMerge(snapshot.tasks, existingCourses)
        } else {
            TaskMergePlan()
        }

        // 考试的逐场合并结论。与提交走同一个 [planExamMerge]。
        val examMerge = planExamMerge(validated.exams)
        val existingExamCount = if (validated.exams.isEmpty()) 0 else existingExamCount()

        return PreviewResult.Ready(
            validated = validated,
            preview = ImportPreview(
                strategy = strategy,
                fileCourseCount = validated.courses.size,
                newCourses = newCourses,
                updatedCourses = updated,
                unchangedCount = unchanged,
                // 课表问题在前，用户数据问题在后：预览上本来就先讲课表。
                issues = validated.issues + snapshot.issues,
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
                tasksKeepingCourse = taskLinkPlan.count { it.value != null },
                fileTaskCount = snapshot.tasks.size,
                taskMerge = taskMerge,
                fileTaskTypeCount = snapshot.taskTypes.size,
                fileTaskRuleCount = snapshot.taskRules.size,
                taskTypesOverwritten = countOverwrittenTypes(snapshot.taskTypes),
                taskRulesOverwritten = countOverwrittenRules(snapshot.taskRules, existingCourses),
                existingExamCount = existingExamCount,
                fileExamCount = validated.exams.size,
                examMerge = examMerge
            ),
            snapshot = snapshot
        )
    }

    /** 确认后落库。整段在一个事务里。 */
    suspend fun commit(ready: PreviewResult.Ready): ImportReport {
        val v = ready.validated
        val strategy = ready.preview.strategy

        return db.withTransaction {
            // 必须在清课程**之前**记下待办挂在谁身上：清完就再也认不出谁是谁了。
            val taskLinks = if (strategy == ImportStrategy.REPLACE) captureTaskLinks() else emptyList()
            // 同样必须在清课程之前把本机的**课程备注**记下来。理由与待办一致：
            // 浙大同步的语义是「把课表换成服务器上的那份」，而服务器根本不知道
            // 用户写过什么备注。不先记下来，一次同步就会把用户写好的备注全清空。
            val localNotes = captureCourseNotes()

            // 本机每门课的**类别**，同样必须在清课程之前记下来。
            //
            // 理由与备注完全一样：教务网的数据里没有「类别」这一项，
            // 同步产出的文件同样没有 —— 不先记下来，用户每同步一次
            // 就丢一次自己整理过的类别。
            //
            // **取的是提交时的最新数据**，不是预览时缓存的那一份：
            // 用户在预览之后又改了一次类别的话，要保留的是后者。
            val localCategories = captureLocalCategories()

            // 最终类别在**删数据之前**就算好。两个原因：
            //
            // 1. 冲突要在删之前报出来 —— 本机同一门课有多个互不相同的类别时，
            //    继承哪一个都是猜。此时抛异常，事务整体回滚，一门课都不少。
            // 2. 最终类别必须在写库之前就定下来 —— 新建课程要按它初始化
            //    类别规则（见 `CourseRepository.saveCourseWithSlots`）。
            val finalCategories = resolveCategories(v.courses, localCategories)

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
                val entity = course.toEntity(finalCategories.getValue(key))
                val hits = courseRepo.getByDedupeKey(key)
                // **SNAPSHOT 的课表部分按 MERGE 处理**，理由与它对待办的态度不同：
                // 课程是有服务端这个权威源的，整表替换一份本地快照会毁掉刚同步来的
                // 课表；而待办只有本地有，不存在「别人的更权威」这回事。
                //
                // 这里若漏掉 SNAPSHOT（只写 `== MERGE`），文件里那门课每次导入都会
                // 被当成新课程再插一份 —— 同一个自然键下冒出两门课，而界面上
                // 周课表会显示两行一模一样的东西，且没有任何报错。
                val mergeByKey = strategy == ImportStrategy.MERGE || strategy == ImportStrategy.SNAPSHOT
                val existing = if (mergeByKey) hits.firstOrNull() else null
                val slots = course.slots.map { it.toSlotWithWeeks(v) }

                // 备注归谁：**包里带了才算数，没带就留着本机的。**
                //
                // 三种来源都落到这一条判断上：
                // - 浙大同步：教务网没有「备注」这个字段 → 包里没有 → 保住本机的
                // - zju-ical 的课表文件：同样没有这一项 → 保住本机的
                // - 本 App 自己导出的文件：一定带（空备注写成空串）→ 以文件为准
                //
                // 判据必须是「有没有这一项」而不是「内容是不是空」：
                // 用「空不空」判的话，用户在 A 机器上删掉备注再导出，
                // 导入 B 机器时就会把 B 上的备注留着 —— 删不掉。
                val note = if (course.note != null) entity.note else localNotes[key].orEmpty()

                if (existing != null) {
                    courseRepo.saveCourseWithSlots(entity.copy(id = existing.id, note = note), slots)
                    updatedCount++
                } else {
                    courseRepo.saveCourseWithSlots(entity.copy(id = 0L, note = note), slots)
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

            // 考试：逐场认人合并。**与策略无关** —— 考试不是「课表」，
            // 它是学校已经公布的事实，删课不该连带删它；
            // 而「学校取消了一场考试」只有教务网知道，导入一份文件没有依据做这个判断，
            // 所以文件里没提到的考试一律留在本机（没有「删除」这一项）。
            //
            // 文件里**没有考试块**时（浙大同步产出的课表文档就是这种形状）
            // 本机的考试一条都不动 —— 考试是同步那条路单独拉的。
            val examMerge = applyExams(v.exams)

            val removed = if (strategy == ImportStrategy.REPLACE) ready.preview.existingCourseCount else 0

            // ---- 整机快照的三块。课表部分已经落完，这里才轮到用户数据 ----
            //
            // 顺序有讲究：**自定义类型必须先落**，待办的 taskType 才解析得出来
            // （`TaskTypes.resolve` 认不出就返回 null，待办静默退化成「未分类」）。
            val merge = if (strategy == ImportStrategy.SNAPSHOT) {
                applySnapshot(ready.snapshot)
            } else {
                TaskMergePlan()
            }

            ImportReport(
                inserted = inserted,
                updated = updatedCount,
                removed = removed,
                issues = v.issues + ready.snapshot.issues,
                semesterUpdated = v.semester != null,
                timetableUpdated = v.timetable != null,
                noClassDayCount = v.noClassDays.size,
                makeupDayCount = v.makeupDays.size,
                tasksUnlinked = taskLinkPlan.count { it.value == null },
                tasksRelinked = taskLinkPlan.count { it.value != null },
                taskMerge = merge,
                taskTypesImported = if (strategy == ImportStrategy.SNAPSHOT) ready.snapshot.taskTypes.size else 0,
                taskRulesImported = if (strategy == ImportStrategy.SNAPSHOT) countAppliedRules(ready.snapshot) else 0,
                examMerge = examMerge
            )
        }
    }

    // ------------------------------------------------------------------ v3 用户数据

    /**
     * 落快照的三块。**只能在 [commit] 的事务内调用。**
     *
     * 步骤顺序就是依赖顺序：
     * 1. 自定义类型 —— 待办的 `taskType` 依赖它才能解析；
     * 2. 生成规则 —— 依赖课程（外键带级联），所以必须在课程落库之后；
     * 3. 待办 —— 同时依赖上面两者。
     *
     * 三块**都遵循「包里没有就别动」**。浙大同步走的是同一条管线、产出的文档
     * 三块全空；不守住这一条，一次浙大同步就会把用户配好的自定义分类与
     * 生成规则清空，而界面上不会有任何提示。
     */
    private suspend fun applySnapshot(snapshot: ValidatedSnapshot): TaskMergePlan {
        applyTaskTypes(snapshot.taskTypes)
        val rulesApplied = applyTaskRules(snapshot.taskRules)
        return applyTasks(snapshot.tasks)
    }

    /** 自定义待办分类。**空列表直接返回**，见 [applySnapshot] 的说明。 */
    private suspend fun applyTaskTypes(rows: List<TaskTypeBundle>): Int {
        if (rows.isEmpty()) return 0
        val dao = db.customTaskTypeDao()
        for (row in rows) {
            val id = row.id ?: continue
            // 覆盖而非跳过：这份快照是「另一台设备上的我」，那边的名字与配色
            // 就是用户在最近一次修改后的样子，本机那份已经更旧了。
            dao.insert(
                CustomTaskTypeEntity(
                    id = id,
                    label = row.label.orEmpty(),
                    colorHex = row.colorHex.orEmpty(),
                    inkHex = row.inkHex.orEmpty(),
                    sortOrder = row.sortOrder ?: 0
                )
            )
        }
        return rows.size
    }

    /**
     * 课程级生成规则。课程用自然键换成本机 id —— 文件里没有本地 `courseId`。
     * 本机没有这门课的规则**跳过**（课程没了，规则也就没有归属）。
     */
    private suspend fun applyTaskRules(rows: List<TaskRuleBundle>): Int {
        if (rows.isEmpty()) return 0
        val courseIdByKey = courseRepo.getAllCoursesOnce().courseIdByNaturalKey()
        var applied = 0
        for (row in rows) {
            val courseId = courseIdByKey[row.courseKey] ?: continue
            val type = row.taskType?.let { runCatching { TaskType.valueOf(it) }.getOrNull() } ?: continue
            settingsRepo.saveTaskRule(
                courseId = courseId,
                taskType = type,
                spec = CourseRuleSpec(
                    cadence = row.cadence ?: TaskCadence.PER_WEEK.name,
                    dueOffsetDays = row.dueOffsetDays ?: 0,
                    dueBeforeMinutes = row.dueBeforeMinutes ?: 0
                )
            )
            applied++
        }
        return applied
    }

    /**
     * 待办逐条后写覆盖。返回的就是 [applySnapshot] 交给报告的那个 [TaskMergePlan]。
     *
     * 判定与 [planTaskMerge] 完全一致（两边共用同一个纯函数 `wins`），
     * 所以预览上写的数字就是这里的实际结果。
     */
    private suspend fun applyTasks(rows: List<TaskBundle>): TaskMergePlan {
        if (rows.isEmpty()) return TaskMergePlan()
        val taskDao = db.taskDao()
        val courses = courseRepo.getAllCoursesOnce()
        val coursesById = courses.associateBy { it.id }
        val courseIdByKey = courses.courseIdByNaturalKey()

        var added = 0
        var overwritten = 0
        var keptLocal = 0
        var losingCourse = 0

        for (row in rows) {
            val uid = row.syncUid ?: continue
            val courseId = if (row.courseKey.isNullOrBlank()) {
                TaskEntity.UNLINKED_COURSE_ID
            } else {
                courseIdByKey[row.courseKey] ?: TaskEntity.UNLINKED_COURSE_ID
            }
            val course = coursesById[courseId]
            if (courseId == TaskEntity.UNLINKED_COURSE_ID && !row.courseKey.isNullOrBlank()) losingCourse++

            val incoming = row.toEntity(courseId, course)
            val local = taskDao.getBySyncUid(uid)
            when {
                local == null -> {
                    taskDao.insertTask(incoming)
                    added++
                }
                // 本机这条更晚改过：保留本机版本。丢的是文件里那份旧副本，
                // 而它正是本机那条被改之前的模样。
                local.updatedAt > incoming.updatedAt -> keptLocal++
                else -> {
                    // 覆盖时**保留本机 id**：Room 的主键换不得，
                    // 而「同一条待办换了本地行号」对用户没有任何好处。
                    taskDao.updateTask(incoming.copy(id = local.id))
                    overwritten++
                }
            }
        }
        return TaskMergePlan(added, overwritten, keptLocal, losingCourse)
    }

    // ------------------------------------------------------------------ 考试

    /**
     * 逐场认人的合并结论，**不写库**。
     *
     * 与 [applyExams] 共用同一套判定（同一个自然键、同一个「较新者胜」），
     * 所以预览上写的数字就是提交后的实际结果。
     *
     * **文件里没有考试就一条都不动**（返回空计划）：浙大同步产出的课表文档
     * 就是这种形状，而考试在那条路上是单独拉的 —— 这里要是把它当成「清空」，
     * 一次同步就会把刚拉到的考试抹掉。
     */
    private suspend fun planExamMerge(rows: List<ExamBundle>): ExamMergePlan {
        if (rows.isEmpty()) return ExamMergePlan()
        val localByKey = db.examDao().getAllExamsOnce().associateBy { it.naturalKey() }
        var added = 0
        var overwritten = 0
        var keptLocal = 0
        for (row in rows) {
            val hit = localByKey[row.naturalKey]
            when {
                hit == null -> added++
                // 本机这场更晚改过：保留本机版本。
                hit.updatedAt > (row.updatedAt ?: 0L) -> keptLocal++
                else -> overwritten++
            }
        }
        return ExamMergePlan(added, overwritten, keptLocal)
    }

    /**
     * 落考试。**只能在 [commit] 的事务内调用。**
     *
     * 认人靠自然键（教务网的选课课号原串），输赢靠 `updatedAt`：
     * 两台设备都从同一个教务网拉过之后，各自那份的 `updatedAt` 不一样，
     * 较晚的那份才是学校最新公布的。
     *
     * **没有「删除」**：文件里没提到的考试留在本机。理由见 [applyExams] 的调用处。
     */
    private suspend fun applyExams(rows: List<ExamBundle>): ExamMergePlan {
        if (rows.isEmpty()) return ExamMergePlan()
        val dao = db.examDao()
        val localByKey = dao.getAllExamsOnce().associateBy { it.naturalKey() }
        var added = 0
        var overwritten = 0
        var keptLocal = 0
        val toWrite = mutableListOf<ExamEntity>()
        for (row in rows) {
            val entity = row.toEntity()
            val hit = localByKey[row.naturalKey]
            when {
                hit == null -> {
                    toWrite += entity
                    added++
                }
                hit.updatedAt > entity.updatedAt -> keptLocal++
                else -> {
                    // 覆盖时**保留本机 id**：Room 的主键换不得，
                    // 而「同一场考试换了本地行号」对用户没有任何好处。
                    toWrite += entity.copy(id = hit.id)
                    overwritten++
                }
            }
        }
        if (toWrite.isNotEmpty()) dao.insertExams(toWrite)
        return ExamMergePlan(added, overwritten, keptLocal)
    }

    /** 本机现存的考试场次。 */
    private suspend fun existingExamCount(): Int = db.examDao().countExams()

    /** 文件里的一场考试 → 库里的实体。 */
    private fun ExamBundle.toEntity(): ExamEntity = ExamEntity(
        id = 0L,
        xkkh = xkkh.orEmpty(),
        courseCode = courseCode.orEmpty(),
        courseName = courseName.orEmpty(),
        kind = kind ?: ExamEntity.KIND_FINAL,
        startAt = startAt ?: 0L,
        endAt = endAt ?: 0L,
        startMinute = startMinute ?: 0,
        endMinute = endMinute ?: 0,
        dateLabel = dateLabel.orEmpty(),
        // 空串 = 「学校还没安排」，与库里既有的写法一致。
        classroom = classroom.orEmpty(),
        seat = seat.orEmpty(),
        credit = credit ?: 0.0,
        termLabel = termLabel.orEmpty(),
        updatedAt = updatedAt ?: 0L
    )

    /**
     * 本机每门课的类别，`自然键 → 该键下的所有类别`。
     *
     * **一个键可能对应多条**：`dedupeKey` 是**非唯一**索引，历史数据里同一门课
     * 真的可能有两条。所以这里收的是**列表**而不是单个值 ——
     * 收单个值就得用 `associate` 挑一条，而挑哪一条都是猜，
     * 猜错就是**静默丢掉另一个类别**。
     *
     * 键用 [naturalKey]：老数据的 `dedupeKey` 可能为空，那种行按
     * `CourseKeys.compute(code, name, teacher)` 现算 —— 拿空串去匹配的话
     * 这门课认不出来，类别与待办一起丢。
     */
    private suspend fun captureLocalCategories(): Map<String, List<String>> =
        courseRepo.getAllCoursesOnce().toLocalCategories()

    private fun List<CourseEntity>.toLocalCategories(): Map<String, List<String>> =
        groupBy { it.naturalKey() }.mapValues { (_, rows) -> rows.map { it.category } }

    /**
     * 算出每门课最终落库的类别，`自然键 → 最终类别`。
     *
     * 优先级见 [CourseCategoryResolver]：文件里明确给了就用文件的，
     * 否则继承本机的，两者都没有才按课程名推断。
     *
     * 本机同一门课有**多个互不相同的类别**时抛 [CourseCategoryConflictException] ——
     * 继承哪一个都是猜，而猜错就是静默丢数据。
     */
    private fun resolveCategories(
        courses: List<CourseBundle>,
        localCategories: Map<String, List<String>>
    ): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (course in courses) {
            val key = course.key ?: CourseKeys.compute(
                course.code.orEmpty(), course.name.orEmpty(), course.teacher.orEmpty()
            )
            when (val r = CourseCategoryResolver.resolve(
                course.category, localCategories[key].orEmpty(), course.name
            )) {
                is CourseCategoryResolver.Result.Resolved -> out[key] = r.category
                is CourseCategoryResolver.Result.Conflict ->
                    throw CourseCategoryConflictException(
                        courseName = course.name.orEmpty(),
                        naturalKey = key,
                        categories = r.categories
                    )
            }
        }
        return out
    }

    /**
     * 这份文件会不会因为「类别冲突」而被拒绝？会的话给出那句话。
     *
     * 预览与提交走**同一个**判定（都落在 [resolveCategories] 上），
     * 所以预览里说的「导不进来」与提交时的结果不会互相矛盾。
     */
    private fun categoryConflictMessage(
        courses: List<CourseBundle>,
        localCategories: Map<String, List<String>>
    ): String? = try {
        resolveCategories(courses, localCategories)
        null
    } catch (e: CourseCategoryConflictException) {
        e.message
    }

    /** 文件里的一条待办 → 库里的实体。课程的三个快照字段从本机课程行取。 */
    private fun TaskBundle.toEntity(courseId: Long, course: CourseEntity?): TaskEntity = TaskEntity(
        id = 0L,
        courseId = courseId,
        // 课程色 / 类别 / 名称是**从课程行现取**的快照，跟随本机课程而不是
        // 跟着文件走：一门课改了配色之后，它的待办要跟着改。
        courseName = course?.name ?: TaskEntity.UNLINKED_COURSE_NAME,
        courseCategory = course?.category ?: "",
        courseColorHex = course?.colorHex ?: TaskEntity.UNLINKED_COLOR_HEX,
        title = title.orEmpty(),
        description = description.orEmpty(),
        taskType = taskType.orEmpty(),
        startTimeTimestamp = startTimeTimestamp ?: 0L,
        startTimeString = startTimeString.orEmpty(),
        dueDateTimestamp = dueDateTimestamp ?: TaskEntity.NO_DUE_TIMESTAMP,
        dueDateString = dueDateString.orEmpty(),
        priority = priority ?: TaskStar.encode(false),
        isCompleted = isCompleted ?: false,
        completedAt = completedAt,
        isAutoGenerated = isAutoGenerated ?: false,
        generatedDate = generatedDate,
        week = week ?: 0,
        dayOfWeek = dayOfWeek ?: 0,
        createdAt = createdAt ?: 0L,
        syncUid = syncUid.orEmpty(),
        updatedAt = updatedAt ?: 0L
    )

    // ------------------------------------------------------------------ 预览用的纯判定

    /**
     * 逐条合并的结论，**不写库**。
     *
     * 与 [applyTasks] 共用 [taskWinsFile]，两边对「谁赢」的判断必须是同一个函数 ——
     * 否则会出现「预览说覆盖 3 条、实际覆盖了 5 条」，而界面上看不出矛盾。
     */
    private suspend fun planTaskMerge(
        rows: List<TaskBundle>,
        existingCourses: List<CourseEntity>
    ): TaskMergePlan {
        if (rows.isEmpty()) return TaskMergePlan()
        val taskDao = db.taskDao()
        val courseKeys = existingCourses.map { it.naturalKey() }.toSet()

        var added = 0
        var overwritten = 0
        var keptLocal = 0
        var losingCourse = 0
        for (row in rows) {
            if (!row.courseKey.isNullOrBlank() && row.courseKey !in courseKeys) losingCourse++
            val uid = row.syncUid ?: continue
            val local = taskDao.getBySyncUid(uid)
            when {
                local == null -> added++
                taskWinsFile(local.updatedAt, row.updatedAt ?: 0L) -> overwritten++
                else -> keptLocal++
            }
        }
        return TaskMergePlan(added, overwritten, keptLocal, losingCourse)
    }

    private suspend fun countOverwrittenTypes(rows: List<TaskTypeBundle>): Int {
        if (rows.isEmpty()) return 0
        val existing = db.customTaskTypeDao().getAllOnce().map { it.id }.toSet()
        return rows.count { it.id != null && it.id in existing }
    }

    private suspend fun countOverwrittenRules(rows: List<TaskRuleBundle>, existingCourses: List<CourseEntity>): Int {
        if (rows.isEmpty()) return 0
        val courseIdByKey = existingCourses.courseIdByNaturalKey()
        val local = db.taskGenerationRuleDao().getAllOnce()
            .filter { runCatching { TaskType.valueOf(it.taskType) }.isSuccess }
            .map { "${it.courseId}|${it.taskType}" }
            .toSet()
        return rows.count { row ->
            val id = row.courseKey?.let { courseIdByKey[it] } ?: return@count false
            "$id|${row.taskType}" in local
        }
    }

    private suspend fun countAppliedRules(snapshot: ValidatedSnapshot): Int {
        val rows = snapshot.taskRules
        if (rows.isEmpty()) return 0
        val courseIdByKey = courseRepo.getAllCoursesOnce().courseIdByNaturalKey()
        return rows.count { row ->
            courseIdByKey[row.courseKey] != null &&
                row.taskType?.let { runCatching { TaskType.valueOf(it) }.isSuccess } == true
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
     * 记下本机每门课写过的备注，返回 `自然键 → 备注`。
     *
     * **只收非空的**：没写备注的课不必占一个键位。
     *
     * 键用 [naturalKey]，与导入循环里算出来的 `key` 是同一个串：本地存的是
     * `dedupeKey`（由 `CourseKeys.compute` 算出），浙大那边也是
     * `CourseKeys.compute`（见 `ZjuScheduleMapper`）—— 两边同算法，键才对得上。
     *
     * 为什么要在清课程之前记：浙大同步走 REPLACE，会把课程整表清空重建。
     * 教务网的数据里没有「备注」这一项，所以那份文件**永远不会**把用户写的
     * 备注带回来 —— 不先记下来，用户每同步一次就丢一次。
     */
    private suspend fun captureCourseNotes(): Map<String, String> =
        courseRepo.getAllCoursesOnce()
            .filter { it.note.isNotBlank() }
            .associate { it.naturalKey() to it.note }

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


    /**
     * 文件里的一门课 → 库里的实体。
     *
     * @param finalCategory 这一门课**最终**的类别（已经按
     *        「文件 → 本机 → 推断」的优先级算好了，见 [resolveCategories]）。
     *        它必须在写库之前就定下来：新建课程要按它初始化类别规则。
     */
    private fun CourseBundle.toEntity(finalCategory: String): CourseEntity {
        val a = automation
        return CourseEntity(
            id = 0L,
            name = name.orEmpty(),
            code = code.orEmpty(),
            category = finalCategory,
            teacher = teacher.orEmpty(),
            classroom = classroom.orEmpty(),
            colorHex = colorHex.orEmpty(),
            // 包里没带这一项时这里是空串，但空串**不等于**「用户把备注清空了」——
            // 到底用本机的还是用包的，由调用方按「包里有没有这一项」决定（见 commit 里的 note）。
            note = note.orEmpty(),
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

    private fun SlotBundle.toSlotWithWeeks(v: ValidatedBundle): SlotWithWeeks {        // 校验阶段已经把 weeks 归一化成 Set<Int>。这里仍然走一遍 WeekSet.parse
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