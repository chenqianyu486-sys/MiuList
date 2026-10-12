package com.example.data.repository

import androidx.room.withTransaction
import com.example.data.generation.CourseRuleSpec
import com.example.data.local.AppDatabase
import com.example.data.local.CategoryRuleDao
import com.example.data.local.CategoryRuleEntity
import com.example.data.local.TaskGenerationRuleDao
import com.example.data.local.TaskGenerationRuleEntity
import com.example.data.model.CategoryRule
import com.example.data.model.TaskType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

/**
 * 生成规则（课程级 + 类别级）与周次浏览状态。
 *
 * 类别规则落库前只活在 `CourseTodoRepository` 的内存 `MutableStateFlow` 里 ——
 * 进程一杀就丢，导入的新类别课程也因此拿不到默认规则。现在两层都在库里：
 * - `task_generation_rules`：课程 × 类型，**优先级更高**；
 * - `category_rules`：课程类别，作为上面那层缺项时的默认值。
 *
 * 解析顺序见 `GenerationRuleResolver`，这里只负责存取。
 */
class ScheduleSettingsRepository(
    private val db: AppDatabase,
    private val ruleDao: CategoryRuleDao,
    private val taskRuleDao: TaskGenerationRuleDao
) {
    private val _selectedWeek = MutableStateFlow(0)
    val selectedWeek: StateFlow<Int> = _selectedWeek.asStateFlow()

    val categoryRules: Flow<List<CategoryRule>> =
        ruleDao.getAll().map { list -> list.map { it.toDomain() } }

    /**
     * 课程 × 类型的规则，按 `courseId → type → 规格` 组织好再对外。
     * 上层（生成引擎、设置弹层）拿到的就是最终形状，不必自己拆 entity。
     */
    val taskRules: Flow<Map<Long, Map<TaskType, CourseRuleSpec>>> =
        taskRuleDao.getAll().map { rows ->
            rows.groupBy { it.courseId }
                .mapValues { (_, list) ->
                    list.mapNotNull { row ->
                        runCatching { TaskType.valueOf(row.taskType) }
                            .getOrNull()
                            ?.let { type ->
                                type to CourseRuleSpec(
                                    cadence = row.cadence,
                                    dueOffsetDays = row.dueOffsetDays,
                                    dueBeforeMinutes = row.dueBeforeMinutes
                                )
                            }
                    }.toMap()
                }
        }

    suspend fun getCategoryRulesOnce(): List<CategoryRule> = ruleDao.getAllOnce().map { it.toDomain() }

    suspend fun getTaskRulesOnce(): Map<Long, Map<TaskType, CourseRuleSpec>> =
        taskRuleDao.getAllOnce().groupBy { it.courseId }
            .mapValues { (_, list) ->
                list.mapNotNull { row ->
                    runCatching { TaskType.valueOf(row.taskType) }.getOrNull()?.let { type ->
                        type to CourseRuleSpec(
                            row.cadence,
                            row.dueOffsetDays,
                            row.dueBeforeMinutes
                        )
                    }
                }.toMap()
            }

    /**
     * 首次读取为空时写入内置默认规则。
     *
     * 必须做这一步：没有规则表时课程自动生成待办的默认时长会退化成
     * CourseEntity 里的硬编码值，实验课就拿不到「自动生成实验报告」了。
     */
    suspend fun ensureDefaultRules(defaults: List<CategoryRule>) = db.withTransaction {
        if (ruleDao.count() > 0) return@withTransaction
        ruleDao.insertAll(defaults.map { it.toEntity() })
    }

    suspend fun saveRule(rule: CategoryRule) = db.withTransaction {
        ruleDao.insertAll(listOf(rule.toEntity()))
    }

    suspend fun replaceAll(rules: List<CategoryRule>) = db.withTransaction {
        ruleDao.clear()
        ruleDao.insertAll(rules.map { it.toEntity() })
    }

    /**
     * 存一条课程 × 类型的规则。主键就是这两列，所以「保存」就是覆盖。
     *
     * 不写 enabled：开关仍然只存在 `CourseEntity.auto*` 上（见
     * `TaskGenerationRuleEntity` 的注释），避免两个真相源。
     */
    suspend fun saveTaskRule(
        courseId: Long,
        taskType: TaskType,
        spec: CourseRuleSpec
    ) = db.withTransaction {
        taskRuleDao.upsert(
            TaskGenerationRuleEntity(
                courseId = courseId,
                taskType = taskType.name,
                cadence = spec.cadence,
                dueOffsetDays = spec.dueOffsetDays,
                dueBeforeMinutes = spec.dueBeforeMinutes
            )
        )
    }

    /** 删掉一格规则，让它回落到类别默认 / 内置默认。 */
    suspend fun clearTaskRule(courseId: Long, taskType: TaskType) = db.withTransaction {
        taskRuleDao.delete(courseId, taskType.name)
    }

    suspend fun countRules(): Int = ruleDao.count()

    fun setSelectedWeek(week: Int) {
        _selectedWeek.value = week
    }

    private fun CategoryRuleEntity.toDomain() =
        CategoryRule(
            category = category,
            autoPreStudy = preStudy,
            autoPostHomework = postHomework,
            autoLabReport = labReport,
            defaultPostDays = defaultPostDays,
            defaultMinutes = defaultMinutes,
            preDueBeforeMinutes = preDueBeforeMinutes
        )

    private fun CategoryRule.toEntity() =
        CategoryRuleEntity(
            category = category,
            preStudy = autoPreStudy,
            postHomework = autoPostHomework,
            labReport = autoLabReport,
            defaultPostDays = defaultPostDays,
            defaultMinutes = defaultMinutes,
            preDueBeforeMinutes = preDueBeforeMinutes
        )
}