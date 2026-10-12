package com.example.data.model

/**
 * 课程自然键。重复导入时靠它判「是同一门课还是新的一门」。
 *
 * 算法：`normalize(code) | normalize(name) | normalize(teacher)`，
 * normalize = trim + 折叠连续空白 + 仅 ASCII 小写。
 *
 * 放在 model 而不是 local：导入校验器与 Repository 都要用，
 * 而它们不该反向依赖 Room 实体所在包。
 */
object CourseKeys {
    fun normalize(s: String): String = s.trim()
        .replace(Regex("\\s+"), " ")
        .lowercase()

    fun compute(code: String, name: String, teacher: String): String =
        listOf(code, name, teacher).joinToString("|") { normalize(it) }
}