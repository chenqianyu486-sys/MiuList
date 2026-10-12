package com.example.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeDefaults
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

// =========================================================================
// 纯白底 + 粉彩染色毛玻璃卡片
//
// 这一版把「页面底提供颜色、玻璃面只做透光」的老结构整个翻了过来。
//
// 以前页面底是 Mesh Gradient（抹茶绿→白）+ 六团色斑，于是玻璃面板必须做
// 「屏幕对齐重绘」：在自己的 drawBehind 里按本面板在根坐标的偏移，把同一张
// 背景再画一遍，背后的颜色透过半透明白面显出来。自定义壁纸也是同一套机制。
//
// 现在页面底是一张纯白，**重绘一张白底等于没重绘**，这套机制连同 MeshPaint /
// buildMeshPaint / drawBackdrop / withWallpaper 一起删掉了。颜色的来源从
// 「背景」搬到了「面」上：
//
//   * 页面底 = 纯白，负责干净和留白；
//   * 卡片面 = 半透明粉彩，透出底下那片白，于是是「粉彩染色毛玻璃」而不是实色块；
//   * 课程卡把 tint 换成这门课自己的粉彩，颜色就有了语义来源，不必再靠色条认课。
//
// haze 保留，但它的作用变了。页面底纯白，模糊一张白纸等于没模糊，所以卡片
// 的玻璃感不再指望它——靠的是「材质三件套」：磨砂颗粒、卡压卡时的真模糊、
// 以及边缘那圈折射高光。三样都不需要背景提供颜色。
//
// haze 真正出效果的地方是**卡片叠卡片**：课表今日页里，内层待办卡是压在
// 染好色的课程卡上面的，那一层才是货真价实的毛玻璃。底部浮岛压在滚过去的
// 卡片上同理。
// =========================================================================

/** 页面底色。纯白，不是「接近白」——所有层次都交给卡片面去挣。 */
val CanvasPureWhite = Color(0xFFFFFFFF)

/**
 * haze 的兜底底色。
 *
 * hazeSource 只录自己的内容，页面底在它外面，所以「岛下面没有卡片」的像素在源层里
 * 是全透明的，直接糊会糊出一块透明洞，必须显式给 haze 一个填充色。现在页面底是纯白，
 * 就填纯白。haze 在 backgroundColor 没给时会直接抛 IllegalArgumentException，
 * 所以这里不能给 Color.Unspecified。
 */
private val HazeFallbackBg = CanvasPureWhite

/**
 * haze 的共享状态：MainActivity 在包住整个页面的 Box 上 hazeSource，
 * 需要真·背景模糊的玻璃面板从这里取状态去 hazeEffect。
 *
 * 默认 null = 没有开 haze。弹层（Dialog/ModalBottomSheet）跑在独立窗口里，
 * 拿不到这个 Local，正好落回平涂，功能不会崩。
 */
val LocalGlassHazeState = staticCompositionLocalOf<HazeState?> { null }

/**
 * 玻璃面的默认染色：中性冷灰，接近白。
 *
 * 中性面给「不隶属于某门课」的容器用（统计卡、设置项、筛选行、浮岛）。
 * 它必须足够淡——页面底已经是纯白了，面再深一点整屏就发闷；淡到几乎看不见也不行，
 * 那卡片就没有边界了。这档刚好把卡片从白底上托起来，又不抢内容的注意力。
 */
val GlassTintNeutral = Color(0xFFEDF1F5)

/**
 * 玻璃面不透明度（中性面）。
 *
 * 中性色本身已经很淡（#EDF1F5），靠不透明度把它从纯白底上托起来。
 *
 * 0.72 是上一版的手感：卡片和纯白底之间差出一档灰，整屏结构清楚但偏闷。
 * 用户要「通透简约清新」，这里压到 0.55——纯白从底下透上来的比例更大，
 * 卡片像蒙了一层薄雾而不是一块面板。
 *
 * 代价是明确的：面越淡，卡片和纯白底的边界越弱，纯粹靠大圆角和这一层薄雾撑着。
 * 0.55 → 0.48 是能压到的下限附近了。中性底色 #EDF1F5 本身就是 93% 白，
 * alpha 每降 0.1 出来的色差只有 2~3 个色阶（实测 0.72→0.55 才从 #F2F5F8 走到
 * #F5F7FA），再往下这张卡就直接消失在大圆角里了。中性面想通透，
 * 瓶颈在底色本身，不在透明度。
 */
private const val GLASS_FACE_ALPHA = 0.48f

/**
 * 课程色的染色强度。
 *
 * 课程色不能原样铺满一张卡：调色板里 #208E9E「粉彩青」的相对亮度只有 0.42，
 * 按 72% 铺出去就是一块重青，压得深色字全糊住，那不是毛玻璃是色块。
 *
 * 现在压到 0.24 才是「染」的效果——朝白合成之后，明度天然被拉平，
 * 八种颜色都落在同一档很浅的区间里：青落到 #CCE5E8，黄落到 #FEFADF，
 * 深色文字在哪种上面都读得出来，颜色本身还在，但只做暗示不做配色。
 *
 * 彩色卡是整屏最「重」的元素（中性卡底色接近白，反而不显眼），
 * 所以「通透」这件事主要靠这一档在扛。往下降还会顺带把正文对比度一起提上来，
 * 因为底越浅，深色字读得越清楚，方向上是双赢的。
 *
 * 注意这两档是分开的：中性面底色本来就淡，要 0.55 才托得住；
 * 课程色底色浓，再压就几乎没颜色了，只留 0.30。
 */
const val CourseTintAlpha = 0.20f

/**
 * 课程卡是否把染色往「鲜艳」推一档。
 *
 * 用户要的是「鲜艳但通透」，这两件事只调 alpha 是打架的：彩度 ≈ alpha × 彩度(源色)，
 * 光降 alpha 会把颜色洗淡，正好和鲜艳相反。所以课程卡一律先过一遍 [vivid]
 * （饱和拉满 + 明度封顶 0.62），再用 0.20 的透明度染上去，
 * 净效果是颜色更足、卡面更透。
 *
 * 中性面不走鲜艳：它的底色 `#EDF1F5` 彩度本来就接近 0，过一遍 vivid 只会平白染灰。
 */
const val CourseTintVivid = 1f

/**
 * 嵌在染色卡里面的内层卡片，用近白「挖入」时的面不透明度。
 *
 * 内层绝不能再染同一个课程色：同色相叠两次只会更深（实测外层 #D6E9FE 会叠成
 * #C2DFFD），层次正好反过来。反过来用近白压在染色面上，层次靠「浅在深上」撑。
 *
 * 数值要跟着 [CourseTintAlpha] 一起调：外层越淡，内层就得越白，
 * 否则两层都接近纯白，卡片会糊成一片。现在 0.24 / 0.72 这一对，
 * 内外差出一档能看出来的明度（青课：外层 #C9E4E8，内层 #EFF7F9）。
 */
const val NestedFaceAlpha = 0.72f

// =========================================================================
// 玻璃的「材质三件套」
//
// 页面底是纯白，模糊一张白纸等于没模糊，所以卡片不能只靠 hazeEffect 出效果。
// 真实磨砂玻璃的质感其实来自三样**不依赖背景**的东西，这三样就是这里配的：
//
//   1. 颗粒（GlassGrain）——磨砂表面把光散射成细密的噪点，缺了它，
//      半透明的卡面就只是一块淡色板，怎么调 alpha 都出不来玻璃感。
//   2. 模糊（GlassBlurRadius）——背后没东西时看不出来，但卡片压卡片时立刻见效：
//      课表里内层待办卡叠在染色课程卡上，那层才是真·毛玻璃。
//   3. 边缘折射高光（GlassRim*）——玻璃的边会聚光，比面本身亮一线。
//
// 三样都不碰页面底色，纯白背景不动。
// =========================================================================

/** 卡片级真背景模糊半径。卡压卡的地方（课表内层待办卡）靠它出纵深。 */
val GlassBlurRadius = 16.dp

/** 磨砂颗粒强度。之前写死 0f，理由是「像老照片」，现在反过来了——没有颗粒就不是玻璃。 */
private const val GlassGrain = 0.055f

/** 边缘折射高光的粗细。0.5dp 是发丝级存在感刚好，不至于看起来像画了框。 */
private val GlassRimWidth = 0.5.dp

/** 边缘高光不透明度。白内线压在染色面上要比面亮一点才看得出来。 */
private const val GlassRimAlpha = 0.55f

// 形状：现代极简靠大圆角，不靠描边和阴影堆叠
val GlassCardShape = RoundedCornerShape(20.dp)
val GlassIslandShape = RoundedCornerShape(28.dp)
val GlassChipShape = RoundedCornerShape(12.dp)

/**
 * 冷调发丝边，白底上唯一能勾出轮廓的东西。
 *
 * 以前这里是半透明白（#1FFFFFFF），那在彩色 Mesh 背景上是「玻璃下缘反光」；
 * 页面底换成纯白之后，白压白等于没有边，chip 会整个糊掉。改成一档冷灰，
 * 存在感刚好够勾边，不至于看起来像描了框。
 */
val GlassEdgeBottom = Color(0xFFE3E9EF)

/** 页面级 chip / 小控件的底色。比卡片面浅一档：它们是筛选器，不该和内容卡抢层。 */
val ChipSurfaceNeutral = Color(0xFFF1F5F9)

/**
 * 页面底层：纯白 + 内容。
 *
 * 放在 Scaffold 之外、内容之下，且 Scaffold 的 containerColor 必须是透明。
 * 以前这层负责画 Mesh Gradient，现在只有一块白——但仍然保留这层容器，
 * 因为将来若要换页面底色，只需改这一处，不用动每个 screen。
 */
@Composable
fun AppBackgroundLayer(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(CanvasPureWhite),
        content = content
    )
}

/**
 * 玻璃表面（Glassmorphism），扁平版 + 粉彩染色。
 *
 * 扁平化：不描边、不投影、不做上亮下暗的受光面，全靠大圆角 + 一块半透明面撑着层次。
 * 面里也没有任何渐变——渐变本身就是一种「光影」，要扁平就别在面里藏一条明暗轴。
 *
 * 只有两层：
 * 1. blurRadius > 0 且拿得到 haze 状态：hazeEffect 真·背景模糊（身后是别的 UI 元件，
 *    浮岛就属于这种）；否则背后只有纯白，不需要糊，直接平涂；
 * 2. 一块半透明的 [tint] 面 —— 纯白从底下透上来，所以是「染色」而不是实色块。
 *
 * @param tint 面的染色。默认中性冷灰；**课程相关的卡片要传这门课自己的粉彩**，
 *   这样颜色本身就是「这是哪门课」的标识，不必再靠一条色条去认。
 * @param tintAlpha 染色强度。中性面用 GLASS_FACE_ALPHA（要够实才托得住），
 *   课程色要压到 [CourseTintAlpha]（原色直接铺会变成色块而不是玻璃）。
 * @param blurRadius 真背景模糊半径。> 0 才生效，且需要祖先上有 hazeSource。
 * @param vividness 染色的鲜艳度提升。0 = 原色，1 = 满饱和且明度封顶（见 [vivid]）。
 *   课程卡传 [CourseTintVivid]，中性面保持 0。
 * @param faceAlphaScale 面不透明度倍率，默认 1 = tintAlpha 的值。要「压扁」某个面板
 *   （比如已完成项）就往下调。
 * @param hazeWash 糊完之后再叠的一层白。原本写死 0.24，是为浮岛设计的——
 *   它背后是滚过去的卡片和文字，不洗白就读得出底下的字。但这张卡一旦被所有卡片
 *   继承，卡面就等于被统一洗淡：实测内外两层待办卡被拉平到同一个 #EEF6FF，
 *   层次直接没了。所以默认 0，只有浮岛显式要。
 *
 *   现在它还兼着第二个任务：**压住背光的亮度起伏**。浮岛背后是滚动内容，深色文字
 *   被糊成一片灰，文字行一进一出岛面就跟着亮一下暗一下，滚快了就是频闪。
 *   洗白是线性把背光往白拉，等于直接压缩这段动态范围，是三个手段里最有效的一个。
 *   具体数值和推导见 `Surfaces.FloatingIslandNav`。
 * @param noiseFactor 磨砂颗粒强度，默认 [GlassGrain]。颗粒本身不闪（haze 用的是一张
 *   静态噪点纹理，不是逐帧随机），但它叠在会动的背光上会跟着抖，浮岛要按背光
 *   的稳定程度自己拿捏。
 * @param hazeFloor 亮度地板强度，0 = 不要。见 [HazeFloorColor]：这是**限定突变范围**
 *   的手段，和 [hazeWash]「整体压暗度」是两种思路，别混着用。
 */
@Composable
fun Modifier.glassSurface(
    shape: Shape = GlassCardShape,
    tint: Color = GlassTintNeutral,
    tintAlpha: Float = GLASS_FACE_ALPHA,
    blurRadius: Dp = GlassBlurRadius,
    vividness: Float = 0f,
    faceAlphaScale: Float = 1f,
    hazeWash: Float = 0f,
    noiseFactor: Float = GlassGrain,
    hazeFloor: Float = 0f,
): Modifier {
    val hazeState = LocalGlassHazeState.current
    val useHaze = blurRadius > 0.dp && hazeState != null
    // 鲜艳度是往 HSL 里推的，只有二元开关时才付这次计算
    val faceColor = if (vividness > 0f) vivid(tint) else tint

    return this
        .clip(shape)
        .then(
            if (useHaze) {
                // A. 真·背景模糊 + 磨砂颗粒
                Modifier.hazeEffect(
                    state = hazeState!!,
                    style = hazeStyle(
                        wash = hazeWash,
                        floor = hazeFloor,
                        blurRadius = blurRadius,
                        noise = noiseFactor,
                    ),
                )
            } else {
                Modifier
            }
        )
        .background(
            color = faceColor.copy(alpha = (tintAlpha * faceAlphaScale).coerceIn(0f, 1f)),
            shape = shape
        )
        // C. 边缘折射高光：玻璃的边会聚光，比面本身亮一线。
        // 这是**白**描边不是深色描边——深色描边是「画了个框」，白色内线是材质本身，
        // 和之前「取消描边」那条不冲突，也是玻璃感最省力的一条线索。
        .border(GlassRimWidth, Color.White.copy(alpha = GlassRimAlpha), shape)
}

/**
 * 亮度地板的颜色：背光里**比它暗**的像素一律被抬到它，比它亮的像素原样通过。
 *
 * 这是「限定突变范围」而不是「整体压暗」：
 *
 *   * 深灰正文被糊出来的那片灰（约 0.75 亮度）在地板下面 → 一律钳到 0.89，
 *     变化范围被压成 0，文字的进出不再引起任何亮度起伏，频闪的源头直接没了；
 *   * 粉彩课程卡（如 `#CCE5E8`，亮度约 0.87 但**逐通道**都接近地板）在地板边缘
 *     或之上 → 几乎原样透过，颜色还在，只是最暗的那一档被削平；
 *   * 纯白页面底在地板之上 → 完全不动。
 *
 * 选 0.89 这一档（#DEE4EA）是卡在「文字灰」和「粉彩卡」之间：再往上，课程色就
 * 会被一起钳平，玻璃里就没颜色了；再往下，文字就钳不住，闪会回来。
 *
 * 注意它是**逐通道**取 max（[BlendMode.Lighten]），不是整体提亮：抬高的是每个通道
 * 各自的下限，所以浅色卡的色相基本保得住，只有真正偏暗的像素被拉平。
 */
private val HazeFloorColor = Color(0xFFDEE4EA)

/**
 * 组装 haze 样式：模糊 → 亮度地板 → 洗白 → 颗粒。
 *
 * 顺序不能乱。地板必须在**模糊之后**：haze 的 tint 是用
 * `RenderEffect.createBlendModeEffect` 链在模糊结果上的，先钳再糊会把钳平的边界
 * 又糊回别人的颜色里，等于白钳。
 */
private fun hazeStyle(
    wash: Float,
    floor: Float,
    blurRadius: Dp,
    noise: Float,
): HazeStyle {
    val base = HazeDefaults.style(
        // 源层没录到内容的像素（卡片之间的空隙）用页面底色填上
        backgroundColor = HazeFallbackBg,
        // 染色面已经单独画在上一层了，这里只在浮岛需要时补一点白
        tint = HazeDefaults.tint(Color.White.copy(alpha = wash)),
        blurRadius = blurRadius,
        noiseFactor = noise,
    )
    if (floor <= 0f) return base
    return base.copy(
        tints = listOf(
            HazeTint(color = HazeFloorColor.copy(alpha = floor), blendMode = BlendMode.Lighten)
        ) + base.tints
    )
}
