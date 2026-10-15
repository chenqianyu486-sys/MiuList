package com.example.data.model

/**
 * 导入时按课程名猜类别。
 *
 * 为什么需要它：教务系统只给课程名 / 教师 / 教室，**完全不给类别**。解析层除了
 * 「是不是实验课」之外无从判断，所以默认落到 [DEFAULT_CATEGORY]，剩下的交给用户改。
 *
 * **只在这一层猜一次**：落库之后类别就是一个普通字符串，之后由用户维护。
 * 写成纯函数、不依赖 Android / Room，可以直接单测（见 `CourseCategoryTest`）。
 */
object CourseCategory {

    /** 实验类课程的类别名。与 [COURSE_CATEGORIES]、内置类别规则里的写法保持一致。 */
    const val LAB = "实验实践"

    /**
     * 命中任一关键词即视为实验类课程。
     *
     * 「实训 / 实践 / 实习」与「实验」并列，不是顺手加的：浙大课表里这几种写法
     * 出现在同一个语义位置 —— 单独排课、动手做、要单独交一份报告。只认「实验」
     * 会漏掉大半实验课，它们就会顶着一个「公共基础」的名字混在基础课里，
     * 而且用户很难自己想起来去逐门改回来。
     *
     * 子串匹配是刻意的：中文没有词边界，「大学物理实验(一)」「程序设计实训(上)」
     * 都得认。代价是「实验室管理」这类通识课会被误判 —— 宁可多认一门，
     * 也不要让实验课混在基础课里被漏掉。
     */
    private val LAB_KEYWORDS = listOf("实验", "实训", "实践", "实习")

    /** 课程名是否指向实验类课程。null / 空串一律 false。 */
    fun isLabCourse(name: String?): Boolean {
        val text = name.orEmpty()
        return LAB_KEYWORDS.any { it in text }
    }

    /**
     * 导入落库前决定这门课进哪一类。
     *
     * **刻意不在这里打开「实验报告」开关**：那是生成规则的事，交给「实验实践」的
     * 类别规则兜底。解析层只认课程名、不猜开关 —— 同名带「实验」的课未必真交
     * 实验报告，在解析层写死 `true` 等于凭空替用户决定要产出多少条待办。
     */
    fun classifyOnImport(courseName: String?): String =
        if (isLabCourse(courseName)) LAB else DEFAULT_CATEGORY
}
