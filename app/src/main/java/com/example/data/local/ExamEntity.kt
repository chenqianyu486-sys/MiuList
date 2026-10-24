package com.example.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一场考试。
 *
 * ## 为什么考试单独一张表，而不是挂在课程上
 *
 * 考试**来自另一个接口**（教务网「个人考试查询」），**粒度也不同**：
 * 一门课一个学期可能只有一场期末考，期中考干脆没有；
 * 而考试里的「教室」「座位」是考试当天才定的，和这门课平常上课的教室**没有必然关系**。
 *
 * 把它塞进 `courses` 或 `course_slots` 会带来两个坏处：
 * 删课会连带删掉考试（考试是学校已经公布的事实，不该随用户的课程增删而消失），
 * 而导入课表时又必然要把考试整个重建一遍（课表是 REPLACE 语义）。
 *
 * 所以这里只存**冗余**的关联信息（课程代码 / 课程名），
 * 真要显示颜色、跳转到课程时再按代码去课程表里找 —— 关联是**读时解析**的，
 * 不靠外键，于是考试数据与课程数据真正独立。
 *
 * ## 三种「还没安排好」的状态
 *
 * 学校公布考试是分批的，实测存在三种中间态，各自的表达方式不能混：
 *
 * | 情况 | [startAt] | [dateLabel] | [classroom] / [seat] |
 * |---|---|---|---|
 * | 全部安排好 | 具体时刻 | 空 | 有值 |
 * | 只公布时间，日期待定 | 0 | 「考试周第 3 天」 | 通常有值 |
 * | 座位表还没出 | 具体时刻 | 空 | 空串 |
 *
 * **空串不等于「未知」**：它明确表示「学校还没安排」。
 * 界面上这两者要显示成不同的话 —— 「待学校安排」是一个**进度**，
 * 而「未知」是**信息缺失**，混成一句话就等于告诉用户「你的座位是未知的」，
 * 而事实上学校根本没排，用户会以为要去哪里问。
 */
@Entity(
    tableName = "exams",
    indices = [
        Index(value = ["courseCode"]),
        Index(value = ["startAt"])
    ]
)
data class ExamEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** 教务网选课课号原串，如 `(2026-2027-1)-MATH2432F-0083261-1`。 */
    val xkkh: String = "",

    /**
     * 从 [xkkh] 抽出的课程代码，与 [CourseEntity.code] 同一口径。
     *
     * 用同一套抽取规则（见 `ZjuScheduleMapper` 的课程代码解析），
     * 课表与考试两边算出来的字符串才能对上。
     */
    val courseCode: String = "",

    /**
     * 课程名。**冗余存一份**：考试数据独立于课程，
     * 用户还没导入课表时这份考试也得能显示、能认出来。
     */
    val courseName: String = "",

    /** 期中还是期末。见 [KIND_MIDTERM] / [KIND_FINAL]。 */
    val kind: String = KIND_FINAL,

    /**
     * 绝对开始时间（epoch 毫秒，北京时间）。
     *
     * **0 = 学校还没公布具体日期**（只公布了「考试周第几天」）。
     * 用 0 而不是 null：Room 的可空列在这张表上没有意义，
     * 而 0 这个哨兵值与「1970 年」不可能混淆。
     */
    val startAt: Long = 0L,

    /** 绝对结束时间（epoch 毫秒）。与 [startAt] 同时为 0 或同时有值。 */
    val endAt: Long = 0L,

    /**
     * 开始时刻（当天第几分钟，0..1439）。
     *
     * **日期没公布时也有值**：学校总是先定「几点考」，再定「哪天考」。
     * 只存 [startAt] 的话，日期未定时连时间都一起丢了，
     * 那条考试在界面上就只剩一个课程名。
     */
    val startMinute: Int = 0,

    /** 结束时刻（当天第几分钟）。 */
    val endMinute: Int = 0,

    /**
     * 日期未公布时的原始标签，如「考试周第 3 天」「秋季第 2 天」。
     *
     * 存**原文**而不是自己编一套说法：学校怎么写就怎么显示，
     * 免得用户对着「考试周第 3 天」和教务网上的「秋季第 3 天」对不上号。
     */
    val dateLabel: String = "",

    /** 考试教室。**空串 = 学校还没安排**（见类注释的表格）。 */
    val classroom: String = "",

    /** 座位号。**空串 = 学校还没安排**。 */
    val seat: String = "",

    /** 学分。教务网给的是字符串，这里存成数值方便排序与展示。 */
    val credit: Double = 0.0,

    /** 学年学期标签，如「2026-2027 秋冬」。用于把不同学期的考试分开显示。 */
    val termLabel: String = "",

    /** 本条记录的更新时间（epoch 毫秒）。用于「数据有多旧」的提示。 */
    val updatedAt: Long = 0L
) {
    companion object {
        const val KIND_MIDTERM = "MIDTERM"
        const val KIND_FINAL = "FINAL"

        /** 学校还没公布具体日期。 */
        const val NO_DATE = 0L

        /** 教室 / 座位都还没安排。 */
        val PENDING_LOCATION = ""
    }
}
