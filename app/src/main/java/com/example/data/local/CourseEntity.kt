package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "courses")
data class CourseEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val code: String = "",
    val category: String, // e.g. "专业必修", "公共基础", "实验实践", "通识公共", "专业选修"
    val teacher: String,
    val classroom: String, // Default classroom
    val colorHex: String,
    val autoPreStudy: Boolean = true,
    val autoPostHomework: Boolean = true,
    val autoLabReport: Boolean = false,
    val autoReview: Boolean = false,
    val defaultPostDueDays: Int = 3,
    val preStudyEstimatedMinutes: Int = 30,
    val postHomeworkEstimatedMinutes: Int = 60,
    val labReportEstimatedMinutes: Int = 90
)
