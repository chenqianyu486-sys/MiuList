package com.example.ui.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

// =========================================================================
// 屏幕自适应：让同一套设计在 320dp ~ 1000dp 宽的屏、1.0 ~ 2.0 倍系统字号上都成立
//
// ## 宽度分三档，各走各的路
//
// 这一层现在按**窗口宽度**分档，而不是「一律缩放」：
//
// - **COMPACT（< 600dp，手机竖屏与多数分屏）**：走原来的路。窄于 411dp 时
//   整体缩字号，宽于基准时一字不动。这一档的行为**一分不变**。
// - **MEDIUM / EXPANDED（≥ 600dp，平板、折叠屏展开、桌面窗口）**：字号**往上走**
//   （见 [typeScaleFor]），主卡换成两栏排法（见 DashboardScreen）。
//   这两档之前只有「内容列放宽到 680dp」这一件事，于是主卡里被
//   `SpaceBetween` 撕出一个几百 dp 的空洞（见 [contentMaxWidthFor] 的注释）。
//
// ## 为什么要做这一层
//
// 这套界面是按 **411dp 宽**（1080×2400 @420dpi）量的：天气块 108dp、
// 主卡 140dp 高、弹层左右各留 40dp，那些数字在 411dp 上刚好成立。
//
// 真机换到 **332dp 宽 + 系统字号 1.2 倍** 之后，同一套数字的相对大小全变了：
// 文字放大 1.2 倍、可用宽度只剩 80%，于是日期折行被 140dp 的固定卡高裁掉、
// 弹层顶到状态栏、按钮文字挤到两行被裁。这就是「字体及 UI 元素太大」的成因，
// **不是某一行写大了，是整套尺寸没有跟着屏宽走。**
//
// ## 这一层做什么
//
// 1. **整体缩放**（[contentScaleFor]）：屏宽比 411dp 窄多少，**字号与版式一起**缩多少。
//    缩放同时作用于 **sp**（字号）与 **dp**（版式），两者用**同一个系数** ——
//    这样「411dp 宽的设计」会被等比缩进 332dp 的屏里，
//    版式比例和留白关系保持不变 —— 相当于网页里的 `vw` 缩放，而不是「少放点东西」。
//
//    **只缩字不缩框是做不到这件事的**：框的尺寸写死在 dp 上，它不缩，
//    文字就相对变大 → 放不下 → 文字被迫再缩一次 → 比例彻底失衡
//    （实测：320dp 的屏上固定 chrome 占主卡 48%，设计稿上是 36%；
//    22.sp 的日期被迫降到 16.sp，主角变小）。
//
//    缩放之后，**排版里的 dp 宽度会变大**（320dp 的屏 → 排版看到 390dp），
//    所以「按宽度做决策」的地方要用 [rememberLayoutWidthDp]（排版宽），
//    不是物理宽。物理宽落在 [337, 411] 时排版宽恒等于 411 ——
//    设计分毫不差地缩进屏幕，比例 100% 保持。
//
//    宽屏（≥ 600dp）**不同步**：那条路是内容列按窗口宽拉伸，
//    [typeScaleFor] 只放大字号。这一档的版式还没定型，留到横屏/平板版式一起设计。
// 2. **安全区**（[topSafeArea] / [bottomSafeArea] / [panelSafeArea]）：刘海、挖孔、
//    状态栏、导航栏都算进去。弹层与底部弹层据此避让。
// 3. **内容列限宽**（[contentMaxWidthFor]）：手机上 480dp 封顶；平板 / 折叠屏展开
//    按窗口宽的 82% 放宽到 680dp。宽出来的部分留给壁纸，但不会再出现
//    「800dp 屏中间贴着一个手机 App」。
// 4. **按实测文字定尺寸**（[measureTextSize]）：凡是「盒子里装一行字」的地方，
//    盒子要跟着字的实际渲染尺寸长。**只缩 sp、不动 dp** 的直接后果就是
//    系统字号一放大，固定 dp 的盒子就装不下那行字了（实测：日期被截、浮岛标签
//    被切、节次数字被砍成一位、教室胶囊压到徽标上）。
// 5. **大字号下改结构，而不是继续缩字**：1.5 / 2.0 倍字号不只是「字更大」，
//    它会让「一行里塞两样东西」的版式不再成立。这一层的做法是分情况换排法：
//    - 课表七列密表：网格内字号封顶（[TimetableMaxFontScale]），页面其余部分照常放大；
//    - 顶栏、主卡、弹层按钮：高度改成下限（`heightIn(min = …)`），装不下就长高；
//    - 周次选择：每行列数按最宽标签实测（4 → 3 → 2 列），不让「第 10 周」折行；
//    - 课程列表卡：时段 chip 从「和课程名同一行」改成「另起一行」。
//    判据是**这一屏还能不能读**，不是「字号有没有严格等于系统设定」。
//
// ## 一条贯穿全文的规则
//
// **判据是「渲染出来放不放得下」，不是「sp 数字对不对」。**
// 系统字号会放大文字，而所有按 sp 写的宽度比较都是在拿 fontScale = 1 的数去比 ——
// 那是在大字号这一档正好算错的地方。
//
// ## 另一条：缩字优先，版式兜底
//
// 屏窄了，**先让字等比缩小**（[contentScaleFor]），整页的比例和留白关系就都保持住了 ——
// 字变小，围着它的框、卡片、胶囊跟着变小，**版式一个字都不用改**。
//
// 反过来做（先保字号、再把版面撑大）是不划算的：框、卡片、胶囊为了塞下没缩小的字
// 只能变大，这一屏的信息密度就变了，整页比例和设计时的那一版对不上。
//
// 所以顺序是固定的：
//
// 1. **缩字**（[heroDateFontSize] 逐档降 + [contentScaleFor] 整体缩）；
// 2. 缩到**最小档**还放不下 → 才**改版式**（把一行里塞两样东西的东西拆成两段）。
//
// 改版式是兜底，不是常态。主卡那一处就是照这个顺序做的：
// 320/332/360/390dp 在系统字号 1.0 时**保持并排**（日期降到 20/20/22/22.sp），
// 只有窄屏 + 用户把系统字号放大、或任何宽度上的极端字号，才把天气挪到第二段。
//
// ## 第三条：宽度不够时看宽，**高度不够时看高**
//
// 上面两条都只管**宽度**。但横屏（914×411）与分屏小窗（640×360）反过来了：
// 宽度过剩、**高度**才是稀缺资源 —— 竖排布局在 411dp 的高度里被 chrome 吃光
// （顶栏 40 + 浮岛 100 + 主卡 140 = 280dp，只剩 130dp 给列表）。
//
// 所以另有一档判断：[isShortWindow]（按**可用高度**判，不按方向判）。
// 矮屏上主卡按并排内容算高度、浮岛只留图标 —— 都是**省高度**的改法，
// 而竖屏那一档（占绝大多数设备）**一个字都不动**。
// =========================================================================

/**
 * 设计基准宽度（dp）。
 *
 * 411dp = 1080×2400 @420dpi，也就是本项目一直用的模拟器与基线截图。
 * 改这个数等于改整套设计的基准，改之前先重新量一遍基线截图。
 */
const val DesignWidthDp = 411

/**
 * 整体缩放下限。
 *
 * 0.82 是「再窄就认得出这是同一个 App」的那条线：低于它，缩放本身开始变成
 * 另一种设计（字会小到读不动），那就不该继续缩，而该改版式。
 *
 * ## 这条线现在同时管着两件事
 *
 * 缩放是**版式与字号一起**缩的（见 [effectiveLayoutScaleFor]），所以下限的代价
 * 也翻倍了：0.82 意味着整页都缩到 82%，
 * 顶栏按钮 32.dp → 26 物理 dp、浮岛入口 72.dp → 59 物理 dp。
 *
 * 触控目标跟着缩是**已知的取舍**（用户明确接受）：整个设计等比缩小，
 * 触摸区自然一起缩小，版面比例保持统一。
 *
 * 实测：物理宽落在 [337, 411] 区间时，排版宽恒等于 411（见 [rememberLayoutWidthDp]），
 * 也就是设计分毫不差地缩进屏幕；低于 337dp 才触底，版式开始比屏幕宽，
 * 那时靠日期降档（缩字）兜底，兜不住才改版式。
 */
const val MinContentScale = 0.82f

/**
 * 手机上内容列的宽度上限（dp）。
 *
 * ## 为什么手机上是 480 而不是拉满
 *
 * 这套版式是按 411dp 宽的屏定稿的，**里面的比例关系**（主卡左列多宽、天气块占多少、
 * 课程名与徽标各占几成）都在那个宽度上量过。屏更宽时如果让内容跟着拉满，
 * 这些比例会被一起撕开：
 *
 * - 主卡里日期在左、天气块在右，中间是 `SpaceBetween` 撑出来的空档 ——
 *   800dp 屏上那个空档有 600dp，整张卡像坏了一样（见实测截图）；
 * - 课程卡的徽标被甩到天边，一行字和一枚圆点之间隔着半张卡。
 *
 * 所以手机上（< 600dp）不让内容超过 480dp：宽出来的部分留给壁纸。
 *
 * ## 为什么是 480 而不是 411
 *
 * 411 是设计基准，取它等于「手机上看到的和基准逐像素一样」，最保守。
 * 取 480 是给课表一点余地：课表是七列并排的网格，411dp 下每列只有 55dp，
 * 课程名要断成两三行；480dp 下每列 66dp，能多放下几个字。
 * 这个宽度下主卡左右仍有约 80dp 的空档 —— 可接受（那本来就是"日期在左、
 * 天气在右"的仪表盘式布局），但不再是 600dp 那种破相。
 *
 * ## 平板 / 折叠屏展开后不再用这个值
 *
 * 800dp 的屏上如果仍是 480dp，两侧各空 160dp，读起来像「一个手机 App 贴在
 * 壁纸中间」。所以宽屏交给 [contentMaxWidthFor]：按 82% 窗口宽放宽、680dp 封顶。
 * 这个常量只在手机宽度范围内定义上限，**不是所有屏幕的上限**。
 *
 * 411dp 及以下的屏完全不受影响（列宽 = 屏宽）。
 */
val MaxContentWidth = 480.dp

/**
 * 大屏上内容列最多能长到多宽（dp）。
 *
 * 手机（< 600dp）用 [MaxContentWidth] 就够；平板 / 折叠屏展开后如果还死守 480dp，
 * 800dp 的屏上会有 160dp×2 的空白，界面读起来像「一个手机 App 贴在壁纸中间」。
 * 让内容列占屏宽的 [ContentWidthFraction]、上限 680dp，宽出来的部分仍然是壁纸，
 * 但卡片能拿到足够宽度，课表七列、待办标题、表单都不再被迫挤在窄列里。
 *
 * 680 而不是拉满：文字行的可读宽度（约 45–75 个字符）在这个数量级；
 * 再宽只会让同一行里的东西离得更远，信息量一点没变。
 *
 * ## 放宽内容列之后，主卡的空洞由谁来收拾
 *
 * 这一层放宽列宽，只解决了「内容列窄」这一个问题，**没有**解决
 * 「列宽变宽之后主卡里出现几百 dp 的空洞」——
 * 主卡原本用 `Arrangement.SpaceBetween` 把天气块甩到天边，
 * 656dp 的列里那个空洞能有 255dp（见 [heroDateMaxSpFor] 附近）。
 *
 * 空洞是**主卡自己的排法**造成的，所以由主卡自己收拾：宽屏上主卡改两栏
 * （见 DashboardScreen 的 `HeroWide` 分支），日期与天气不再被推到两端。
 * 内容列的宽度和主卡的排法是**两件事**，别拿其中一个去解释另一个。
 */
const val WideContentWidthMax = 680

/**
 * 宽屏内容列占窗口宽度的比例。
 *
 * 0.82 = 两侧各留 9% 壁纸。这个比例在 600dp 屏上给内容 492dp（接近满宽），
 * 800dp 上 656dp，1280dp 上顶到 680dp 上限——三档都不是「突然从 480 跳到 680」，
 * 而是随窗口连续变化。
 */
const val ContentWidthFraction = 0.82f

/**
 * 屏宽 → 内容列最大宽度。
 *
 * 只返回**上限**，不是实际宽度：小屏上宽度由父级约束决定，这个上限不生效
 * （`widthIn(max = 480.dp)` 对 332dp 的屏等于没有约束）。所以基准屏与窄屏
 * 的观感一分不变，变化只发生在宽于 480dp 的屏上。
 *
 * @param windowWidthDp 窗口宽度（dp），用窗口而不是物理屏：分屏、折叠屏展开后
 *   窗口才是真正能排内容的宽度。
 */
fun contentMaxWidthFor(windowWidthDp: Int): Dp =
    (windowWidthDp * ContentWidthFraction)
        .coerceIn(MaxContentWidth.value, WideContentWidthMax.toFloat())
        .dp

/**
 * 当前窗口的内容列最大宽度。
 *
 * 所有「内容列」都必须用这个值对齐：顶栏、页面主体、FAB、弹层、抽屉。
 * 漏掉任何一个，宽屏上就会出现「一行在中间、另一行贴边」的错位。
 *
 * ## 用的是**排版宽**，不是物理宽
 *
 * 版式被缩放之后，同一个物理窗口在排版里更宽（见 [rememberLayoutWidthDp]）。
 * 内容列的上限是排版概念（卡片能排多宽），所以必须按排版宽算 ——
 * 否则会拿物理宽去比排版空间，窄屏上凭空多出（或少掉）一段。
 *
 * 算出来的**物理**宽度与改版前一致：排版宽 × 缩放系数 = 物理宽。
 * 变的是列**里面**的东西整体缩小了，不是列本身。
 */
@Composable
fun rememberContentMaxWidth(): Dp {
    val layoutWidthDp = rememberLayoutWidthDp()
    return remember(layoutWidthDp) {
        contentMaxWidthFor(layoutWidthDp)
    }
}

/**
 * 屏宽 → 整体缩放系数。
 *
 * 只在**窄于基准**时收缩，宽于基准不放大：大屏上把一套紧凑的排版拉大，
 * 空气会变多但信息量不变，收益抵不上「一屏更空」的代价。
 *
 * @param screenWidthDp 窗口宽度（dp）。用窗口而不是屏幕：分屏与折叠屏展开后
 *   窗口才是真正能排内容的宽度。
 */
fun contentScaleFor(screenWidthDp: Int): Float =
    (screenWidthDp / DesignWidthDp.toFloat()).coerceIn(MinContentScale, 1f)

/**
 * **版式（dp）方向**的缩放系数。
 *
 * ## 它和 [effectiveFontScaleFor] 里那半是**同一个判定**
 *
 * 自适应的基础是「字号与版式同步缩放」：屏窄了，字缩小多少，围着它的
 * 框、卡片、胶囊也要缩小多少 —— 这样整页的比例和留白关系才和设计稿一致。
 *
 * 只缩字不缩框是**做不到**这件事的：框的尺寸是写死的 dp，它不缩，
 * 于是文字相对变大 → 放不下 → 文字被迫**再缩一次** → 比例彻底失衡。
 * （实测：320dp 的屏上，固定 chrome 占主卡的 48%，而设计稿上是 36%；
 * 22.sp 的日期被迫降到 16.sp，主角变小。）
 *
 * ## 让位规则：用户放大字号时，dp 与 sp **一起**让位
 *
 * 用户把系统字号调到 150% 之后，如果只让位给字号（dp 照缩），就等于
 * 「框缩了、字没缩」——和上面犯的是同一个错，只是方向相反。
 * 所以内容缩放是**全有或全无**：用户明确要大字，App 就不自作主张地缩版式，
 * 改由重排机制（降档、`heightIn`、实测高度）去接。
 *
 * @param windowWidthDp 窗口宽度（dp）。
 * @param systemFontScale 用户设的系统字号（1.0 = 没放大）。
 */
fun effectiveLayoutScaleFor(windowWidthDp: Int, systemFontScale: Float): Float =
    if (systemFontScale > 1f) 1f else contentScaleFor(windowWidthDp)

/**
 * 本次施加的**版式缩放系数**（dp 方向）。
 *
 * 由主题提供（见 [MyApplicationTheme] 与 [AdaptiveDialogContent]），
 * 内容里靠它把「物理宽」换算成「排版宽」。
 *
 * ## 为什么要有这个
 *
 * density 被缩放之后，**同一个物理窗口在排版里的 dp 宽度会变大**：
 * 320dp 的屏在 0.82 的缩放下，排版看到的是 390dp。
 * 于是「按宽度做决策」的地方必须分清自己在量哪一种宽度：
 *
 * - **物理宽**（[LocalConfiguration.screenWidthDp]）：设备是什么样。
 * - **排版宽**（[rememberLayoutWidthDp]）：版面里实际有多宽 —— **决策要用这个**。
 */
val LocalLayoutScale = staticCompositionLocalOf { 1f }

/**
 * 物理宽 + 缩放系数 → 排版宽（纯函数版，供单测用）。
 *
 * @param physicalWidthDp 窗口的物理宽度（dp）。
 * @param layoutScale 版式缩放系数（见 [effectiveLayoutScaleFor]）。
 */
fun layoutWidthDpFor(physicalWidthDp: Int, layoutScale: Float): Int =
    (physicalWidthDp / layoutScale).roundToInt()

/**
 * 当前窗口在**排版 dp**里的宽度。
 *
 * = 物理宽 ÷ 版式缩放系数。
 *
 * ## 关键性质
 *
 * 物理宽落在 [337, 411] 区间时，缩放系数 = 物理宽 / 411，排版宽**恒等于 411** ——
 * 也就是 411dp 的设计**分毫不差**地被缩进屏幕，比例 100% 保持：
 *
 * | 物理宽 | 缩放 | 排版宽 |
 * |--------|------|--------|
 * | 411    | 1.00 | 411    |
 * | 390    | 0.95 | 411    |
 * | 360    | 0.88 | 411    |
 * | 337    | 0.82 | 411    |
 * | 320    | 0.82 | 390（触底，设计比屏幕宽，交给兜底机制） |
 */
@Composable
fun rememberLayoutWidthDp(): Int {
    val configuration = LocalConfiguration.current
    val layoutScale = LocalLayoutScale.current
    return remember(configuration.screenWidthDp, layoutScale) {
        layoutWidthDpFor(configuration.screenWidthDp, layoutScale)
    }
}

/**
 * 一次缩放的完整结果：**dp 方向的系数** + 合成后的密度。
 *
 * 两个数必须一起给：dp 方向的系数要交给 [LocalLayoutScale]（给内容里
 * 换算排版宽用），密度要交给 [LocalDensity]（给整个排版用）。
 * 分开算就会有一处漏改 —— 而漏改的那一处正是「字缩了框没缩」的老毛病。
 */
data class ContentScale(
    /** dp 方向的缩放系数。 */
    val layoutScale: Float,
    /** 合成后的密度：dp 与 sp 都带着缩放。 */
    val density: Density
)

/**
 * 算出这一次的缩放结果。
 *
 * @param base 平台密度（主题层取到的就是系统的那个密度）。
 * @param windowWidthDp 窗口的**物理**宽度（dp）。
 */
@Composable
fun rememberContentScale(base: Density, windowWidthDp: Int): ContentScale {
    return remember(base, windowWidthDp) {
        val layoutScale = effectiveLayoutScaleFor(windowWidthDp, base.fontScale)
        ContentScale(
            layoutScale = layoutScale,
            density = Density(
                // dp 也跟着缩：这是「等比缩进」里缺的那一半。
                density = base.density * layoutScale,
                // sp 跟着缩（宽屏再乘字阶，那一项只作用于字号，不动版式）。
                fontScale = effectiveFontScaleFor(windowWidthDp, base.fontScale)
            )
        )
    }
}

/**
 * 窗口宽度分档。
 *
 * ## 为什么要自己分，不直接用 M3 的 `WindowSizeClass`
 *
 * 两个理由：
 *
 * 1. **粒度不同**。M3 的边界是 600 / 840dp，它要服务的是一整套自适应版式；
 *    这个 App 的宽屏差别只有一处（主卡排法）与一件事（字号档），不值得为它
 *    引入一整套 size class 依赖。
 * 2. **量的东西不同**。M3 按**物理窗口像素**分再换算；这里按 **dp** 分
 *    （`screenWidthDp`）。dp 才是「能排内容的宽度」，在分屏、折叠屏半开、
 *    桌面自由窗口下，dp 才是对的那把尺子。
 *
 * - [COMPACT]：手机竖屏与绝大多数分屏场景。**这一档的行为一分不变**。
 * - [MEDIUM]：平板竖屏、折叠屏半开、桌面窄窗口。
 * - [EXPANDED]：平板横屏、桌面宽窗口。
 */
enum class WindowWidthClass {
    COMPACT,
    MEDIUM,
    EXPANDED
}

/**
 * 窗口宽度 → 宽度分档。
 *
 * 边界取 M3 的 600 / 840dp：600dp 是「够放下两栏内容」的门槛，
 * 840dp 是「再多也用不满、再加宽只是让同一行离得更远」的门槛。
 *
 * @param windowWidthDp 窗口宽度（dp）。用窗口而不是屏幕：分屏与折叠屏展开后
 *   窗口才是真正能排内容的宽度。
 */
fun widthClassFor(windowWidthDp: Int): WindowWidthClass = when {
    windowWidthDp < MediumWidthDp -> WindowWidthClass.COMPACT
    windowWidthDp < ExpandedWidthDp -> WindowWidthClass.MEDIUM
    else -> WindowWidthClass.EXPANDED
}

/** 当前窗口的宽度分档。 */
@Composable
fun rememberWindowWidthClass(): WindowWidthClass {
    val configuration = LocalConfiguration.current
    return remember(configuration.screenWidthDp) {
        widthClassFor(configuration.screenWidthDp)
    }
}

/** MEDIUM 档的门槛（dp）。 */
const val MediumWidthDp = 600

/** EXPANDED 档的门槛（dp）。 */
const val ExpandedWidthDp = 840

/**
 * 窗口是不是「矮」的。
 *
 * ## 判据是**可用高度**，不是方向
 *
 * 横屏只是矮的一种（手机横过来 914×411）；分屏小窗（640×360）也是矮的；
 * 而竖着的小窗（320×568）并不矮。用方向判会漏掉分屏，用高度判不会。
 *
 * ## 420dp 这个门槛从哪来
 *
 * 矮屏上要摆下的固定内容：顶栏 40dp + 浮岛（矮版约 72dp）+ 主卡（矮版约 110dp）
 * ≈ 222dp，再留一张列表卡（约 80dp）与一条分区标题（约 30dp）——
 * 也就是说可用高度低于 ~420dp 时，这一屏就只剩半张卡片了。
 *
 * 竖屏手机是 914dp 高，离这条线很远；**这一档的绝大多数设备根本进不来**，
 * 所以矮屏的版式可以放心做得和竖屏不一样。
 */
const val ShortWindowHeightDp = 420

/**
 * 窗口高度（dp）→ 是不是矮屏。
 *
 * @param screenHeightDp 窗口的**物理**高度（dp）。
 */
fun isShortWindow(screenHeightDp: Int): Boolean = screenHeightDp < ShortWindowHeightDp

/** 当前窗口是不是矮屏（横屏、分屏小窗）。 */
@Composable
fun rememberIsShortWindow(): Boolean {
    val configuration = LocalConfiguration.current
    return remember(configuration.screenHeightDp) {
        isShortWindow(configuration.screenHeightDp)
    }
}

/**
 * 宽屏上的字阶倍率。
 *
 * ## 为什么宽屏要放大字，而不是把内容拉满
 *
 * 宽屏上把一套紧凑的排版拉大，空气会变多但信息量不变——所以 [contentScaleFor]
 * 在宽屏上**不放大**（它只负责窄屏收缩）。但「不放大」不等于「不重排」：
 *
 * 411dp 上 22.sp 的日期，在 656dp 宽的内容列里只有三分之一的长度，
 * 读起来像「一个手机 App 贴在壁纸中间」——这正是 [contentMaxWidthFor] 想解决的
 * 问题，但只放宽列宽解决不了，**字号不跟着走才是根因**。
 *
 * 所以这里给宽屏一个**温和**的字阶倍率（1.10 / 1.18，不是 1.5）：
 * 1.18 之后主卡日期从 22.sp 到 26.sp，正好把日期在那一行里该有的分量补回来，
 * 再多就变成「标题抢了整张卡的戏」，而这张卡的主角是日期与状态，不是标题。
 *
 * ## 为什么倍率这么小
 *
 * 倍率是乘在**系统字号**上的：用户设 200% 就是 200% × 1.18 = 236%，
 * 而 App 里那些按 dp 写死的盒子（行高、图标尺寸）不会跟着长。
 * 倍率每大一分，这些盒子被文字顶破的风险就高一分。
 * 1.18 是「字明显变大了，但还没到撑破固定盒子」的那一档。
 *
 * 窄屏（COMPACT）恒为 1.0：手机路径一个字都不动。
 */
fun typeScaleFor(windowWidthDp: Int): Float = when (widthClassFor(windowWidthDp)) {
    WindowWidthClass.COMPACT -> 1f
    WindowWidthClass.MEDIUM -> 1.10f
    WindowWidthClass.EXPANDED -> 1.18f
}

/**
 * 顶部安全区：状态栏 + 刘海 / 挖孔。
 *
 * 挖孔在横屏时落在**左右两侧**（`Horizontal`），竖屏才在顶部。只避让
 * `statusBars` 的话，横屏时内容会从挖孔底下穿过去。
 */
val topSafeArea: WindowInsets
    @Composable get() = WindowInsets.statusBars
        .union(WindowInsets.displayCutout)
        .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)

/**
 * 底部安全区：导航栏 + 刘海 / 挖孔。
 */
val bottomSafeArea: WindowInsets
    @Composable get() = WindowInsets.navigationBars
        .union(WindowInsets.displayCutout)
        .only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)

/**
 * 弹层用的安全区：系统栏 + 刘海/挖孔 + 键盘。
 *
 * 用 [WindowInsets.safeDrawing] 而不是自己拼：它就是这三者的并集，
 * **每条边取最大值**，所以键盘弹起时底部不会被「导航栏高度 + 键盘高度」
 * 算两遍（旧代码为此专门写过一段 `imeCompensation` 手工判据）。
 *
 * 键盘那一项在两种系统行为下都成立：
 * - API 30+ 走 edge-to-edge，窗口不会被 resize，键盘以 inset 形式给出 → 面板抬到键盘上方；
 * - 老系统 `adjustResize` 会先把窗口缩到键盘之上，此时键盘与窗口不再重叠，
 *   inset 自然为 0 → 面板留在缩好的窗口里。
 */
val panelSafeArea: WindowInsets
    @Composable get() = WindowInsets.safeDrawing

/**
 * 有效字号缩放 = 系统字号 × 宽屏字阶。
 *
 * ## 这里**没有**窄屏收缩——它已经进了 density
 *
 * 窄屏收缩（[contentScaleFor]）这一版搬到了 `density` 上，因为 density 是
 * 「dp → px」和「sp → px」**共同的**那个换算系数：
 *
 * ```
 * px = dp × density
 * px = sp × fontScale × density
 * ```
 *
 * 把 density 乘上 k，字和框就**一起**缩了 k 倍（等比）。
 * 如果同时再把 fontScale 乘上 k，字就会缩 k² 倍、框只缩 k 倍 ——
 * 那不是等比，是「字比设计稿更小」，比原来的「字相对变大」错得更隐蔽。
 *
 * 所以分工是：
 * - **density × 内容缩放** → 版式与字号**一起**等比缩（窄屏的主机制）；
 * - **fontScale = 系统字号 × 宽屏字阶** → 字号相对版式的**额外**放大。
 *
 * ## 让位规则
 *
 * 用户主动放大系统字号时，**内容缩放整体让位**（[effectiveLayoutScaleFor]）：
 * dp 与 sp 一起不缩，让用户要的「大字」按大字的样子排，由重排机制接住。
 *
 * ## 为什么抽成纯函数
 *
 * 这条公式同时被两处用：主题算 `LocalDensity`（[rememberContentScaledDensity]）
 * 和单测验「某个宽度 + 某个系统字号下，字到底多大」。
 *
 * 如果单测自己抄一份公式，两边迟早会对不上——而对不上的那一天，
 * 测试还在「通过」，因为它验的是自己那份实现。**同一份实现必须是同一段代码。**
 *
 * @param windowWidthDp 窗口宽度（dp）。
 * @param systemFontScale 用户设的系统字号（1.0 = 没放大）。
 */
fun effectiveFontScaleFor(windowWidthDp: Int, systemFontScale: Float): Float =
    systemFontScale * typeScaleFor(windowWidthDp)

/**
 * 把字号整体缩放后的密度。
 *
 * 只改 `fontScale`、不动 `density`：**dp 的物理尺寸由屏幕密度决定**，那是系统
 * 已经算对的一层；再乘一次就等于把 dp 缩成「dp 的 dp」。
 * 改 fontScale 之后，所有 `sp`（不论是 `MaterialTheme.typography` 还是各处
 * 写死的 `fontSize = 13.sp`）都跟着缩，而所有 `dp`（按钮、留白、卡片）原样保留。
 *
 * ## 三个乘数，各管一件事
 *
 * ```
 * 有效字号 = 系统字号 × 窄屏收缩 × 宽屏字阶
 * ```
 *
 * - **系统字号**：用户自己设的，一个字不动。
 * - **窄屏收缩**（[contentScaleFor]）：屏窄于 411dp 时按比例收。
 *   **但用户已经放大过字号时这一项让位**（见下）。
 * - **宽屏字阶**（[typeScaleFor]）：窗口 ≥ 600dp 时轻微放大。
 *   窄屏恒为 1.0。
 *
 * ## 用户放大字号时，窄屏收缩必须让位
 *
 * 这是这一版新加的一条规矩。原来是**无条件**相乘：332dp 的屏上把系统字号
 * 设成 150%，App 实际只给 123%——**App 在替用户做决定**，把人家明确要的
 * 「大一点」又压回去一部分。
 *
 * 窄屏收缩存在的原因是「411dp 的设计塞不进 320dp 的屏」，是替版面兜底；
 * 而用户放大字号是一个**明确的意图**，它要的不是「字大一点」，是
 * 「这一屏按大字的规矩重新排」。用缩放去回应意图，等于回错了话。
 *
 * 让位之后靠什么兜住？靠这一层早就建好的重排机制：顶栏 `heightIn(min = …)`、
 * 浮岛高度按实测文字算、主卡高度按实测文字算、日期按可用宽逐档降、
 * 弹层大字号时收边距。这些全都与「窄屏收缩」无关，用户字号一放大它们就顶上来了。
 *
 * @see LocalEffectiveFontScale 这个合成结果怎么被布局决策用到。
 */
/**
 * 把字号与版式一起缩放后的密度。
 *
 * ## 这一版改了什么
 *
 * 原来只缩 `fontScale`（字号），`density`（dp 换算）原样透传 ——
 * 也就是「字缩小了、框还是原尺寸」。那样做的后果是：
 * 固定 dp（页距、卡内边距、天气块 108dp）不缩 → 文字相对变大 →
 * 放不下 → 文字被迫**再缩一次** → 整页比例与设计稿对不上。
 *
 * 现在 **dp 与 sp 用同一个系数缩**，411dp 的设计被等比缩进小屏：
 * 框、卡片、胶囊跟着字一起变小，比例和留白关系保持不变。
 *
 * ## 宽屏为什么不同步
 *
 * 宽屏（≥ 600dp）走的是另一条路：内容列按窗口宽**拉伸**（82%），
 * 而不是把设计整体放大；[typeScaleFor] 那一档字阶放大也只作用于字号。
 * 所以宽屏上「字变大、框没大」的不对称依然存在 —— 这是**有意的**：
 * 宽屏版式还没定型，等横屏/平板版式一起设计时再统一处理。
 * 窄屏这边才是「等比缩进」的主战场。
 *
 * @see LocalEffectiveFontScale 这个合成结果怎么被布局决策用到。
 * @see effectiveLayoutScaleFor 让位规则（用户放大字号时 dp 与 sp 一起让位）。
 */
@Composable
fun rememberContentScaledDensity(): Density {
    val base = LocalDensity.current
    val configuration = LocalConfiguration.current
    val width = configuration.screenWidthDp
    return remember(base, width) {
        Density(
            density = base.density * effectiveLayoutScaleFor(width, base.fontScale),
            fontScale = effectiveFontScaleFor(width, base.fontScale)
        )
    }
}

/**
 * 周课表这种**七列并排的密表**允许的最大字号缩放。
 *
 * ## 为什么这里要单独封顶
 *
 * 别的页面系统字号调到 2.0 只是「字更大、卡片更高」，滚动一下照样读；
 * 课表不行：七个日列在 411dp 屏上每列只有 ~55dp，10.5.sp 的课程名放大到 21.sp
 * 后一行只放得下 1 个字，整张表变成一条条竖排的省略号（实测截图）。
 * 这不是「用户想要更大字」，而是**这个控件在大字号下已经不能表达信息**。
 *
 * 所以网格内部把有效字号缩放钳在 1.3：系统 1.5 / 2.0 时网格里的字仍比基准
 * 大 30%，只是不再跟着无限放大；页面其余部分（顶栏、周次条、分段控件、
 * 列表视图）**照常跟随系统字号**。列表视图本来就是大字号下的可读退路，
 * 用户真想看大字的课，切到「列表」即可。
 *
 * 1.3 是实测出来的上限：1.5 时「复变函数与积分变换」在 55dp 列里已经只剩
 * 两行各 2 个字，1.3 时能放下 3 个字；再大一档就开始认不出课名了。
 */
const val TimetableMaxFontScale = 1.3f

/**
 * 把当前字号缩放**封顶**后的密度。
 *
 * 只改 `fontScale`，`density` 原样保留——图标、行高、内边距这些 dp 不该跟着变。
 * 已经低于上限时直接返回原密度（同一个对象），不制造无谓的重组。
 */
@Composable
fun rememberCappedFontDensity(maxFontScale: Float): Density {
    val base = LocalDensity.current
    return remember(base, maxFontScale) {
        if (base.fontScale > maxFontScale) {
            Density(density = base.density, fontScale = maxFontScale)
        } else {
            base
        }
    }
}

/**
 * 当前**有效字号缩放**：系统字号 × 宽屏字阶。
 *
 * `LocalDensity.current.fontScale` 已经是这个值（见 [rememberContentScaledDensity]：
 * 它把这几个乘数乘完再交出去），所以这里只是把它换成一个
 * **能进布局决策**的名字。
 *
 * ## 注意：窄屏收缩**不在这里**
 *
 * 窄屏收缩走的是 density（dp 与 sp 一起缩），所以在这个数里看不到它。
 * 这正是它该在的地方：字号相对**版式**的额外放大才是这个数的含义，
 * 而窄屏时字号相对版式**没有**额外放大（两者一起缩了）。
 *
 * ## 为什么需要单独拿它
 *
 * 「这段文字放不放得下」这个判断必须用**渲染出来的实际宽度**，
 * 而不是 `sp` 那个数字本身。22.sp 在系统字号 1.5 的机器上渲染出来是 33.sp 的高度、
 * 1.5 倍的宽度 —— 只按 22.sp 去算宽度，就会得出「放得下」的结论，
 * 然后被 `maxLines = 1` 截成「2026年10月20日 ...」。
 *
 * 凡是**按宽度选字号**的地方（主卡日期的档位、胶囊的截断预算），
 * 都要用这个值，不能用裸的 sp 数。
 */
val LocalEffectiveFontScale: Float
    @Composable get() = LocalDensity.current.fontScale

/**
 * 卡片高度的**设计锚点缩放系数**。
 *
 * ## 它回答的是同一个问题：**这张卡的主角比设计稿大了多少倍**
 *
 * 版式（所有 dp 尺寸）已经跟着屏幕宽缩放过一次了 —— 那是 [rememberContentScaledDensity]
 * 里 `density` 乘上的那个系数，字与框**一起**缩，所以窄屏上比例天然不变。
 *
 * 真正会让「字相对框变大」的只剩两件事：
 * - 用户把系统字号调大了（[effectiveFontScaleFor] 里的 systemFontScale）；
 * - 宽屏字阶（[typeScaleFor]）。
 *
 * 这两件事都体现在**有效字号缩放**（[LocalEffectiveFontScale]）里。框要守比例，
 * 就必须按同一个系数长。
 *
 * @param effectiveFontScale 有效字号缩放（系统 × 宽屏字阶），见 [LocalEffectiveFontScale]。
 */
fun cardHeightScaleFor(effectiveFontScale: Float): Float =
    effectiveFontScale.coerceAtLeast(0.1f)

/**
 * 一张卡片的**高度锚点**：设计高度按「主角字号的渲染放大倍数」放大。
 *
 * ## 为什么按**主角**算，而不是按全局字号算
 *
 * 卡片里最大的那行字决定了这张卡在视觉上的分量 —— 主卡是日期，课程卡是课名。
 * 锚点跟着主角走，「主角在卡里占多少」才不变。
 *
 * 主角字号有两处会变：
 * - **档位**：窄屏上日期会降档（22sp → 20sp），宽屏上会升档（→ 26sp）；
 * - **字号缩放**：系统字号与宽屏字阶。
 *
 * 合起来就是「这一屏上主角比设计稿大了多少倍」。
 *
 * ## 为什么不能只按内容算
 *
 * 只按内容算的话，卡片在基准屏上是 112dp、在 1.5 倍字号下是 139dp —— 卡是跟着内容长高了，
 * 但**设计留白**（140 − 112 = 28dp）没有跟着长，于是字在卡里越占越满，
 * 「同一张设计」在不同字号下长得不一样。
 *
 * 锚点乘上系数之后，留白与文字同步放大，比例才守得住：
 * 设计稿上日期的行盒占卡高 28/140 = 20%，1.5 倍字号下 42/210 还是 20%。
 *
 * ## 下限仍然是实测高度
 *
 * 缩字救不了时（窄屏 + 极端字号），版面会重排（天气挪到第二段），实测高度会超过锚点 ——
 * 那时以实测为准（见 DashboardScreen 的 `heroCardHeightFor`）。
 * **锚点守比例，实测守不裁切**，两个都要。
 *
 * @param designHeight 设计高度（基准屏、系统字号 1.0 上的那一版）。
 * @param designMainSp 设计稿上主角的字号（sp）。
 * @param mainSp 这一屏上主角实际用到的字号（sp）。
 * @param effectiveFontScale 有效字号缩放，见 [LocalEffectiveFontScale]。
 */
fun cardHeightAnchor(
    designHeight: Dp,
    designMainSp: Float,
    mainSp: Float,
    effectiveFontScale: Float
): Dp {
    val amplification = (mainSp / designMainSp) * effectiveFontScale
    return designHeight * cardHeightScaleFor(amplification)
}

/**
 * 固定尺寸盒子按**文字放大倍数**放大。
 *
 * ## 为什么
 *
 * 「字变大了、框没变」是这一层反复在修的那个毛病：
 * - 一个 22.dp 的圆圈里装 14.sp 的字，用户的字号一放到 150%，字就被裁掉半截；
 * - 一个 32.dp 的勾选圈旁边立着 29.sp 的标题，圈就小得像个句号。
 *
 * 固定盒子不是不能有 —— 图标、圆圈、按钮都该是固定比例的。
 * 但**比例要跟着字走**：字放大 1.5 倍，盒子也放大 1.5 倍，
 * 该裁的不会裁，该失衡的不会失衡。
 *
 * ## 与卡片高度锚点的区别
 *
 * 高度锚点（[cardHeightAnchor]）跟的是**主角字号**（可能降档）；
 * 这里跟的是**全局文字放大倍数**（[LocalEffectiveFontScale]）——
 * 旁边的图标、圆圈跟的是所有文字共同的放大倍数，不是主角那一行。
 *
 * @receiver 基准屏上的尺寸（dp）。
 */
@Composable
fun Dp.scaledWithText(): Dp = this * LocalEffectiveFontScale

/**
 * 实测一段文字渲染出来的尺寸（dp）。
 *
 * 用来回答「装它的盒子要留多大」这个问题。
 *
 * ## 为什么不用「字号 × 系数」估
 *
 * 中文回退字体的行高比拉丁字体大得多（实测约 1.5em），拿系数估会在**大字号**
 * 这一档正好估小 —— 而大字号恰恰就是出问题的那一档。
 * 这里直接用 [androidx.compose.ui.text.TextMeasurer] 量一次，测多少是多少。
 *
 * @param text 要量的文字（取最长/最重的那一条）。
 * @param style 文字样式，必须与实际渲染时**完全一致**，否则量出来的高度不算数。
 */
@Composable
fun measureTextSize(text: String, style: TextStyle): DpSize {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val layout = remember(measurer, text, style) {
        measurer.measure(text = text, style = style)
    }
    return with(density) { DpSize(layout.size.width.toDp(), layout.size.height.toDp()) }
}

@Composable
fun measureTextHeight(text: String, style: TextStyle): Dp =
    measureTextSize(text, style).height

/**
 * 给**独立窗口**（`androidx.compose.ui.window.Dialog`）用的自适应包装。
 *
 * ## 为什么单独做一个
 *
 * `Dialog` 开的是**另一个窗口**，它有自己的 Composition 与 CompositionLocal：
 * - 主题里覆盖的 [LocalDensity] **不会**传进去（实测：同窗口 overlay 的
 *   标题在 332dp 屏上高 52px，独立窗口里的同款标题高 48px——后者没缩）；
 * - 窗口也不继承 `MyApplicationTheme` 提供的其它 Local。
 *
 * 所以独立窗口里必须**自己**再套一遍缩放与安全区，否则窄屏上它就是唯一
 * 没自适应的那个弹层。
 *
 * 用法：把 Dialog 的内容整体包一层。
 */
@Composable
fun AdaptiveDialogContent(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    // 独立窗口里的密度是**平台**的（不继承主题的覆盖），所以这里要自己算一遍缩放。
    val base = LocalDensity.current
    val configuration = LocalConfiguration.current
    val contentScale = rememberContentScale(base, configuration.screenWidthDp)
    CompositionLocalProvider(
        LocalDensity provides contentScale.density,
        // 弹层里也有按宽度做决策的地方（左右留白、面板宽度上限），
        // 它们需要的是排版宽 —— 所以系数也要一起给。
        LocalLayoutScale provides contentScale.layoutScale,
    ) {
        Box(
            modifier = modifier
                // 独立窗口默认不避让系统栏：内容会顶到状态栏与导航栏下面。
                .windowInsetsPadding(WindowInsets.safeDrawing),
            contentAlignment = Alignment.Center
        ) {
            content()
        }
    }
}

/**
 * 弹层左右留白。
 *
 * 411dp 上是 40dp；窄屏按屏宽的 8% 收，但不低于 16dp（再窄内容就没地方放了）。
 * 基准屏上这个函数返回 40.dp，**与改动前完全一致**。
 */
fun adaptivePanelPadding(availableWidth: Dp): Dp =
    if (availableWidth >= 400.dp) 40.dp
    else (availableWidth * 0.08f).coerceAtLeast(16.dp)

/**
 * 主卡日期的字号：按**左列实际可用宽度**选档。
 *
 * ## 为什么不是固定一个值
 *
 * V3 定稿要求日期是 22sp（用户明确否掉过"日期变小"的方案，见设计笔记）。
 * 但 22sp 的日期行实测宽 **232.8dp**（411dp 屏上量出来的），
 * 而左列 = 卡内容宽 − 间隔 12dp − 天气块 108dp：
 * - 411dp 屏（排版宽 411）：363 − 12 − 108 = **243dp** → 22sp 放得下 ✓
 * - 332dp 屏（排版宽 405）：357 − 12 − 108 = **237dp** → 22sp 放得下 ✓
 * - 320dp 屏（排版宽 390）：342 − 12 − 108 = **222dp** → 22sp 放不下，降一档
 *
 * 所以窄屏上大部分时候**不用**降档：版式跟着字一起缩，日期列就没被挤窄。
 * 真要降档时**降档也不等于推翻定稿**：定稿要的是「日期是有分量的主角」，
 * 22sp 放不下时退到能放下的最大一档，而不是直接掉到 13sp 变成一行小字。
 *
 * ## 公式估的宽度与**实测**的宽度不一样
 *
 * 这里的 232.8dp 是**渲染出来**的宽度（uiautomator 量的语义边界）。
 * 而 [measureTextSize] 量出来的宽度更大 —— 实测 22.sp 那句量出来是 **236.59dp**
 * （差 1.6%：字距 -0.3.sp 的取整、中文回退字形的字形宽度与排版宽度的差）。
 *
 * 所以公式只用来**估**「大概放不放得下」，真正的判定要用实测值
 * （见 DashboardScreen 的 `heroDateTierWithMeasuredFit`）。
 *
 * ## 档位怎么算出来的
 *
 * 公式只有一条：**这一档字号在当前缩放后，放进左列还剩不剩得下**。
 *
 * ```
 * 放得下的最大字号 = 22sp × 左列宽 / (232.8dp × 有效字号缩放)
 * ```
 *
 * 分母上的**有效字号缩放**是这一版新加的，也是之前漏掉的那一环：
 * 系统字号调到 1.5 时，22.sp 实际渲染成 33.sp 的样子、占 1.5 倍的宽度。
 * 旧算法只拿左列宽去比 232.8dp（那是 fontScale = 1 时的数），
 * 于是判定「243dp 装得下 22sp」→ 实际渲染 349dp → 被 `maxLines = 1` 截成
 * 「2026年10月20日 ...」。实测截图就是这么来的。
 *
 * 档位是 **向下取整** 到 [HeroDateTiers] 里的某一档（不是就近）——
 * 就近会选到放不下的那一档，截断照样发生。
 *
 * ## 窄屏上为什么**几乎不再**降档
 *
 * 窄屏（< 411dp）时 [contentScaleFor] 把**版式与字号一起**等比缩了
 * （320dp 的屏上是 0.82），整页的比例和留白关系保持不变 —— 这是自适应的**基础**。
 *
 * 因为缩的是**版式**，日期所在的那一列也跟着缩宽了：
 * 排版宽 = 物理宽 ÷ 缩放系数，落在 [337, 411] 区间时恒等于 411，
 * 于是日期列还是设计的 243dp，22.sp 的 232.8dp **放得下** —— 不降档。
 *
 * 只有物理宽掉到 337dp 以下（缩放触底 0.82，版式比屏幕宽）时才降一档：
 * 320dp → 排版宽 390 → 日期列 222dp → 22.sp 的 232.8dp 放不下 → 降到 20.sp。
 *
 * 降档只动日期这一行，其余文字的档位不变，所以整页仍然是「411dp 的设计等比缩进
 * 小屏」，只是日期这一行按比例缩得更多一点。**这是缩字，不是改版式。**
 *
 * ## 什么时候才该改版式
 *
 * 缩字缩到底（最小档 [HeroDateMinTierSp]）还放不下的时候 ——
 * 那才是「缩字救不了」的时刻，这时才把天气块挪到第二段去（见 DashboardScreen）。
 * 版式改动是**兜底**，不是常态：常态是缩字。
 *
 * @param leftColumnWidth 左列可用宽度（dp，**排版 dp**）。
 * @param fontScale 有效字号缩放（系统 × 宽屏字阶），见 [LocalEffectiveFontScale]。
 * @param maxSp 允许的最大字号。手机上是 22（V3 定稿，宽屏不放大）；
 *   宽屏上放宽到 26（见 [heroDateMaxSpFor]）。
 */
fun heroDateFontSize(
    leftColumnWidth: Dp,
    fontScale: Float,
    maxSp: Float = HeroDateMaxSpCompact
): TextUnit {
    val scale = fontScale.coerceAtLeast(0.01f)
    // 放得下的最大 sp
    val affordableSp = 22f * leftColumnWidth.value / (HeroDateWidthAt22Sp * scale)
    val tier = HeroDateTiers.lastOrNull { it <= affordableSp && it <= maxSp }
        ?: HeroDateTiers.first()
    return tier.sp
}

/**
 * 主卡日期的字号档位（sp）。
 *
 * 22 是定稿值。24 / 26 两档**只给宽屏用**（见 [heroDateMaxSpFor]），
 * 手机路径永远选不到它们 —— `heroDateFontSize` 的 `maxSp` 默认就是 22。
 */
private val HeroDateTiers = listOf(14f, 16f, 18f, 20f, 22f, 24f, 26f)

/** 日期字号的最小档（sp）。再小就读不出来了，所以它就是「缩字」的底线。 */
const val HeroDateMinTierSp = 14f

/**
 * 下一档更小的日期字号（sp）；已经是最小档则返回 null。
 *
 * 用来做「实测宽度差一点点就降一档」的修正（见 DashboardScreen 的档位修正）：
 * 宁可字号小一点，也要保住完整的日期写法（含星期几）——
 * 读不出星期几比字号小一点更糟。
 */
fun nextSmallerHeroDateTier(currentSp: Float): Float? =
    HeroDateTiers.filter { it < currentSp - 0.01f }.maxOrNull()

/**
 * 日期列至少要有的宽度（dp）：**最小档都放不下**时的宽度。
 *
 * 判据是「日期缩到最小档 [HeroDateMinTierSp]、还放不下没有」。
 * 放得下 → 缩字就够了，版式不动（整页比例保持设计的样子）。
 * 放不下 → 缩字救不了，这时才改版式（把天气块挪到第二段）。
 *
 * @param fontScale 有效字号缩放（系统 × 宽屏字阶）。
 */
fun heroMinDateColumnWidth(fontScale: Float): Dp {
    val scale = fontScale.coerceAtLeast(0.01f)
    return (HeroDateWidthAt22Sp * (HeroDateMinTierSp / 22f) * scale).dp
}

/** 手机（COMPACT）上日期字号的封顶档：V3 定稿的 22sp。 */
const val HeroDateMaxSpCompact = 22f

/** 宽屏（MEDIUM / EXPANDED）上日期字号的封顶档。 */
const val HeroDateMaxSpWide = 26f

/**
 * 宽屏上日期字号的封顶档（sp）。
 *
 * 手机恒为 22（V3 定稿，你明确否掉过"日期变小"）。
 * 宽屏放宽到 26：那一档的内容列有 600dp 以上，22.sp 的日期只占三分之一宽，
 * 主角不像主角。26 是「补回了分量」而不是「抢了戏」的那一档。
 */
fun heroDateMaxSpFor(widthClass: WindowWidthClass): Float =
    when (widthClass) {
        WindowWidthClass.COMPACT -> HeroDateMaxSpCompact
        WindowWidthClass.MEDIUM, WindowWidthClass.EXPANDED -> HeroDateMaxSpWide
    }

/**
 * 22sp 那句日期的实测宽度（dp，fontScale = 1）。
 *
 * 实测来源：411dp 基准屏上「2026年10月20日 星期二」这行的渲染宽度。
 * 档位公式的分母就是它 —— 换设备、换文案（换成不含星期的短日期）都要重测。
 */
private const val HeroDateWidthAt22Sp = 232.8f
