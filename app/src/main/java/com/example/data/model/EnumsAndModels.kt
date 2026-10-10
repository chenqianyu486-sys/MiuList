package com.example.data.model

enum class TaskType(val label: String, val colorHex: String) {
    PRE_STUDY("课前预习", "#0891B2"),   // 统一青色 (#0891B2)
    POST_CLASS("课后作业", "#18181B"),  // 曜黑 (#18181B)
    LAB_REPORT("实验报告", "#0891B2"),  // 统一青色 (#0891B2)
    REVIEW("复习总结", "#52525B"),      // 墨灰 (#52525B)
    CUSTOM("自定义待办", "#71717A");    // 浅墨灰 (#71717A)

    companion object {
        fun fromString(value: String): TaskType {
            return entries.find { it.name.equals(value, ignoreCase = true) } ?: POST_CLASS
        }
    }
}

enum class TaskPriority(val label: String, val colorHex: String) {
    HIGH("高", "#0891B2"),              // 统一青色强调色 (#0891B2)
    MEDIUM("中", "#3F3F46"),            // 墨灰 (#3F3F46)
    LOW("普通", "#71717A");             // 浅灰 (#71717A)

    companion object {
        fun fromString(value: String): TaskPriority {
            return entries.find { it.name.equals(value, ignoreCase = true) } ?: MEDIUM
        }
    }
}

data class StudentProfile(
    val name: String = "陈晨",
    val studentId: String = "2023091024",
    val university: String = "电子科技大学",
    val college: String = "计算机科学与工程学院",
    val major: String = "软件工程与人工智能",
    val semester: String = "2026-2027学年 秋季学期",
    val currentWeek: Int = 6,
    val isLoggedIn: Boolean = true
)

data class CategoryRule(
    val category: String,
    val autoPreStudy: Boolean = true,
    val autoPostHomework: Boolean = true,
    val autoLabReport: Boolean = false,
    val defaultPostDays: Int = 3,
    val defaultMinutes: Int = 45
)
