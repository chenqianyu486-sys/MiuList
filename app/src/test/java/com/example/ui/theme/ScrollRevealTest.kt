package com.example.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「从下方滚入」浮现效果的验收。
 *
 * 钉的是三件事：
 *
 *  1. **进度由位置决定**：卡片中心还在视口下缘之下时进度必须是 0（完全透明），
 *     高过浮现带之后必须是 1。中间单调递增 —— 这条保证「越滚上来越实」，
 *     也保证快速甩动时不会看到卡片半透明地卡在屏幕中间（进度只看位置，不看时间）。
 *  2. **缩放只缩一点点**：起点 [ScrollRevealStartScale] = 0.96，终点 1。
 *     再大就会从「浮出」变成「弹出」。
 *  3. **带子高度为 0 时整条效果不生效**（进度恒为 1），
 *     免得 band 传 0 变成「所有卡片永久透明」。
 */
class ScrollRevealTest {

    private val band = 132f

    @Test
    fun `卡片中心还在视口下缘之下时完全透明`() {
        assertEquals(0f, scrollRevealProgress(-40f, band), 1e-5f)
        assertEquals(0f, scrollRevealProgress(0f, band), 1e-5f)
    }

    @Test
    fun `卡片中心高过整条浮现带之后完全不透明`() {
        assertEquals(1f, scrollRevealProgress(band, band), 1e-5f)
        assertEquals(1f, scrollRevealProgress(band * 3f, band), 1e-5f)
    }

    @Test
    fun `进度随位置单调递增`() {
        var previous = -1f
        for (px in 0..band.toInt() step 4) {
            val p = scrollRevealProgress(px.toFloat(), band)
            assertTrue("px=$px 处进度没有比前一档高", p >= previous)
            previous = p
        }
    }

    @Test
    fun `浮现带高度为零时效果不生效`() {
        assertEquals(1f, scrollRevealProgress(-100f, 0f), 1e-5f)
        assertEquals(1f, scrollRevealProgress(0f, 0f), 1e-5f)
    }

    @Test
    fun `缩放从零点九六线性长回一`() {
        assertEquals(ScrollRevealStartScale, scrollRevealScale(0f), 1e-5f)
        assertEquals(1f, scrollRevealScale(1f), 1e-5f)
        assertEquals(
            ScrollRevealStartScale + (1f - ScrollRevealStartScale) * 0.5f,
            scrollRevealScale(0.5f),
            1e-5f
        )
    }

    @Test
    fun `缩放幅度是轻微的那一档`() {
        // 缩得太少看不出「浮出」，太多就读成「弹一下」。
        assertTrue(ScrollRevealStartScale >= 0.94f)
        assertTrue(ScrollRevealStartScale <= 0.98f)
    }

    @Test
    fun `进度越界时被夹住`() {
        assertEquals(0f, scrollRevealProgress(-9999f, band), 1e-5f)
        assertEquals(1f, scrollRevealProgress(9999f, band), 1e-5f)
    }
}
