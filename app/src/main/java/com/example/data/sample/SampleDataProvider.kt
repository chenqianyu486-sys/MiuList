package com.example.data.sample

import com.example.data.local.CourseEntity
import com.example.data.local.CourseSlotEntity
import com.example.data.local.TaskEntity
import com.example.data.model.TaskStar
import com.example.data.model.TaskType
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object SampleDataProvider {

    val DEMO_COURSE_CODES: Set<String> = setOf("MATH101", "CS201", "CS205", "ENG103", "CS302L", "AI301")

    fun isDemoCourseList(courses: List<CourseEntity>): Boolean {
        if (courses.isEmpty()) return false
        return courses.all { it.code in DEMO_COURSE_CODES }
    }

    val sampleCourses = listOf(
        CourseEntity(
            id = 1,
            name = "高等数学(上)",
            code = "MATH101",
            category = "公共基础",
            teacher = "王明阳 教授",
            classroom = "仙林教二-301",
            colorHex = "#93C5FD", // 粉彩蓝 (Pastel Blue)
            // 三个开关逐条对齐 AppContainer.DEFAULT_CATEGORY_RULES：演示数据是给新用户
            // 看「联动规则长什么样」的第一手样本，规则说关而这里开着就成了假演示。
            autoPreStudy = false,
            autoPostHomework = true,
            autoLabReport = false,
            defaultPostDueDays = 3,
            preStudyEstimatedMinutes = 30,
            postHomeworkEstimatedMinutes = 60
        ),
        CourseEntity(
            id = 2,
            name = "数据结构与算法",
            code = "CS201",
            category = "专业必修",
            teacher = "李成远 副教授",
            classroom = "信息楼 304",
            colorHex = "#208E9E", // 粉彩青 (Pastel Cyan)
            autoPreStudy = false,
            autoPostHomework = true,
            autoLabReport = false,
            defaultPostDueDays = 4,
            preStudyEstimatedMinutes = 45,
            postHomeworkEstimatedMinutes = 90
        ),
        CourseEntity(
            id = 3,
            name = "计算机系统与网络",
            code = "CS205",
            category = "专业必修",
            teacher = "赵海 教授",
            classroom = "科技馆 B201",
            colorHex = "#86EFAC", // 粉彩绿 (Pastel Green)
            autoPreStudy = false,
            autoPostHomework = true,
            autoLabReport = false,
            defaultPostDueDays = 3,
            preStudyEstimatedMinutes = 30,
            postHomeworkEstimatedMinutes = 60
        ),
        CourseEntity(
            id = 4,
            name = "大学英语(学术读写)",
            code = "ENG103",
            category = "通识公共",
            teacher = "Sarah Miller",
            classroom = "外语楼 205",
            colorHex = "#F9A8D4", // 粉彩粉 (Pastel Pink)
            // 通识公共三个开关默认全关（用户 2026-10-14 定）
            autoPreStudy = false,
            autoPostHomework = false,
            autoLabReport = false,
            defaultPostDueDays = 4,
            preStudyEstimatedMinutes = 25,
            postHomeworkEstimatedMinutes = 45
        ),
        CourseEntity(
            id = 5,
            name = "操作系统实验",
            code = "CS302L",
            category = "实验实践",
            teacher = "周立峰 讲师",
            classroom = "计机实验楼 502",
            colorHex = "#D8B4FE", // 粉彩紫 (Pastel Purple)
            autoPreStudy = true,
            autoPostHomework = false,
            autoLabReport = true,
            defaultPostDueDays = 5,
            preStudyEstimatedMinutes = 40,
            labReportEstimatedMinutes = 120
        ),
        CourseEntity(
            id = 6,
            name = "人工智能与深度学习",
            code = "AI301",
            category = "专业选修",
            teacher = "郑晓宇 教授",
            classroom = "AI创新中心 410",
            colorHex = "#FDBA74", // 粉彩橙 (Pastel Orange)
            autoPreStudy = false,
            autoPostHomework = true,
            autoLabReport = false,
            defaultPostDueDays = 4,
            preStudyEstimatedMinutes = 35,
            postHomeworkEstimatedMinutes = 75
        )
    )

    val sampleCourseSlots = listOf(
        // 高数: 一周2次 (周一 1-2节，周四 3-5节)
        CourseSlotEntity(id = 1, courseId = 1, dayOfWeek = 1, startPeriod = 1, endPeriod = 2, classroom = "仙林教二-301"),
        CourseSlotEntity(id = 2, courseId = 1, dayOfWeek = 4, startPeriod = 3, endPeriod = 5, classroom = "仙林教二-301"),

        // 数据结构: 一周2次 (周二 3-4节，周五 6-7节)
        CourseSlotEntity(id = 3, courseId = 2, dayOfWeek = 2, startPeriod = 3, endPeriod = 4, classroom = "信息楼 304"),
        CourseSlotEntity(id = 4, courseId = 2, dayOfWeek = 5, startPeriod = 6, endPeriod = 7, classroom = "信息楼 304"),

        // 计算机网络: 一周2次 (周三 1-2节，周五 1-2节)
        CourseSlotEntity(id = 5, courseId = 3, dayOfWeek = 3, startPeriod = 1, endPeriod = 2, classroom = "科技馆 B201"),
        CourseSlotEntity(id = 6, courseId = 3, dayOfWeek = 5, startPeriod = 1, endPeriod = 2, classroom = "科技馆 B201"),

        // 大学英语: 周三 5-6节
        CourseSlotEntity(id = 7, courseId = 4, dayOfWeek = 3, startPeriod = 5, endPeriod = 6, classroom = "外语楼 205"),

        // 操作系统实验: 周四 5-8节 (连续4节大实验)
        CourseSlotEntity(id = 8, courseId = 5, dayOfWeek = 4, startPeriod = 5, endPeriod = 8, classroom = "计机实验楼 502"),

        // 人工智能与深度学习: 周二 11-13节 (晚课3节连上)
        CourseSlotEntity(id = 9, courseId = 6, dayOfWeek = 2, startPeriod = 11, endPeriod = 13, classroom = "AI创新中心 410")
    )

    fun createSampleTasks(nowMillis: Long = System.currentTimeMillis()): List<TaskEntity> {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val dateOnlySdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

        fun makeTime(daysOffset: Int, hour: Int, minute: Int): Pair<Long, String> {
            val c = Calendar.getInstance().apply {
                timeInMillis = nowMillis
                add(Calendar.DAY_OF_YEAR, daysOffset)
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            return Pair(c.timeInMillis, sdf.format(c.time))
        }

        val todayDateStr = dateOnlySdf.format(Date(nowMillis))
        val d0 = makeTime(0, 23, 59)
        val d1 = makeTime(1, 23, 59)
        val d2 = makeTime(2, 23, 59)
        val d3 = makeTime(3, 23, 59)

        val startMon = makeTime(0, 8, 0)
        val startTue = makeTime(1, 10, 0)
        val startTueNight = makeTime(1, 20, 20)
        val startWed = makeTime(2, 8, 0)
        val startThu = makeTime(3, 14, 0)

        return listOf(
            TaskEntity(
                id = 1,
                courseId = 1,
                courseName = "高等数学(上)",
                courseCategory = "公共基础",
                courseColorHex = "#93C5FD",
                title = "【预习】高等数学 第4章 定积分与微积分基本定理",
                description = "通读教材P128-P136例题，重点标记牛顿-莱布尼茨公式推导疑惑点",
                taskType = TaskType.PRE_STUDY.name,
                startTimeTimestamp = startMon.first,
                startTimeString = "周一 08:00 (第1-2节)",
                dueDateTimestamp = d0.first,
                dueDateString = d0.second,
                priority = TaskStar.encode(true),
                isCompleted = true,
                completedAt = nowMillis - 3600000,
                isAutoGenerated = true,
                generatedDate = todayDateStr
            ),
            TaskEntity(
                id = 2,
                courseId = 1,
                courseName = "高等数学(上)",
                courseCategory = "公共基础",
                courseColorHex = "#93C5FD",
                title = "【作业】高数课后习题集 P134 A组 1-8题",
                description = "完成换元积分法与分部积分法证明题，拍照上传学习通并备纸质作业本",
                taskType = TaskType.POST_CLASS.name,
                startTimeTimestamp = startMon.first,
                startTimeString = "周一 08:00 (第1-2节)",
                dueDateTimestamp = d2.first,
                dueDateString = d2.second,
                priority = TaskStar.encode(true),
                isCompleted = false,
                isAutoGenerated = true,
                generatedDate = todayDateStr
            ),
            TaskEntity(
                id = 3,
                courseId = 2,
                courseName = "数据结构与算法",
                courseCategory = "专业必修",
                courseColorHex = "#208E9E",
                title = "【预习】二叉树遍历与AVL平衡树旋转实现原理",
                description = "预习第6章树形结构，理解左旋与右旋在维持平衡因子中的计算过程",
                taskType = TaskType.PRE_STUDY.name,
                startTimeTimestamp = startTue.first,
                startTimeString = "周二 10:00 (第3-4节)",
                dueDateTimestamp = d1.first,
                dueDateString = d1.second,
                priority = TaskStar.encode(false),
                isCompleted = false,
                isAutoGenerated = true,
                generatedDate = todayDateStr
            ),
            TaskEntity(
                id = 4,
                courseId = 2,
                courseName = "数据结构与算法",
                courseCategory = "专业必修",
                courseColorHex = "#208E9E",
                title = "【作业】LeetCode 树专项 3题 + 递归与非递归遍历代码",
                description = "完成前中后序非递归遍历，并撰写算法时间与空间复杂度简析",
                taskType = TaskType.POST_CLASS.name,
                startTimeTimestamp = startTue.first,
                startTimeString = "周二 10:00 (第3-4节)",
                dueDateTimestamp = d3.first,
                dueDateString = d3.second,
                priority = TaskStar.encode(true),
                isCompleted = false,
                isAutoGenerated = true,
                generatedDate = todayDateStr
            ),
            TaskEntity(
                id = 5,
                courseId = 5,
                courseName = "操作系统实验",
                courseCategory = "实验实践",
                courseColorHex = "#D8B4FE",
                title = "【实验】实验三：多进程并发与信号量互斥同步报告",
                description = "整理生产者-消费者问题代码运行截图，分析死锁预防机制并填写实验思考题",
                taskType = TaskType.LAB_REPORT.name,
                startTimeTimestamp = startThu.first,
                startTimeString = "周四 14:00 (第5-8节)",
                dueDateTimestamp = d3.first,
                dueDateString = d3.second,
                priority = TaskStar.encode(true),
                isCompleted = false,
                isAutoGenerated = true,
                generatedDate = todayDateStr
            ),
            TaskEntity(
                id = 6,
                courseId = 3,
                courseName = "计算机系统与网络",
                courseCategory = "专业必修",
                courseColorHex = "#86EFAC",
                title = "【作业】Wireshark 抓包分析实验准备与思考题",
                description = "分析HTTP与TCP三次握手报文结构字段，绘制时序图",
                taskType = TaskType.POST_CLASS.name,
                startTimeTimestamp = startWed.first,
                startTimeString = "周三 08:00 (第1-2节)",
                dueDateTimestamp = d1.first,
                dueDateString = d1.second,
                priority = TaskStar.encode(false),
                isCompleted = true,
                completedAt = nowMillis - 7200000,
                isAutoGenerated = true,
                generatedDate = todayDateStr
            ),
            TaskEntity(
                id = 7,
                courseId = 6,
                courseName = "人工智能与深度学习",
                courseCategory = "专业选修",
                courseColorHex = "#FDBA74",
                title = "【预习】梯度下降与反向传播数学推导课件预习",
                description = "复习偏导数与链式求导法则，理解学习率超参数对损失函数收敛的影响",
                taskType = TaskType.PRE_STUDY.name,
                startTimeTimestamp = startTueNight.first,
                startTimeString = "周二 20:20 (第11-13节)",
                dueDateTimestamp = d2.first,
                dueDateString = d2.second,
                priority = TaskStar.encode(false),
                isCompleted = false,
                isAutoGenerated = true,
                generatedDate = todayDateStr
            )
        )
    }
}
