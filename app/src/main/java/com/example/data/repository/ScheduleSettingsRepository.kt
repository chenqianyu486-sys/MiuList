package com.example.data.repository

import androidx.room.withTransaction
import com.example.data.generation.CourseRuleSpec
import com.example.data.generation.GenerationRuleResolver
import com.example.data.local.AppDatabase
import com.example.data.local.CategoryRuleDao
import com.example.data.local.CourseDao
import com.example.data.local.TaskGenerationRuleDao
import com.example.data.local.TaskGenerationRuleEntity
import com.example.data.local.toDomain
import com.example.data.local.toEntity
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
 *
 * ## 联动规则会批量设置到课程
 *
 * 「课程联动规则」里的三个开关（预习 / 作业 / 实验）**过去只写进 `category_rules`
 * 却没人读**：`GenerationRuleResolver.isEnabled` 只看 `CourseEntity.auto*`。
 * 于是勾掉「专业必修 · 课后作业」对生成毫无影响，整张表是死的。
 *
 * 现在保存类别规则时把三个开关**批量设置**到该类别下所有课程的 `auto*`，
 * 开关仍然只存在一处，不引入第二个真相源。返回受影响的课程门数，
 * 供调用方如实告诉用户——批量设置改的是别人的课，闷头改完会让人以为没生效。
 */
class ScheduleSettingsRepository(
    private val db: AppDatabase,
    private val ruleDao: CategoryRuleDao,
    private val taskRuleDao: TaskGenerationRuleDao,
    private val courseDao: CourseDao
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
     *
     * **播种之后立刻写穿课程**，而表非空时一个格子都不碰：
     * - 表非空 = 用户（或导入）已经有规则配置，内置默认值没有发言权；
     * - 表为空 = 从来没有过任何规则行，这时课程上的开关要么是实体默认值、
     *   要么来自上一份内置默认，正好是这份默认值该管的范围。只补规则行而不写穿，
     *   会留下一份「规则说有、课上说没有」的配置，而且**用户无法自救**：
     *   联动弹层的「应用」在无净改动时是禁用的（`enabled = changed.isNotEmpty()`），
     *   想推平就得先造一处无意义的改动。这不是假想——规则表被导入清空过一次，
     *   当时的表现正是规则卡消失、课程退回「预习 + 作业」。
     */
    suspend fun ensureDefaultRules(defaults: List<CategoryRule>) = db.withTransaction {
        if (ruleDao.count() > 0) return@withTransaction
        ruleDao.insertAll(defaults.map { it.toEntity() })
        defaults.sumOf { applySwitchesLocked(it) }
    }

    /**
     * 保存一条类别规则，并把三个开关**批量设置**到该类别下的所有课程。
     *
     * @return 开关真正发生变化的课程门数（0 = 该类别下没有课程，或本来就全部一致）。
     */
    suspend fun saveRule(rule: CategoryRule): Int = saveRules(listOf(rule))

    /**
     * 批量保存类别规则（弹层「应用」一次可能改多个类别）。单事务，避免中间失败留下半套规则。
     *
     * 语义是**批量设置**而不是「补差量」：每次保存都把这个类别下的课程整体设成
     * 三个开关，包括用户此前在单门课上单独调过的那些。代价是明确的——
     * 应用之后想改回来，得在弹层里重新应用或在课程里单独再设一次。
     * 这条能成立是因为弹层改成了「先暂存 → 应用 / 取消」，不再是点一下就落库。
     *
     * @return 开关真正发生变化的课程门数合计。
     */
    suspend fun saveRules(rules: List<CategoryRule>): Int = db.withTransaction {
        var synced = 0
        for (rule in rules) {
            ruleDao.insertAll(listOf(rule.toEntity()))
            synced += applySwitchesLocked(rule)
        }
        synced
    }

    /**
     * 整表替换（导入用）。导入的类别规则同样是权威，逐类别覆盖其下课程。
     *
     * 被这次替换**删掉**的类别不会去动它的课程：没有「新值」就没有可推的方向，
     * 课程保持现状，而不是被回退到某个默认值。
     */
    suspend fun replaceAll(rules: List<CategoryRule>): Int = db.withTransaction {
        ruleDao.clear()
        ruleDao.insertAll(rules.map { it.toEntity() })
        rules.sumOf { applySwitchesLocked(it) }
    }

    /**
     * 把 [rule] 的三个开关批量套到该类别下的每一门课。**只能在事务内调用。**
     *
     * 只写真正变了的行：值本来就一致的课程不落库，既省一次 UPDATE，
     * 也避免 Room 无谓地让整屏课程卡片重建。
     */
    private suspend fun applySwitchesLocked(rule: CategoryRule): Int {
        val courses = courseDao.getByCategory(rule.category)
        // applySwitches 对「本来就没变」的课返回同一实例，据此挑出真正要写的行。
        val changed = courses.map { GenerationRuleResolver.applySwitches(it, rule) }
            .filterIndexed { index, updated -> updated != courses[index] }
        if (changed.isEmpty()) return 0
        courseDao.updateCourses(changed)
        return changed.size
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
}