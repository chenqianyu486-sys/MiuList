package com.example.ui.screens

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 主卡高度的**决策**规则：正反两面取更高的那个。
 *
 * ## 为什么这条要单独钉住
 *
 * 主卡是一张卡、两面（正面日期天气 / 背面学在浙大待交），翻转时高度不变。
 * 以前卡高只按**正面**算，背面那排待办小卡在窄屏 / 大字号下会被卡的下沿裁掉：
 * 用户真机（332dp + 1.16 倍系统字号）上背面要 141.5dp、正面只要 124.8dp，
 * 差的 17dp 全从小卡身上裁 —— 截图里那行「12月3日截止 · 数字电路」只剩半个字。
 *
 * ## 为什么不在 UI 测试里量
 *
 * 这个 bug 取决于**中文回退字体的行高**：真机上背面比正面高，
 * Robolectric 用的是字体桩、中文行高差一大截，那张卡在测试环境里刚好够高，
 * bug 就不显形了。所以这里把「取高」这条决策抽成纯函数单独钉住，
 * 具体的高度由 UI 层实测后喂进来。
 */
class HeroCardHeightTest {

    @Test
    fun `背面比正面高时，卡按背面的高度走`() {
        assertEquals(
            141.5.dp,
            heroCardHeight(
                frontNeeded = 124.8.dp,
                backNeeded = 141.5.dp,
                designAnchor = 118.1.dp,
                compact = false
            )
        )
    }

    @Test
    fun `正面比背面高时，卡按正面的高度走`() {
        assertEquals(
            140.dp,
            heroCardHeight(
                frontNeeded = 140.dp,
                backNeeded = 126.dp,
                designAnchor = 140.dp,
                compact = false
            )
        )
    }

    @Test
    fun `设计锚点是竖排时的下限，但盖不过内容`() {
        // 内容比锚点矮 → 取锚点（保得住设计留白）
        assertEquals(
            140.dp,
            heroCardHeight(
                frontNeeded = 107.6.dp,
                backNeeded = 126.dp,
                designAnchor = 140.dp,
                compact = false
            )
        )
        // 内容比锚点高 → 取内容（内容优先，锚点只是下限）
        assertEquals(
            141.5.dp,
            heroCardHeight(
                frontNeeded = 124.8.dp,
                backNeeded = 141.5.dp,
                designAnchor = 140.dp,
                compact = false
            )
        )
    }

    @Test
    fun `矮屏不套设计锚点，但背面照样参与取高`() {
        // 矮屏：那 140dp 是竖排时定的，套上会吃掉半屏 → 不套
        assertEquals(
            126.dp,
            heroCardHeight(
                frontNeeded = 107.6.dp,
                backNeeded = 126.dp,
                designAnchor = 140.dp,
                compact = true
            )
        )
        // 背面更高时，矮屏也得装下背面 —— 否则矮屏翻到背面一样是裁的
        assertEquals(
            141.5.dp,
            heroCardHeight(
                frontNeeded = 107.6.dp,
                backNeeded = 141.5.dp,
                designAnchor = 140.dp,
                compact = true
            )
        )
    }
}
