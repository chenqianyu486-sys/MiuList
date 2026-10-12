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
import com.example.data.model.CourseKeys
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
                existingTaskCount = existingTaskCount()
            )
        )
    }

    /** 确认后落库。整段在一个事务里。 */
    suspend fun commit(ready: PreviewResult.Ready): ImportReport {
        val v = ready.validated
        val strategy = ready.preview.strategy

        return db.withTransaction {
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
                settingsRepo.replaceAll(v.categoryRules)
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
                if (v.categoryRules.isNotEmpty()) settingsRepo.replaceAll(v.categoryRules)
            }

            val removed = if (strategy == ImportStrategy.REPLACE) ready.preview.existingCourseCount else 0

            ImportReport(
                inserted = inserted,
                updated = updatedCount,
                removed = removed,
                issues = v.issues,
                semesterUpdated = v.semester != null,
                timetableUpdated = v.timetable != null,
                noClassDayCount = v.noClassDays.size,
                makeupDayCount = v.makeupDays.size
            )
        }
    }

    private suspend fun existingTaskCount(): Int = db.taskDao().countTasks()

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