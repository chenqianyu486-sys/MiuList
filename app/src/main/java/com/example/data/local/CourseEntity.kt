package com.example.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.data.model.CourseKeys

/**
 * 课程实体。
 *
 * 自然键算法见 `com.example.data.model.CourseKeys`。
 */
@Entity(
    tableName = "courses",
    // 非唯一索引：历史数据里若已存在重复课程，唯一索引会让 v3→v4 迁移直接失败。
    // 去重在导入事务内用「同 key 取首条 + 记 issue」完成。
    indices = [Index(value = ["dedupeKey"])]
)
data class CourseEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val code: String = "",
    val category: String, // e.g. "专业必修", "公共基础", "实验实践", "通识公共", "专业选修"
    val teacher: String,
    val classroom: String, // Default classroom
    val colorHex: String,
    /**
     * 自然键。
     *
     * [defaultValue] 必须显式声明：`MIGRATION_3_4` 用
     * `ALTER TABLE courses ADD COLUMN dedupeKey TEXT NOT NULL DEFAULT ''` 加这一列，
     * SQLite 要求给已有行的表加 NOT NULL 列时必须带默认值。而 Room 会拿数据库里
     * 实际存在的默认值来校验实体——实体这边不声明，**每个老用户升级时都会
     * 撞上 schema 校验失败并崩溃**（这正是移除 destructive fallback 后要付的代价）。
     */
    @ColumnInfo(defaultValue = "")
    val dedupeKey: String = "",
    val autoPreStudy: Boolean = true,
    val autoPostHomework: Boolean = true,
    val autoLabReport: Boolean = false,
    val autoReview: Boolean = false,
    val defaultPostDueDays: Int = 3,
    val preStudyEstimatedMinutes: Int = 30,
    val postHomeworkEstimatedMinutes: Int = 60,
    val labReportEstimatedMinutes: Int = 90
) {
    /** 落库前统一补上自然键，避免各处各算一遍算出不同结果。 */
    fun withDedupeKey(): CourseEntity =
        if (dedupeKey.isNotBlank()) this
        else copy(dedupeKey = CourseKeys.compute(code, name, teacher))
}