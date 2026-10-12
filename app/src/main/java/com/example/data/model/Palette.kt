package com.example.data.model

/** 课程可用的粉彩配色。导入时按顺序给未指定颜色的课程循环分配。 */
val COURSE_PALETTE = listOf(
    "#FCA5A5", // 粉彩红
    "#F9A8D4", // 粉彩粉
    "#86EFAC", // 粉彩绿
    "#208E9E", // 粉彩青
    "#93C5FD", // 粉彩蓝
    "#D8B4FE", // 粉彩紫
    "#FDBA74", // 粉彩橙
    "#FDE047"  // 粉彩黄
)

/** 预设课程类别。导入出现表外类别时保留原字符串并补一条默认规则，不报错。 */
val COURSE_CATEGORIES = listOf(
    "专业必修",
    "公共基础",
    "实验实践",
    "通识公共",
    "专业选修"
)

val DEFAULT_CATEGORY = "专业必修"

val HEX_COLOR_REGEX = Regex("^#[0-9A-Fa-f]{6}$")

fun isValidHexColor(value: String?): Boolean = value != null && HEX_COLOR_REGEX.matches(value.trim())