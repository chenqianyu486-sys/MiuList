package com.example.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

// =========================================================================
// MiuList Natural Refreshing Palette
// 主题色：青竹绿/抹茶嫩绿 (#BCE472)
// 背景色：米白（温润护眼雅致）
// 点缀色：湖蓝（静谧澄澈点缀）
// 课程可选颜色：8 种粉彩色（红、粉、绿、青、蓝、紫、橙、黄）
// =========================================================================

// 1. 主题色：绿 (#BCE472 - Fresh Bamboo Lime / Matcha Green)
val LimeGreenPrimary = Color(0xFFBCE472)             // 用户指定主题色绿 (#BCE472)
val LimeGreenDark = Color(0xFFA6CD56)                // 稍深绿色调 (#A6CD56)
val LimeGreenContainer = Color(0xFFDCC9A6).copy(alpha = 0.30f) // 半透明淡茶色底容器（30%），绿作前景时使用
val LimeGreenOnContainer = Color(0xFF4A6B2A)         // 淡茶底上的深抹茶绿前景 (#4A6B2A)
val LimeGreenOnPrimary = Color(0xFF1E2D0E)           // 浅绿底上的高对比墨炭深色文字 (#1E2D0E)
// 主题绿反过来当浅底上的前景（课程时间等提示文字/图标）时用这一档：
// #BCE472 直接当文字色在白卡上只有 1.45:1，几乎读不出来。
// 这里保持与主题绿同色相（约 82°，原绿 81°）但压暗一档，
// 白卡约 5.3:1、米白页面底约 5.0:1，小字号也能过 AA。
val LimeGreenOnLight = Color(0xFF55751E)            // 浅底（米白/白卡）上的深抹茶绿前景 (#55751E)

val LinenGreenPrimary = LimeGreenPrimary
val LinenGreenDark = LimeGreenDark
val LinenGreenContainer = LimeGreenContainer
val LinenGreenOnContainer = LimeGreenOnContainer

// 2. 点缀色：湖蓝 (Lake Blue - Tranquil, Serene Alpine Water)
val LakeBlueAccent = Color(0xFF387B9B)               // 湖蓝点缀色 (#387B9B)
val LakeBlueContainer = Color(0xFFE8F2F7)            // 浅湖蓝微容器色 (#E8F2F7)
val LakeBlueOnContainer = Color(0xFF1E526D)          // 湖蓝容器深色文字 (#1E526D)

// 3. 背景色与基底：米白 (Rice White / Cream / Warm Off-White)
val WarmRiceWhite = Color(0xFFFAF8F5)                // 柔和米白背景底色 (#FAF8F5)
val WarmCreamSurface = Color(0xFFFFFFFF)             // 卡片纯白浮层 (#FFFFFF)
val WarmCreamSurfaceVariant = Color(0xFFF3EFE8)      // 微米灰容器/分界底色 (#F3EFE8)
val WarmCreamBorder = Color(0xFFE6E1D6)              // 细腻温润米灰边框线 (#E6E1D6)
val WarmCreamBorderLight = Color(0xFFEDE9E1)         // 浅淡米灰细边框 (#EDE9E1)

// 4. 文字颜色体系 (墨炭深色与温和次级字)
val TextPrimary = Color(0xFF1E2320)                  // 极深炭青/墨炭主要标题与正文 (#1E2320)
val TextSecondary = Color(0xFF4A524D)                // 深石墨次要文字 (#4A524D)
val TextMuted = Color(0xFF78827B)                    // 柔和次级辅助文字 (#78827B)

/**
 * 三级文字：层级在 [TextSecondary] 之下，但**必须仍然过 4.5:1**。
 *
 * 存在的理由是课程块上的教室号——8.5sp 的小字，底色还是课程粉彩本身。
 * [TextSecondary] #4A524D 压在最暗的那支浅粉彩蓝 #93C5FD 上只有 4.42:1，
 * 差一点点就掉出 AA；再深一号 #3B433F 能到 5.6:1。
 * 「三级」只体现在和其它文字的明度差上，不拿对比度换层级。
 */
val TextTertiary = Color(0xFF3B433F)

// 5. 「茶色」体系 → 实际已改为冷调白玻璃（名字保留避免大范围改名）
// 「绿作前景时用淡茶色当底」这条规则本身是对的（浅绿压白底读不出来），
// 但底色不该是暖茶：暖茶压在薄荷青网格背景上会显旧。
// 现在统一改成中性白玻璃 + 一道发丝边，和浮岛、卡片完全同源。
val TeaColorBase = Color(0xFFFFFFFF)                  // 基色（不透明，供需要自行控制 alpha 的场景使用）
val TeaColor = Color.White.copy(alpha = 0.52f)        // 冷白玻璃底（52% 不透明）
val TeaColorLight = Color(0xFFEAF1EE)                 // 极浅冷调底 (#EAF1EE)
val TeaColorDark = Color(0xFFBFCBC5)                  // 沉一档的冷灰绿
val TeaColorBorder = Color(0xFFD3DEDA).copy(alpha = 0.65f) // 发丝边：底色很淡时靠它保证轮廓
val TeaOnColor = Color(0xFF3F6B1C)                    // 深抹茶绿前景 (#3F6B1C)，在白玻璃上约 7.4:1

// Helper: 识别颜色是否属于亮色/高明度颜色
fun isLightColor(color: Color): Boolean {
    val r = color.red
    val g = color.green
    val b = color.blue
    val luminance = 0.2126f * r + 0.7152f * g + 0.0722f * b
    return luminance > 0.55f
}

// Helper: 识别是否为绿色系（抹茶绿、粉彩绿、黄绿等）
//
// **判据是 HSL 色相区间，不是逐通道阈值。** 旧写法是两支 RGB 比较：
//   (g > 0.60 && g >= r && g > b) || (g > 0.65 && r > 0.55 && b < 0.60)
// 第二支本意是捞「淡黄」（#FDE047 的 g .878 / r .992 / b .278），但**橙色也全中**：
// #FDBA74 的 r .992 / g .729 / b .455，三条一个不差地被判成绿系，
// 于是橙色课在设置页 chip、待办卡课程名、课程弹层图标这些地方全被染成深绿。
//
// 逐通道阈值只能问「亮不亮」这类标量属性，问不了「是什么色相」——
// r 高 g 中 b 低，既可能是黄也可能是橙，阈值分不开。改成走色相：
// 45°~180° 覆盖黄(50°)→黄绿→绿(142°)，排除橙(31°)和青(188°)。
//
// 调色板里唯一因此改变归属的就是**橙色 #FDBA74**（绿系 → 非绿系），
// 其余 8 色与旧阈值结论完全一致：绿/黄仍是绿系，青蓝紫粉红都不是。
//
// 黄色 #FDE047（约 50°）**刻意留在绿系里**，维持「绿课/黄课沿用深抹茶绿」
// 这条既有约定；真要让黄课也走自己的色相，把下界从 45° 降到 20° 即可。
fun isGreenishColor(color: Color): Boolean {
    val r = color.red
    val g = color.green
    val b = color.blue
    val maxC = maxOf(r, g, b)
    val minC = minOf(r, g, b)
    if (maxC == minC) return false // 纯灰没有色相可言
    val h = hue(r, g, b, maxC, minC) * 360f
    return h in GREENISH_HUE_MIN..GREENISH_HUE_MAX
}

/** 绿系色相下界：45°。黄（50°）在内、橙（31°）在外。 */
private const val GREENISH_HUE_MIN = 45f

/** 绿系色相上界：180°。绿（142°）在内、青（188°）在外。 */
private const val GREENISH_HUE_MAX = 180f

// Helper: 把课程色压成「能当前景用」的深色版本（保留色相）。
// 8 种课程粉彩色里有好几档明度很高，直接当文字色压在近白胶囊底上根本读不出来：
// #FDE047 只有 1.2:1、#FDBA74 约 1.7:1、#D8B4FE 约 1.8:1，
// 而这些胶囊上的字只有 7.5-12sp。朝墨色压 0.55 之后都能稳定过 5:1，色相也还在。
fun readableOn(color: Color): Color = lerp(color, Color(0xFF1E2320), 0.55f)

/**
 * 课程染色玻璃面上的「强调前景」——时间图标、+ 添加、备注、编辑按钮都用它。
 *
 * 主题绿 #BCE472 压在这种面上只有 1.15:1（实测：#BCE575 落在 #D3E7FC 上），
 * 等于隐形。这不是随手调色调出来的，是这版配色新开的口子：
 * 面染上课程粉彩之后，绿就不再是「亮底上的绿」，而是「浅蓝/浅青底上的绿」，
 * 亮度差被整个抹平了。
 *
 * 处理方式和普通前景一样：压暗。绿色课沿用深抹茶绿（保住「绿色课就是绿」的身份），
 * 其余走 [readableOn]，色相还在。最深的青（#208E9E 染到 #AAD4D8）也能到 3.98:1，
 * 比主题绿原来的 1.15:1 强一个数量级。
 *
 * 只有**背景/色块/进度条/勾选填充**能继续用原色，那些不需要当前景对比度。
 */
fun accentOnCourse(color: Color): Color =
    if (isGreenishColor(color)) TeaOnColor else readableOn(color)

// =========================================================================
// 卡片染色：不是「调 alpha」，是**解出该叠哪支颜色**
//
// 原来的做法是两件事叠在一起各调各的：先把课程粉彩用 [vivid] 提饱和、压明度，
// 再拿一个写死的 alpha（0.30）盖到卡面上。那样做有四个说不清的地方：
//
//   1. **HSL 的 L 不是感知明度**。L 同样是 0.62，黄仍然明显比蓝亮，
//      「统一明度」这个目标根本没达成——八门课的卡面深浅差一档，
//      扫列表时重的课比轻的课更抢眼，而这门课并没有更重要。
//   2. **同一个 alpha 在不同色相上不是同一件事**。同一明度下能承载的彩度上限是
//      1-|2L-1|，于是黄被洗成近白、蓝还留着重色，一屏卡片像出自两套配色。
//   3. **结果依赖衬底**。alpha 是「叠多少」而不是「叠成什么」，
//      衬底是壁纸时（白衬底 0.55 + 壁纸 0.45），换壁纸就换一批卡面色。
//   4. **正文对比度逐课不同**。底色深浅不齐，深色字压上去的对比度自然也不齐。
//
// 现在的做法反过来了：先定「卡面最终要长成什么样」（固定的感知明度与彩度），
// 再**反解**出在给定 alpha 下要叠哪支颜色。
//
//   感知上近似成立（OKLab 对小比例混合足够线性）：final = α·source + (1-α)·backing
//   ⇒ source = (final - (1-α)·backing) / α
//
// 解不出来的那部分（彩度超出 sRGB 色域）二分降下来，于是每门课都是
// 「同一个明度、同一个彩度上限内的那支最饱和的颜色」——色相各不相同，
// 但**重量一致**。这也让卡面上的深色正文在八门课上拿到同一个对比度。
// =========================================================================

/** 卡面最终落点：OKLab 明度。八门课共用，这是「等重」的唯一来源。 */
private const val CARD_TARGET_L = 0.905f

/** 卡面最终落点：OKLab 彩度。压得很低——卡面要的是暗示色，不是色块。 */
private const val CARD_TARGET_C = 0.085f

/**
 * 染色下面的衬底，用于反解时的底。
 *
 * 真底 = 白衬底（0.55）叠壁纸（0.45），壁纸会变。真底一变卡面就变，
 * 而那正是要消除的东西——所以这里**按最坏情况取一个偏暖的近白**当定值：
 * 它和真底的偏差不超过几个色阶，换来的是卡面颜色不再跟着壁纸漂。
 */
private val CardTintBacking = Color(0xFFF6F3EF)

/**
 * 近中性色的彩度阈值：低于它就不参与任何按彩度反解的重建。
 *
 * 反解是「按目标彩度解出源色」，对本来就没有彩度的颜色执行它等于凭空造一个色相：
 * 待办卡里「不关联课程」那一档的 #C9D0D8 看着是灰，实际 b > r（chroma ≈ 0.014），
 * 反解后会变成一整块饱和蓝，认起来像有关联课程。
 * 0.03 高于任何一支真·粉彩（最低约 0.08），只拦中性色。
 */
private const val NEUTRAL_CHROMA = 0.03f

/** 课程卡左侧竖条的落点明度：卡面的「加浓档」，比卡面低一档就该看得清。 */
private const val ACCENT_BAR_L = 0.70f

/**
 * 暖色相（橙→黄）竖条的落点明度，比其余色相高一大截。
 *
 * 原因是**黄和橙在低明度下会掉进橄榄/棕褐的死区**，这不是审美偏好而是物理事实：
 * 同一个 OKLab 明度、同一个彩度请求，八个色相解出来的结果差别很大——
 *
 * | 课程色 | L 0.70 时解出的竖条 | 抬亮后（L 0.80） |
 * |---|---|---|
 * | #FCA5A5 红 | `#E8777A` 干净的珊瑚红 | 不抬 |
 * | #FDBA74 橙 | `#D88B2B` 正常橙 | `#F1A248` |
 * | #FDE047 黄 | **`#B69E16` 土黄 / 橄榄褐** | **`#D6BE45` 干净的金黄** |
 *
 * 同一档明度下红是红的、黄是土的——因为黄的可感知亮度本来就高，
 * 压到 0.70 就已经是很深的一支黄。**所以明度不能一刀切**，
 * 暖色相要往上抬（这条和 [deepenCourse] 里 [hueCorrectForDeepen] 是同一个道理，
 * 那边处理的是压深方向，这里处理的是同一现象的另一半）。
 *
 * 代价：竖条和卡面是同一个色相，抬亮之后两者的明差必然变小（黄从 2.3:1 掉到 1.48:1）。
 * 这笔不靠把竖条压回去补（压回去又是土黄），而是靠**把卡面按用户要求加白**——
 * 暖色相卡面的彩度砍掉一半（`#EAE0A7` → `#EEE6C6`），竖条才浮得出来。
 */
private const val ACCENT_BAR_L_WARM = 0.80f

/** 竖条的彩度：比卡面（0.085）高一档，让它是识别线索而不是装饰线。 */
private const val ACCENT_BAR_C = 0.14f

/** 暖色相带：橙→黄（色相 40°~115°）。 */
private const val WARM_HUE_LO = 40f
private const val WARM_HUE_HI = 115f

/** 暖色相带上侧收口：绿（实测 154°）不能被算进暖色，否则它会跟着一起变亮。 */
private const val WARM_HUE_OUT_HI = 150f

/** 卡面在暖色相上的明度补偿。卡面本来就浅，补偿只需一小档。 */
private const val CARD_WARM_LIFT = 0.018f

/** 卡面在暖色相上的彩度补偿（往白里减一点彩度）。 */
private const val CARD_WARM_C_DROP = 0.045f

/**
 * 暖色相补偿强度，0 = 不补偿，1 = 全额补偿。**是一个窗口，不是一条半轴**。
 *
 * ⚠️ 这里踩过一次坑：第一版写成 `hue > 40° 就开始补偿、到 115° 封顶`，
 * 结果 115° 以上的色相全部拿满额补偿——绿课（实测色相 154°）的竖条
 * 从 `#48B776` 变成 `#79E5A1`，跟这次要修的黄色毫无关系却跟着变亮了。
 * 「黄需要抬明度」不等于「比黄更绿的东西也要抬」。
 *
 * 形状：40°→75° 线性爬升，75°~115° 满额，115°→150° 线性回落，之后归零。
 * 实测色相（OKLCh，度）：红 19.6 / 粉 -14 / 绿 154.4 / 青 -150 / 蓝 -108 /
 * 紫 -54 / **橙 66.3 / 黄 98.1**——窗口正好罩住橙黄两支，其余六支拿 0。
 */
private fun warmLift(hueDeg: Float): Float = when {
    hueDeg <= WARM_HUE_LO -> 0f
    hueDeg >= WARM_HUE_OUT_HI -> 0f
    hueDeg < 75f -> (hueDeg - WARM_HUE_LO) / (75f - WARM_HUE_LO)
    hueDeg <= WARM_HUE_HI -> 1f
    else -> (WARM_HUE_OUT_HI - hueDeg) / (WARM_HUE_OUT_HI - WARM_HUE_HI)
}

/**
 * 取色相（度）。**近中性色返回 null**——它没有色相可谈，理由见 [NEUTRAL_CHROMA]。
 */
private fun hueDegreesOf(color: Color): Float? {
    val src = oklab(color)
    if (kotlin.math.hypot(src.a, src.b) < NEUTRAL_CHROMA) return null
    return Math.toDegrees(kotlin.math.atan2(src.b, src.a).toDouble()).toFloat()
}

/**
 * 课程卡左侧那根圆角竖条的颜色。
 *
 * 原来直接画课程原色，于是八门课的竖条**轻重差一大截**：调色板里
 * #FDE047（黄）的 OKLab 明度约 0.86，压在同样是黄的卡面上几乎看不见；
 * #208E9E（青）约 0.55，就是一道压得很重的深条。同一条竖条在两个屏幕上
 * 一个消失、一个抢眼——这不是设计选择，是把课程色当成「已经可用」的颜色直接搬了过来。
 *
 * 现在它和卡面走同一个色相、同一套等重建：卡面固定落在 L 0.905，竖条固定落在 L 0.70，
 * **暖色相额外往上抬一档到 0.84**（见 [ACCENT_BAR_L_WARM]：不抬的话黄色会变成土黄）。
 * 八门课的竖条于是等重，而且和各自的卡面是「同一个颜色、深一档」的关系——
 * 扫列表时那道竖条指的是这门课，而不是「这门课原来是哪个色号」。
 *
 * 0.70 / 0.14 这一对是量出来的：竖条压在卡面上要能一眼分开，
 * 又不能深到把整张卡的注意力都拉走。实测冷色相 1.85~2.09:1，
 * 暖色相因为抬亮只到 1.48~1.62:1——**这条下限是刻意分色的**，理由见 [ACCENT_BAR_L_WARM]。
 */
fun accentBarColor(courseColor: Color): Color {
    val hueDeg = hueDegreesOf(courseColor) ?: return courseColor
    val lift = warmLift(hueDeg)
    return oklchToColor(
        ACCENT_BAR_L + lift * (ACCENT_BAR_L_WARM - ACCENT_BAR_L),
        ACCENT_BAR_C,
        Math.toRadians(hueDeg.toDouble()).toFloat()
    )
}

/**
 * 课程色叠到卡片上时要用的那一支**实色**。
 *
 * @param alpha 叠上去的不透明度，和调用处传给 [glassSurface] 的 `tintAlpha` 必须是同一个值——
 *   这里是按这个 alpha 反解的，两者对不上，解出来的颜色就不是想要的颜色。
 * @return 叠上去之后卡面落在 [CARD_TARGET_L] / [CARD_TARGET_C] 那一档的颜色。
 *   近灰（无色相可解）原样返回。
 */
fun cardTint(courseColor: Color, alpha: Float): Color {
    val a = alpha.coerceIn(0.02f, 1f)
    val hueDeg = hueDegreesOf(courseColor) ?: return courseColor
    // 近中性色原样叠，理由见 [NEUTRAL_CHROMA]。

    val back = oklab(CardTintBacking)
    val backChroma = kotlin.math.hypot(back.a, back.b)

    // 暖色相（橙/黄）补偿：卡面已经够浅，所以只抬一小档明度、并减一点彩度。
    // 减彩度就是用户说的「加多一点白」——黄是八支里最艳的一支
    // （原色 C 0.166，而红 0.103、蓝 0.096），同样的彩度目标落在黄色相上
    // 出来的 `#EAE0A7` 是米卡其，不是奶油黄。冷色相不受影响。
    val lift = warmLift(hueDeg)
    val targetL = CARD_TARGET_L + lift * CARD_WARM_LIFT
    val targetC = (CARD_TARGET_C - lift * CARD_WARM_C_DROP).coerceAtLeast(0f)

    val needL = ((targetL - (1f - a) * back.l) / a).coerceIn(0f, 1f)
    val needC = ((targetC - (1f - a) * backChroma) / a).coerceAtLeast(0f)
    return oklchToColor(needL, needC, Math.toRadians(hueDeg.toDouble()).toFloat())
}

/**
 * OKLCh → sRGB，**超出色域就二分降彩度**而不是直接截断。
 *
 * 直接逐通道 clamp 会让颜色偏色相：请求的蓝紫会被拉成偏红的紫，降彩度则保持色相不变、
 * 只少一点鲜艳度——而彩度本来就是「少一点更好」的那个量（卡面要暗示不要色块）。
 */
private fun oklchToColor(l: Float, c: Float, h: Float): Color {
    var lo = 0f
    var hi = c
    if (oklchInGamut(l, c, h)) lo = c
    else {
        repeat(14) {
            val mid = (lo + hi) / 2f
            if (oklchInGamut(l, mid, h)) lo = mid else hi = mid
        }
    }
    val (rl, gl, bl) = linearRgbFromOklch(l, lo, h)
    return Color(
        red = encodeSrgb(rl.coerceIn(0f, 1f)),
        green = encodeSrgb(gl.coerceIn(0f, 1f)),
        blue = encodeSrgb(bl.coerceIn(0f, 1f))
    )
}

private fun oklchInGamut(l: Float, c: Float, h: Float): Boolean {
    val (r, g, b) = linearRgbFromOklch(l, c, h)
    return r >= -0.0008f && r <= 1.0008f &&
            g >= -0.0008f && g <= 1.0008f &&
            b >= -0.0008f && b <= 1.0008f
}

/** OKLab 三元组。 */
private class Oklab(val l: Float, val a: Float, val b: Float)

/** sRGB（Compose 的 0..1 分量）→ OKLab。矩阵取 Björn Ottosson 的标准值。 */
private fun oklab(color: Color): Oklab {
    val r = linearize(color.red)
    val g = linearize(color.green)
    val b = linearize(color.blue)
    val l = cbrtF(0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b)
    val m = cbrtF(0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b)
    val s = cbrtF(0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b)
    return Oklab(
        l = 0.2104542553f * l + 0.7936177850f * m - 0.0040720468f * s,
        a = 1.9779984951f * l - 2.4285922050f * m + 0.4505937099f * s,
        b = 0.0259040371f * l + 0.7827717662f * m - 0.8086757660f * s
    )
}

/** OKLCh → 线性 sRGB（未做 gamma 编码，供色域判定用）。 */
private fun linearRgbFromOklch(l: Float, c: Float, h: Float): Triple<Float, Float, Float> {
    val a = c * kotlin.math.cos(h)
    val bb = c * kotlin.math.sin(h)
    val l3 = (l + 0.3963377774f * a + 0.2158037573f * bb).let { it * it * it }
    val m3 = (l - 0.1055613458f * a - 0.0638541728f * bb).let { it * it * it }
    val s3 = (l - 0.0894841775f * a - 1.2914855480f * bb).let { it * it * it }
    return Triple(
        4.0767416621f * l3 - 3.3077115913f * m3 + 0.2309699292f * s3,
        -1.2684380046f * l3 + 2.6097574011f * m3 - 0.3413193965f * s3,
        -0.0041960863f * l3 - 0.7034186147f * m3 + 1.7076147010f * s3
    )
}

private fun linearize(c: Float): Float =
    if (c <= 0.04045f) c / 12.92f else
        Math.pow(((c + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()

/** 立方根。`kotlin.math.cbrt` 只有 Double 重载，Float 侧得自己过一道。 */
private fun cbrtF(v: Float): Float = Math.cbrt(v.toDouble()).toFloat()

private fun encodeSrgb(c: Float): Float =
    if (c <= 0.0031308f) 12.92f * c else
        (1.055 * Math.pow(c.toDouble(), 1.0 / 2.4) - 0.055).toFloat()

/**
 * 按比例压明度、按倍数提饱和，得到一个更深的同色相颜色。
 *
 * 和 [vivid] 正好相反：[vivid] 是把太浅的粉彩压下来换彩度，这个是把颜色往下压，
 * 好让白字站得住（课表块上只有 8.5-10.5sp 的字，底色亮度差一点就过不了 4.5:1）。
 *
 * 两个维度一起动：明度按 [lightnessScale] 乘下去，饱和度按 [satBoost] 提上去。
 * 只压明度不补饱和的话，深色会变成闷灰；[vivid] 那条注释里「彩度 ≈ alpha × 彩度(源色)」
 * 的道理在这里反过来用——明度降了，同一色相本来能承载的彩度上限会涨，
 * 所以加深之后反而应该把饱和补回来，颜色才不会脏。
 *
 * 彩度上限仍是 1-|2L-1|，由 [fromHsl] 自己兜住，这里 clamp 只是防溢出。
 *
 * 调用方请用 [deepEnoughForWhite]，它会按对比度目标二分出合适的比例。
 * 输出色相经过 [hueCorrectForDeepen] 修正：橙黄区间（15°-55°）在低明度下
 * 会落到棕色/橄榄色的感知死区，精细修正后下推绕开脏色带，橙色转为夕阳珊瑚橙，黄色转为暖阳金。
 */
private fun deepenCourse(
    color: Color,
    lightnessScale: Float,
    satBoost: Float
): Color {
    val r = color.red
    val g = color.green
    val b = color.blue
    val maxC = maxOf(r, g, b)
    val minC = minOf(r, g, b)
    if (maxC == minC) return color // 纯灰没有色相可加深
    val l = (maxC + minC) / 2f
    val d = (maxC - minC) / 2f
    // 标准 HSL 饱和度：s = d / (1 - |2L - 1|)，分母按明度分两支写
    val denom = if (l > 0.5f) (2f - maxC - minC) else (maxC + minC)
    val s = if (denom <= 0f) 0f else d / denom
    val h = hue(r, g, b, maxC, minC)
    val hDeg = h * 360f

    // 暖色系（橙、黄 15°-55°）：饱和度适度补益至 1.60f，既消除发灰发暗的泥土感，
    // 又保持柔和轻透的粉彩/马卡龙淡雅调（避免 100% 满彩度导致的过于浓烈焦灼）；
    // 冷色系维持原 satBoost 1.45 保持通透自然的粉彩深调。
    val effectiveSatBoost = if (hDeg in DEEPEN_HUE_CORRECT_LO..DEEPEN_HUE_CORRECT_HI) 1.60f else satBoost

    val targetL = (l * lightnessScale).coerceIn(0f, 1f)
    val targetS = (s * effectiveSatBoost).coerceIn(0f, 1f)
    return fromHsl(hueCorrectForDeepen(h), targetS, targetL)
}

/**
 * 橙黄色相在压深时的色相精细调校（柔和偏淡调）。
 *
 * 核心设计目标：
 * 1. 橙色（原约 31°）：下推 ~11.0° 至 ~19.8°，呈现柔润淡雅的「轻暖淡珊瑚杏橙」（#EC9164），
 *    清爽明亮、柔和适度，绝无浓重刺眼感与暗焦糖感。
 * 2. 黄色（原约 50°）：微调 ~6.5° 至 ~44.5°，呈现轻盈温润的「淡暖阳鹅黄/金芒黄」（#E7B936），
 *    明亮纯粹、温润怡人，彻底告别沉重浓黄与暗浊感。
 * 3. 边界处（15° 与 55°）平滑衔接归零，红与绿完全不受影响。
 */
private fun hueCorrectForDeepen(h: Float): Float {
    val deg = h * 360f
    if (deg < DEEPEN_HUE_CORRECT_LO || deg > DEEPEN_HUE_CORRECT_HI) return h
    val shift = when {
        deg <= 30f -> {
            val t = (deg - DEEPEN_HUE_CORRECT_LO) / (30f - DEEPEN_HUE_CORRECT_LO)
            t * 11.0f
        }
        deg <= 50f -> {
            val t = (deg - 30f) / 20f
            11.0f - t * 4.5f // 30° 时偏 11.0°，50° 时偏 6.5°（黄落到 ~44.5° 柔暖淡鹅黄）
        }
        else -> {
            val t = (deg - 50f) / (DEEPEN_HUE_CORRECT_HI - 50f)
            6.5f * (1f - t)
        }
    }
    return ((deg - shift) / 360f).coerceIn(0f, 1f)
}

/** 色相修正区间下界（°）：15° 以下为红系，无需修正。 */
private const val DEEPEN_HUE_CORRECT_LO = 15f

/** 色相修正区间上界（°）：55° 以上为黄绿系，无需修正。 */
private const val DEEPEN_HUE_CORRECT_HI = 55f

/**
 * 把颜色按 [alpha] 比例保留、和白色混合，算出**实际显示**的那一档颜色。
 *
 * 注意 alpha 的方向是「保留多少原色」而不是「加多少白」：
 * [alpha] = 1 返回原色，[alpha] = 0 返回纯白。
 * 按 alpha 本身去理解会写出方向相反的插值（二分上下界、首末两档都搞错）。
 */
fun overWhite(color: Color, alpha: Float): Color {
    val a = alpha.coerceIn(0f, 1f)
    return Color(
        red = color.red * a + (1f - a),
        green = color.green * a + (1f - a),
        blue = color.blue * a + (1f - a),
        alpha = color.alpha
    )
}

/** 课程块上的白字不用纯白，和无待办档的深字取同一个色温，避免两种字看着像两套系统。 */
val CourseCellOnPending = Color(0xFFFCFCF8)

/**
 * 有待办（深色）档的次要文字色：教室号用的「白灰」。
 *
 * **这一档是主动放弃对比度的**，按用户明确要求——要的就是把教室号往后推、
 * 减轻它对视觉注意力的占用，所以定值时没有拿 WCAG 正文线当约束。
 *
 * 和标题白 [CourseCellOnPending] 的关系是用**对比度比值**定的，不是随手挑的灰：
 * 标题在最深的那支蓝（#3482DB）上是 3.83:1，教室号取标题的 0.69 倍 ≈ 2.65:1，
 * 刚好落到「一眼扫过去先读到课程名、再低头才看清教室」的位置，
 * 同时还不至于淡到看不出那行有字。
 *
 * 注意这比原来那个带绿的 #F0F4F2 更**中性**——原来是 R240/G244/B242 的冷白偏绿，
 * 压在偏绿的课程块上会和底色串味；换成几乎无色相的白灰之后，
 * 它不再和任何一门课的底色争夺注意力，只承担「这里还有一行小字」这一个职能。
 *
 * 代价（有意接受，不要顺手调亮）：8.5sp 教室号在深色档全程低于 AA，
 * 蓝课约 2.65:1、绿课约 2.67:1、青课约 3.05:1。
 * 无待办（浅色）档的教室号不受影响，[CourseKhakiMetaOnIdle] 仍然过 4.5:1。
 */
val CourseCellMetaOnPending = Color(0xFFD2D5D4)

/**
 * 无未完成待办（浅色）档的课程名颜色。
 *
 * **这一档同样是主动放弃对比度的**：用户要的是让「没未完成待办」的格子往后退，
 * 减轻它对视觉注意力的占用，所以定值时同样没有拿 WCAG 正文线当约束。
 *
 * 取「刚好看得见」的下限，约 1.98:1。这一档的底色亮度是**被钉死的**——
 * [pushAwayFrom] 会把深色那一档朝白推，直到和新字色的对比度达标，
 * 而深色那一档明度都在 0.18~0.22，所以八门课减淡之后底色基本都落在
 * 相对亮度 0.616 附近（实测粉 #F1C1DB = 0.620、紫 #DCC5F5 = 0.617）。
 * 底色钉在这个亮度上，字色才有「一个统一的退后程度」可言，
 * 不然每门课各自算一遍，八门课会退成八个深浅。
 *
 * 注意「白灰」压淡粉彩底是**做不到可见的**：#D2D5D4 的亮度 0.660 比底色还高，
 * 落在 #F1C1DB 上只有 1.06:1，等于把字抹掉。用户选定的折中是
 * 「取刚好看得见的下限」，所以这里用的是中灰而不是白灰——
 * 字仍然比底色深（0.286 < 0.616），方向没翻，只是退到了边缘。
 */
val CourseCellOnIdle = Color(0xFF8E9390)

/**
 * 无未完成待办档的教室号，比课程名再亮一档（也就是再退一档）。
 *
 * 沿用深色档的同一条比例规则：**次要文字取主文字对比度的 0.69 倍**。
 * 实测 1.36:1 对 1.98:1，比值 0.687。
 *
 * 字更亮但对比度更低，在浅底上这才是「更退后」——深色档那条
 * 「白字再暗一点就是更退后」的直觉在浅底上是反的，别照搬。
 */
val CourseCellMetaOnIdle = Color(0xFFADB2B0)

/**
 * 无未完成待办档的「卡其灰」**背景调亮旋钮**——注意它已经不是字色了。
 *
 * 它的唯一用途是当 [pushAwayFrom] 的第二参数，作用是把深色那一档朝白推到一个
 * 固定亮度上（相对亮度 ≈ 0.616），好让八门课的浅色档底色有统一的退后程度。
 * 之所以要钉死：字色改成 [CourseCellOnIdle] 之后已经退到 AA 以下的「刚好看见」，
 * 如果底色还随课程色各推各的，八门课的可见程度会差出一大截。
 *
 * 值本身不用改（仍然是当初按卡其灰教室号 4.5:1 定下的那个钉点），
 * 改了浅色档底色会跟着动、两档的层次关系就变了。
 */
val CourseKhakiMetaOnIdle = Color(0xFF565A4C)

/**
 * 把背景朝「离 [fg] 更远」的方向推（朝白），直到两者对比度达到 [target]。
 *
 * 用来给「浅底配浅字」兜底。粉彩青 #208E9E、淡红 #FCA5A5 这两支明度本来就低，
 * 卡其灰字压上去只有 2.2~4.2:1，但直接把底色刷成浅色调又会毁掉课程色身份。
 *
 * 二分的理由和 [deepEnoughForWhite] 一样：取刚好够用的那一档，不多提亮。
 * 本来就达标的颜色原样返回，所以 8 支粉彩里大多数根本不受影响。
 */
fun pushAwayFrom(bg: Color, fg: Color, target: Float = 4.5f): Color {
    if (contrastRatio(bg, fg) >= target) return bg
    // 注意 [overWhite] 的 alpha 语义是「保留原色的比例」：1 = 原色，0 = 纯白。
    // 所以二分的不变式是 lo=不满足（原色那端）、hi=满足（白那端），
    // 收敛出的是**还能保住最多原色**的 alpha。这里两个界的方向写反过一次，
    // 结果直接收敛到 0，无待办的格子整片渲成纯白、和页面底完全同色，
    // 肉眼只看到「卡片没了」，不采像素根本发现不了。
    var lo = 1f // overWhite(bg, 1) = 原色，不满足
    var hi = 0f // overWhite(bg, 0) = 全白，满足
    repeat(20) {
        val mid = (lo + hi) / 2f
        val candidate = overWhite(bg, mid)
        if (contrastRatio(candidate, fg) >= target) hi = mid else lo = mid
    }
    return overWhite(bg, hi)
}

/**
 * 把课程色压到「白字能站得住」的深度。
 *
 * 课表改成两档制之后，「有待办」这一档要**统一用白字**。但 8 色粉彩里有 7 档
 * 明度本来就高，白字压上去是隐形的（#93C5FD 只有 1.6:1），所以这一档不能只调 alpha，
 * 必须真的把底色压深——也就是「未完成 = 更深 + 白字」的那条规则。
 *
 * 压到多深不是拍脑袋定的，是**二分找出来的**：在明度比例上二分，取仍然满足
 * 目标对比度的最亮的那一档。三个好处：
 * 1. 每门课都保证白字过线，不靠逐个手调；
 * 2. 取的是「刚够用」的最亮档，黄绿这类天生亮的色相不会被压成一团黑；
 * 3. 本来就够深的色（粉彩青 #208E9E）原样返回，不会被无谓地加深。
 *
 * [satBoost] 在压深的同时补饱和：明度降下去之后同一色相能承载的彩度上限会涨
 * （还是 (1-|2L-1|)），不补的话深色会发闷、发脏。
 *
 * **默认 1.45 是为了「鲜艳」**：粉彩本身饱和度就高（0.66~0.96），压深之后不补，
 * 出来的深色看着像掺了灰的旧色块（实测蓝 #3269A6、绿 #2E7448 都偏闷）；
 * 补到彩度接近上限，才像「清爽的深色」而不是「脏色」。上限由 [fromHsl] 兜住。
 *
 * **默认目标是 3.8 而不是 4.5，是按用户明确选定的「适度压深」来的**：
 * 再深一点就不是这门课的颜色了（#93C5FD 压到 #2067B7 时已经很难和别的蓝区分），
 * 白字因此落在 3.8:1 —— 低于 AA 正文线，但课程名是 10.5sp 半粗体，
 * 在课表这种大色块上可读。**这是有意接受的降级，不是没算到**；
 * 同样受影响的还有那一档上的 8.5sp 教室号（约 3.4:1），汇报时必须一起说。
 *
 * 想要严格过 AA 时把 target 传 5.5 即可：白字 5.5:1、教室号 4.88:1，
 * 代价是颜色压得比设置页那条主题色竖条明显更深。
 *
 * **暖色区自动降档**：橙/黄（20°-55°）在低明度下脏感远比冷色严重，
 * 同样 3.8:1 的蓝仍是「深蓝」，黄已经是「土黄」。[warmHueTargetAdjust]
 * 会把暖色的有效目标线性降到 3.0，让深色版本停在干净的橙金色而不是棕泥色。
 */
fun deepEnoughForWhite(
    color: Color,
    target: Float = 3.8f,
    satBoost: Float = 1.45f
): Color {
    val effectiveTarget = warmHueTargetAdjust(color, target)
    if (contrastRatio(color, CourseCellOnPending) >= effectiveTarget) return color
    // lo = 仍满足对比度的最亮比例，hi = 已不满足的比例
    var lo = 0.04f
    var hi = 1.0f
    repeat(20) {
        val mid = (lo + hi) / 2f
        val candidate = deepenCourse(color, lightnessScale = mid, satBoost = satBoost)
        if (contrastRatio(candidate, CourseCellOnPending) >= effectiveTarget) lo = mid else hi = mid
    }
    return deepenCourse(color, lightnessScale = lo, satBoost = satBoost)
}

/**
 * 暖色区（橙/黄）的对比度目标下调。
 *
 * 为什么必须下调：
 * 相对亮度公式中绿色分量权重大 (0.7152)，黄色与橙色天生带强烈的红绿组合。
 * 如果强行按冷色的 3.8 对比度二分压暗，系统必须把亮度和明度大幅砍到 44% 以下，
 * 物理上立刻成为暗浊泥土/屎黄/焦泥琥珀。
 *
 * 暖色本身与白色在课表大色块（10.5sp 半粗体）上具备极高的色相知觉分离度，
 * 将橙色对比度目标适度放宽至 ~2.33、黄色放宽至 ~1.79，
 * 就能让深色档橙色呈现明亮柔润的轻淡珊瑚杏橙（#EC9164），让黄色呈现清亮轻盈的淡暖阳鹅黄（#E7B936），
 * 整体色调更加清淡柔和、舒目耐看。
 *
 * 只对色相 15°-55° 生效，冷色仍走调用方传入的 [target]（默认 3.8）。
 */
private fun warmHueTargetAdjust(color: Color, target: Float): Float {
    val r = color.red; val g = color.green; val b = color.blue
    val maxC = maxOf(r, g, b); val minC = minOf(r, g, b)
    if (maxC == minC) return target
    val hDeg = hue(r, g, b, maxC, minC) * 360f
    if (hDeg < DEEPEN_HUE_CORRECT_LO || hDeg > DEEPEN_HUE_CORRECT_HI) return target

    val drop = when {
        hDeg <= 30f -> {
            val t = (hDeg - DEEPEN_HUE_CORRECT_LO) / (30f - DEEPEN_HUE_CORRECT_LO)
            t * 1.45f // 15° 时无降档，30° 时降 1.45 (目标 3.8 -> 2.35，使橙色明亮偏淡)
        }
        hDeg <= 50f -> {
            val t = (hDeg - 30f) / 20f
            1.45f + t * 0.75f // 50° 时降 2.20 (目标 3.8 -> ~1.79，使黄色明朗偏淡)
        }
        else -> {
            val t = (hDeg - 50f) / (DEEPEN_HUE_CORRECT_HI - 50f)
            2.20f * (1f - t)
        }
    }
    return target - drop
}

/** WCAG 相对亮度。阈值 0.03928 是 sRGB→线性 的分段点。 */
private fun relativeLuminance(color: Color): Float {
    fun channel(c: Float): Float {
        return if (c <= 0.03928f) c / 12.92f else
            Math.pow(((c + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
    }
    return 0.2126f * channel(color.red) +
        0.7152f * channel(color.green) +
        0.0722f * channel(color.blue)
}

/** WCAG 对比度，两个色之间。用来决定底色上该压白字还是黑字。 */
fun contrastRatio(a: Color, b: Color): Float {
    val la = relativeLuminance(a)
    val lb = relativeLuminance(b)
    val hi = maxOf(la, lb)
    val lo = minOf(la, lb)
    return (hi + 0.05f) / (lo + 0.05f)
}

/**
 * 底色上该用哪一档前景：白字还是墨炭字。
 *
 * 不写死「深色配白、浅色配黑」，而是把两个候选都算一遍对比度取高的那个。
 * 课程色是 8 种粉彩，明度跨得很开（#FDE047 的 L≈0.86 到 #208E9E 的 L≈0.37），
 * 凭 `isLightColor` 的 0.55 阈值一刀切，在中间那几档上会选到明显更差的一边。
 *
 * 返回的是**该档色块上要用的主文字色**；次要文字（教室号）由调用方在主色基础上再降一档。
 */
fun contentOn(bg: Color): Color {
    val onLight = contrastRatio(bg, Color(0xFFFCFCF8))
    val onDark = contrastRatio(bg, Color(0xFF1E2320))
    return if (onLight >= onDark) Color(0xFFFCFCF8) else Color(0xFF1E2320)
}

private fun hue(r: Float, g: Float, b: Float, maxC: Float, minC: Float): Float {
    val d = maxC - minC
    val raw = when (maxC) {
        r -> (g - b) / d + if (g < b) 6f else 0f
        g -> (b - r) / d + 2f
        else -> (r - g) / d + 4f
    }
    return ((raw / 6f) % 1f + 1f) % 1f
}

private fun fromHsl(h: Float, s: Float, l: Float): Color {
    // 标准 HSL→RGB：C/X/m 三件套。注意 C 是彩度，不是 sd，
    // 写成 s*(l+0.5) 之类的近似会把颜色整体洗成灰调。
    val c = (1f - kotlin.math.abs(2f * l - 1f)) * s
    val hp = h * 6f
    val x = c * (1f - kotlin.math.abs(hp % 2f - 1f))
    val m = l - c / 2f
    val rr: Float
    val gg: Float
    val bb: Float
    when {
        hp < 1f -> { rr = c; gg = x; bb = 0f }
        hp < 2f -> { rr = x; gg = c; bb = 0f }
        hp < 3f -> { rr = 0f; gg = c; bb = x }
        hp < 4f -> { rr = 0f; gg = x; bb = c }
        hp < 5f -> { rr = x; gg = 0f; bb = c }
        else -> { rr = c; gg = 0f; bb = x }
    }
    return Color(
        red = (rr + m).coerceIn(0f, 1f),
        green = (gg + m).coerceIn(0f, 1f),
        blue = (bb + m).coerceIn(0f, 1f)
    )
}

/**
 * 周课表课程块的两档状态色。
 *
 * 视觉语言只有一条：**有待办 = 压深的课程色配白字，无待办 = 原本的浅粉彩配墨炭字**。
 * 深浅本身就成了「有没有待办」的信号，不需要第三档——「已结清」归到无待办那一档，
 * 只在徽标上留一个 ✓ 区分。
 *
 * **这一档不再有任何 alpha 常量**，两档的底色各自由一道**实色**算出来，
 * 调用处见 `CourseTimetableCell`：
 *
 *   * 有待办（深色档）= [deepEnoughForWhite] 压深，白字。因为白字压不住浅粉彩
 *     （#93C5FD 配白字只有 1.6:1）。面上另有一层 [CourseCellDeepFaceAlpha] 的玻璃，
 *     它只让 6% 的模糊壁纸透上来，不参与配色。
 *   * 无待办（浅色档）= [pushAwayFrom] 把上面那支深色朝白推到固定明度，字色退到下限。
 *
 * ⚠️ 这里原来有 `CourseCellPendingAlpha = 1.0f` / `CourseCellIdleAlpha = 0.70f`，
 * 注释还写着「让课表图例和课程块本体引用同一份定义」，但**两处都没有读者**：
 * 图例早已撤掉，格子也早就改走上面那两道实色算法。留着它们只会让人以为深色档
 * 的 alpha 仍然钉在 1.0，从而把玻璃面「修」回实色。已删，别加回来。
 */

// 6. 课程粉彩色系 (8 种粉彩色：红、粉、绿、青、蓝、紫、橙、黄)
val PastelRed = Color(0xFFFCA5A5)                    // 粉彩红 (Pastel Red #FCA5A5)
val PastelPink = Color(0xFFF9A8D4)                   // 粉彩粉 (Pastel Pink #F9A8D4)
val PastelGreen = Color(0xFF86EFAC)                  // 粉彩绿 (Pastel Green #86EFAC)
val PastelCyan = Color(0xFF208E9E)                   // 粉彩青 (Pastel Cyan #208E9E 降亮沉静清雅微调)
val PastelBlue = Color(0xFF93C5FD)                   // 粉彩蓝 (Pastel Blue #93C5FD)
val PastelPurple = Color(0xFFD8B4FE)                 // 粉彩紫 (Pastel Purple #D8B4FE)
val PastelOrange = Color(0xFFFDBA74)                 // 粉彩橙 (Pastel Orange #FDBA74)
val PastelYellow = Color(0xFFFDE047)                 // 粉彩黄 (Pastel Yellow #FDE047)

// 6. 辅助状态颜色
val AlertRed = Color(0xFFEF4444)                     // 紧急截止/告警

/**
 * 告警红的**文字档**，比 [AlertRed] 深一档。
 *
 * 为什么要有两支：#EF4444 是红-500，它压在本项目所有浅底上只有 **3.4:1**
 * （实机采样：淡红卡面 #FCF3F4 上 3.45、弹层白面 #FAFBFC 上 3.44）。
 * 拿它当图标色没问题，拿它当 11sp 正文就不行 —— WCAG 对小字要 4.5:1，
 * 而「失败：连不上浙大服务器」这种行恰恰是必须看清的。
 *
 * 所以文字往红-700 走一档：同一色相、视觉上仍然是「红」，但小字能过 AA
 * （实测 6.2:1）。**两支不要合并** —— 合并等于把所有红字都变深，
 * 连图标与 14sp 粗体标题那种本来合规的地方也会跟着变味。
 */
val AlertRedText = Color(0xFFB91C1C)                 // 告警文字专用（红-700）

// 7. 扁平化课表栅格色 (Flat Timetable Grid)
// 栅格线全部转**中性灰 + 半透明**：网格画在默认壁纸上，壁纸本身就是暖黄→淡紫的一整条色带，
// 冷灰绿的线压在暖调上会显脏（当初在薄荷青底上显脏，所以从暖米灰改成冷灰绿——
// 底色换了，这条理由就不成立了：现在该退回不带色相的灰，让线只做「分隔」不做「配色」）。
// 极简化之后栅格只剩**一种横线**：早上/中午/晚上的大节分割线整条删掉，表头那条
// 分隔线与标尺轴那条竖线也都并回 FlatGridLine，于是原「FlatGridLineStrong 沉一档」
// 这个色值**已删除**，别再加回来。
//
// 判据：栅格线只做「分隔」，不承担「说明什么」。给其中某几条单独加粗或加深，
// 换来的是「这里更重要」的暗示，可表头并不比别处重要、标尺轴左边的节次数字也
// 已经说清了自己的身份——那点差异化买的只是一道更抢眼的线，不是信息。
// 于是规律变成：**同一种线就同粗同色**，需要区分的地方靠位置与内容区分。
//
// ### 这几档的可见度是靠**底色**给的，不是靠自身深浅
//
// 栅格线压得越深，在壁纸上越显眼——但它们的作用只是「分隔」，不是「信息」，
// 太深就成了压在淡雅壁纸上的脏线。所以这里用的是**半透明**：线本身保持中性灰，
// 由 alpha 决定它在**任何底色**上留多少存在感，于是换壁纸也不用重算色值。
//
// ⚠️ 两轮改动留下的经验，别退回：
//
// 1. 纯白页面底时代这批线是 #E8EEEB 一档的「近白线画在近白底上」，可见度全靠底色当参照。
//    底换成壁纸后实测只剩 1.00~1.16:1 —— 数学上等于不存在，整张表读不出「几节课」。
// 2. 压深到不透明灰能救（1.32~1.50:1），但那是拿「更重的线」换可见度，
//    代价正好和「降低栅格线透明度」这条要求相反。
//
// 现在这一档是两头都要的解：**中性灰 + alpha 0.55**，实测 1.16~1.24:1 ——
// 比不透明灰明显轻，又还没掉进「线消失」的那一档。
// **下限在 1.15 左右**：再往下（alpha 0.35 → 1.10~1.15:1）线就贴回第 1 条那个坑。
// 判据不是 WCAG——栅格线不是文字，3:1 那套是给要读的信息用的；
// 这里要的是「能数清行」，所以按壁纸最亮处（左上暖黄）取参照。
val FlatGridLine = Color(0xFFC6C6C6)                     // 行分隔细线（0.5dp）：中性灰 + alpha 0.55
val FlatGridColumnLine = Color(0xFFD4D4D4)               // 竖向列分隔线：比横线更淡，竖线一多就成灰墙
val FlatGridNumber = Color(0xFF949494)                   // 节次水印数字：淡到只在空格里露一下

/**
 * 栅格线的统一不透明度。**所有** Flat* 线都在各自色值上乘这一档，而不是把 alpha
 * 写进各自的色值里——四条线的深浅关系（横线 / 表头线 / 竖线 / 数字）由色值决定，
 * 「整体多淡」由这一档决定，改透明度时只动这一个数。
 *
 * 0.55 是实测出来的：装机截图量到的线色是 #DAD8D7，压在壁纸上 1.20~1.22:1，
 * 比不透明灰（1.44~1.47:1）明显轻，又还没掉进「线消失」的那一档
 * （纯白底时代的原值 #E8EEEB 在壁纸上只有 1.01:1，等于不存在）。
 * **下限约 1.15**，再淡就数不清行了；实测 alpha 0.35 会掉到 1.10 左右，别再往下试。
 */
const val FlatGridLineAlpha = 0.55f

/**
 * 给栅格线套上统一的不透明度。
 *
 * 为什么不把 alpha 直接写进上面那四个色值（`Color(0xFFC6C6C6C6)`）：
 * 那样子看着更省事，但「线有多淡」就被焊死进每个色值里了，
 * 以后调透明度要改四处、且很容易漏掉某一处，导致四条线不再同步变淡。
 * 这里把「深浅关系」（谁更沉）和「整体多淡」（[FlatGridLineAlpha]）分开管：
 * 改色值调层级，改一个 alpha 调整体。
 */
fun Color.asGridLine(): Color = copy(alpha = FlatGridLineAlpha)
// ⚠️ FlatTodayColumn **已不再用作平涂底色**。「今天」那一列改成挂真·背景模糊
// （hazeEffect，见 ScheduleScreen 的 DayColumn），不再是硬涂一层色——
// 涂色只能靠不透明度表达「今天」，而压得越明显越像一块贴上去的色板、越不像
// 底子上的雾。模糊则是直接作用在壁纸上，颜色随壁纸走，整列自然融进去。
// 这个 token 保留仅为记录原值与来由，别再拿去当背景色用。
val FlatTodayColumn = Color(0xFFB8D2CB)              // 已废弃：原「今天」整列平涂底
// 「放假整列压一层底」的 FlatOffColumn 已删：一天放假要给整列 624.dp 高的网格铺一层
// 颜色，才能换来列头旁边一枚 11.dp 的小图标就能说完的事。七列一齐压底时整张表发灰，
// 而那条信息本身只是一个比特 —— 放在列头的图标上更省、也更像提示而不是状态。
// 原 FlatTodayHeader（淡抹茶表头底）已删：星期表头改成每列一个独立圆角块，
// 今天那一块直接填主题绿 LimeGreenPrimary 配深墨字，淡抹茶底这个中间档用不上了。
// 新增两个 token 见文件末尾「待办勾选圈」。

// =========================================================================
// 待办卡片左侧的勾选圈
// =========================================================================

/**
 * 未勾选时勾选圈的**填充**：一个纯白圆，不描边。
 *
 * 沿革：
 * 1. `Color.Transparent` + `outline`(#DDE6E2) 描边 —— 压在染色卡面上只有约 1.05:1，等于没有圈。
 * 2. 白填充 + [TaskCheckIdleBorder] 中灰边（#98A29E，1.5dp）—— 读得出轮廓，但用户明确表示
 *    「灰色圆边框丑」，这条已作废，圆圈只剩填充。
 * 3. 现在：纯白圆，无描边。alpha 从 0.92 提到 1.0——半透明白会透出底下的染色色相，
 *    圈就不再是白的了；要白就白彻底。
 *
 * 代价（用户已知取舍）：白圈靠「比卡面亮」读出轮廓，不是靠描边。在很浅的卡面上
 * （染色面 L≈0.82、嵌套挖入面 #F4F6F7）对比度有限，属于纯装饰元素，不承载状态文字。
 * 要圈重新「有轮廓」只有一条路：给未完成态换非白色的填充，而不是加边。
 */
val TaskCheckIdleFill = Color(0xFFFFFFFF)

/**
 * 星标的颜色：清亮黄 [#FFC53D]。用户要的是「黄色、醒目清新」，这一档就是那个黄。
 *
 * 它**不满足**图形元素的 3:1：实测压在蓝课卡面 #D8EBFF 上只有 1.47:1、
 * 弹层白底 1.79:1（亮度和近白底几乎一样）。这是刻意接受的结果，不是没算：
 * 黄压在粉蓝卡面上色相差接近 180°，识别靠的是色相而不是亮度差——
 * 和「浅绿 #BCE472 在白底 1.4:1 会整个消失」是两种完全不同的情况
 * （那一条色相和底色只差十几度，才是真的隐形）。
 *
 * 代价要认：**在很浅的卡面上星星会偏柔和，不是强对比**。
 * 要它更硬只能压深（#B37E00 是 2.9:1），但那就已经是琥珀不是黄色了。
 *
 * 不用主题绿：#BCE472 压近白底 1.4:1、压染色卡面 1.15:1，星形会整个消失
 * （见「主题绿只能当填充色」那条约定）。
 *
 * ---
 *
 * **也用作清单「按星标」组头的色块**（2026-10-15）：那道 5×20dp 的圆角色标
 * 原本是更深的一档 #D08F00（白底 2.76:1，理由是「亮黄做这道竖条等于没画」），
 * 用户要它亮一些，于是换成和星形同一支：2.76:1 → 1.58:1，色块比原来难找，
 * 但仍是这一组唯一的黄色信号。那支 #D08F00 已随之删除。
 *
 * **只用色块、不用标题**：组头标题「已标星」试过也走本支（1.58:1），
 * 14sp 粗体在近白页面上直接淡没了，所以退回普通墨色。组头是列表里的主要
 * 地标，「能读出来」优先于「和色块同色」。
 *
 * 应用：
 * - **别按 3:1 把色块压深「修对比度」**。这是知情选的，要改先问。
 * - 纯白是 App 内对浅黄最友好的底，所以 1.58:1 已是本支黄的上限；
 *   再「亮一点」只会更低（#FEDE4F 是 1.33:1，近白页面上等于没有）。
 */
val StarInk = Color(0xFFFFC53D)

// =========================================================================
// Backward Compatibility Mappings
// =========================================================================
val CyanAccent = LinenGreenPrimary
val PaletteCyanPrimary = LinenGreenPrimary
val PaletteCyanVivid = LakeBlueAccent
val PaletteCyanDark = LinenGreenDark
val PaletteCyanContainer = LinenGreenContainer
val PaletteCyanOnContainer = LinenGreenOnContainer

val PaletteWhite = WarmCreamSurface
val PaletteWhiteSoft = WarmRiceWhite
val PaletteWhiteSurface = WarmCreamSurface
val PaletteGrayLight = WarmCreamSurfaceVariant
val PaletteGrayBorder = WarmCreamBorder
val PaletteGrayMuted = TextMuted
val PaletteBlackSecondary = TextSecondary
val PaletteBlackPrimary = TextPrimary
val PaletteBlack = Color(0xFF000000)

val SkyBluePrimary = LinenGreenPrimary
val SkyBluePrimaryContainer = LinenGreenContainer
val SkyBlueOnPrimaryContainer = LinenGreenPrimary
val SkyBlueSecondary = LakeBlueAccent
val SkyBlueSecondaryContainer = LakeBlueContainer
val SkyBlueOnSecondaryContainer = LakeBlueOnContainer
val SkyBlueTertiary = LakeBlueAccent
val SkyBlueTertiaryContainer = LakeBlueContainer
val SkyBlueBackground = WarmRiceWhite
val SkyBlueSurface = WarmCreamSurface
val SkyBlueSurfaceVariant = WarmCreamSurfaceVariant
val SkyBlueOnSurface = TextPrimary
val SkyBlueOnSurfaceVariant = TextSecondary
val SkyBlueOutline = WarmCreamBorder
val SkyBlueOutlineVariant = WarmCreamBorderLight

val SoftBluePrimary = SkyBluePrimary
val SoftBluePrimaryContainer = SkyBluePrimaryContainer
val SoftBlueOnPrimaryContainer = SkyBlueOnPrimaryContainer
val SoftBlueSecondary = SkyBlueSecondary
val SoftBlueSecondaryContainer = SkyBlueSecondaryContainer
val SoftBlueOnSecondaryContainer = SkyBlueOnSecondaryContainer
val SoftBlueTertiary = SkyBlueTertiary
val SoftBlueTertiaryContainer = SkyBlueTertiaryContainer
val SoftBlueBackground = SkyBlueBackground
val SoftBlueSurface = SkyBlueSurface
val SoftBlueSurfaceVariant = SkyBlueSurfaceVariant
val SoftBlueOnSurface = SkyBlueOnSurface
val SoftBlueOnSurfaceVariant = SkyBlueOnSurfaceVariant
val SoftBlueOutline = SkyBlueOutline
val SoftBlueOutlineVariant = SkyBlueOutlineVariant
