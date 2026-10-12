package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 课程类别 → 待办联动规则的默认值。落库前这只活在 `CourseTodoRepository` 的内存
 * `MutableStateFlow` 里，进程一杀就丢；导入的新类别课程也因此拿不到默认规则。
 *
 * 现在这层退居为「默认值」：课程 × 类型的规则（`task_generation_rules`）优先，
 * 这里只在课程级没配时兜底。
 */
@Entity(tableName = "category_rules")
data class CategoryRuleEntity(
    @PrimaryKey val category: String,
    val preStudy: Boolean = true,
    val postHomework: Boolean = true,
    val labReport: Boolean = false,
    val defaultPostDays: Int = 6,
    val defaultMinutes: Int = 45,
    @androidx.room.ColumnInfo(defaultValue = "5")
    val preDueBeforeMinutes: Int = 5
)