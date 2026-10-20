package com.example.data.transfer

import com.example.data.codec.CourseBundle
import com.example.data.validate.ImportIssue

/**
 * 导入冲突策略。每次都由用户在导入前选，不记忆。
 *
 * **标签里不加「整表」这类限定词**：设置页里摆着导入导出、分类、壁纸、
 * 关于、致谢五项功能，一个叫「整表替换」的按钮会被读成「把整张表换掉」，
 * 像是这一页在做的事，而不是三个冲突策略里的一个。而且它和旁边的「整机同步」
 * 只差一个字（整表 / 整机），两枚并排的 chip 读起来几乎分不开。
 *
 * 判据：**限定词要跟着它修饰的那一样东西，不能拿页面的范围去修饰页里的某一项。**
 */
enum class ImportStrategy(val label: String, val description: String) {
    MERGE(
        "合并",
        // 「自然键」是开发口径（课程代码 + 名称 + 教师算出来的键），
        // 用户看到这个词只会觉得莫名其妙，得先知道自己被什么匹配上了。
        "按课程代码、名称和教师认同一门课：认得出的更新，认不出的新增；" +
            "文件里没提到的课程保持不动，待办完全不受影响。"
    ),
    REPLACE(
        "替换",
        "先清空现有课表，再导入文件里的全部课程。待办不会被删除：课程还在的继续挂在原处，" +
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