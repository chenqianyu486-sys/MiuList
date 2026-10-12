package com.example.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * 时段的「开课周次」。
 *
 * 每周 / 单周 / 双周 / 第 1–8 周 / `{1,3,5,8,12}` 任意组合全都是同一形状，
 * 所以只有这一张表，没有 `weekParity` 枚举列，也没有 `startWeek`/`endWeek` 两列。
 *
 * **一律展开成全部周次写入**，不用「空集合 = 每周」的哨兵语义——空集合的两种解释
 * （每周 / 未知）太容易搞混。
 *
 * 依赖 [CourseSlotEntity] 的 `ON DELETE CASCADE`：`deleteSlotsByCourseId` /
 * `clearCourses` 这些裸 SQL DELETE 同样会级联，顺带堵掉「时段删了周次残留」。
 */
@Entity(
    tableName = "course_slot_weeks",
    primaryKeys = ["courseSlotId", "week"],
    foreignKeys = [
        ForeignKey(
            entity = CourseSlotEntity::class,
            parentColumns = ["id"],
            childColumns = ["courseSlotId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["week"])]
)
data class CourseSlotWeekEntity(
    val courseSlotId: Long,
    val week: Int
)