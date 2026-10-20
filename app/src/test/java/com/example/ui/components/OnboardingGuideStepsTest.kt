package com.example.ui.components

import com.example.ui.viewmodel.GuideTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 引导的四步与四个目标必须一一对应。
 *
 * ViewModel 用 [GuideTarget] 的枚举个数判断「是不是最后一步」，
 * 界面用 [GuideSteps] 决定显示什么。两边一旦对不上，
 * 表现是「最后一步按了没反应」或者「某一步跳过去了一页」——
 * 都不是编译期能发现的错，所以在这里钉住。
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
            assertTrue("说明不能为空", step.description.isNotBlank())
            assertTrue("按钮上要有话", step.actionLabel.isNotBlank())
        }
    }
}
