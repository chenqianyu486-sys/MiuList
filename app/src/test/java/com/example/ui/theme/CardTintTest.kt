package com.example.ui.theme

import androidx.compose.ui.graphics.Color
import com.example.data.model.COURSE_PALETTE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * 卡片染色的验收：[cardTint] 反解出来的颜色，叠上去之后八门课必须**等重**。
 *
 * 这条测试钉的是 [cardTint] 存在的全部理由：不是「颜色好不好看」，而是
 * 「八门课的卡面落在同一个感知明度、同一档彩度」。以前那套「粉彩 → vivid → 固定
 * alpha」做不到这件事，而它退化成的样子是肉眼说不清、只有取样才看得出的
 * 「这一屏卡片深浅不一」。
 *
 * 断言用 OKLab 的 L/C，而不是 sRGB 的通道值或相对亮度：
 * HSL 的 L 和 WCAG 亮度都测不出感知明度（黄在同 L 下明显比蓝亮），而
 * 「等重」正是感知层面的要求。
 */
class CardTintTest {

    /**
     * 走暖色相补偿的两支：橙 `#FDBA74`（色相 66°）与黄 `#FDE047`（色相 98°）。
     *
     * 绿课（色相 154°）**不在其中**——它离黄更远但没有土黄问题，早先一版补偿写成
     * 「115° 以上一律满额」时它被误伤成 `#79E5A1`，详见 Color.kt 的 [warmLift]。
     */
    private val WARM_PALETTE = setOf("#FDBA74", "#FDE047")

    private fun oklabOf(c: Color): Triple<Float, Float, Float> {
        fun lin(v: Float): Float =
            if (v <= 0.04045f) v / 12.92f else Math.pow(((v + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
        fun cbrt(v: Float): Float = Math.cbrt(v.toDouble()).toFloat()
        val r = lin(c.red); val g = lin(c.green); val b = lin(c.blue)
        val l = cbrt(0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b)
        val m = cbrt(0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b)
        val s = cbrt(0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b)
        return Triple(
            0.2104542553f * l + 0.7936177850f * m - 0.0040720468f * s,
            1.9779984951f * l - 2.4285922050f * m + 0.4505937099f * s,
            0.0259040371f * l + 0.7827717662f * m - 0.8086757660f * s
        )
    }

    private fun chroma(c: Color): Float {
        val (_, a, b) = oklabOf(c)
        return hypot(a, b)
    }

    /** 测试里把「叠上去之后」的卡面算出来：用白衬底近似真衬底。 */
    private fun compositedOnBacking(tint: Color, alpha: Float): Color {
        val back = Color(0xFFF6F3EF)
        return Color(
            red = tint.red * alpha + back.red * (1f - alpha),
            green = tint.green * alpha + back.green * (1f - alpha),
            blue = tint.blue * alpha + back.blue * (1f - alpha)
        )
    }

    private fun parse(hex: String): Color {
        val v = hex.removePrefix("#").toLong(16)
        return Color(
            red = ((v shr 16) and 0xFF) / 255f,
            green = ((v shr 8) and 0xFF) / 255f,
            blue = (v and 0xFF) / 255f
        )
    }

    /**
     * 卡面「等重」在**冷色相**上成立；暖色相被抬高一档（用户要求黄色加白、不要米卡其）。
     *
     * 抬高的幅度必须守住：卡面再高就压过「卡片是浅色的」这条，暖色相应 ≤ 0.94。
     */
    @Test
    fun `冷色相的卡面等重，暖色相被抬高但仍是浅色`() {
        val alpha = CourseTintAlpha
        val cold = COURSE_PALETTE.filterNot { it in WARM_PALETTE }.map { hex ->
            oklabOf(compositedOnBacking(cardTint(parse(hex), alpha), alpha)).first
        }
        assertEquals("冷色相应当是 6 支", 6, cold.size)
        val coldSpread = cold.max() - cold.min()
        assertTrue(
            "冷色相卡面的 OKLab 明度差应 < 0.02，实际 $coldSpread（各门：$cold）",
            coldSpread < 0.02f
        )
        WARM_PALETTE.forEach { hex ->
            val l = oklabOf(compositedOnBacking(cardTint(parse(hex), alpha), alpha)).first
            assertTrue("$hex 的卡面明度 $l 抬得太高，会洗成白卡（应 ≤ 0.94）", l <= 0.94f)
            assertTrue("$hex 的卡面应当比冷色相更浅（$l vs ${cold.min()}）", l > cold.max())
        }
    }

    @Test
    fun `八门课的卡面彩度不超过同一档上限`() {
        val alpha = CourseTintAlpha
        val cs = COURSE_PALETTE.map { hex ->
            chroma(compositedOnBacking(cardTint(parse(hex), alpha), alpha))
        }
        val spread = cs.max() - cs.min()
        // 彩度会因色域被二分降下来而略有差异（这正是期望行为：保色相、减彩度），
        // 但差得不该超过目标值的一半，否则等于「有的门课有颜色、有的没有」。
        assertTrue(
            "八门课卡面的彩度差应 < 0.045，实际 $spread（各门：$cs）",
            spread < 0.045f
        )
    }

    @Test
    fun `卡面保持接近近白，正文深色字才有对比度`() {
        val alpha = CourseTintAlpha
        COURSE_PALETTE.forEach { hex ->
            val face = compositedOnBacking(cardTint(parse(hex), alpha), alpha)
            val l = oklabOf(face).first
            assertTrue("$hex 的卡面明度 $l 应当是浅色（> 0.85）", l > 0.85f)
        }
    }

    @Test
    fun `色相被保留`() {
        val alpha = CourseTintAlpha
        COURSE_PALETTE.forEach { hex ->
            val source = parse(hex)
            val tint = cardTint(source, alpha)
            val (sa, sb) = oklabOf(source).let { it.second to it.third }
            val (ta, tb) = oklabOf(tint).let { it.second to it.third }
            val srcHue = Math.toDegrees(atan2(sb.toDouble(), sa.toDouble()).toFloat().toDouble())
            val tintHue = Math.toDegrees(atan2(tb.toDouble(), ta.toDouble()).toFloat().toDouble())
            var diff = abs(srcHue - tintHue)
            if (diff > 180f) diff = 360f - diff
            assertTrue("$hex 的色相偏了 ${diff}°（应该在 5° 内）", diff < 5f)
        }
    }

    @Test
    fun `中性灰原样叠，不被凭空染出颜色`() {
        // 待办卡「不关联课程」那一档的灰：看着是灰，实际 b > r。
        // 反解对它是危险的——会凭空造出一个饱和蓝，认起来像有关联课程。
        val neutral = Color(0xFFC9D0D8)
        assertEquals(neutral, cardTint(neutral, CourseTintAlpha))
    }

    @Test
    fun `解出来的颜色落在 sRGB 色域内`() {
        COURSE_PALETTE.forEach { hex ->
            val tint = cardTint(parse(hex), CourseTintAlpha)
            listOf(tint.red, tint.green, tint.blue).forEach { channel ->
                assertTrue("$hex 解出的通道 $channel 越界", channel in 0f..1f)
            }
        }
    }

    // ---- 课程卡左侧那根竖条 ----

    /**
     * 竖条「等重」在**冷色相**上成立。
     *
     * 暖色相是故意的例外：橙/黄在低明度下会掉进橄榄褐死区，统一压到 L 0.70 时
     * 黄色竖条解出来是 `#B69E16`（土黄），所以它们被抬到 0.80。
     */
    @Test
    fun `冷色相的竖条落在同一档明度`() {
        val ls = COURSE_PALETTE.filterNot { it in WARM_PALETTE }.map {
            oklabOf(accentBarColor(parse(it))).first
        }
        assertEquals("冷色相应当是 6 支", 6, ls.size)
        val spread = ls.max() - ls.min()
        assertTrue("冷色相竖条的 OKLab 明度差应 < 0.02，实际 $spread（各门：$ls）", spread < 0.02f)
    }

    /**
     * 暖色相的竖条必须够亮——这是「不要土黄/灰褐」那条要求的直接断言。
     *
     * 实测：#FDE047 在统一 L 0.70 下是 `#B69E16`（橄榄褐），抬到 0.80 后是 `#D6BE45`。
     */
    @Test
    fun `暖色相的竖条不会落进土黄区间`() {
        WARM_PALETTE.forEach { hex ->
            val l = oklabOf(accentBarColor(parse(hex))).first
            assertTrue("$hex 的竖条明度 $l 太暗，会变成土黄（应 ≥ 0.77）", l >= 0.77f)
        }
    }

    /**
     * 竖条与卡面一眼能分开，又不至于压过整张卡。
     *
     * 暖色相的下限比冷色相松（1.4 vs 1.8）：竖条抬亮之后与卡面的明差必然变小，
     * 这笔是「不画成土黄」换来的，靠**把卡面按用户要求加白**（暖色相卡面彩度减半）
     * 补回来，而不是把竖条重新压暗——压暗就又回到土黄。
     * 实测：黄 1.48 / 橙 1.62，冷色相 1.85~2.09。
     */
    @Test
    fun `竖条与卡面一眼能分开，又不至于压过整张卡`() {
        val alpha = CourseTintAlpha
        COURSE_PALETTE.forEach { hex ->
            val face = compositedOnBacking(cardTint(parse(hex), alpha), alpha)
            val bar = accentBarColor(parse(hex))
            val c = contrastRatio(bar, face)
            val floor = if (hex in WARM_PALETTE) 1.4f else 1.8f
            assertTrue("$hex 的竖条/卡面对比度 $c 太低，竖条会消失（应 ≥ $floor）", c >= floor)
            assertTrue("$hex 的竖条/卡面对比度 $c 太高，竖条会抢走整张卡（应 ≤ 2.6）", c <= 2.6f)
        }
    }

    /** 加白之后黄色卡面仍是「有颜色的浅色」，不能洗成和别的课一样白。 */
    @Test
    fun `加白之后黄色卡面仍有颜色`() {
        val c = chroma(compositedOnBacking(cardTint(parse("#FDE047"), CourseTintAlpha), CourseTintAlpha))
        assertTrue("黄色卡面彩度 $c 洗过头了，会认不出是哪门课（应 ≥ 0.035）", c >= 0.035f)
    }

    @Test
    fun `竖条与卡面同色相`() {
        COURSE_PALETTE.forEach { hex ->
            val source = parse(hex)
            val (_, sa, sb) = oklabOf(source)
            val (_, ba, bb) = oklabOf(accentBarColor(source))
            val srcHue = Math.toDegrees(atan2(sb.toDouble(), sa.toDouble()))
            val barHue = Math.toDegrees(atan2(bb.toDouble(), ba.toDouble()))
            var diff = abs(srcHue - barHue)
            if (diff > 180.0) diff = 360.0 - diff
            assertTrue("$hex 的竖条色相偏了 $diff°（应在 5° 内）", diff < 5.0)
        }
    }

    /** WCAG 相对亮度 + 对比度，这里要的是「竖条和卡面分不分得开」。 */
    private fun contrastRatio(a: Color, b: Color): Float {
        fun lum(c: Color): Float {
            fun ch(v: Float): Float =
                if (v <= 0.03928f) v / 12.92f else Math.pow(((v + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
            return 0.2126f * ch(c.red) + 0.7152f * ch(c.green) + 0.0722f * ch(c.blue)
        }
        val la = lum(a); val lb = lum(b)
        return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
    }
}
