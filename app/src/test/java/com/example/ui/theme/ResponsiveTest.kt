package com.example.ui.theme

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.screens.gridFontScaleOf
import com.example.ui.screens.heroLayoutFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 屏幕自适应的验收。
 *
 * ## 这一版钉的是「字号与版式同步缩放」
 *
 * 自适应的**基础**是字体自适应：屏窄了，字与**版式**用同一个系数等比缩小，
 * 411dp 的设计被缩进小屏，整页的比例、留白关系、信息密度与设计稿一致。
 *
 * 只缩字不缩框是做不到这件事的：框的尺寸写死在 dp 上，它不缩，
 * 文字就相对变大 → 放不下 → 文字被迫**再缩一次** → 比例彻底失衡。
 *
 * ## 三条判据
 *
 * 1. **缩字优先，版式兜底**：日期按左列宽逐档降；只有缩到最小档还放不下，
 *    才允许把天气块挪到第二段（改版式）。
 * 2. **同步**：版式缩放系数与字号缩放里的内容缩放是**同一个数**。
 * 3. **让位是全有或全无**：用户放大系统字号时，dp 与 sp 一起让位。
 */
class ResponsiveTest {

    /**
     * 22sp 那句日期在 fontScale = 1 时的实测宽度（dp）。
     * 与 [com.example.ui.theme.heroDateFontSize] 里的同名常量是同一个数：
     * 那边是私有的，这里复制一份是为了让测试不依赖私有实现细节。
     */
    private val dateWidthAt22Sp = 232.8f

    /** 按某个档位渲染出来的实际宽度（dp，排版 dp）。 */
    private fun renderedWidth(sp: Float, fontScale: Float): Float =
        dateWidthAt22Sp * (sp / 22f) * fontScale

    /** 某个「物理宽 × 系统字号」下的版式缩放系数。 */
    private fun layoutScaleOf(physicalWidthDp: Int, systemFontScale: Float = 1.0f): Float =
        effectiveLayoutScaleFor(physicalWidthDp, systemFontScale)

    /** 某个「物理宽 × 系统字号」下的排版宽（dp）。 */
    private fun layoutWidthOf(physicalWidthDp: Int, systemFontScale: Float = 1.0f): Int =
        layoutWidthDpFor(physicalWidthDp, layoutScaleOf(physicalWidthDp, systemFontScale))

    /** 某个组合下主卡的排法（与界面里用的是同一个函数）。 */
    private fun layoutOf(physicalWidthDp: Int, systemFontScale: Float = 1.0f) =
        heroLayoutFor(
            physicalWidthDp,
            layoutWidthOf(physicalWidthDp, systemFontScale),
            effectiveFontScaleFor(physicalWidthDp, systemFontScale)
        )

    /** 某个组合下选出来的日期档位（sp）。 */
    private fun dateSizeOf(physicalWidthDp: Int, systemFontScale: Float = 1.0f): Float {
        val layout = layoutOf(physicalWidthDp, systemFontScale)
        val fontScale = effectiveFontScaleFor(physicalWidthDp, systemFontScale)
        val maxSp = heroDateMaxSpFor(widthClassFor(physicalWidthDp))
        return heroDateFontSize(layout.dateColumnWidth, fontScale, maxSp).value
    }

    // ---------------------------------------------------------------------
    // 同步性
    // ---------------------------------------------------------------------

    @Test
    fun `版式缩放与字号缩放里的内容缩放是同一个数`() {
        // 「同步」必须是结构性的：两处读同一个函数，而不是各写一遍。
        for (width in intArrayOf(320, 332, 360, 375, 390, 411, 600, 800, 1280)) {
            assertEquals(
                "${width}dp 的版式缩放应等于内容缩放",
                contentScaleFor(width),
                layoutScaleOf(width),
                0.0001f
            )
        }
    }

    @Test
    fun `窄屏收缩不再进字号缩放（否则字会被缩两遍）`() {
        // density 已经是「dp → px」和「sp → px」共同的换算系数，
        // 内容缩放乘在它上面就同时缩了字和框。
        // 如果字号那边再乘一次，字就缩了 k² 倍、框只缩 k 倍 —— 那不是等比。
        for (width in intArrayOf(320, 332, 360, 390)) {
            assertEquals(
                "${width}dp 的字号缩放应只剩系统字号",
                1f,
                effectiveFontScaleFor(width, 1.0f),
                0.0001f
            )
        }
        // 用户放大系统字号时，字号就是用户的字号，一点不多一点不少。
        assertEquals(1.5f, effectiveFontScaleFor(332, 1.5f), 0.0001f)
    }

    @Test
    fun `用户放大字号时版式与字号一起让位`() {
        // 332dp 的屏上内容缩放是 0.82。
        // 系统字号 1.5 时，版式缩放必须回到 1 —— 只让位给字号、版式照缩，
        // 就成了「框缩了字没缩」，和「字缩了框没缩」是同一个错的两个方向。
        assertEquals(1f, layoutScaleOf(332, 1.5f), 0.0001f)
        assertEquals(1f, layoutScaleOf(320, 2f), 0.0001f)
        // 用户没放大时，版式照缩。
        assertEquals(0.82f, layoutScaleOf(332, 1.0f), 0.0001f)
    }

    @Test
    fun `排版宽在基准区间恒等于设计宽`() {
        // 物理宽落在 [337, 411] 时，排版宽恒等于 411 ——
        // 设计分毫不差地缩进屏幕，比例 100% 保持。
        for (width in intArrayOf(337, 360, 375, 390, 411)) {
            assertEquals(
                "${width}dp 的排版宽应为 411",
                411.0,
                layoutWidthOf(width).toDouble(),
                0.5
            )
        }
        // 触底之后排版宽会超过 411（设计比屏幕宽），交给兜底机制。
        assertEquals(390.0, layoutWidthOf(320).toDouble(), 0.5)
        assertEquals(405.0, layoutWidthOf(332).toDouble(), 0.5)
    }

    // ---------------------------------------------------------------------
    // 比例不变式
    // ---------------------------------------------------------------------

    @Test
    fun `基准屏保持 22sp 定稿`() {
        // 411dp、字号 1.0：排版宽 411 → 日期列 243dp，22sp 的实测宽 232.8dp，放得下。
        assertEquals(22f, dateSizeOf(411), 0.01f)
    }

    @Test
    fun `基准屏日期的行高与改版前逐值相同`() {
        // 22.sp 的行高原先是写死的 28.sp。行高改成按字号比例算之后，
        // 22 × 1.27 = 27.94 —— 不取整就不是 28 了，基准屏会差 0.06sp。
        // 这条钉住「取整」这件事：基准屏必须回到 **28.sp**。
        assertEquals(28f, heroDateStyle(22.sp).lineHeight.value, 0.001f)
        // 宽屏 26.sp 时行高跟着走（26 × 1.27 = 33.02 → 33），
        // 否则中文字形（ascent+descent ≈ 1.16em = 30.2sp）会顶出 28.sp 的行盒被裁。
        assertEquals(33f, heroDateStyle(26.sp).lineHeight.value, 0.001f)
    }

    @Test
    fun `日期在左列里的占比与设计稿一致`() {
        // 设计：232.8 / 243 = 95.8%。
        // 基准区间（物理宽 ∈ [337, 411]）排版宽恒为 411 → 比例必须一分不差。
        val designRatio = dateWidthAt22Sp / 243f
        for (width in intArrayOf(337, 360, 375, 390, 411)) {
            val layout = layoutOf(width)
            val fontScale = effectiveFontScaleFor(width, 1.0f)
            val size = dateSizeOf(width)
            val ratio = renderedWidth(size, fontScale) / layout.dateColumnWidth.value
            assertEquals(
                "${width}dp 的日期占比应与设计一致（实际 $ratio，设计 $designRatio）",
                designRatio,
                ratio,
                0.005f
            )
        }
    }

    @Test
    fun `窄屏几乎不再降档`() {
        // 版式跟着字一起缩之后，日期列没被挤窄，22.sp 放得下。
        // 只有物理宽掉到 337dp 以下（缩放触底、版式比屏幕宽）才降一档。
        for (width in intArrayOf(332, 360, 375, 390, 411)) {
            assertEquals("${width}dp 的日期应保持 22sp", 22f, dateSizeOf(width), 0.01f)
        }
        // 320dp：排版宽 390 → 日期列 222dp → 22.sp 的 232.8dp 放不下 → 降一档。
        assertEquals("320dp 的日期应降到 20sp", 20f, dateSizeOf(320), 0.01f)
    }

    @Test
    fun `窄屏不改版式`() {
        // 常态是并排（日期降档即可），整页版式一个字不改。
        for (width in intArrayOf(320, 332, 360, 375, 390, 411)) {
            assertFalse("${width}dp 不应改版式", layoutOf(width).stacked)
        }
    }

    @Test
    fun `各档日期都真的放得下`() {
        for (width in intArrayOf(320, 332, 360, 375, 390, 411, 430, 480, 600, 800, 1280)) {
            val layout = layoutOf(width)
            val fontScale = effectiveFontScaleFor(width, 1.0f)
            val size = dateSizeOf(width)
            assertTrue(
                "${width}dp / ${size}sp 必须放得下（列宽 ${layout.dateColumnWidth.value}dp）",
                renderedWidth(size, fontScale) <= layout.dateColumnWidth.value
            )
        }
    }

    // ---------------------------------------------------------------------
    // 缩字救不了时才改版式
    // ---------------------------------------------------------------------

    @Test
    fun `缩字救不了时才改版式`() {
        // 411dp + 系统字号 2.0：版式照缩吗？不 —— 用户放大了字号，
        // 内容缩放整体让位，版式是 411dp 的原样，日期列 243dp。
        // 而字号是用户的 2.0 倍，日期缩到最小档也要 296dp —— 缩字救不了，改版式。
        assertTrue("411dp + 2.0 字号应改版式", layoutOf(411, 2.0f).stacked)
        // 窄屏 + 大字号同理。
        assertTrue("320dp + 1.5 字号应改版式", layoutOf(320, 1.5f).stacked)
        assertTrue("332dp + 1.2 字号应改版式", layoutOf(332, 1.2f).stacked)
        // 反过来：字号正常时永远不改版式。
        assertFalse("411dp + 1.0 不应改版式", layoutOf(411, 1.0f).stacked)
        assertFalse("411dp + 1.5 不应改版式", layoutOf(411, 1.5f).stacked)
    }

    @Test
    fun `改版式之后日期拿整行宽度并放得下`() {
        // 上下两段时日期列 = 整行，不是并排时的「整行 − 间隔 − 天气块」。
        val layout = layoutOf(411, 2.0f)
        assertTrue("411dp + 2.0 应改版式", layout.stacked)
        assertEquals(363f, layout.dateColumnWidth.value, 0.01f)
        val fontScale = effectiveFontScaleFor(411, 2.0f)
        val size = dateSizeOf(411, 2.0f)
        assertTrue(
            "改版式之后 ${size}sp 必须放得下（列宽 ${layout.dateColumnWidth.value}dp）",
            renderedWidth(size, fontScale) <= layout.dateColumnWidth.value
        )
    }

    @Test
    fun `用户放大字号时仍然放得下`() {
        val layout = layoutOf(332, 1.2f)
        val fontScale = effectiveFontScaleFor(332, 1.2f)
        val size = dateSizeOf(332, 1.2f)
        assertTrue(
            "332dp + 1.2 字号下必须放得下，实际 ${size}sp / 列宽 ${layout.dateColumnWidth.value}dp",
            renderedWidth(size, fontScale) <= layout.dateColumnWidth.value
        )
    }

    @Test
    fun `系统字号放大时降档而不是截断`() {
        // 411dp、系统字号 1.5：22sp 会渲染成 349dp，远超 243dp 的日期列。
        val layout = layoutOf(411, 1.5f)
        val fontScale = effectiveFontScaleFor(411, 1.5f)
        val size = heroDateFontSize(layout.dateColumnWidth, fontScale)
        assertTrue("字号放大后应降档，实际 ${size.value}", size.value < 22f)
        assertTrue(
            "降档后必须放得下",
            renderedWidth(size.value, fontScale) <= layout.dateColumnWidth.value
        )
    }

    // ---------------------------------------------------------------------
    // 宽屏（本轮不同步，维持现状）
    // ---------------------------------------------------------------------

    @Test
    fun `宽屏走两栏而不是并排`() {
        // 800dp 窗口 → 内容列 656dp → 卡内容宽 608dp ≥ 600dp → 两栏。
        assertTrue("800dp 应走两栏", layoutOf(800).wide)
        assertFalse("411dp 不走两栏", layoutOf(411).wide)
        // 600dp 窗口：内容列 492dp → 卡内容宽 444dp < 600dp → 仍是并排。
        assertFalse("600dp 不走两栏", layoutOf(600).wide)
    }

    @Test
    fun `宽屏字号往上走而窄屏不动`() {
        assertEquals(1f, effectiveFontScaleFor(411, 1f), 0.001f)
        assertEquals(1f, effectiveFontScaleFor(599, 1f), 0.001f)
        assertEquals(1.10f, effectiveFontScaleFor(600, 1f), 0.001f)
        assertEquals(1.10f, effectiveFontScaleFor(800, 1f), 0.001f)
        assertEquals(1.18f, effectiveFontScaleFor(840, 1f), 0.001f)
        assertEquals(1.18f, effectiveFontScaleFor(1280, 1f), 0.001f)
        // 宽屏的放大只进字号，版式不缩（本轮决定：宽屏不同步）。
        assertEquals(1f, layoutScaleOf(800), 0.001f)
        assertEquals(1f, layoutScaleOf(1280), 0.001f)
    }

    @Test
    fun `宽屏时天气块跟着字阶加宽否则指数会被截`() {
        // 竖排版 108.dp 是按字号 1.0 量出来的（最坏那句要 105.9.dp）。
        // 宽屏字阶 1.1 / 1.18 之后它要 116.5 / 125.dp —— 108 装不下，
        // 指数会被 Ellipsis 截掉。所以块宽必须等比跟着字阶走。
        assertEquals(108f, layoutOf(411).weatherWidth.value, 0.01f)
        // 600dp 是 MEDIUM 档但还没到两栏（卡内容宽 444dp < 600dp），
        // 走的是「并排 + 放大字阶」这一档，块宽按字阶等比放大。
        assertEquals(108f * 1.10f, layoutOf(600).weatherWidth.value, 0.01f)
        // 800dp 起走两栏，天气独占一栏，给的是那一栏的固定宽度。
        assertEquals(160f, layoutOf(800).weatherWidth.value, 0.01f)
        assertEquals(160f, layoutOf(840).weatherWidth.value, 0.01f)
    }

    // ---------------------------------------------------------------------
    // 既有基线（内容列上限、缩放下限等）
    // ---------------------------------------------------------------------

    @Test
    fun `档位修正只往下走且到最小档为止`() {
        // 实测宽度差 1–2dp 时，降一档字号（保住完整日期），
        // 而不是换掉日期写法（丢掉星期几）。
        assertEquals(20f, nextSmallerHeroDateTier(22f)!!, 0.01f)
        assertEquals(18f, nextSmallerHeroDateTier(20f)!!, 0.01f)
        assertEquals(14f, nextSmallerHeroDateTier(16f)!!, 0.01f)
        // 已经是最小档就停 —— 再小就读不出来了。
        assertEquals(null, nextSmallerHeroDateTier(HeroDateMinTierSp))
    }

    @Test
    fun `矮屏按高度判定而不是方向`() {
        // 手机竖屏 914dp：高度够，不算矮屏（这一档占绝大多数设备，不能动）
        assertFalse(isShortWindow(914))
        assertFalse(isShortWindow(1200))
        // 手机横屏 411dp：矮屏
        assertTrue(isShortWindow(411))
        // 分屏小窗 360dp：矮屏
        assertTrue(isShortWindow(360))
        // 竖着的小窗 568dp 不算矮 —— 矮不矮看的是**能排下多少**，不是方向
        assertFalse(isShortWindow(568))
    }

    @Test
    fun `内容列上限不小于设计基准`() {
        // MaxContentWidth 必须 ≥ 设计基准 411dp，
        // 否则 411dp 的基准屏上内容列会被收窄，那一屏的定稿就变了。
        assertTrue(
            "内容列上限 ${MaxContentWidth.value}dp 不应窄于设计基准 ${DesignWidthDp}dp",
            MaxContentWidth.value >= DesignWidthDp
        )
    }

    @Test
    fun `整体缩放只在窄于基准时收缩`() {
        assertEquals(1f, contentScaleFor(411), 0.001f)
        assertEquals(1f, contentScaleFor(600), 0.001f)
        assertTrue(contentScaleFor(332) < 1f)
        // 下限：再窄也不低于 MinContentScale
        assertEquals(MinContentScale, contentScaleFor(200), 0.001f)
    }

    /**
     * 内容列宽随窗口长大，但手机上仍是 480dp 封顶。
     *
     * 这条钉的是宽屏上的一个具体退化：如果内容列在平板上还是 480dp，
     * 800dp 的屏两侧各空 160dp，界面读起来像贴了一张手机截图在中间。
     * 同时不能反过来把小屏收窄——411dp 与 332dp 上必须仍是 480dp 上限
     * （对 332dp 的屏，480dp 上限等于没有约束，宽度由父级决定）。
     */
    @Test
    fun `内容列在宽屏上放宽而在手机上不变`() {
        assertEquals(480f, contentMaxWidthFor(332).value, 0.01f)
        assertEquals(480f, contentMaxWidthFor(411).value, 0.01f)
        // 600dp 屏：0.82 × 600 = 492dp
        assertEquals(492f, contentMaxWidthFor(600).value, 0.5f)
        // 800dp 屏：0.82 × 800 = 656dp
        assertEquals(656f, contentMaxWidthFor(800).value, 0.5f)
        // 再宽就顶到 680dp 上限，不跟着无限拉宽
        assertEquals(680f, contentMaxWidthFor(1280).value, 0.01f)
    }

    /**
     * 无论窗口多宽，内容列都必须能装下设计基准那一屏。
     *
     * 否则会出现「411dp 上排版正常、415dp 上反而被收窄」的倒挂。
     */
    @Test
    fun `内容列在所有宽度上都不窄于设计基准`() {
        for (width in intArrayOf(200, 320, 360, 411, 600, 800, 1280, 2000)) {
            assertTrue(
                "${width}dp 窗口下内容列上限 ${contentMaxWidthFor(width).value}dp 小于基准",
                contentMaxWidthFor(width).value >= DesignWidthDp
            )
        }
    }

    @Test
    fun `各档宽度与缩放下渲染宽度都不超过日期列`() {
        // 遍历一圈真实会遇到的组合，逐个验「选出来的档位放得下」。
        // 唯一的例外是连最小档 14sp 都放不下（那才会出现省略号，
        // 界面上走的是「降信息密度」那条路：完整日期 → 月日 + 短星期），
        // 所以断言写成：只要放得下 14sp，就必须是放得下的那一档。
        val widths = intArrayOf(320, 332, 360, 375, 390, 411, 430, 480, 600, 800, 1280)
        val systemScales = floatArrayOf(1.0f, 1.15f, 1.2f, 1.3f, 1.5f, 2.0f)

        for (width in widths) {
            for (system in systemScales) {
                val layout = layoutOf(width, system)
                val fontScale = effectiveFontScaleFor(width, system)
                val maxSp = heroDateMaxSpFor(widthClassFor(width))
                val size = heroDateFontSize(layout.dateColumnWidth, fontScale, maxSp).value
                val rendered = renderedWidth(size, fontScale)
                val minTierFits = renderedWidth(14f, fontScale) <= layout.dateColumnWidth.value
                if (minTierFits) {
                    assertTrue(
                        "${width}dp / 系统字号 $system：选了 ${size}sp，渲染宽 $rendered 超出日期列 ${layout.dateColumnWidth.value}",
                        rendered <= layout.dateColumnWidth.value
                    )
                }
            }
        }
    }

    // ---------------------------------------------------------------------
    // 卡片高度锚点
    //
    // 卡高 = max(设计锚点, 实测内容高)：
    // 锚点守「比例不变」，实测守「不裁切」。
    // ---------------------------------------------------------------------

    /** 主卡的设计高度（dp）。与 DashboardScreen 里的 HeroCardHeight 是同一个数。 */
    private val heroDesignHeight = 140f

    /** 主卡的主角：日期。设计稿上它是 22.sp。 */
    private val heroDesignMainSp = 22f

    /** 某个组合下主卡的高度锚点（dp）。与界面里用的是同一个函数。 */
    private fun heroAnchorOf(physicalWidthDp: Int, systemFontScale: Float = 1.0f): Float {
        val fontScale = effectiveFontScaleFor(physicalWidthDp, systemFontScale)
        val size = dateSizeOf(physicalWidthDp, systemFontScale)
        return cardHeightAnchor(heroDesignHeight.dp, heroDesignMainSp, size, fontScale).value
    }

    @Test
    fun `基准屏与用户真机的卡片高度一分不变`() {
        // 411dp（设计基准）、332dp（用户真机）、360 / 375 / 390dp：
        // 日期都是 22.sp、字号缩放都是 1.0 → 锚点恒为 140dp。
        // 这条是硬约束：动了它，基准截图与用户真机就都变了。
        for (width in intArrayOf(332, 360, 375, 390, 411)) {
            assertEquals(
                "${width}dp 的主卡高度锚点应仍是 140dp",
                140f,
                heroAnchorOf(width),
                0.01f
            )
        }
    }

    @Test
    fun `锚点跟着主角的渲染放大倍数走`() {
        // 宽屏：日期升到 26.sp、字阶 1.10 → 140 × (26/22) × 1.10 = 182dp。
        // 修的是「字放大了、卡没长」——宽屏上主卡曾被压成一条扁带子。
        assertEquals(182f, heroAnchorOf(800), 0.5f)
        // 窄屏降档：320dp 上日期降到 20.sp → 140 × (20/22) = 127.3dp。
        // 主角在卡里占的份额不变（20/22 与 28/140 是同一件事）。
        assertEquals(127.3f, heroAnchorOf(320), 0.5f)
    }

    @Test
    fun `主角在卡高里的占比不超过设计稿`() {
        // 设计：日期 22.sp → 行盒 28.sp，在 140dp 的卡里占 20%。
        // 任何宽度 × 任何字号下，主角在卡里都不能比这更满 ——
        // 更满就说明「同一张设计在不同屏上长得不一样」。
        val designRatio = 28f / 140f
        val widths = intArrayOf(320, 332, 360, 375, 390, 411, 600, 800, 1280)
        val systemScales = floatArrayOf(1.0f, 1.2f, 1.5f, 2.0f)
        for (width in widths) {
            for (system in systemScales) {
                val fontScale = effectiveFontScaleFor(width, system)
                val size = dateSizeOf(width, system)
                val anchor = heroAnchorOf(width, system)
                val lineBox = heroDateStyle(size.sp).lineHeight.value * fontScale
                assertTrue(
                    "${width}dp / 系统字号 $system：行盒 $lineBox dp 在卡高 $anchor dp 里占 " +
                        "${lineBox / anchor}，超过设计的 $designRatio",
                    lineBox / anchor <= designRatio + 0.005f
                )
            }
        }
    }

    @Test
    fun `锚点系数不会退化成零`() {
        // 极端设备上系统字号可能给出 0 —— 锚点不能因此塌成 0dp（卡片就没了）。
        assertTrue(cardHeightScaleFor(0f) > 0f)
        assertTrue(cardHeightScaleFor(0.85f) > 0f)
        // 系统字号调小时锚点跟着缩：字小了，卡里就该更空。
        assertEquals(0.85f, cardHeightScaleFor(0.85f), 0.0001f)
    }

    @Test
    fun `课表网格的行高跟封顶后的字号走`() {
        // 网格内的字号封顶 1.3（TimetableMaxFontScale），行高必须跟**封顶后**的数走：
        // 系统字号 2.0 时若行高按 2.0 长，格子里的字却只长到 1.3 ——
        // 白白多出一截空行，表却撑得比一屏还高。
        assertEquals(1f, gridFontScaleOf(1f), 0.001f)
        assertEquals(1.3f, gridFontScaleOf(1.5f), 0.001f)
        assertEquals(1.3f, gridFontScaleOf(2f), 0.001f)
        // 字号没到封顶线时就是它自己。
        assertEquals(1.2f, gridFontScaleOf(1.2f), 0.001f)
    }
}
