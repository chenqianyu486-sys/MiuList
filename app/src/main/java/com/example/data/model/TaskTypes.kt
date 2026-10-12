package com.example.data.model

/**
 * 「待办类型」这一列现在有两种来源：App 内置的三种，和用户自己新建的。
 *
 * 之前它是纯枚举，于是每加一种类型都要动代码 + 发版。改成
 * 「枚举存内置、数据库存自定义、[TaskTypes] 负责把两者解析成同一个形状」之后，
 * 新建类型不再需要迁移数据层以外的东西。
 *
 * 存储约定（都写进 `tasks.taskType` 这一列 TEXT）：
 * - 内置类型存枚举名：`PRE_STUDY` / `POST_CLASS` / `LAB_REPORT`
 * - 自定义类型存 `CUSTOM:<id>`，`id` 是新建时生成的稳定串
 * - **留空存空串 `""`**（[TASK_TYPE_NONE]）。不用 `null`：这一列是 NOT NULL，
 *   而「无类型」是绝大多数手写待办的常态，不值得为它牵动一次 schema 迁移。
 */

/** `tasks.taskType` 表示「未分类」的取值。空串，而不是 null（列是 NOT NULL）。 */
const val TASK_TYPE_NONE = ""

private const val CUSTOM_PREFIX = "CUSTOM:"

/** 筛选 chip / 分组头 / 徽标共用的「已解析类型」形状。 */
data class TaskTypeOption(
    /** 落库用的原样字符串，直接回写 `tasks.taskType`。 */
    val key: String,
    val label: String,
    /** 淡彩面（chip 底色）。 */
    val colorHex: String,
    /** 同色相墨色（chip 字色），已按 WCAG 验算过，见 [TaskType]。 */
    val inkHex: String,
    /** 用户新建的（内置的不可删）。 */
    val isCustom: Boolean
)

/**
 * 用户新建的待办类型。
 *
 * 刻意不与 Room 实体共用类型：解析规则要能在没有 Android 运行时的地方跑
 * （单元测试、生成引擎），所以 data 层只认这一个纯 Kotlin 形状。
 */
data class CustomTaskType(
    val id: String,
    val label: String,
    val colorHex: String,
    val inkHex: String,
    val sortOrder: Int = 0
) {
    /** 落库 key。 */
    val key: String get() = "$CUSTOM_PREFIX$id"

    fun toOption() = TaskTypeOption(
        key = key,
        label = label,
        colorHex = colorHex,
        inkHex = inkHex,
        isCustom = true
    )
}

/**
 * 新建类型时可选的配色。
 *
 * 全部是从既有类型/优先级徽标上原样搬来的**已验算**色对（淡彩面 + 同色相墨色），
 * 不新造颜色——淡彩当前景读不出字这件事，配色是逐对验算过的，
 * 这里随手挑一个色相就等于把一个不过 AA 的 chip 写进产品。
 */
data class TaskTypePalette(val colorHex: String, val inkHex: String)

val TaskTypePalettes: List<TaskTypePalette> = listOf(
    TaskTypePalette("#A6D6E4", "#3F5F69"), // 雾青
    TaskTypePalette("#CFC6EE", "#5F4F94"), // 藕紫
    TaskTypePalette("#B3E0BE", "#43634B"), // 薄荷
    TaskTypePalette("#F3C9D6", "#8A475B"), // 藕粉
    TaskTypePalette("#D2D8DE", "#565D64"), // 雾灰
    TaskTypePalette("#F6B0AC", "#8E3F3B"), // 珊瑚
    TaskTypePalette("#F3DFAE", "#6B5C38"), // 暖麦
    TaskTypePalette("#C2D2E4", "#4C5D6F")  // 雾霭蓝
)

object TaskTypes {

    /**
     * 下拉/筛选/徽标用的完整列表：内置在前（枚举顺序），自定义按创建顺序在后。
     *
     * **不包含**「无类型」——那是 [TASK_TYPE_NONE] 的一个独立筛选项，
     * 不是一种类型，混进来会让「有哪些类型」这个问题本身变模糊。
     */
    fun options(custom: List<CustomTaskType>): List<TaskTypeOption> =
        TaskType.entries.map {
            TaskTypeOption(it.name, it.label, it.colorHex, it.inkHex, isCustom = false)
        } + custom.sortedBy { it.sortOrder }.map { it.toOption() }

    /**
     * 把 `tasks.taskType` 的原始值解析成一个可展示的类型。
     *
     * 返回 **null** 表示「不展示类型」：留空是绝大多数手写待办的常态，
     * 给它们挂一个灰 chip 只会让 meta 行永远占着一个位置却说不出任何信息。
     * 解析不出来（新建的类型被删了、历史遗留的 REVIEW/CUSTOM）同样返回 null——
     * 与其在界面上编一个「其他」出来，不如诚实地当作未分类。
     */
    fun resolve(raw: String, custom: List<CustomTaskType>): TaskTypeOption? {
        if (raw.isBlank()) return null
        TaskType.parseOrNull(raw)?.let { builtin ->
            return TaskTypeOption(builtin.name, builtin.label, builtin.colorHex, builtin.inkHex, false)
        }
        if (!raw.startsWith(CUSTOM_PREFIX)) return null
        val id = raw.removePrefix(CUSTOM_PREFIX)
        return custom.firstOrNull { it.id == id }?.toOption()
    }

    /** 给筛选用的稳定分组 key：解析不出来统一塌成 [TASK_TYPE_NONE]，不各自成组。 */
    fun groupKeyOf(raw: String, custom: List<CustomTaskType>): String =
        resolve(raw, custom)?.key ?: TASK_TYPE_NONE
}