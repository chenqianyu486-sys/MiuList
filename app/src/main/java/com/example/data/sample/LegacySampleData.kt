package com.example.data.sample

import com.example.data.local.CourseEntity
import com.example.data.local.TaskEntity

/**
 * **历史遗留**的「示例数据」识别信息。
 *
 * 2026-10-20 起 App 不再播种示例数据、也不再提供「恢复示例数据」的入口。
 * 这里保留的只是「旧版示例数据长什么样」这份记录，供启动时清掉存量安装里残留的那一份。
 *
 * ## 为什么要留下这一份
 *
 * 装了旧版的机器里，库里躺着 6 门示例课和 7 条示例待办。功能取消后，
 * 界面上已经没有任何东西说明它们是假的 —— 用户会把「高等数学(上)」「数据结构与算法」
 * 当成自己的课，把示例作业当成真作业。放着不管，等于「取消」只取消了入口，
 * 没取消数据：App 里仍然有一整套不是用户的东西。
 *
 * ## 为什么只认精确匹配
 *
 * 清理是在用户没点头的情况下动他的库，判据必须**宁可漏清、不可误删**：
 *
 * - 课程按「课程代码 + 课程名」**成对**匹配。只有代码相同是不够的 ——
 *   浙大的课号形如 `CS201` 是完全可能的，只看代码会连真实课程一起删掉。
 * - 待办按标题**精确**匹配。示例待办只有 7 条，标题是写死的字面量，
 *   用户自己写一条一模一样的标题的概率约等于零。
 *
 * 用户挂在示例课程上的**手写待办不删**，只跟着课程一起失去归属（移入「未关联课程」），
 * 与 `CourseRepository.deleteCourse` 的既有约定一致：待办是用户自己的数据，
 * 删一门课不该顺手删掉它。
 */
object LegacySampleData {

    /**
     * 旧版示例课的「课程代码 + 课程名」。
     *
     * 课程名也列进来是为了防误伤：代码相同而名字不同的，那是真实课程。
     */
    private val SAMPLE_COURSES: Set<Pair<String, String>> = setOf(
        "MATH101" to "高等数学(上)",
        "CS201" to "数据结构与算法",
        "CS205" to "计算机系统与网络",
        "ENG103" to "大学英语(学术读写)",
        "CS302L" to "操作系统实验",
        "AI301" to "人工智能与深度学习"
    )

    /**
     * 旧版示例待办的标题。7 条，全是写死的字面量。
     */
    private val SAMPLE_TASK_TITLES: Set<String> = setOf(
        "【预习】高等数学 第4章 定积分与微积分基本定理",
        "【作业】高数课后习题集 P134 A组 1-8题",
        "【预习】二叉树遍历与AVL平衡树旋转实现原理",
        "【作业】LeetCode 树专项 3题 + 递归与非递归遍历代码",
        "【实验】实验三：多进程并发与信号量互斥同步报告",
        "【作业】Wireshark 抓包分析实验准备与思考题",
        "【预习】梯度下降与反向传播数学推导课件预习"
    )

    /** 这门课是不是旧版的示例课。 */
    fun isSampleCourse(course: CourseEntity): Boolean =
        (course.code to course.name) in SAMPLE_COURSES

    /** 这条待办是不是旧版的示例待办。 */
    fun isSampleTask(task: TaskEntity): Boolean = task.title in SAMPLE_TASK_TITLES
}
