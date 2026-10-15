package com.example.ui.theme

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeDefaults
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import kotlinx.coroutines.delay

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
 * 切页转场里「主体卡片入场」的**总进度**（0f → 1f）。
 *
 * 这是整页一条的时间轴，不是「每张卡片各自的进度」：每张卡片按自己在页面上
 * 从上到下的序号，从这条轴上切出自己的窗口（见 [cardStaggerAlpha]）。节奏因此
 * 只由 [CARD_STAGGER_STEP] 一个数决定，改一处整页跟着变。
 *
 * 给的是 [State] 而不是 Float，是为了让卡片把 `.value` 读到 **draw 阶段**去：
 * 只触发重绘，不触发重组。一屏十几张卡片每帧重组一次是不能接受的。
 * 默认值恒为 1f——首帧渲染这类不在转场里的场合直接满显示。
 */
val LocalCardRevealProgress = compositionLocalOf<State<Float>> { mutableStateOf(1f) }

/**
 * 相邻两张卡片的错峰步长，占总时间轴的比例。
 *
 * 它不是直接当偏移用的，而是喂给 [cardStaggerAlpha] 里的软上限，所以列表再长
 * 也不会出现「后面一整批同时淡入」——步长会自己被压缩掉。
 *
 * 0.20 配 [TAB_CARD_MS] 的 600ms 是每张错开 120ms 的起步量，经软上限压缩后
 * 相邻卡片的实际起跑间隔呈 **83 → 44 → 27 → 19 → 13 → 10 → 8ms**：越往下越密，
 * 合起来就是一道「先快后慢」往下推的波。早先 0.07 配 160ms 只有 11ms 间隔，
 * 不足一帧，慢放下才看得出顺序、正常速度下等于没做。
 */
private const val CARD_STAGGER_STEP = 0.20f

/**
 * 单张卡片自身「由虚及实」占的窗口，占总时间轴的比例。剩下的额度全部留给错峰。
 *
 * 0.55 意味着任意一张卡的起点都不会超过 0.45：再靠后的卡片至少还有半条时间轴
 * 用来淡完，不会因为被挤到末尾就半路停住。
 */
private const val CARD_FADE_WINDOW = 0.55f

/**
 * 第 [order] 张卡片（0 起，从上往下）在当前总进度下该有多不透明。
 *
 * 起点用软上限 `maxStart * raw / (maxStart + raw)` 而不是 `coerceAtMost(maxStart)`：
 * 前者对任意 order 都严格递增且恒小于上限；后者会让排在后面的卡片全部撞在同一个
 * 上限上同时起跑——总览页那种「学期卡 + 3 张课程卡 + 5 张待办卡」有九张，最后几张
 * 就变成「啪」地一起亮，前面排出来的顺序白排了。
 */
fun cardStaggerAlpha(progress: Float, order: Int): Float {
    if (progress >= 1f) return 1f
    val maxStart = 1f - CARD_FADE_WINDOW
    val raw = order * CARD_STAGGER_STEP
    val start = if (raw <= 0f) 0f else maxStart * raw / (maxStart + raw)
    val t = ((progress - start) / CARD_FADE_WINDOW).coerceIn(0f, 1f)
    // 单卡曲线是 easeOutCubic，即用户要的「先快后慢」：起步快、尾巴长，
    // 「由虚及实」读起来是「清晰起来」而不是「亮起来」。
    //
    // 注意**不要**再把减速加到驱动这条轴的时间轴缓动上（见 MainActivity 的
    // `LinearEasing` 注释）：实测那会把错峰压成一记顿挫。
    val inv = 1f - t
    return 1f - inv * inv * inv
}

/**
 * 卡片的入场修饰符：纯淡入，不带缩放。
 *
 * 「弹入」的观感全部由不透明度承担——由虚及实、从上往下依次点亮。缩放反而会让
 * 卡片在淡入的同时轻微变形，读成「抖一下」而不是「浮现」。
 *
 * `.value` 刻意写在 `graphicsLayer` 的 lambda 里：那是在 draw 阶段执行的，
 * 读 snapshot state 只会让这一层重绘，不会让整张卡（连带它里面所有 Text 和
 * 图形）重组。这是这个修饰符存在的全部理由，别在调用点把它提到外面算好再传进来。
 */
fun Modifier.cardRevealAt(progress: State<Float>, order: Int): Modifier =
    graphicsLayer { alpha = cardStaggerAlpha(progress.value, order) }

// =========================================================================
// 切页转场的时间轴（三段串起来 ≈ 300ms）
//
//  0ms ─── 85ms    旧界面：以屏幕中央为参照点缩小并淡出
//  75ms ── 185ms    新界面骨架（不含卡片）：略缩小淡入，再放大到位
// 185ms ── 785ms    主体卡片：由虚及实，按从上往下的顺序依次亮起
//
// **有两个地方用这一套**：底部浮岛切页（MainActivity），以及课表页内
// 「周课表 ↔ 列表」两种视图的切换（ScheduleScreen）。它们是同一个动作的
// 两种规模——换一个数据视图和换一页，区别只是排版结构，节奏不该另起一套。
// 所以常量、转场规格、入场时间轴都定义在这里，两个调用点只管提供
// `targetState` 和内容。
//
// 为什么是这三段、而不是一次交叉淡入淡出：
//
//  * **拖影**：旧页必须在新页开始出现之前就淡干净。85ms 的加速离场里，新页要到
//    75ms 才起步，两者只重叠 10ms，此时旧页的透明度已经只剩 2%——肉眼读不到，
//    但也足以保证不会看到两层文字叠在一起。
//  * **闪白**：单次交叉淡入的中点必然是「旧页淡完、新页刚起」的一瞬纯底，中间
//    什么都看不见，于是像闪了一下白。分段之后新页骨架先在，闪白的位置上填着
//    标题、筛选、分隔这些小体量元素；更要紧的是此刻**卡片还没到**，屏幕上大片
//    是留白而不是空洞，观感就变成「新内容在长出来」而不是「屏幕白了」。
//
// 第三段独占 600ms，是为了让「依次」看得见也看得清。相邻卡片的起跑间隔是
// 83 → 44 → 27 → 19 → 13 → 10 → 8ms：**越往下越密**，于是整道波读起来就是
// 「先快后慢」往下推。早先压到 120ms 时间隔只有 11ms，不足一帧，慢放下才看得出
// 顺序、正常速度下整批卡片「啪」地一起亮，等于没做错峰。
// 换页因此总长 785ms：前 185ms 依旧利落，慢的是内容显形，不是页面切换本身。
// =========================================================================

/** 第一段：旧界面缩小淡出的时长。 */
const val TAB_OUT_MS = 85

/** 第二段：新界面骨架的起步延迟。取 [TAB_OUT_MS] - 10ms，只为那 10ms 的交叠余量。 */
const val TAB_IN_DELAY_MS = 75

/** 第二段：新界面骨架淡入并放大到位的时长。 */
const val TAB_IN_MS = 110

/**
 * 第三段：主体卡片起步的延迟。
 *
 * 严格等于 [TAB_IN_DELAY_MS] + [TAB_IN_MS]，也就是骨架**刚好**放大到位的时刻——
 * 「先让骨架到位，再让内容弹进来」这个顺序本身就是效果的一部分，抢 10ms 提前
 * 起跑会让两者糊在一起。类型是 Long，`delay` 不吃 Int。
 */
val TAB_CARD_DELAY_MS = TAB_IN_DELAY_MS + TAB_IN_MS.toLong()

/**
 * 第三段：卡片入场的总时长，也是整条错峰时间轴的长度。
 *
 * 它要同时装下「错峰铺开的偏移」和「单张淡入的窗口」，所以比前两段加起来还长。
 * 压到 120ms 不是更快，是顺序根本读不出来——见本文件的 [CARD_STAGGER_STEP]。
 */
const val TAB_CARD_MS = 600

/**
 * 切页转场的规格。两个调用点都直接用它：
 * `AnimatedContent(transitionSpec = { pageChangeSpec() })`。
 *
 * 收成函数而不是让两处各抄一遍，是因为这里每一个数字都牵着理由（见上面的时间轴）：
 * 抄一份就等于把「10ms 防拖影交叠」「必须线性驱动卡片轴」这些结论放进某一个人的
 * 记忆里，而这类数字最容易被后来者当成手感参数随手改。
 *
 * [S] 只为让调用点少写一次泛型推断，两种 `targetState` 类型都能用。
 */
fun <S> AnimatedContentTransitionScope<S>.pageChangeSpec(): ContentTransform {
    // 第一段：旧界面各元素以屏幕中央为参照点略缩小并淡出。
    // 用加速曲线，让它「走掉」而不是「停下来」——收尾越干脆，
    // 第三段卡片弹入时就越读得出「新的一页正在成形」。
    val exitTransition = fadeOut(
        animationSpec = tween(
            durationMillis = TAB_OUT_MS,
            easing = FastOutLinearInEasing
        )
    ) + scaleOut(
        targetScale = 0.94f,
        transformOrigin = TransformOrigin.Center,
        animationSpec = tween(
            durationMillis = TAB_OUT_MS,
            easing = FastOutLinearInEasing
        )
    )

    // 第二段：新界面（卡片除外）略缩小淡入、再放大到 1.0。
    // 起步比旧页的淡出晚 10ms，那 10ms 是防拖影的唯一窗口。
    val enterTransition = fadeIn(
        animationSpec = tween(
            durationMillis = TAB_IN_MS,
            delayMillis = TAB_IN_DELAY_MS,
            easing = LinearOutSlowInEasing
        )
    ) + scaleIn(
        initialScale = 0.96f,
        transformOrigin = TransformOrigin.Center,
        animationSpec = tween(
            durationMillis = TAB_IN_MS,
            delayMillis = TAB_IN_DELAY_MS,
            easing = LinearOutSlowInEasing
        )
    )

    return (enterTransition togetherWith exitTransition).using(
        SizeTransform(clip = false)
    )
}

/**
 * 第三段那条「卡片由虚及实」的时间轴，返回可直接喂给 [LocalCardRevealProgress] 的值。
 *
 * **必须在 `AnimatedContent` 的内容 lambda 里调用**：`targetState` 一变，内容块
 * 会重建，`remember` 不带 key，于是每次重建都新建一条 0f 起步的轴——这就是
 * 「换一次页就重放一次入场」的机制本身。
 *
 * 这条轴**必须线性**，减速放在每张卡片自己的曲线里（[cardStaggerAlpha] 的 easeOutCubic）。
 * 理由是实测的，不是偏好：时间轴一旦减速，越靠后的卡片起跑点（progress 越大）就越被
 * 挤到同一小段时间里。600ms 总长下，
 *   线性时间轴：相邻卡片起跑间隔 83 → 44 → 27 → 19 → 13 → 10 → 8ms，
 *               梯度能铺满约 54% 的时长；
 *   FastOutSlowIn：间隔塌成 57 → 11 → 7 → 5 → 3 → 3 → 2ms，
 *               梯度只剩 18%——第一张之后其余全糊在一起，读成一记顿挫而不是一道波。
 * 所以「先快后慢」不能加在轴上，要靠 [CARD_STAGGER_STEP] 让减速出现在**波往下推的节奏**上。
 *
 * @param firstMountSeed 第一次挂载时这条轴的起点。默认 0f = 照常放入场动画。
 *   只有**冷启动的首帧**该传 1f：那时根本没有「旧界面」，依次亮起只会让用户对着
 *   空页面先等 [TAB_CARD_DELAY_MS]。判断交给调用点的 App 级标志
 *   （MainActivity 持有；课表页内切视图每次重建都真的有一次「旧视图」，别传 1f）。
 */
@Composable
fun rememberCardRevealProgress(firstMountSeed: Float = 0f): State<Float> {
    val progress = remember { Animatable(firstMountSeed) }
    LaunchedEffect(Unit) {
        if (progress.value >= 1f) return@LaunchedEffect
        delay(TAB_CARD_DELAY_MS)
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = TAB_CARD_MS, easing = LinearEasing)
        )
    }
    // `Animatable` 不是 `State<Float>`，卡片那边只想要一个「会变的数值」，
    // 不需要知道这是条动画。包一层 derivedStateOf，免得把动画类型漏进主题层。
    return remember(progress) { derivedStateOf { progress.value } }
}

/**
 * 弹层专用的 haze 状态，**整个进程共用这一个实例**。
 *
 * haze 的源层和效果层必须持有同一个 [HazeState] 才能对上。页面内没问题，
 * 弹层是另一回事：`Dialog` / `ModalBottomSheet` 跑在独立窗口里，
 * MainActivity 挂在 CompositionLocal 上的那份传不进去（跨窗口就是另一棵
 * Composition 树），而 `remember { HazeState() }` 又会在每个 Composition 各造一个。
 * 所以这份只能挂在进程级的单例上，MainActivity 与所有弹层都指它。
 *
 * 独立窗口能不能真模糊到主窗口的内容，取决于 haze 对跨窗口源层的处理——
 * 这一条是实测出来的，不是读文档读出来的，见 [OverlayHazeState] 的使用方注释。
 */
val OverlayHazeState = HazeState()

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
 * 页面左右留白，四个主页面（总览 / 课表 / 清单 / 设置）共用一个值，顶栏也跟着它对齐。
 *
 * 沿革：课表 12.dp、其余三页 16.dp → 收成共享的 10.dp → **6.dp**。
 *
 * 压到 6.dp 是「提高信息密度」这一条要求里最直接的一档：411dp 宽的屏上，
 * 卡片宽度从 391.dp 变成 399.dp，一条待办的标题多出约两个字的宽度，
 * 而屏幕左右各 6.dp 仍足够让 18.dp 圆角的卡片不贴边（小于 4.dp 时圆角会显得被切掉）。
 *
 * 弹窗（TaskEditDialog / NewTaskTypeDialog）里那个 16.dp 不跟着改：
 * 那是弹窗自己离屏幕多远，不是页面边距。
 */
val PageHInset = 6.dp

/**
 * 顶栏本体高度，不含状态栏（那部分由 `statusBarsPadding()` 补）。
 *
 * 原来是 M3 `TopAppBar` 的 64.dp，其中约 20.dp 排在标题「MiuList」上——
 * 一个不承载操作、也不随页面变化的装饰字。删掉之后一行里最高的只剩
 * 22.dp 的周次 chip 和 [TopBarActionSize] 的按钮，40.dp 已经宽松。
 *
 * 别再往下压：一行顶栏既是唯一的全局操作区，也承担「现在在第几周」的指示，
 * 太矮会把它挤成一条状态栏残片。
 */
val TopBarHeight = 40.dp

/**
 * 顶栏右侧动作按钮的边长（同时也是触摸区域）。
 *
 * 用 `Box` + `clickable` 而不是 `IconButton`：后者挂着
 * `minimumInteractiveComponentSize()`，那个 Modifier 用 `maxOf(measured, 48.dp)`
 * 算尺寸、**不参考父级的 maxHeight**，放进 [TopBarHeight] = 40.dp 的行里会溢出。
 */
val TopBarActionSize = 32.dp

/**
 * 同一列表里**卡片与卡片**的竖直间距。
 *
 * 全 App 统一收在这一个值上，因为以前是各写各的：清单页 5.6.dp、课表列表页 14.dp、
 * 设置页 16.dp。同一套设计里三种卡片间距，横竖切页时卡片的疏密会明显跳一下，
 * 「哪一页更挤」变成布局的副产品而不是有意为之。
 *
 * 4.dp 够用的理由：卡片自己是一块 18.dp 圆角的染色面，两张卡之间留 4.dp 的白缝，
 * 圆角 + 白色内线（玻璃边缘折射高光）已经把分界画出来了。间距再大只是把
 * 同样的信息用空白重复表达一遍，一屏能放的条数就少了。
 *
 * 注意它和 [CardBlockGap] 是两种间距，别混用：这里管「一列连续的同层卡片」，
 * 那里管「一个大块和下一个大块」（主卡、分区标题、分区内容）。
 */
val CardListGap = 4.dp

/**
 * 页面里**大块之间**的竖直间距（总览的主卡↔分区、设置页的功能卡↔功能卡）。
 *
 * 比 [CardListGap] 宽，是要让「换了一个话题」这件事在扫视时读得出来。
 * 16.dp 在信息密度上太奢侈：总览页大半屏被空白吃掉，一屏只看得到
 * 一门课加两条待办。10.dp 仍能把两块内容分开，但一屏能多滚出一整条待办。
 */
val CardBlockGap = 10.dp

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
 * @param hazeState 覆盖要用的 haze 状态，默认取 [LocalGlassHazeState]。
 *   **弹层必须显式传 [OverlayHazeState]**：它们在独立窗口里，拿不到 MainActivity
 *   挂的那个 Local，不传就会退化成平涂（功能不崩，只是没有模糊）。
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
    hazeState: HazeState? = LocalGlassHazeState.current,
    showBorder: Boolean = true,
): Modifier {
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
        .then(
            if (showBorder) {
                Modifier.border(GlassRimWidth, Color.White.copy(alpha = GlassRimAlpha), shape)
            } else {
                Modifier
            }
        )
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
