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

/**
 * 把粉彩往「鲜艳」推一档，**明度和饱和度两个维度一起动**：
 * 饱和度拉到上限，明度封顶 0.62。
 *
 * 只提饱和是没用的——这 8 个粉彩的 HSL 饱和度本来就有 0.66~0.96，蓝紫甚至已经
 * 接近满值。它们「粉」的原因是**明度高**（0.62~0.86），而同一明度下能承载的
 * 彩度上限是 (1-|2L-1|) 决定的：L=0.78 的蓝最多只有 0.43 的彩度空间，
 * 提到满饱和也只从 106 走到 110。把 L 压到 0.62 之后彩度空间涨到 0.76，
 * 同一个透明度下能染出接近两倍的彩色。
 *
 * 为什么必须同时降 alpha：彩度 ≈ alpha × 彩度(源色)。只降 alpha 会把颜色往白里洗，
 * 正好和「鲜艳」相反；只提源色彩度又会把卡面染重。两件事一起做才是
 * 「颜色更足、但卡面更透」。
 *
 * 明度封顶而不是压暗到固定值：调色板里 #208E9E 本身就暗（L≈0.37），
 * 再乘一个固定系数只会更闷。按上限截断才是一视同仁的。
 */
fun vivid(color: Color): Color {
    val r = color.red
    val g = color.green
    val b = color.blue
    val maxC = maxOf(r, g, b)
    val minC = minOf(r, g, b)
    if (maxC == minC) return color // 纯灰没有色相可提
    val l = (maxC + minC) / 2f
    if (l <= VIVID_L_MAX) {
        // 明度本来就不高，只需要把饱和度拉满
        return fromHsl(hue(r, g, b, maxC, minC), 1f, l)
    }
    return fromHsl(hue(r, g, b, maxC, minC), 1f, VIVID_L_MAX)
}

/** [vivid] 的明度上限。再往下彩度虽高，卡面就不像「薄雾」而像色块了。 */
private const val VIVID_L_MAX = 0.62f

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
    val targetL = (l * lightnessScale).coerceIn(0f, 1f)
    val targetS = (s * satBoost).coerceIn(0f, 1f)
    return fromHsl(hue(r, g, b, maxC, minC), targetS, targetL)
}

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
 */
fun deepEnoughForWhite(
    color: Color,
    target: Float = 3.8f,
    satBoost: Float = 1.45f
): Color {
    if (contrastRatio(color, CourseCellOnPending) >= target) return color
    // lo = 仍满足对比度的最亮比例，hi = 已不满足的比例
    var lo = 0.04f
    var hi = 1.0f
    repeat(20) {
        val mid = (lo + hi) / 2f
        val candidate = deepenCourse(color, lightnessScale = mid, satBoost = satBoost)
        if (contrastRatio(candidate, CourseCellOnPending) >= target) lo = mid else hi = mid
    }
    return deepenCourse(color, lightnessScale = lo, satBoost = satBoost)
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
 * 放在 theme 层而不是各自的 Composable 里，是为了让课表图例和课程块本体
 * 引用同一份定义——以前两边各写各的 alpha，改了一边另一边就悄悄对不上。
 *
 * 未完成那一档不是「透明度更实」，而是走 [deepEnoughForWhite] 真正压深，
 * 因为白字压不住浅粉彩（#93C5FD 配白字只有 1.6:1）。
 */
const val CourseCellPendingAlpha = 1.0f  // 有待办（未完成）：压深配白字
const val CourseCellIdleAlpha = 0.70f    // 无待办：原本浅粉彩配墨炭字

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

// 7. 扁平化课表栅格色 (Flat Timetable Grid)
// 栅格线全部转冷调：网格现在画在冷色玻璃面板上，原来那套暖米灰（#EDE9E1/#D9D2C2）
// 在薄荷青背景上会显脏，是「复古感」的一大来源。
// 极简化之后栅格只剩**一种线**：早上/中午/晚上的大节分割线整条删掉，
// 所以原来的 FlatGridLineStrong 沉一档不再有第二档可对应，留给表头那条分隔线复用。
val FlatGridLine = Color(0xFFE8EEEB)                 // 行分隔细线（0.5dp）：冷灰绿，只做节奏不抢戏
val FlatGridLineStrong = Color(0xFFDDE6E1)           // 表头与主体之间的分隔线：比行线沉一档就够
val FlatGridColumnLine = Color(0xFFF0F4F2)           // 竖向列分隔线（0.5dp）：比横线更淡，竖线一多就成灰墙
val FlatGridNumber = Color(0xFFA9B5B0)               // 节次水印数字：淡到只在空格里露一下
val FlatTodayColumn = Color(0xFFEFF5F2)              // 「今天」整列平涂底（冷白），无课格子一眼可辨
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
