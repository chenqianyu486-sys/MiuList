package com.example.ui.components

import com.example.data.model.COURSE_CATEGORIES
import com.example.data.model.WallpaperPresets
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

    @Test
    fun `第 2 步列出的类别要和 App 里真正的类别一致`() {
        // 引导是在教用户用这个 App：它列的类别必须就是类别 chip 上那五个。
        // 少一个，用户照着找找不到那一栏；多一个，用户点了发现没有这一类。
        val step = GuideSteps.first { it.target == GuideTarget.COURSE_CATEGORY }
        COURSE_CATEGORIES.forEach { category ->
            assertTrue(
                "第 2 步没提到真实存在的类别「$category」",
                step.description.contains(category)
            )
        }
    }

    @Test
    fun `第 4 步说的预设张数要和真的预设数一致`() {
        val step = GuideSteps.first { it.target == GuideTarget.WALLPAPER }
        val count = WallpaperPresets.ALL.size
        assertTrue(
            "第 4 步说「${chineseNumber(count)}张」，实际有 $count 张",
            step.description.contains("${chineseNumber(count)}张")
        )
    }

    @Test
    fun `第 1 步不许承诺把学在浙大的作业并进待办清单`() {
        // 学在浙大的待办只以**只读清单**出现在总览页，从来不写进待办清单；
        // 而且登录课表那一步根本不拉它（它走的是 App 打开时按已存凭据刷新那条路）。
        // 引导说了 App 做不到的事，用户照着找只会扑空。
        val step = GuideSteps.first { it.target == GuideTarget.ZJU_SYNC }
        assertFalse(
            "引导不能承诺学在浙大的作业会并进待办清单",
            step.description.contains("待办清单")
        )
        assertFalse(
            "课表同步那一步并不拉学在浙大的待办，别在引导里提它",
            step.description.contains("学在浙大")
        )
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

    /** 1~10 的中文数字。引导的文案里写的是「八张」这种中文数字。 */
    private fun chineseNumber(value: Int): String = when (value) {
        1 -> "一"; 2 -> "二"; 3 -> "三"; 4 -> "四"; 5 -> "五"
        6 -> "六"; 7 -> "七"; 8 -> "八"; 9 -> "九"; 10 -> "十"
        else -> value.toString()
    }
}
