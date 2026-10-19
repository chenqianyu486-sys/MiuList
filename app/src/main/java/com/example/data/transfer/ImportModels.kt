package com.example.data.transfer

import com.example.data.codec.CourseBundle
import com.example.data.validate.ImportIssue

/** 导入冲突策略。每次都由用户在导入前选，不记忆。 */
enum class ImportStrategy(val label: String, val description: String) {
    MERGE(
        "合并",
        "按课程自然键匹配：文件里有就更新，没有就新增；库里文件没提到的课程保持不动，待办完全不受影响。"
    ),
    REPLACE(
        "整表替换",
        "先清空现有课表，再整份导入。待办不会被删除：课程还在的继续挂在原处，" +
            "课程消失的移入「未关联课程」。"
    ),
    SNAPSHOT(
        "整机同步",
        "把这份快照当作另一台设备上的「我」：课表按自然键合并，待办按同步身份逐条合并，" +
            "较新的一版胜出。两台设备可以交替使用，不会互相覆盖对方的改动。" +
            "本机独有的待办不会被删除——删除不会跨设备传播。"
    )
}

/**
 * 待办合并的逐条结论。预览与提交共用同一套判定（都走
 * `ScheduleImportService.planTaskMerge`），保证「预告的数字就是提交后的结果」。
 */
data class TaskMergePlan(
    /** 将新增的待办数。 */
    val added: Int = 0,
    /** 将被文件内容覆盖的待办数（本机那条较旧）。 */
    val overwritten: Int = 0,
    /** 本机较新、文件里的旧版本被跳过。 */
    val keptLocal: Int = 0,
    /** 会失去课程归属、落成「未关联课程」的待办数。 */
    val losingCourse: Int = 0
)

/** 预览摘要：确认前告诉用户「会发生什么」。 */
data class ImportPreview(
    val strategy: ImportStrategy,
    val fileCourseCount: Int,
    val newCourses: List<CourseBundle>,
    /** 将被覆盖的课程：显示名 → 现有课程 id */
    val updatedCourses: List<Pair<String, Long>>,
    val unchangedCount: Int,
    val issues: List<ImportIssue>,
    val semesterChanged: Boolean,
    val semesterSummary: String?,
    val timetableChanged: Boolean,
    val noClassDayCount: Int,
    val makeupDayCount: Int,
    val existingCourseCount: Int,
    val existingTaskCount: Int,
    /** 替换后会有多少条待办的课程不在新表里、将移入「未关联课程」。 */
    val tasksLosingCourse: Int = 0,
    /** 替换后仍能挂回同一门课的待办条数。 */
    val tasksKeepingCourse: Int = 0,
    /** 快照里带了多少条待办（[ImportStrategy.SNAPSHOT] 才有意义）。 */
    val fileTaskCount: Int = 0,
    /** 逐条合并的结论。 */
    val taskMerge: TaskMergePlan = TaskMergePlan(),
    /** 快照里带的自定义待办分类数。 */
    val fileTaskTypeCount: Int = 0,
    /** 快照里带的课程级生成规则条数。 */
    val fileTaskRuleCount: Int = 0,
    /** 将被覆盖的自定义分类数。 */
    val taskTypesOverwritten: Int = 0,
    /** 将被覆盖的生成规则条数。 */
    val taskRulesOverwritten: Int = 0
) {
    val hasCalendarChange: Boolean
        get() = semesterChanged || timetableChanged || noClassDayCount > 0 || makeupDayCount > 0

    /** 这次导入会不会碰用户数据（预览上要不要显示「待办」那一段）。 */
    val touchesUserData: Boolean
        get() = strategy == ImportStrategy.SNAPSHOT
}

data class ImportReport(
    val inserted: Int,
    val updated: Int,
    val removed: Int,
    val issues: List<ImportIssue>,
    val semesterUpdated: Boolean,
    val timetableUpdated: Boolean,
    val noClassDayCount: Int,
    val makeupDayCount: Int,
    /** 移入「未关联课程」的待办条数。 */
    val tasksUnlinked: Int = 0,
    /** 重新挂回新课程（课程换了新 id）的待办条数。 */
    val tasksRelinked: Int = 0,
    /** 快照导入的逐条合并结果。非 SNAPSHOT 策略下全为 0。 */
    val taskMerge: TaskMergePlan = TaskMergePlan(),
    val taskTypesImported: Int = 0,
    val taskRulesImported: Int = 0
)