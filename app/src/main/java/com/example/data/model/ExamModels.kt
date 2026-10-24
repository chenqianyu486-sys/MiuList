package com.example.data.model

import com.example.data.local.ExamEntity

/**
 * 期中 / 期末。
 *
 * 教务网对一场考试给的是**两套**字段（期中一套、期末一套），
 * 所以「这场是期中还是期末」是解析时就要定下来的事实，不是显示时才决定的。
 */
enum class ExamKind(val label: String) {
    MIDTERM("期中"),
    FINAL("期末");

    companion object {
        fun of(raw: String): ExamKind =
            if (raw == ExamEntity.KIND_MIDTERM) MIDTERM else FINAL
    }
}

/**
 * 一场考试，以及它**对应的那门课**。
 *
 * ## 为什么是「读时关联」而不是外键
 *
 * 考试数据独立于课程数据（见 `ExamEntity` 的注释）。关联靠两条线索，
 * 按可靠性排序：
 *
 * 1. **课程代码**（从选课课号里抽出来的，与 `CourseEntity.code` 同一口径）；
 * 2. **课程名**（代码对不上时的兜底，比如用户手工加的课没有课程代码）。
 *
 * 两条都对不上时 [courseId] 为 null —— 考试照常显示，只是没有颜色可继承、
 * 也不能点进课程详情。**这比「猜一门课」好**：猜错了会让用户点进去看到
 * 一门不相干的课。
 */
data class ExamEntry(
    val id: Long,
    val courseName: String,
    val kind: ExamKind,
    /** 绝对开始时刻；0 = 日期还没公布。 */
    val startAt: Long,
    val endAt: Long,
    /** 当天第几分钟；0 = 连时间都还没公布。 */
    val startMinute: Int,
    val endMinute: Int,
    /** 学校给出的日期文字（日期未公布时用）。 */
    val dateLabel: String,
    val classroom: String,
    val seat: String,
    val credit: Double,
    val termLabel: String,
    /** 关联到的课程；null = 没对上。 */
    val courseId: Long?,
    /** 关联到的课程颜色；没对上时用中性色。 */
    val courseColorHex: String
) {
    /** 具体日期已知吗。 */
    val hasDate: Boolean get() = startAt > 0L

    /** 具体时间已知吗（几点考）。 */
    val hasClock: Boolean get() = startMinute > 0

    /**
     * 考场与座位**都**还没安排。
     *
     * 这是一个**进度**，不是「信息缺失」——
     * 学校会先公布考试时间，座位表与考场安排往往要晚几天甚至几周。
     * 界面上要显示成「待学校安排」而不是「未知」：
     * 后者会让用户以为自己漏了什么，或者要去教务网问为什么查不到。
     */
    val locationPending: Boolean get() = classroom.isBlank() && seat.isBlank()

    /** 考场定了、座位还没定。 */
    val seatPending: Boolean get() = classroom.isNotBlank() && seat.isBlank()

    /** 连时间都还没公布。 */
    val timePending: Boolean get() = !hasClock && !hasDate

    companion object {
        /**
         * 没能关联到课程时用的颜色。
         *
         * 取调色板里的**最后一位**（粉彩黄）而不是灰色：
         * 灰色在界面上读起来像「这条数据坏了」，
         * 而黄色读起来像「这是一个提醒」—— 事实也正是如此：
         * 这场考试确实存在，只是还没挂到某门课上。
         */
        const val UNLINKED_COLOR_HEX = "#FDE047"
    }
}
