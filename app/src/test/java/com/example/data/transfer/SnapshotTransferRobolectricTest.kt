package com.example.data.transfer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.codec.CourseBundle
import com.example.data.codec.DecodeResult
import com.example.data.codec.JsonScheduleCodec
import com.example.data.codec.ProvenanceBundle
import com.example.data.codec.ScheduleBundle
import com.example.data.codec.SlotBundle
import com.example.data.codec.TaskBundle
import com.example.data.codec.TaskRuleBundle
import com.example.data.codec.TaskTypeBundle
import com.example.data.codec.TimetableBlockBundle
import com.example.data.di.AppContainer
import com.example.data.generation.CourseRuleSpec
import com.example.data.local.CourseEntity
import com.example.data.local.CourseSlotEntity
import com.example.data.local.CustomTaskTypeEntity
import com.example.data.local.TaskEntity
import com.example.data.model.CourseKeys
import com.example.data.model.SlotWithWeeks
import com.example.data.model.TaskStar
import com.example.data.model.TaskType
import com.example.data.model.TaskTypes
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v3 整机快照的导入编排。Robolectric + 真实 Room。
 *
 * 这一层要守住的**核心承诺是「两台设备可以交替使用而不互相丢东西」**：
 * - A 上新建的待办，导入到 B 之后 B 有；
 * - B 上勾掉的待办，导回 A 之后 A 也勾上了；
 * - A 上改过的（较新的）那一份，导入到 B 之后**不被 B 的旧副本覆盖**。
 *
 * 另外两条：
 * - 预览绝不写库（用户点确认之前 DB 必须一动不动）；
 * - 课表导入（浙大同步走的那条）**不许碰待办**。
 */
@RunWith(RobolectricTestRunner::class)
// 与 ScheduleTransferRobolectricTest 同一个理由：Robolectric 的 36 号沙箱要求
// JDK 21，本机构建环境固定 JDK 17。这里验的是导入编排与合并语义。
@Config(sdk = [34])
class SnapshotTransferRobolectricTest {

    private fun container(): AppContainer =
        AppContainer(ApplicationProvider.getApplicationContext<Context>())

    private companion object {
        /**
         * 课程的自然键。
         *
         * **必须用 `CourseKeys.compute` 现算，不要手写字面量。**
         * 手写出来的（当年是 `"CS101|王教授"`）与真实算法不符，于是待办与规则
         * 一条也配不上 —— 而表现只是「规则没落库」，没有任何报错。
         * 这个类的每条待办都靠它关联课程，键错了整套断言都在测别的东西。
         */
        val COURSE_KEY = com.example.data.model.CourseKeys.compute("CS101", "高等数学", "王教授")
    }

    /**
     * 每条用例**开始前**清库，而不只是结束后。
     *
     * 只有 `@After` 是不够的：`AppContainer` 拿的是 `AppDatabase` 单例，
     * 库文件在 Robolectric 沙箱里**跨测试类存活**。别的类（尤其是
     * `ScheduleTransferRobolectricTest`）留下的同键课程会让本类里
     * 「本机恰好只有那一门课」的断言失真 —— 而失败的报错只会显示
     * 「库里有两门同键的课」，完全看不出是谁弄脏的。
     *
     * 两条都留着：开头清是为了不被别人污染，结尾清是为了不污染别人。
     */
    @Before
    fun setUp() {
        runBlocking { reset(container()) }
    }

    @After
    fun tearDown() {
        runBlocking { reset(container()) }
    }

    /** 每个测试从空库开始，免得 Room 单例在方法之间串状态（与既有测试同因）。 */
    private suspend fun reset(c: AppContainer) {
        c.database.taskDao().clearTasks()
        c.database.customTaskTypeDao().clear()
        c.database.taskGenerationRuleDao().clear()
        c.courseRepository.clearAllCourses()
        c.calendarRepository.saveCalendar(
            semester = null,
            periods = emptyList(),
            noClass = emptyList(),
            makeup = emptyList(),
            replacePeriods = false,
            replaceNoClass = true,
            replaceMakeup = true
        )
        c.settingsRepository.replaceAll(emptyList())
    }

    private suspend fun seedCourse(
        c: AppContainer,
        name: String = "高等数学",
        teacher: String = "王教授"
    ): Long = c.courseRepository.saveCourseWithSlots(
        CourseEntity(
            name = name, code = "CS101", category = "公共基础", teacher = teacher,
            classroom = "教二-301", colorHex = "#93C5FD"
        ),
        listOf(
            SlotWithWeeks(
                slot = CourseSlotEntity(courseId = 0, dayOfWeek = 1, startPeriod = 1, endPeriod = 2),
                weeks = emptySet()
            )
        )
    )

    private suspend fun seedTask(
        c: AppContainer,
        courseId: Long,
        title: String,
        uid: String = "uid-$title",
        completed: Boolean = false,
        starred: Boolean = false,
        updatedAt: Long = 1000L,
        taskType: String = "POST_CLASS"
    ): TaskEntity {
        val id = c.database.taskDao().insertTask(
            TaskEntity(
                courseId = courseId,
                courseName = if (courseId == TaskEntity.UNLINKED_COURSE_ID) {
                    TaskEntity.UNLINKED_COURSE_NAME
                } else {
                    "高等数学"
                },
                courseCategory = if (courseId == TaskEntity.UNLINKED_COURSE_ID) "" else "公共基础",
                courseColorHex = if (courseId == TaskEntity.UNLINKED_COURSE_ID) {
                    TaskEntity.UNLINKED_COLOR_HEX
                } else {
                    "#93C5FD"
                },
                title = title,
                taskType = taskType,
                dueDateTimestamp = TaskEntity.NO_DUE_TIMESTAMP,
                isCompleted = completed,
                priority = TaskStar.encode(starred),
                createdAt = 500L,
                syncUid = uid,
                updatedAt = updatedAt
            )
        )
        return c.database.taskDao().getBySyncUid(uid) ?: error("刚插入的待办查不到：$id")
    }

    private suspend fun taskByUid(c: AppContainer, uid: String) =
        c.database.taskDao().getBySyncUid(uid)

    private suspend fun importSnapshot(c: AppContainer, json: String): ImportReport {
        val preview = c.importService.preview(json, ImportStrategy.SNAPSHOT)
        assertTrue("本该预览成功：$preview", preview is PreviewResult.Ready)
        return c.importService.commit(preview as PreviewResult.Ready)
    }

    /**
     * 一份 v3 文档，**由 Bundle 经真正的 codec 编码得到**。
     *
     * 刻意不手写 JSON 字符串：手写过一版，接连踩了三个坑 ——
     * 「空块」与「语法错」留下的痕迹一模一样（都指向 `$.null`）、
     * 手写的课程键与 `CourseKeys.compute` 的真实算法不符（于是待办与规则
     * 一条也配不上、而没有任何报错）、以及逗号收尾。
     * 这三条都在**测试自己的代码**里，与被测的同步逻辑无关。
     *
     * 走 codec 的另一个好处：这条路径本身就是 v3 编解码的往返验证。
     */
    private fun snapshotJson(
        tasks: List<TaskBundle> = emptyList(),
        taskTypes: List<TaskTypeBundle> = emptyList(),
        taskRules: List<TaskRuleBundle> = emptyList(),
        courseName: String = "高等数学"
    ): String = com.example.data.codec.JsonScheduleCodec().encode(
        com.example.data.codec.ScheduleBundle(
            provenance = com.example.data.codec.ProvenanceBundle(
                source = "snapshot",
                generator = "test"
            ),
            timetable = com.example.data.codec.TimetableBlockBundle(
                courses = listOf(
                    com.example.data.codec.CourseBundle(
                        key = COURSE_KEY,
                        name = courseName,
                        code = "CS101",
                        teacher = "王教授",
                        category = "公共基础",
                        slots = listOf(
                            com.example.data.codec.SlotBundle(
                                dayOfWeek = 1,
                                startPeriod = 1,
                                endPeriod = 2
                            )
                        )
                    )
                )
            ),
            tasks = tasks,
            taskTypes = taskTypes,
            taskRules = taskRules
        )
    )

    private fun taskBundle(
        uid: String,
        title: String,
        completed: Boolean = false,
        starred: Boolean = false,
        updatedAt: Long = 1000L,
        courseKey: String? = COURSE_KEY,
        taskType: String? = "POST_CLASS"
    ) = com.example.data.codec.TaskBundle(
        syncUid = uid,
        title = title,
        taskType = taskType,
        courseKey = courseKey,
        isCompleted = completed,
        priority = TaskStar.encode(starred),
        createdAt = 500L,
        updatedAt = updatedAt
    )

    // ================================================================ 核心：两台设备

    @Test
    fun `a task created on one device shows up on the other`() = runBlocking {
        val a = container()
        val courseId = seedCourse(a)

        seedTask(a, courseId, "A 上新建的待办", uid = "shared-1")
        val json = a.exportService.exportJson()

        reset(a)
        val report = importSnapshot(a, json)

        assertEquals("应该新增 1 条待办", 1, report.taskMerge.added)
        val arrived = taskByUid(a, "shared-1")
        assertNotNull("B 端没收到这条待办", arrived)
        assertEquals("A 上新建的待办", arrived?.title)
        assertNotNull("关联的课程也要一起过来", arrived?.courseName)
    }

    @Test
    fun `a change made on one device travels back to the other`() = runBlocking {
        // 这条是本次改造的核心验收项：勾选状态与星标是**只有本地才知道**的东西，
        // 浙大同步永远带不过去。整机快照就是为了让它们能过去。
        //
        // 模拟两台设备：A 建了一条并导出 → B 导入（此时 B 有这条、未完成）→
        // B 勾掉并打星标、导出 → A 导入回来，A 那边也要跟着变。
        // 目标端必须**已经有**那条才谈得上「覆盖」，所以中间不 reset A。
        val a = container()
        val courseId = seedCourse(a)
        seedTask(a, courseId, "要勾掉的待办", uid = "shared-2", updatedAt = 1000L)
        val initial = a.exportService.exportJson()

        // ---- 设备 B：拿到 A 的快照 ----
        reset(a)
        importSnapshot(a, initial)
        assertEquals("刚导入时应该是未完成", false, taskByUid(a, "shared-2")?.isCompleted)

        // B 上勾掉并打星标。updatedAt 显式给一个确定值：`toggleTaskComplete`
        // 用的是 `System.currentTimeMillis()`，与种子数据的 1000 撞在同一毫秒时，
        // 「相等时文件赢」会让这次改动被判成「保留本机」——
        // 那是在测毫秒而不是在测同步。
        val changedAt = 5_000L
        a.database.taskDao().updateTask(
            taskByUid(a, "shared-2")!!.copy(
                isCompleted = true,
                completedAt = changedAt,
                priority = TaskStar.encode(true),
                updatedAt = changedAt
            )
        )
        val fromB = a.exportService.exportJson()

        // ---- 设备 A：拿到 B 改过的版本（此时 A 手上已有同一条的旧版本）----
        reset(a)
        importSnapshot(a, initial)
        assertEquals("A 这边一开始也是未完成", false, taskByUid(a, "shared-2")?.isCompleted)

        val report = importSnapshot(a, fromB)

        val back = taskByUid(a, "shared-2")
        assertEquals("勾选状态必须传回来", true, back?.isCompleted)
        assertEquals("星标必须传回来", TaskStar.encode(true), back?.priority)
        assertEquals("同一条待办不该变成两条", 1, a.taskRepository.countTasks())
        assertEquals("应当算作一次覆盖", 1, report.taskMerge.overwritten)
        assertEquals("不应当算作新增", 0, report.taskMerge.added)
    }

    @Test
    fun `the newer version wins when both devices changed the same task`() = runBlocking {
        val a = container()
        val courseId = seedCourse(a)
        seedTask(a, courseId, "被两台设备改过的待办", uid = "shared-3", updatedAt = 1000L)
        val initial = a.exportService.exportJson()

        reset(a)
        importSnapshot(a, initial)

        // 这台设备改了它（updatedAt 更大），另一台还是旧版本
        a.database.taskDao().updateTask(
            taskByUid(a, "shared-3")!!.copy(
                title = "本机改过的标题",
                updatedAt = 9999L
            )
        )

        val report = importSnapshot(a, initial) // 再导入一次那份旧的

        assertEquals("本机较新的改动必须保住", "本机改过的标题", taskByUid(a, "shared-3")?.title)
        assertEquals("应当算作保留本机", 1, report.taskMerge.keptLocal)
        assertEquals("不应当有任何覆盖", 0, report.taskMerge.overwritten)
    }

    @Test
    fun `previewing never touches the tasks table`() = runBlocking {
        val a = container()
        val courseId = seedCourse(a)
        seedTask(a, courseId, "本机已有的待办", uid = "mine")
        val before = a.taskRepository.getAllTasksOnce()

        val json = snapshotJson(tasks = listOf(taskBundle("remote-1", "远端来的待办")))
        val preview = a.importService.preview(json, ImportStrategy.SNAPSHOT)

        assertTrue(preview is PreviewResult.Ready)
        val after = a.taskRepository.getAllTasksOnce()
        assertEquals("预览阶段绝不能写库", before.size, after.size)
        assertEquals(before.map { it.id }.sorted(), after.map { it.id }.sorted())
        // 预览该把「将新增几条」说清楚
        assertEquals(1, (preview as PreviewResult.Ready).preview.taskMerge.added)
    }

    @Test
    fun `preview promises exactly what the commit does`() = runBlocking {
        // 预告的数字必须就是提交后的结果。这条是 v2 就在守的不变量
        // （预览与提交共用 planTaskLinks），v3 的逐条合并同样不能破。
        val a = container()
        val courseId = seedCourse(a)
        seedTask(a, courseId, "已存在的", uid = "shared-4", updatedAt = 100L)

        val json = snapshotJson(
            tasks = listOf(
                taskBundle("shared-4", "从文件覆盖过来的", updatedAt = 5000L),
                taskBundle("brand-new", "全新的")
            )
        )
        val preview = a.importService.preview(json, ImportStrategy.SNAPSHOT) as PreviewResult.Ready
        val promised = preview.preview.taskMerge
        val report = a.importService.commit(preview)

        assertEquals(promised.added, report.taskMerge.added)
        assertEquals(promised.overwritten, report.taskMerge.overwritten)
        assertEquals(promised.keptLocal, report.taskMerge.keptLocal)
        assertEquals("从文件覆盖过来的", taskByUid(a, "shared-4")?.title)
        assertNotNull(taskByUid(a, "brand-new"))
    }

    // ================================================================ 自定义分类与生成规则

    @Test
    fun `custom task types land before tasks so the type chip still resolves`() = runBlocking {
        // 顺序反了的后果是静默降级：待办的 taskType 解析不出来，
        // 界面上就变成「未分类」，而导入过程不报任何错。
        val a = container()
        val json = snapshotJson(
            tasks = listOf(taskBundle("c-1", "自定义类型的待办", taskType = "CUSTOM:ct-1")),
            taskTypes = listOf(
                com.example.data.codec.TaskTypeBundle(
                    id = "ct-1", label = "竞赛", colorHex = "#A6D6E4", inkHex = "#3F5F69", sortOrder = 1
                )
            )
        )

        importSnapshot(a, json)

        val custom = a.taskTypeRepository.getAllOnce()
        assertEquals(1, custom.size)
        assertEquals("竞赛", custom.single().label)

        val resolved = TaskTypes.resolve("CUSTOM:ct-1", custom)
        assertNotNull("类型必须解析得出来，不能退化成未分类", resolved)
        assertEquals("竞赛", resolved?.label)
    }

    @Test
    fun `course level generation rules come across keyed by the course natural key`() = runBlocking {
        val a = container()
        val courseId = seedCourse(a)
        val key = COURSE_KEY

        val json = snapshotJson(
            taskRules = listOf(
                com.example.data.codec.TaskRuleBundle(
                    courseKey = key,
                    taskType = "PRE_STUDY",
                    cadence = "PER_CLASS",
                    dueOffsetDays = 0,
                    dueBeforeMinutes = 45
                )
            )
        )
        importSnapshot(a, json)

        // 按**自然键反查**课程行，不用 `seedCourse` 返回的 id。
        // `clearAllCourses()` 不重置自增序列，那个 id 是历史遗留的计数，
        // 拿它当「本机那门课」在跨 reset 的用例里必然对不上 ——
        // 而断言其实要验的是「规则挂到了正确的那门课上」。
        val rules = a.settingsRepository.getTaskRulesOnce()
        val course = a.courseRepository.getAllCoursesOnce().firstOrNull { it.naturalKeyOf() == key }
        assertNotNull("本机没有这门课，键=$key", course)
        val spec = rules[course!!.id]?.get(TaskType.PRE_STUDY)
        assertNotNull(
            "规则没落到本机这门课上（键=$key，本机课程=${a.courseRepository.getAllCoursesOnce().map { it.dedupeKey }}）",
            spec
        )
        assertEquals("PER_CLASS", spec?.cadence)
        assertEquals(45, spec?.dueBeforeMinutes)
    }

    /** 与导入侧同一口径的自然键（见 `ScheduleImportService.naturalKey`）。 */
    private fun CourseEntity.naturalKeyOf(): String =
        dedupeKey.ifBlank { CourseKeys.compute(code, name, teacher) }

    @Test
    fun `rules and tasks still attach when the local course has no dedupe key`() = runBlocking {
        // `MIGRATION_3_4` 补不出键的老数据会留空。此时若拿空串去匹配，
        // 结果是「这门课找不到」，待办静默变成「未关联课程」而界面上看不出异常。
        val a = container()
        seedCourse(a)
        val key = COURSE_KEY
        // 把本机这门课的键抹掉，模拟 `MIGRATION_3_4` 补不出来的老数据。
        // 改完按自然键反查拿回这一行 —— `seedCourse` 返回的自增 id 在跨 reset
        // 的场景里不可靠，而这条断言要验的是「键为空也能认出这门课」。
        val blank = a.courseRepository.getAllCoursesOnce().first()
        a.courseRepository.saveCourseWithSlots(
            blank.copy(dedupeKey = ""),
            listOf(
                SlotWithWeeks(
                    slot = CourseSlotEntity(
                        courseId = blank.id,
                        dayOfWeek = 1,
                        startPeriod = 1,
                        endPeriod = 2
                    ),
                    weeks = emptySet()
                )
            )
        )
        val courseId = a.courseRepository.getAllCoursesOnce().single().id

        val json = snapshotJson(
            tasks = listOf(taskBundle("no-key-1", "老数据课程下的待办", courseKey = COURSE_KEY))
        )
        val report = importSnapshot(a, json)

        val arrived = taskByUid(a, "no-key-1")
        assertNotNull(arrived)
        assertEquals("键为空的老课程也得认出来", courseId, arrived?.courseId)
        assertEquals(0, report.taskMerge.losingCourse)
    }

    @Test
    fun `a course only document leaves the users custom types and rules untouched`() = runBlocking {
        // 浙大同步走的就是这条路：产出的文档三块全空。
        // 不守住这一条，一次浙大同步就会把用户配好的自定义分类与生成规则清空，
        // 而界面上不会有任何提示。
        val a = container()
        val courseId = seedCourse(a)
        a.database.customTaskTypeDao().insert(
            CustomTaskTypeEntity(id = "keep-me", label = "我的分类", colorHex = "#B3E0BE", inkHex = "#43634B")
        )
        a.settingsRepository.saveTaskRule(
            courseId = courseId,
            taskType = com.example.data.model.TaskType.POST_CLASS,
            spec = com.example.data.generation.CourseRuleSpec("PER_WEEK", 6, 60)
        )

        val json = snapshotJson()
        importSnapshot(a, json)

        assertEquals("浙大同步不该清空用户的自定义分类", 1, a.taskTypeRepository.getAllOnce().size)
        assertEquals("我的分类", a.taskTypeRepository.getAllOnce().single().label)
        assertNotNull(
            "浙大同步不该清空用户的生成规则",
            a.settingsRepository.getTaskRulesOnce()[courseId]
        )
    }

    // ================================================================ 边界与防御

    @Test
    fun `a task whose course is unknown still arrives as unlinked`() = runBlocking {
        val a = container()
        val json = snapshotJson(
            tasks = listOf(taskBundle("orphan", "课程没了的待办", courseKey = "不存在的课|某某")),
            courseName = "完全不同的课"
        )

        val report = importSnapshot(a, json)

        val orphan = taskByUid(a, "orphan")
        assertNotNull("待办本身不能丢", orphan)
        assertEquals(TaskEntity.UNLINKED_COURSE_ID, orphan?.courseId)
        assertEquals(1, report.taskMerge.losingCourse)
    }

    @Test
    fun `a task with no sync identity is skipped and reported instead of duplicated`() = runBlocking {
        val a = container()
        val json = snapshotJson(
            tasks = listOf(com.example.data.codec.TaskBundle(title = "没有身份的待办"))
        )

        val preview = a.importService.preview(json, ImportStrategy.SNAPSHOT) as PreviewResult.Ready
        val report = a.importService.commit(preview)

        assertEquals("没有身份的条目不该被插进来", 0, report.taskMerge.added)
        assertEquals("库里不该多出待办", 0, a.taskRepository.countTasks())
        assertTrue(
            "必须报出来：${preview.preview.issues}",
            preview.preview.issues.any { it.detail.contains("缺少编号") }
        )
    }

    @Test
    fun `importing the same snapshot twice is idempotent`() = runBlocking {
        val a = container()
        val courseId = seedCourse(a)
        seedTask(a, courseId, "重复导入的待办", uid = "idem-1")
        val json = a.exportService.exportJson()

        reset(a)
        importSnapshot(a, json)
        val first = a.taskRepository.countTasks()

        importSnapshot(a, json)

        assertEquals("导两次不该变成两条", first, a.taskRepository.countTasks())
        assertEquals(1, a.taskRepository.countTasks())
    }

    @Test
    fun `a snapshot import does not duplicate a course that is already on file`() = runBlocking {
        // 这条钉的是一个真出现过的问题：SNAPSHOT 的课表部分若没被算进 MERGE，
        // 文件里那门课每次导入都会被当成新课程再插一份 —— 同一个自然键下冒出
        // 两门课，周课表上显示两行一模一样的东西，且没有任何报错。
        val a = container()
        seedCourse(a)
        assertEquals(1, a.courseRepository.getAllCoursesOnce().size)

        importSnapshot(a, snapshotJson())

        val after = a.courseRepository.getAllCoursesOnce()
        assertEquals("同一门课不该变成两门", 1, after.size)
        assertEquals(COURSE_KEY, after.single().naturalKeyOf())

        // 导两次也一样：整条链路必须幂等
        importSnapshot(a, snapshotJson())
        assertEquals(1, a.courseRepository.getAllCoursesOnce().size)
    }

    @Test
    fun `a snapshot import does not wipe the courses`() = runBlocking {
        // REPLACE 才清空课程。整机同步刻意用合并：整表替换一份本地快照
        // 会毁掉刚同步来的课表，而课表是有服务端这个权威源的。
        val a = container()
        seedCourse(a, name = "本机自己加的课", teacher = "李教授")
        val before = a.courseRepository.getAllCoursesOnce().size

        importSnapshot(a, snapshotJson())

        assertTrue("整机同步不许清空课程", a.courseRepository.getAllCoursesOnce().size >= before)
    }

    @Test
    fun `a plain schedule import never touches the tasks`() = runBlocking {
        // 浙大同步走 MERGE / REPLACE 两条课表路径。它们**都不该碰待办** ——
        // 服务端只管课表，它不知道本机的待办勾没勾。
        val a = container()
        val courseId = seedCourse(a)
        seedTask(a, courseId, "本机的待办", uid = "untouched")
        val before = a.taskRepository.getAllTasksOnce()

        val preview = a.importService.preview(
            snapshotJson(tasks = listOf(taskBundle("remote-x", "文件里的待办"))),
            ImportStrategy.REPLACE
        ) as PreviewResult.Ready
        assertTrue("课表导入不该预告待办合并", preview.preview.taskMerge.added == 0)

        a.importService.commit(preview)

        val after = a.taskRepository.getAllTasksOnce()
        assertEquals("课表导入一条待办都不该动", before.size, after.size)
        assertNotNull(taskByUid(a, "untouched"))
        assertEquals(null, taskByUid(a, "remote-x"))
    }

    @Test
    fun `a full round trip through the real export and import keeps everything`() = runBlocking {
        val a = container()
        val courseId = seedCourse(a)
        seedTask(a, courseId, "往返之一", uid = "rt-1", starred = true)
        seedTask(a, courseId, "往返之二", uid = "rt-2", completed = true)
        a.database.customTaskTypeDao().insert(
            CustomTaskTypeEntity(id = "rt-type", label = "临时", colorHex = "#F3C9D6", inkHex = "#8A475B")
        )
        a.taskRepository.ensureSyncUids()

        val first = a.exportService.exportJson()
        reset(a)
        importSnapshot(a, first)
        val second = a.exportService.exportJson()

        val bundle = com.example.data.codec.JsonScheduleCodec().decode(second)
                as com.example.data.codec.DecodeResult.Success
        val uids = bundle.bundle.tasks.mapNotNull { it.syncUid }.sorted()
        assertEquals(listOf("rt-1", "rt-2"), uids)
        assertEquals(TaskStar.encode(true), bundle.bundle.tasks.first { it.syncUid == "rt-1" }?.priority)
        assertEquals(true, bundle.bundle.tasks.first { it.syncUid == "rt-2" }?.isCompleted)
        assertEquals(1, bundle.bundle.taskTypes.size)
        assertEquals("临时", bundle.bundle.taskTypes.single().label)
    }

    @Test
    fun `every seeded task gets a distinct sync identity`() = runBlocking {
        val a = container()
        val courseId = seedCourse(a)
        seedTask(a, courseId, "甲", uid = "u1")
        seedTask(a, courseId, "乙", uid = "u2")
        seedTask(a, courseId, "丙", uid = "u3")

        // 清成空 uid 模拟「迁移后还没补齐」，再跑一次补齐
        for (t in a.taskRepository.getAllTasksOnce()) {
            a.database.taskDao().updateTask(t.copy(syncUid = "", updatedAt = 0L))
        }
        assertEquals(3, a.taskRepository.ensureSyncUids())

        val uids = a.taskRepository.getAllTasksOnce().map { it.syncUid }
        assertEquals("每个待办都要有自己的身份", 3, uids.toSet().size)
        assertTrue("补齐后不该再有空身份", uids.none { it.isBlank() })
        // updatedAt 初值取 createdAt：填 0 会让它在任何一次比较里都判负
        assertTrue(
            "补齐后 updatedAt 应当等于各自的 createdAt",
            a.taskRepository.getAllTasksOnce().all { it.updatedAt == it.createdAt }
        )
    }

    @Test
    fun `ensureSyncUids does nothing when every row already has an identity`() = runBlocking {
        val a = container()
        val courseId = seedCourse(a)
        seedTask(a, courseId, "已有身份", uid = "already")

        assertEquals(0, a.taskRepository.ensureSyncUids())
        assertEquals("already", taskByUid(a, "already")?.syncUid)
    }

    @Test
    fun `export skips rows whose identity has not been filled in yet`() = runBlocking {
        // 窗口期保护：迁移完、还没跑补齐的那一下，用户就导出了。
        val a = container()
        val courseId = seedCourse(a)
        seedTask(a, courseId, "没身份", uid = "has-uid")
        for (t in a.taskRepository.getAllTasksOnce()) {
            a.database.taskDao().updateTask(t.copy(syncUid = ""))
        }

        val json = a.exportService.exportJson()
        val decoded = com.example.data.codec.JsonScheduleCodec().decode(json)
                as com.example.data.codec.DecodeResult.Success

        // 写一个空身份进文件看着像「更完整」，实际是给对端埋一条永远配不上的条目
        assertTrue("空身份的行不该进文件", decoded.bundle.tasks.isEmpty())
        assertNotNull(decoded.bundle.timetable)
    }

    @Test
    fun `the default period table is untouched by a snapshot import`() = runBlocking {
        val a = container()
        // 文档没带 timetable 块时作息表必须原样保留（既有 v2 的判据，v3 不能破）
        a.calendarRepository.ensureDefaultPeriods()
        val before = a.calendarRepository.periodsOnce().map { it.period to it.startMinute }
        assertTrue("默认作息表本身不该是空的", before.isNotEmpty())

        importSnapshot(a, snapshotJson())

        assertEquals(
            before,
            a.calendarRepository.periodsOnce().map { it.period to it.startMinute }
        )
    }

    @Test
    fun `two devices keep different identities for their own copies of the same task`() = runBlocking {
        // 这是数据本身的状态，不是 bug：两台设备各自从自己的历史库导出，
        // 补出来的 uid 是各自随机生成的，于是导入后是两条。
        // 这条测试把该行为钉住，避免以后有人以为它是缺陷而去「修」。
        val a = container()
        val courseId = seedCourse(a)
        seedTask(a, courseId, "各自的副本", uid = "device-a")
        for (t in a.taskRepository.getAllTasksOnce()) {
            a.database.taskDao().updateTask(t.copy(syncUid = ""))
        }
        a.taskRepository.ensureSyncUids()
        val fromA = a.exportService.exportJson()

        reset(a)
        seedTask(a, courseId, "各自的副本", uid = "device-b")
        for (t in a.taskRepository.getAllTasksOnce()) {
            a.database.taskDao().updateTask(t.copy(syncUid = ""))
        }
        a.taskRepository.ensureSyncUids()
        val fromB = a.exportService.exportJson()

        val uidOf = { json: String ->
            (com.example.data.codec.JsonScheduleCodec().decode(json)
                as com.example.data.codec.DecodeResult.Success).bundle.tasks.single().syncUid
        }
        assertNotEquals("两台设备补出的身份本来就不同", uidOf(fromA), uidOf(fromB))
    }
}
