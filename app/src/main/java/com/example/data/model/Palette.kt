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

/**
 * 预设课程类别。导入出现表外类别时保留原字符串并补一条默认规则，不报错。
 *
 * 顺序 = 设置页类别 chip 的排列顺序，**默认类别排第一个**，这样新建课程时
 * 第一个 chip 就是已经选中的那个。
 */
val COURSE_CATEGORIES = listOf(
    "公共基础",
    "专业必修",
    "实验实践",
    "通识公共",
    "专业选修"
)

/**
 * 导入时课程没写类别时的落点（浙大导入与文件导入共用）。
 *
 * 取「公共基础」而不是「专业必修」：教务系统给的课程名不带任何性质信息，
 * 默认值不该替用户断言「这是专业课」。而按常见课程名看，落到公共基础的那一批
 * （高等数学、大学英语…）本来就确实是公共基础。猜错了用户在设置里点一下就改回来。
 *
 * 实验课不走这里，由 [CourseCategory.classifyOnImport] 单独识别。
 */
val DEFAULT_CATEGORY = "公共基础"

val HEX_COLOR_REGEX = Regex("^#[0-9A-Fa-f]{6}$")

fun isValidHexColor(value: String?): Boolean = value != null && HEX_COLOR_REGEX.matches(value.trim())