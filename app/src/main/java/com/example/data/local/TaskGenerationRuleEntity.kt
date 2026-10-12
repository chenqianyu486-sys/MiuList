package com.example.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * 课程 × 待办类型 的生成规则。**课程级为主，`category_rules` 是它下面那层默认值。**
 *
 * 存的是节奏与截止参数，**不存开关**：开关继续用 `CourseEntity.autoPreStudy /
 * autoPostHomework / autoLabReport`。设置页课程卡上已经显示「预习 开 · 作业 开 · 实验 关」，
 * 再加一列 enabled 就是两个真相源，改一处忘一处，最后没人说得清单子上到底是开是关。
 *
 * **可以带外键**（与 `tasks` 不同）：这张表永远指向真实课程，courses.id 是 AUTOINCREMENT
 * 不会为 0，所以不存在「未关联」那种哨兵值问题。CASCADE 让删课程时规则不可能残留。
 *
 * 规则查不到行不是错误，落到类别默认值或内置默认值（见 `GenerationRuleResolver`）。
 */
@Entity(
    tableName = "task_generation_rules",
    primaryKeys = ["courseId", "taskType"],
    foreignKeys = [
        ForeignKey(
            entity = CourseEntity::class,
            parentColumns = ["id"],
            childColumns = ["courseId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["courseId"])]
)
data class TaskGenerationRuleEntity(
    val courseId: Long,
    /** PRE_STUDY / POST_CLASS / LAB_REPORT。存 `TaskType.name`。 */
    val taskType: String,
    /** PER_CLASS（每次课一次）/ PER_WEEK（每周一次）。 */
    val cadence: String,
    /** 作业 / 实验报告的截止：上课日 + N 天 23:59。预习不用这个字段。 */
    val dueOffsetDays: Int,
    /** 预习的截止：上课前 N 分钟。作业 / 实验不用这个字段。 */
    val dueBeforeMinutes: Int
)