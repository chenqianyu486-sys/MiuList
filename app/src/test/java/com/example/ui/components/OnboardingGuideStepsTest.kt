package com.example.ui.components

import com.example.ui.viewmodel.GuideTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 引导的七步与七个目标必须一一对应。
 *
 * ViewModel 用 [GuideTarget] 的枚举顺序决定「这一步在清单里的位置」，
 * 界面用 [GuideSteps] 决定显示什么。两边一旦对不上，
 * 表现是「最后一步按了没反应」或者「某一步跳过去了一页」——
 * 都不是编译期能发现的错，所以在这里钉住。
 *
 * ## 为什么这里还要钉「谁有说明」
 *
 * 引导的说明文字有一条规矩：**只有看不见的功能才写**。
 *
 * 原来的四步全都没有说明 —— 标题已经说完了那一步要做什么，
 * 再加一段解释只是把**那个功能自己页面上的话**抄一份，而抄本会先过期
 * （「八张预设」「五个类别」这类数字，别的页面改了就轮到引导说错）。
 *
 * 现在有**三步带说明**：
 * - 「生成本周待办」（入口是顶栏一颗没有文字标签的图标）；
 * - 「学在浙大翻面」（那一面藏在主卡背面）；
 * - 「一键生成待办」（这个功能**默认关着**，不告诉用户它存在，
 *   他就以为这个 App 只能手动加待办）。
 *
 * 这三个是 App 里**唯三看不见**的功能，功能自己那一页上也没有一句话
 * 在介绍它们 —— 引导是唯一能说清的地方。
 *
 * 所以这条断言的作用是：**别顺手给别的步骤也加一段说明**。
 * 真要加，先想清楚「这句话在功能自己那一页上是不是已经有了」。
 */
class OnboardingGuideStepsTest {

    @Test
    fun `每一步都对应一个目标，且不重不漏`() {
        assertEquals(
            "步数与目标数必须一致，否则 ViewModel 判不出最后一步",
            GuideTarget.entries.size,
            GuideSteps.size
        )
        assertEquals(
            "每个目标都要有一步",
            GuideTarget.entries.toSet(),
            GuideSteps.map { it.target }.toSet()
        )
        assertEquals(
            "同一个目标不能出现两次",
            GuideSteps.size,
            GuideSteps.map { it.target }.toSet().size
        )
    }

    @Test
    fun `每一步都把话说全了`() {
        GuideSteps.forEach { step ->
            assertTrue("标题不能为空", step.title.isNotBlank())
            assertTrue("按钮上要有话", step.actionLabel.isNotBlank())
        }
    }

    @Test
    fun `第 2 步的按钮不该把人送去新建重复的课`() {
        // 这一步是「给已有的课分类别」。按钮上写着「去添加课程」的话，
        // 用户点下去开的是一张空表单 —— 引导反而在教他怎么制造重复课。
        val step = GuideSteps.first { it.target == GuideTarget.COURSE_CATEGORY }
        assertFalse(
            "第 2 步的按钮不该是「去添加课程」",
            step.actionLabel.contains("添加课程")
        )
    }

    @Test
    fun `只有那三个看不见的功能带说明`() {
        val withDescription = GuideSteps.filter { it.description != null }.map { it.target }.toSet()

        assertEquals(
            "只有「生成本周待办」「总览页主卡可翻面」「一键生成待办」该带说明：" +
                "它们是 App 里唯三看不见的功能。" +
                "给别的步骤加说明，等于把那个功能自己页面上的话抄一份，抄本会先过期。",
            setOf(
                GuideTarget.WEEK_TASK_GENERATION,
                GuideTarget.HERO_FLIP_CARD,
                GuideTarget.QUICK_GENERATE_TOGGLE
            ),
            withDescription
        )
    }

    @Test
    fun `开关那一步的动作是开启，不是去看看`() {
        // 这一步按下去是**打开这个功能**，不是去某个地方看它。
        // 写成「去看看」的话，用户会以为那儿有个弹层等着他，
        // 按完发现什么也没发生。
        val step = GuideSteps.first { it.target == GuideTarget.QUICK_GENERATE_TOGGLE }
        assertEquals("开启", step.actionLabel)
    }

    @Test
    fun `开关那一步要说清楚不开也能用`() {
        // 「一键生成待办」默认**关着**。引导是唯一能告诉用户
        // 「不配规则也能用、手动加就行」的地方 —— 少了这句，
        // 用户会以为这个 App 少了点什么，或者以为关掉是出错了。
        val desc = GuideSteps.first { it.target == GuideTarget.QUICK_GENERATE_TOGGLE }
            .description.orEmpty()

        assertTrue("该说到可以手动添加待办", desc.contains("手动"))
    }

    @Test
    fun `带说明的那几步都要有内容`() {
        GuideSteps.forEach { step ->
            step.description?.let { desc ->
                assertTrue("${step.target} 的说明不能是空白", desc.isNotBlank())
            }
        }
    }

    @Test
    fun `生成那一步说的三样要跟引擎真会生成的对得上`() {
        // 引擎生成的是三类：课后作业、实验报告、预习。
        // 说明里少说一类，用户就会以为那一类不归它管；
        // 多说一类，就是引导在承诺引擎不做的事。
        val desc = GuideSteps.first { it.target == GuideTarget.WEEK_TASK_GENERATION }
            .description.orEmpty()

        assertTrue("说明里该说到课后作业", desc.contains("作业"))
        assertTrue("说明里该说到实验报告", desc.contains("实验报告"))
        assertTrue("说明里该说到预习", desc.contains("预习"))
    }

    @Test
    fun `生成那一步不许把学在浙大的作业说成会进待办清单`() {
        // 这条事实是真的：学在浙大的待办只以**只读清单**出现在总览页，
        // 从来不写进待办清单；登录课表那一步也不拉它。
        // 引导里一旦写成「学在浙大的作业也会并进待办清单」，就是在骗人。
        val desc = GuideSteps.first { it.target == GuideTarget.WEEK_TASK_GENERATION }
            .description.orEmpty()

        assertFalse(
            "生成这一步的说明不该提学在浙大：它的待办从来不写进待办清单",
            desc.contains("学在浙大")
        )
    }

    @Test
    fun `翻面那一步要说到背面是学在浙大`() {
        // 这一步的全部意义就是「那一面藏着」：说明里得说清翻过去是什么，
        // 否则用户点开引导还是不知道那张卡能翻。
        val desc = GuideSteps.first { it.target == GuideTarget.HERO_FLIP_CARD }
            .description.orEmpty()

        assertTrue("说明里该说到学在浙大", desc.contains("学在浙大"))
        assertTrue("说明里该说到翻面", desc.contains("翻"))
    }

    @Test
    fun `文案不许写长`() {
        // 「专业、清晰、简洁」最后要落到**字数**上：标题一行放不下太长的话，
        // 说明一长，窄屏 + 大字号就得多滚一屏 —— 引导一共六步，每步多一行都是负担。
        //
        // 上限按当前文案留了余量（标题最长 8 字、按钮最长 4 字、说明最长 43 字）：
        // 写长了先**删字**，别加句子。
        GuideSteps.forEach { step ->
            assertTrue(
                "「${step.title}」的标题太长（上限 $MAX_TITLE_CHARS 字）：写长了先删字",
                step.title.length <= MAX_TITLE_CHARS
            )
            assertTrue(
                "「${step.title}」的按钮文案太长（上限 $MAX_ACTION_CHARS 字）：按钮只写动作",
                step.actionLabel.length <= MAX_ACTION_CHARS
            )
            step.description?.let { desc ->
                assertTrue(
                    "「${step.title}」的说明太长（上限 $MAX_DESC_CHARS 字）：",
                    desc.length <= MAX_DESC_CHARS
                )
            }
        }
    }

    @Test
    fun `每一步的按钮文案都不一样`() {
        // 两步的按钮写着同一句话，用户在第二步按下去发现又是同一个动作，
        // 会以为引导卡住了 —— 按钮是**这一步要去哪**的唯一线索。
        val labels = GuideSteps.map { it.actionLabel }
        assertEquals(
            "按钮文案不能重复",
            labels.size,
            labels.toSet().size
        )
    }

    private companion object {
        /** 标题上限（含标点）。 */
        const val MAX_TITLE_CHARS = 12

        /** 动作按钮文案上限。 */
        const val MAX_ACTION_CHARS = 6

        /** 说明文字上限。 */
        const val MAX_DESC_CHARS = 60
    }
}
