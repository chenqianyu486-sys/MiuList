package com.example.data.transfer

import com.example.data.model.CourseCategory

/**
 * 导入时一门课的**最终类别**由谁决定。
 *
 * ## 为什么要单独一个「解析」
 *
 * 三种来源对「类别」的说法完全不同：
 *
 * | 来源 | 类别 |
 * |------|------|
 * | 本 App 自己导出的备份 | 一定是有的（数据库里的实际值） |
 * | 教务网同步（zdbk） | **没有这个字段** |
 * | 用户手改的文件 | 有就有，没有就是没有 |
 *
 * 「没有」与「有，但值是公共基础」在早先的管线里被压成了同一个东西：
 * 解析层按课程名猜一个默认值，校验层在文件没给时再猜一次。
 * 于是教务同步每跑一次，就把用户手动整理过的类别冲回「公共基础」——
 * 而界面上看不出任何异常，因为那个猜出来的值本来就长得像默认值。
 *
 * ## 优先级
 *
 * ```
 * 文件里明确给了（非空） → 用文件的
 * 文件没给，但本机有这门课 → 用本机的
 * 两边都没有 → 按课程名推断默认类别
 * ```
 *
 * 「本机有这门课」按**自然键**认（`code|name|teacher`，与课程本身的去重口径一致），
 * 不按课程名模糊匹配 —— 同名的两门课（不同老师）混在一起比丢类别更糟。
 */
object CourseCategoryResolver {

    /** 解析的结果。冲突是**结果**而不是异常：调用方要能把它当正常情况处理。 */
    sealed interface Result {
        /** 最终类别。 */
        data class Resolved(val category: String) : Result

        /**
         * 本机同一门课有**多个互不相同**的类别，没法决定继承哪一个。
         *
         * 分类一致时不算冲突（同一个值继承多少次都一样）。
         */
        data class Conflict(val categories: List<String>) : Result
    }

    /**
     * @param fileCategory 文件里带的类别。null / 空串 / 纯空白都算「没提供」。
     * @param localCategories 本机同一自然键下的所有类别（可以有多条）。
     * @param courseName 课程名，只用于最后一步的默认推断。
     */
    fun resolve(
        fileCategory: String?,
        localCategories: List<String>,
        courseName: String?
    ): Result {
        // 文件里明确给了就以文件为准 —— 包括明确写「公共基础」：
        // 那个值与默认值长得一样，但它仍然是用户（或来源）的一次明确表态。
        val explicit = fileCategory?.trim()?.takeIf { it.isNotEmpty() }
        if (explicit != null) return Result.Resolved(explicit)

        val locals = localCategories.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        return when {
            // 本机没有这门课 → 新课，按名字推断默认类别。
            locals.isEmpty() -> Result.Resolved(CourseCategory.classifyOnImport(courseName))
            // 本机这门课的类别是一致的 → 继承它。
            locals.size == 1 -> Result.Resolved(locals.single())
            // 多个互不相同的类别 → 继承哪一个都是猜。宁可报出来，也不静默丢掉其中一个。
            else -> Result.Conflict(locals)
        }
    }
}

/**
 * 本机同一门课存在多个互不相同的类别，导入没法决定该继承哪一个。
 *
 * **在删数据之前抛出**，于是事务整体回滚，一门课都不少。
 * 静默挑一个的后果是：另一条记录的类别被无声地丢掉，而用户永远看不到。
 */
class CourseCategoryConflictException(
    val courseName: String,
    val naturalKey: String,
    val categories: List<String>
) : Exception(
    "课程「$courseName」在本机有多条记录，类别分别是 ${categories.joinToString("、")}，" +
        "无法判断导入时该保留哪一个。请先在课程页把它们整理成同一个类别（或删掉多余的一条），" +
        "再重新导入。"
)
