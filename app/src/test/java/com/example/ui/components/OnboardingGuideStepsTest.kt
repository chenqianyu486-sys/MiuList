package com.example.ui.components

import com.example.ui.viewmodel.GuideTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 引导的四步与四个目标必须一一对应。
 *
 * ViewModel 用 [GuideTarget] 的枚举个数判断「是不是最后一步」，
 * 界面用 [GuideSteps] 决定显示什么。两边一旦对不上，
 * 表现是「最后一步按了没反应」或者「某一步跳过去了一页」——
 * 都不是编译期能发现的错，所以在这里钉住。
 *
 * ## 为什么这里只钉「标题 + 按钮」
 *
 * 引导的每一步现在**只有标题和按钮上那句话**，没有说明文字。
 * 于是原来那三条守说明文字的断言也跟着没了：
 *
 * - 「第 2 步列出的类别要和 App 里真正的类别一致」——说明里那份类别清单已经删了，
 *   真正的清单在课程分类弹层里，那儿是唯一出处，不该在引导里存第二份。
 * - 「第 4 步说的预设张数要和真的预设数一致」——同理，「八张」这个数字跟着说明一起删了。
 * - 「第 1 步不许承诺把学在浙大的作业并进待办清单」——没有说明文字就没有可断言的承诺；
 *   这条事实（学在浙大的待办只以只读清单出现在总览页，不写进待办清单，
 *   登录课表那一步也不拉它）记在 [GuideSteps] 的代码注释里。
 *
 * 真要再写说明文字，这三条断言得跟着一起加回来。
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
}
