package com.example.data.model

/**
 * 待办类别配色 = **一对**颜色，不是一个。
 *
 * [colorHex] 是淡彩（面），[inkHex] 是同色相压暗后的墨色（字）。
 * 成对存是必须的：淡彩本身当文字色读不出来（#A6D6E4 压卡片只有 1.5:1），
 * 而课程色那套 `readableOn()` 也不够用——它是朝墨色 lerp 0.55，
 * 作用在淡彩上得到的中间色压在「淡彩底 + 淡卡面」的叠加结果上仍不足 4.5:1。
 * 直接把验算过的墨色写死，比每次现场推导更可控。
 *
 * 墨色的算法：保持色相（偏差 <1°），压明度同时收饱和，
 * 直到在「淡彩 34% 叠实测最深的几档课程染色卡面（紫/蓝/青/橙）」上仍 ≥4.85:1。
 * 阈值不能只按 4.5 卡：实测八支里都曾落在 4.33~4.38，边缘擦过 AA。
 */
enum class TaskType(val label: String, val colorHex: String, val inkHex: String) {
    PRE_STUDY("课前预习", "#A6D6E4", "#3F5F69"),  // 雾青
    POST_CLASS("课后作业", "#CFC6EE", "#5F4F94"), // 藕紫
    LAB_REPORT("实验报告", "#B3E0BE", "#43634B");  // 薄荷

    companion object {
        fun fromString(value: String): TaskType {
            return entries.find { it.name.equals(value, ignoreCase = true) } ?: POST_CLASS
        }

        /** 严格解析：未知/历史值返回 null，而不是悄悄塌成作业。 */
        fun parseOrNull(value: String): TaskType? =
            entries.find { it.name.equals(value, ignoreCase = true) }
    }
}

/** 优先级配色：红→黄→蓝的冷暖梯度，同样是淡彩面 + 同色相墨色，见 [TaskType]。 */
enum class TaskPriority(val label: String, val colorHex: String, val inkHex: String) {
    HIGH("高", "#F6B0AC", "#8E3F3B"),      // 珊瑚
    MEDIUM("中", "#F3DFAE", "#6B5C38"),    // 暖麦
    LOW("低", "#C2D2E4", "#4C5D6F");       // 雾霭蓝

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

/**
 * 课程类别 → 待办生成规则的**默认值层**。
 *
 * 规则有三层优先级（见 `GenerationRuleResolver`）：
 * 课程 × 类型（本表的上一层的上一层的上一层，`task_generation_rules`）
 * → 类别（本表）→ 内置默认。
 *
 * 所以这里存的是「这一类课大概怎么排」，具体某一门课要不要改去课程级改。
 */
data class CategoryRule(
    val category: String,
    val autoPreStudy: Boolean = true,
    val autoPostHomework: Boolean = true,
    val autoLabReport: Boolean = false,
    /** 作业 / 实验报告的截止：上课日 + N 天 23:59。默认 6 天。 */
    val defaultPostDays: Int = 6,
    val defaultMinutes: Int = 45,
    /** 预习的截止：上课前 N 分钟。默认 5 分钟。 */
    val preDueBeforeMinutes: Int = 5
)
