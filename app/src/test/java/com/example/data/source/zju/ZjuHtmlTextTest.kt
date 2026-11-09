package com.example.data.source.zju

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 作业说明从 HTML 转成纯文本的规则。
 *
 * 上游存的是富文本（`<p>`、`<b>`、`<br>`…），直接显示会是一堆尖括号。
 * 这几条是**要显示给人看**的部分：段落要分行、标签要消失、实体要还原。
 */
class ZjuHtmlTextTest {

    @Test
    fun `空字符串转出来还是空字符串`() {
        assertEquals("", htmlToPlainText(""))
        assertEquals("", htmlToPlainText("   "))
    }

    @Test
    fun `普通文本原样留下`() {
        assertEquals("请提交实验报告的 PDF", htmlToPlainText("请提交实验报告的 PDF"))
    }

    @Test
    fun `段落标签换成换行`() {
        val html = "<p>第一段</p><p>第二段</p>"
        assertEquals("第一段\n第二段", htmlToPlainText(html))
    }

    @Test
    fun `br标签换成换行`() {
        assertEquals("第一行\n第二行", htmlToPlainText("第一行<br/>第二行"))
        assertEquals("第一行\n第二行", htmlToPlainText("第一行<br>第二行"))
    }

    @Test
    fun `行内标签被去掉但文字留下`() {
        assertEquals("重点内容", htmlToPlainText("<b>重点内容</b>"))
        assertEquals("红色", htmlToPlainText("<span style=\"color:red\">红色</span>"))
    }

    @Test
    fun `列表项各占一行`() {
        val html = "<ul><li>第一项</li><li>第二项</li></ul>"
        assertEquals("第一项\n第二项", htmlToPlainText(html))
    }

    @Test
    fun `script与style整块丢掉`() {
        val html = "<p>正文</p><script>alert('x')</script><style>p{color:red}</style>"
        assertEquals("正文", htmlToPlainText(html))
    }

    @Test
    fun `常见实体被还原`() {
        assertEquals("A & B", htmlToPlainText("A &amp; B"))
        assertEquals("<标签>", htmlToPlainText("&lt;标签&gt;"))
        assertEquals("他说\"好\"", htmlToPlainText("他说&quot;好&quot;"))
        assertEquals("第一章…结束", htmlToPlainText("第一章&hellip;结束"))
    }

    @Test
    fun `数字实体被还原`() {
        assertEquals("A", htmlToPlainText("&#65;"))
        assertEquals("中", htmlToPlainText("&#x4E2D;"))
    }

    @Test
    fun `转义过的尖括号不会被二次还原`() {
        // 上游写好的 &amp;lt; 表示「要显示 &lt; 这几个字」。
        // 二次还原会把它变成真的尖括号，那不是说明里的话。
        assertEquals("&lt;", htmlToPlainText("&amp;lt;"))
    }

    @Test
    fun `不换行空格当普通空格`() {
        assertEquals("a b", htmlToPlainText("a&nbsp;b"))
    }

    @Test
    fun `源码里的多余空白被压掉`() {
        assertEquals("你好 世界", htmlToPlainText("  你好    世界  "))
    }

    @Test
    fun `连续空行压成一行空行`() {
        val html = "<p>一</p><p></p><p></p><p></p><p>二</p>"
        assertEquals("一\n\n二", htmlToPlainText(html))
    }

    @Test
    fun `说明文字前后不会留下空行`() {
        assertTrue(htmlToPlainText("<p>  正文  </p>").none { it == '\n' })
    }
}
