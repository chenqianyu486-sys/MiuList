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

/**
 * 映射放在这里而不是某个 Repository 私有。
 *
 * 「课程联动规则」现在跨两个仓库：`ScheduleSettingsRepository` 写类别规则并写穿课程开关，
 * `CourseRepository` 新建课程时反过来读类别规则来初始化开关。两边各抄一份映射，
 * 迟早有一边少拷一个字段——而少拷的那格不会报错，只会让联动「半个生效」。
 */
internal fun CategoryRuleEntity.toDomain() =
    com.example.data.model.CategoryRule(
        category = category,
        autoPreStudy = preStudy,
        autoPostHomework = postHomework,
        autoLabReport = labReport,
        defaultPostDays = defaultPostDays,
        defaultMinutes = defaultMinutes,
        preDueBeforeMinutes = preDueBeforeMinutes
    )

internal fun com.example.data.model.CategoryRule.toEntity() =
    CategoryRuleEntity(
        category = category,
        preStudy = autoPreStudy,
        postHomework = autoPostHomework,
        labReport = autoLabReport,
        defaultPostDays = defaultPostDays,
        defaultMinutes = defaultMinutes,
        preDueBeforeMinutes = preDueBeforeMinutes
    )