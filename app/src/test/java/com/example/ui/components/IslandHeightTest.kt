package com.example.ui.components

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 浮岛的高度规则：**永远装得下它自己的内容**。
 *
 * ## 这条为什么值得钉
 *
 * 浮岛里的标签曾经被**裁掉半截**：标签写的是 `Text(text, fontSize = ...)`，
 * 那条重载会把 LocalTextStyle（bodyLarge，lineHeight 24.sp）合并进来，
 * 11.5.sp 的标签于是撑着一个 24.sp 的行盒 —— 行盒比字形高出一倍多，
 * 入口内容比浮岛内高还高，标签被推到下沿、**下半截被裁掉**。
 *
 * 而算岛高的时候量的是**同一个字**（只是没带那个继承来的行高），
 * 两边对不上，于是谁也没发现岛其实装不下。
 *
 * ## 这里钉的是哪一条
 *
 * 算高与渲染现在共用同一套间距函数（[islandInnerPadV] / [islandEntryPadV] /
 * [islandIconLabelGap]），所以「岛高 ≥ 内容高」是**结构上**成立的。
 * 这条测试把那个不等式写出来：以后谁改了间距函数而忘了改另一边，
 * 这里会先红。
 *
 * 真正的渲染结果（标签有没有被裁、看起来挤不挤）由**真机截图**验：
 * `tools/capture-matrix.sh` 里有「用户真机」那一档（332dp + 1.16 倍字号）。
 * Robolectric 量不出真实的中文行高（它用的是字体桩），所以几何断言不放在那边。
 */
class IslandHeightTest {

    /** 标签那一行的实测高度：16.5.sp 的行盒 × 字号缩放。 */
    private fun labelHeight(fontScale: Float) = 16.5.dp * fontScale

    @Test
    fun `岛高永远装得下入口的内容`() {
        listOf(0.9f, 1.0f, 1.16f, 1.5f, 2.0f).forEach { fs ->
            val height = islandHeightFor(
                labelHeight = labelHeight(fs),
                fontScale = fs,
                short = false
            )
            // 入口内容 = 入口内边距 + 图标 + 图标与标签的间距 + 标签行高
            val content = islandEntryPadV(fs) * 2 +
                22.dp * fs +
                islandIconLabelGap(fs) +
                labelHeight(fs)
            val inner = height - islandInnerPadV(fs) * 2
            assertTrue(
                "字号 ${fs} 时浮岛装不下自己的内容：内高 $inner，内容 $content",
                inner >= content
            )
        }
    }

    @Test
    fun `基准屏上的岛高还是 64dp`() {
        // 411dp 基准屏 + 1.0 倍字号：64dp 是设计值，一分不能变。
        assertEquals(
            64.dp,
            islandHeightFor(
                labelHeight = labelHeight(1f),
                fontScale = 1f,
                short = false
            )
        )
    }

    @Test
    fun `矮屏只留图标，岛是 48dp`() {
        assertEquals(
            48.dp,
            islandHeightFor(
                labelHeight = 0.dp,
                fontScale = 1f,
                short = true
            )
        )
    }

    @Test
    fun `字越大岛越高`() {
        val small = islandHeightFor(labelHeight(1f), 1f, short = false)
        val big = islandHeightFor(labelHeight(1.5f), 1.5f, short = false)
        assertTrue("字放大后浮岛没有跟着长高：$small → $big", big > small)
    }
}
