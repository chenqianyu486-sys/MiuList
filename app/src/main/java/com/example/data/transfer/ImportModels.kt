package com.example.data.transfer

import com.example.data.codec.CourseBundle
import com.example.data.validate.ImportIssue

/** 导入冲突策略。每次都由用户在导入前选，不记忆。 */
enum class ImportStrategy(val label: String, val description: String) {
    MERGE(
        "合并",
        "按课程自然键匹配：文件里有就更新，没有就新增；库里文件没提到的课程保持不动，已有待办完全不受影响。"
    ),
    REPLACE(
        "整表替换",
        "先清空现有课表与全部待办，再整份导入。注意这会删除所有待办，且不可撤销。"
    )
}

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
    val existingTaskCount: Int
) {
    val hasCalendarChange: Boolean
        get() = semesterChanged || timetableChanged || noClassDayCount > 0 || makeupDayCount > 0
}

data class ImportReport(
    val inserted: Int,
    val updated: Int,
    val removed: Int,
    val issues: List<ImportIssue>,
    val semesterUpdated: Boolean,
    val timetableUpdated: Boolean,
    val noClassDayCount: Int,
    val makeupDayCount: Int
)