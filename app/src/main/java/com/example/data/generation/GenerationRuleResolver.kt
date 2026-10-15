package com.example.data.generation

import com.example.data.local.CourseEntity
import com.example.data.model.CategoryRule
import com.example.data.model.TaskType

/**
 * 生成节奏。
 *
 * 「每周一次」以**该周最早的那节课**为锚点（而不是随机取一次课），
 * 这样同一条待办的截止日在一周之内是稳定的，不会因为多开一次 App 就漂移。
 */
enum class TaskCadence(val label: String) {
    PER_CLASS("每次课"),
    PER_WEEK("每周");

    companion object {
        fun fromString(value: String): TaskCadence =
            entries.firstOrNull { it.name == value } ?: PER_WEEK
    }
}

/**
 * 一条生效的生成规则（已解析完「课程级 / 类别级 / 内置」三层优先级）。
 */
data class TaskGenerationRule(
    val type: TaskType,
    val cadence: TaskCadence,
    /** 作业 / 实验报告的截止：上课日 + N 天 23:59。预习忽略这个字段。 */
    val dueOffsetDays: Int,
    /** 预习的截止：上课前 N 分钟。作业 / 实验忽略这个字段。 */
    val dueBeforeMinutes: Int
) {
    val perClass: Boolean get() = cadence == TaskCadence.PER_CLASS
}

/** 规则表里的一行，剥掉 `courseId` / `taskType` 之后的形状。 */
data class CourseRuleSpec(
    val cadence: String,
    val dueOffsetDays: Int,
    val dueBeforeMinutes: Int
)

/**
 * 三层优先级里唯一的一层：课程 × 类型 > 课程类别 > 内置默认。
 *
 * 写成纯函数、不依赖 Room / DAO，是为了规则解析能直接在普通 JUnit 里测——
 * 「实验课该每周一份还是每次课一份」是规则里最容易写错的一格，而写错了不会崩，
 * 只会安静地生成一堆重复待办。
 */
object GenerationRuleResolver {

    /**
 * 参与自动生成的类型，按「先预习、再作业、最后实验」的顺序。
 *
 * 只有内置的这三个：自动生成的依据是「课程 × 类型」的规则表，
 * 用户新建的类型没有「每节课都排一条」的默认节奏可言（给它硬编一个
 * 等于替用户决定了一条课程该产出多少条这种类型的待办）。
 */
    val AUTO_TYPES: List<TaskType> = listOf(
        TaskType.PRE_STUDY,
        TaskType.POST_CLASS,
        TaskType.LAB_REPORT
    )

    /**
     * 内置默认。
     *
     * 数值是用户直接给定的，不是推导出来的：预习提前 5 分钟、作业与实验报告 6 天后 23:59。
     * 节奏上预习默认每次课——预习的截止本来就挂在某一节课上，「每周一次的预习」语义上讲不通；
     * 作业与实验默认每周一次，因为多数课一周只上两三次，课课都交作业反而不符合实际。
     */
    fun builtinFor(type: TaskType): TaskGenerationRule = when (type) {
        TaskType.PRE_STUDY -> TaskGenerationRule(
            type = type,
            cadence = TaskCadence.PER_CLASS,
            dueOffsetDays = 0,
            dueBeforeMinutes = 5
        )

        else -> TaskGenerationRule(
            type = type,
            cadence = TaskCadence.PER_WEEK,
            dueOffsetDays = 6,
            dueBeforeMinutes = 5
        )
    }

    /**
     * 解析某门课某一类待办最终生效的规则。
     *
     * @param courseRule 课程 × 类型这一层；null = 用户没配过这一格。
     * @param categoryRule 课程类别这一层的默认值；null = 这个类别没有规则行。
     *
     * 类别层只有开关和截止参数、**没有节奏**，所以走类别层时节奏一律取内置值
     * （预习每次课 / 其余每周），只让类别去改「提前几天」这一格。
     */
    fun resolve(
        type: TaskType,
        courseRule: CourseRuleSpec?,
        categoryRule: CategoryRule?
    ): TaskGenerationRule {
        if (courseRule != null) {
            return TaskGenerationRule(
                type = type,
                cadence = TaskCadence.fromString(courseRule.cadence),
                dueOffsetDays = courseRule.dueOffsetDays,
                dueBeforeMinutes = courseRule.dueBeforeMinutes
            )
        }
        if (categoryRule != null) {
            return builtinFor(type).copy(
                dueOffsetDays = categoryRule.defaultPostDays,
                dueBeforeMinutes = categoryRule.preDueBeforeMinutes
            )
        }
        return builtinFor(type)
    }

    /**
     * 该类型对这门课是否开启。
     *
     * 开关只存在 `CourseEntity` 上，规则表不存这一列——设置页课程卡上已经显示
     * 「预习 开 · 作业 开 · 实验 关」，再加一份就成两个真相源了。
     */
    fun isEnabled(course: CourseEntity, type: TaskType): Boolean = when (type) {
        TaskType.PRE_STUDY -> course.autoPreStudy
        TaskType.POST_CLASS -> course.autoPostHomework
        TaskType.LAB_REPORT -> course.autoLabReport
    }

    // ------------------------------------------------------------ 类别联动到课程

    /**
     * 把类别规则的三个开关**整体**套到一门课上。规则为 null（这个类别没有规则行）时原样返回。
     *
     * 这不是「补差量」而是**批量设置**：类别规则对它下面的所有课程始终具有权威，
     * 保存一次就等于「把这个类别下的课统一设成这三个开关」。
     *
     * 早期版本只推「相对上一版变了的那几格」，理由是弹层点一下就存、
     * 全量刷会抹掉用户在单门课上单独调过的开关。那个理由在有了
     * 「先暂存 → 应用 / 取消」的弹层之后就不成立了：改动不再即时落库，
     * 用户看得到改了什么、也退得回去，于是可以选择让类别规则真正说了算。
     *
     * **代价是明确的**：应用之后，该类别下课程上单独设过的开关会被覆盖。
     * 想改回来，在弹层里重新应用，或在课程里单独再设一次。
     *
     * 没真变就返回**同一实例**：调用方靠这个跳过写库，否则每点一次应用都要
     * UPDATE 一次全部课程，Room 白写、Flow 多发、整屏卡片跟着重建。
     */
    fun applySwitches(course: CourseEntity, rule: CategoryRule?): CourseEntity {
        if (rule == null) return course
        val next = course.copy(
            autoPreStudy = rule.autoPreStudy,
            autoPostHomework = rule.autoPostHomework,
            autoLabReport = rule.autoLabReport
        )
        return if (next == course) course else next
    }
}