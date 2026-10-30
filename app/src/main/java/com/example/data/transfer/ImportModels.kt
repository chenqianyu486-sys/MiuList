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
        "认得出的课程会更新，新课程会加入；" +
            "文件里没有的课程和全部待办都保持不动。"
    ),
    REPLACE(
        "替换",
        "先清空现有课表，再导入文件里的全部课程。待办不会删除，" +
            "只会重新归到对应课程或「未关联课程」。"
    ),
    /**
     * 「整机同步」不写说明：原来那段「把这份备份当作另一台设备上的你……」
     * 是比喻 + 过程描述，界面下方那句「以较晚保存的为准」已经把要紧的说了。
     */
    SNAPSHOT(
        "整机同步",
        ""
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

/**
 * 考试合并的逐条结论。判定与 [TaskMergePlan] 同一套形状：
 * 认出同一场考试 → 比 `updatedAt`，较新的赢。
 *
 * **没有「删除」这一项**：文件里没提到的考试会**留在本机**。
 *
 * 理由是考试的生命周期与课程不同 —— 删课不该连带删考试（考试是学校已经公布的
 * 事实），而「学校取消了这场考试」这件事只有教务网知道，导入一份文件没有依据
 * 去做这个判断。删除只发生在浙大同步那条整表覆盖的路上。
 */
data class ExamMergePlan(
    /** 将新增的考试数。 */
    val added: Int = 0,
    /** 将被文件内容覆盖的考试数（本机那条较旧）。 */
    val overwritten: Int = 0,
    /** 本机较新、文件里的旧版本被跳过。 */
    val keptLocal: Int = 0
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
    val taskRulesOverwritten: Int = 0,
    /** 本机现存的考试场次。 */
    val existingExamCount: Int = 0,
    /** 文件里带的考试场次。0 = 这份文件没带考试，导入时不动本机的考试。 */
    val fileExamCount: Int = 0,
    /** 逐场合并的结论。 */
    val examMerge: ExamMergePlan = ExamMergePlan()
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
    val taskRulesImported: Int = 0,
    /** 考试逐场合并的结果。文件没带考试时全为 0（本机的考试一条不动）。 */
    val examMerge: ExamMergePlan = ExamMergePlan()
)