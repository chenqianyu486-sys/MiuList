package com.example.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.di.AppContainer
import com.example.data.local.CourseEntity
import com.example.data.model.CategoryRule
import com.example.data.model.SlotWithWeeks
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 「课程联动规则」批量设置到课程开关。Robolectric + 真实 Room。
 *
 * 这一层要守住的是**联动真的会生效**：
 * 改造前那三个开关只写进 `category_rules` 却没人读，`GenerationRuleResolver.isEnabled`
 * 只看 `CourseEntity.auto*`——所以整张联动规则表是死的，勾了等于没勾。
 *
 * 语义是**批量设置**而非「默认值」：应用一次就把该类别下所有课程的三个开关整体覆盖，
 * 包括用户此前在单门课上单独调过的那些。配套的安全阀在弹层（先暂存、应用 / 取消），
 * 不在这一层，所以这一层测的就是「覆盖」本身。
 */
@RunWith(RobolectricTestRunner::class)
// 用 SDK 34 而不是工程的 targetSdk 36：Robolectric 的 36 号沙箱要求 JDK 21，
// 本机构建环境固定是 JDK 17。这里验的是联动写穿与事务行为，与 API 36 无关。
@Config(sdk = [34])
class CategoryLinkageRobolectricTest {

    private fun container(): AppContainer =
        AppContainer(ApplicationProvider.getApplicationContext<Context>())

    /**
     * 收尾清空。
     *
     * Robolectric 里 `AppContainer` 拿的是 `AppDatabase` 单例，**测试类之间共用同一个库文件**。
     * `ExampleRobolectricTest.repository initialization and multi-slot course schedule` 自己
     * 播一门「高等数学」，并按「这门课有几个时段」判断要不要重播。
     * 本类用自增 id 建的课若留在库里，那条用例会看到一门**别的**课还挂着时段，
     * 于是把旧课改写而不是新播一份 —— 断言虽然还成立，验的却不是刚播出来的那门课。
     *
     * 不清理的结果就是：加一个 Robolectric 测试类能把一个不相干的冒烟测试搞挂，
     * 而且挂的原因从报错信息里完全看不出来。
     */
    // 返回类型必须显式是 Unit：@After 方法被 JUnit 要求返回 void，
    // 而 replaceAll 现在返回同步门数，写成 `= runBlocking { ... }` 会推导出 Int 而报 initializationError。
    @After
    fun tearDown() {
        runBlocking {
            val c = container()
            c.courseRepository.clearAllCourses()
            c.settingsRepository.replaceAll(emptyList())
        }
    }

    /** 每个测试从空库开始，免得 Room 单例在方法之间串状态。 */
    private suspend fun reset(c: AppContainer) {
        c.courseRepository.clearAllCourses()
        c.settingsRepository.replaceAll(emptyList())
    }

    private suspend fun addCourse(
        c: AppContainer,
        name: String,
        category: String,
        pre: Boolean = true,
        post: Boolean = true,
        lab: Boolean = false
    ): Long = c.courseRepository.saveCourseWithSlots(
        CourseEntity(
            name = name,
            category = category,
            teacher = "王教授",
            classroom = "教二-301",
            colorHex = "#93C5FD",
            autoPreStudy = pre,
            autoPostHomework = post,
            autoLabReport = lab
        ),
        listOf(SlotWithWeeks(slot = slotForCourse(), weeks = emptySet()))
    )

    private fun slotForCourse() = com.example.data.local.CourseSlotEntity(
        id = 0, courseId = 0, dayOfWeek = 1, startPeriod = 1, endPeriod = 2
    )

    private suspend fun switchesOf(c: AppContainer, name: String): Triple<Boolean, Boolean, Boolean> {
        val course = c.courseRepository.getAllCoursesOnce().first { it.name == name }
        return Triple(course.autoPreStudy, course.autoPostHomework, course.autoLabReport)
    }

    // -------------------------------------------------------------- 写穿本身

    @Test
    fun `关掉类别课后作业会同步关掉该类别下所有课程`() = runBlocking {
        val c = container()
        reset(c)
        addCourse(c, "数据结构与算法", "专业必修")
        addCourse(c, "计算机系统与网络", "专业必修")
        addCourse(c, "大学英语", "通识公共")

        val synced = c.settingsRepository.saveRule(CategoryRule("专业必修", autoPostHomework = false))

        assertEquals("两门专业必修都该被改", 2, synced)
        assertFalse(switchesOf(c, "数据结构与算法").second)
        assertFalse(switchesOf(c, "计算机系统与网络").second)
        assertTrue("别的类别不该被牵连", switchesOf(c, "大学英语").second)
    }

    /**
     * 批量设置的语义核心：连用户在单门课上单独设过的开关也一起覆盖。
     *
     * 早期版本只推「相对上一版变了的那几格」，靠差量保住单独设置；改成批量设置后
     * 这条断言反过来——单独设的值会被类别规则盖掉。想改回来就重新应用一次类别规则，
     * 或在课程里单独再设。
     */
    @Test
    fun `会覆盖课程上单独设过的开关`() = runBlocking {
        val c = container()
        reset(c)
        addCourse(c, "操作系统实验", "实验实践", pre = false, post = true, lab = true)

        c.settingsRepository.saveRule(CategoryRule("实验实践", autoPostHomework = false))

        val (pre, post, lab) = switchesOf(c, "操作系统实验")
        assertTrue("用户单独关掉的预习被批量覆盖回类别值", pre)
        assertFalse(post)
        assertFalse("用户单独打开的实验被批量覆盖成类别值", lab)
    }

    @Test
    fun `规则表本身也照常保存`() = runBlocking {
        val c = container()
        reset(c)
        c.settingsRepository.saveRule(CategoryRule("专业必修", autoPostHomework = false, defaultPostDays = 4))
        val saved = c.settingsRepository.getCategoryRulesOnce().first { it.category == "专业必修" }
        assertFalse(saved.autoPostHomework)
        assertEquals(4, saved.defaultPostDays)
    }

    @Test
    fun `课程本来就一致时不重写`() = runBlocking {
        val c = container()
        reset(c)
        addCourse(c, "高等数学", "公共基础", pre = true, post = false, lab = false)

        // 三个开关与规则完全一致，没有可写的行。
        assertEquals(0, c.settingsRepository.saveRule(CategoryRule("公共基础", autoPostHomework = false)))
        // 重复应用同一份规则同样不写。
        assertEquals(0, c.settingsRepository.saveRule(CategoryRule("公共基础", autoPostHomework = false)))
    }

    @Test
    fun `类别下没有课程时保存不报错也不改任何人`() = runBlocking {
        val c = container()
        reset(c)
        addCourse(c, "高等数学", "公共基础")

        assertEquals(0, c.settingsRepository.saveRule(CategoryRule("实验实践", autoLabReport = false)))
        assertTrue(switchesOf(c, "高等数学").second)
    }

    /** 一次应用改多个类别，返回的是受影响课程门数的合计。 */
    @Test
    fun `批量保存会逐个类别应用并合计门数`() = runBlocking {
        val c = container()
        reset(c)
        addCourse(c, "数据结构与算法", "专业必修")
        addCourse(c, "计算机系统与网络", "专业必修")
        addCourse(c, "大学英语", "通识公共")

        val synced = c.settingsRepository.saveRules(
            listOf(
                CategoryRule("专业必修", autoPostHomework = false),
                CategoryRule("通识公共", autoPostHomework = false)
            )
        )

        assertEquals(3, synced)
        assertFalse(switchesOf(c, "数据结构与算法").second)
        assertFalse(switchesOf(c, "计算机系统与网络").second)
        assertFalse(switchesOf(c, "大学英语").second)
        assertEquals(2, c.settingsRepository.getCategoryRulesOnce().size)
    }

    // -------------------------------------------------------------- 播种要能自愈

    /**
     * 规则表空着的时候（全新安装，或者曾被一次不带规则的导入清空过），
     * `ensureDefaultRules` 补回内置默认值，并且**立刻写穿到课程上**。
     *
     * 只补规则行而不写穿会留下一份「规则说有、课上说没有」的配置，而且用户自救不了：
     * 弹层的「应用」在没有净改动时是禁用的。
     */
    @Test
    fun `重新播种时内置默认值会写穿到已有课程`() = runBlocking {
        val c = container()
        reset(c)
        addCourse(c, "高等数学", "公共基础", pre = true, post = true, lab = false)
        addCourse(c, "大学英语", "通识公共", pre = true, post = true, lab = false)
        assertEquals("前置条件：规则表是空的", 0, c.settingsRepository.getCategoryRulesOnce().size)

        c.settingsRepository.ensureDefaultRules(AppContainer.DEFAULT_CATEGORY_RULES)

        assertEquals(
            "公共基础默认只作业",
            Triple(false, true, false),
            switchesOf(c, "高等数学")
        )
        assertEquals("通识课三个都不开", Triple(false, false, false), switchesOf(c, "大学英语"))
    }

    @Test
    fun `规则表非空时播种不碰任何东西`() = runBlocking {
        val c = container()
        reset(c)
        c.settingsRepository.saveRule(CategoryRule("公共基础", autoPreStudy = true, autoPostHomework = true))
        // 新建的课先按规则拿到 (预习开, 作业开)，再模拟用户在这门课上单独关掉作业。
        val id = addCourse(c, "高等数学", "公共基础")
        val current = c.courseRepository.getCourseById(id)!!
        c.courseRepository.saveCourseWithSlots(
            current.copy(autoPostHomework = false),
            listOf(SlotWithWeeks(slot = slotForCourse(), weeks = emptySet()))
        )

        c.settingsRepository.ensureDefaultRules(AppContainer.DEFAULT_CATEGORY_RULES)

        val rule = c.settingsRepository.getCategoryRulesOnce().single()
        assertTrue("用户配过的规则不该被内置默认覆盖", rule.autoPreStudy)
        assertEquals(
            "用户在单门课上关掉的作业也不该被顺手推平",
            Triple(true, false, false),
            switchesOf(c, "高等数学")
        )
    }

    // -------------------------------------------------------------- 新课程要听话

    @Test
    fun `规则设定后新建物课程继承类别开关`() = runBlocking {
        val c = container()
        reset(c)
        c.settingsRepository.saveRule(CategoryRule("实验实践", autoPostHomework = false, autoLabReport = true))

        addCourse(c, "数字电路实验", "实验实践")

        val (pre, post, lab) = switchesOf(c, "数字电路实验")
        assertTrue(pre)
        assertFalse("新导入的课也要跟着联动走", post)
        assertTrue(lab)
    }

    @Test
    fun `类别没有规则行时新课程保持自身默认值`() = runBlocking {
        val c = container()
        reset(c)
        addCourse(c, "人工智能与深度学习", "专业选修")
        assertTrue(switchesOf(c, "人工智能与深度学习").second)
    }

    /** 编辑分支不动开关：那些值是用户自己在课程编辑里给的。 */
    @Test
    fun `编辑已有课程不会把开关重置成类别值`() = runBlocking {
        val c = container()
        reset(c)
        c.settingsRepository.saveRule(CategoryRule("专业必修", autoPostHomework = false))
        val id = addCourse(c, "数据结构与算法", "专业必修")
        assertFalse(switchesOf(c, "数据结构与算法").second)

        val existing = c.courseRepository.getCourseById(id)!!
        c.courseRepository.saveCourseWithSlots(
            existing.copy(autoPostHomework = true, name = "数据结构与算法（下）"),
            listOf(SlotWithWeeks(slot = slotForCourse(), weeks = emptySet()))
        )

        assertTrue("用户在课程上开的作业开关要保留", switchesOf(c, "数据结构与算法（下）").second)
    }

    /**
     * 回归：**一次**应用就把三个开关全部对齐，不留「需要再应用一次才生效」的漂移。
     *
     * 早期版本只推「相对上一版变了的那几格」，于是「上一版就已经和课程对不上」的字段
     * 永远推不到——实测过 9 门课的 `autoPreStudy` 一直是 1，而类别规则里早就写了 0，
     * 且只能靠把开关切过去再切回来一次才能触发。改成全量刷之后这类漂移攒不起来：
     * 类别规则说什么，课就是什么，第一次应用就到位。
     */
    @Test
    fun `一次应用就对齐全部三个开关不留漂移`() = runBlocking {
        val c = container()
        reset(c)
        // 先建课（此时无类别规则，课程保持自身默认值：预习开）。
        addCourse(c, "数据结构与算法", "专业必修", pre = true, post = true, lab = false)

        // 用户只打算改「课后作业」；类别规则里的「课前预习=关」是既有值。
        val synced = c.settingsRepository.saveRule(
            CategoryRule("专业必修", autoPreStudy = false, autoPostHomework = false)
        )

        // 改动最大的两格都要被写到，一格都不留在旧值上。
        assertEquals(1, synced)
        val (pre, post, lab) = switchesOf(c, "数据结构与算法")
        assertFalse("从没在弹层里碰过的那一格也要一次对齐", pre)
        assertFalse(post)
        assertFalse(lab)
    }

    // -------------------------------------------------------------- 导入路径

    @Test
    fun `replaceAll 会把导入的类别规则写穿到已有课程`() = runBlocking {
        val c = container()
        reset(c)
        c.settingsRepository.replaceAll(listOf(CategoryRule("专业必修")))
        addCourse(c, "数据结构与算法", "专业必修")

        c.settingsRepository.replaceAll(listOf(CategoryRule("专业必修", autoPostHomework = false)))

        assertFalse(switchesOf(c, "数据结构与算法").second)
    }

    /** REPLACE 导入是先清课程、再写规则、最后插课——此时写穿没课可改，靠新建时的初始化兜住。 */
    @Test
    fun `replaceAll 在课还没插进来时也不丢规则`() = runBlocking {
        val c = container()
        reset(c)
        c.courseRepository.clearAllCourses()
        c.settingsRepository.replaceAll(listOf(CategoryRule("专业选修", autoPostHomework = false)))

        addCourse(c, "人工智能与深度学习", "专业选修")

        assertFalse(switchesOf(c, "人工智能与深度学习").second)
    }
}
