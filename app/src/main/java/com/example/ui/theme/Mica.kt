package com.example.ui.theme

import android.graphics.BitmapFactory
import android.os.Build
import java.io.File
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.example.data.model.WallpaperConfig
import com.example.data.model.WallpaperPresets
import com.example.data.model.WallpaperType

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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.R
import dev.chrisbanes.haze.HazeDefaults
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.delay

// =========================================================================
// 壁纸底 + 粉彩染色毛玻璃卡片
//
// 这一版把「页面底提供颜色、玻璃面只做透光」的老结构整个翻了过来。
//
// 以前页面底是 Mesh Gradient（抹茶绿→白）+ 六团色斑，于是玻璃面板必须做
// 「屏幕对齐重绘」：在自己的 drawBehind 里按本面板在根坐标的偏移，把同一张
// 背景再画一遍，背后的颜色透过半透明白面显出来。自定义壁纸也是同一套机制。
//
// 页面底一度换成纯白，**重绘一张白底等于没重绘**，于是那套机制连同 MeshPaint /
// buildMeshPaint / drawBackdrop / withWallpaper 一起删掉了，颜色的来源从
// 「背景」搬到了「面」上：
//
//   * 页面底 = 纯白，负责干净和留白；
//   * 卡片面 = 半透明粉彩，透出底下那片白，于是是「粉彩染色毛玻璃」而不是实色块；
//   * 课程卡把 tint 换成这门课自己的粉彩，颜色就有了语义来源，不必再靠色条认课。
//
// 现在页面底是**默认壁纸**（见 [AppBackgroundLayer]），上面那三条里第二条第三条原样
// 成立——卡片面仍是半透明粉彩，透上来的不再是纯白而是壁纸的浅色调，于是玻璃真的
// 「透」到了背后那张图。这是把壁纸接回来而不把删掉的重绘机制也接回来的原因：
// **壁纸不需要被重画**，它本来就在玻璃面下面，被正常绘制顺序挡在后面。
//
// 但它**必须进 haze 源层**，理由和「重绘」无关，见 [AppBackgroundLayer] 里的注释。
//
// haze 真正出效果的地方是**卡片叠卡片**：课表今日页里，内层待办卡是压在
// 染好色的课程卡上面的，那一层才是货真价实的毛玻璃。底部浮岛压在滚过去的
// 卡片上同理。
// =========================================================================

/** 壁纸之下那层永不露面的兜底白。它保证「页面底永远不透明」这条不变量。 */
val CanvasPureWhite = Color(0xFFFFFFFF)

/**
 * 默认壁纸资源。
 *
 * **单点真源**：背景层铺它。换成别的壁纸只改这一行。
 *
 * 用 `val` 而不是 `const val`：顶层 `const val` 在 Kotlin 侧是编译期常量、
 * 引用点会被**内联**，但跨文件经由别的类反射/静态取时会去找一个 `getXxx()` 静态方法
 * 而它不存在，直接 `NoSuchMethodError` 崩在运行时（编译期完全看不出来）。
 * `val` 在这个项目里没有性能代价，别为了省一个字段去踩这个。
 */
val DefaultWallpaperRes: Int = R.drawable.wp_default_cloudy_knoxville

/**
 * haze 的兜底底色：源层里没录到内容的像素拿什么填。
 *
 * hazeSource 只录自己的内容，源层没覆盖到的地方在纹理里是全透明的，直接糊会糊出
 * 一块透明洞，必须显式给 haze 一个填充色。haze 在 backgroundColor 没给时会直接抛
 * IllegalArgumentException，所以这里不能给 Color.Unspecified。
 *
 * 取的是壁纸的中心调而不是纯白。壁纸已经进了源层（见 [AppBackgroundLayer]），
 * 所以主窗口里这个值**几乎不会被看见**；它真正服务的是两类场合：
 *
 *   1. 独立窗口里的弹层。它们拿不到主窗口的源层，糊的是自己那块空背景，
 *      填色若与页面底差太远，玻璃边缘会露出一圈比页面「更白」的框；
 *   2. 壁纸图加载/解码的那一帧。Image 在首帧才拿到位图，此前这里就是过渡色。
 *
 * 填纯白会让弹层在浅黄—浅紫的壁纸上显出一块明显的白，所以取壁纸中段调。
 */
private val HazeFallbackBg = Color(0xFFF2EDEA)

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
 * 卡片面下那层**不透明白衬底**的强度，0 = 不铺。
 *
 * 这是「在不同壁纸上都稳定」的那一层。课程卡 / 待办卡的颜色只由 [CourseTintAlpha]
 * 那么点染色给出，剩下的透光全部来自壁纸——壁纸一变（尤其是深色壁纸），
 * 卡的明度跟着变，而全 App 的文字色是写死的深色，于是深色壁纸上正文对比度掉到 1:1。
 * 铺一层白衬底之后，卡片自身的明度主要由这层白决定，壁纸只贡献剩下的那一小部分，
 * 深色壁纸上的卡片也就仍然是「清新的浅色卡」而不是一块发暗的色板。
 *
 * 数值 0.55 的来历：这张默认壁纸是接近纯白的粉彩渐变（实测 R 232~251），
 * 0.55 时卡面与卡外的明差从「几乎没有」变成「一眼能认出这是张卡」；
 * 而换成深色壁纸（实测 #2B2F52）时，0.55 的白衬底把卡面抬到约 #8FA7D0 那一档，
 * 深色正文仍有 6:1 以上。**别往 0.7 以上加**：那就不是玻璃卡而是一块白板，
 * 背面完全没有「壁纸在玻璃后面」这回事了。
 *
 * 只给课程卡 / 待办卡用（见各自的调用点）；浮岛、主卡、弹层不铺——
 * 浮岛背后是会动的内容，白衬底会把它的通透直接吃掉。
 */
const val CardBaseCoatAlpha = 0.55f

/**
 * 课程色的染色强度。
 *
 * ⚠️ 它同时是 [com.example.ui.theme.cardTint] 的**反解输入**：调用处传给
 * `glassSurface` 的 `tintAlpha` 必须和这里一致，而 `tint` 必须是
 * `cardTint(课程色, CourseTintAlpha)`。两者对不上，解出来的颜色就不是要的那个颜色。
 *
 * 调这一档时**先想清楚要的是浓还是淡**：0.30 之下，卡面主要靠色相认课；
 * 再往上颜色就压过「清新」，而深色文字的对比度会跟着掉。
 */
const val CourseTintAlpha = 0.30f

/**
 * 嵌在染色卡里面的内层卡片，用近白「挖入」时的面不透明度。
 *
 * 内层绝不能再染同一个课程色：同色相叠两次只会更深（实测外层 #D6E9FE 会叠成
 * #C2DFFD），层次正好反过来。反过来用近白压在染色面上，层次靠「浅在深上」撑。
 *
 * 数值要跟着 [CourseTintAlpha] 一起调：外层越淡，内层就得越白，
 * 否则两层都接近纯白，卡片会糊成一片。现在 0.30 / 0.86 这一对，
 * 内外差出一档能看出来的明度。
 *
 * 0.72 → **0.86**：内层待办卡是整屏文字最密的地方，而它背后的外层课程卡
 * 在深色壁纸上会被拉暗。不透明度不够高时，内层的深色正文就跟着一起读不出来。
 * 内层不铺 [CardBaseCoatAlpha]——它本身就是近白，再铺一层白衬底会糊掉内外层次，
 * 直接把面调实是同一件事里更省一层的做法。
 */
const val NestedFaceAlpha = 0.86f

/**
 * 周课表格子里**深色档**课程格（有待办那一档）的背景模糊半径。
 *
 * 这一档原来是一块 100% 不透明的实色块，**壁纸对它零影响**（实测两张壁纸下
 * 基线主色逐字节相同），所以挂模糊**不是为了**「跨壁纸稳定」——那件事本来
 * 就成立。模糊买的是**把壁纸上大尺度的局部色差摊平**：换成自定义照片或带花纹
 * 的壁纸时，格子里那片壁纸先被抹平，卡面不会跟着壁纸一块亮一块暗。
 *
 * 在默认壁纸和预设壁纸上这个收益是**看不出来**的——它们都是极平滑的渐变，
 * 相邻像素最大差只有 2/255，糊了等于没糊。别拿预设壁纸当「模糊没生效」的证据。
 *
 * 取 [ControlBlurRadius] 那一档（18.dp）而不是卡片级的 [GlassBlurRadius] = 28.dp：
 * 格子只有约 51×48dp（连堂两节约 51×86dp），28.dp 的核会越过格子和邻格之间
 * 那 1~2dp 的缝，把隔壁课程的色相拉进来，读成一块染错的边。
 * ⚠️ **已实测 18.dp 不会渗色**：沿第 1~2 节扫一行，格内中心与靠缝那 4px
 * 逐字节相同（Δ 全 0），基线与新版都一样。缝隙比半径小一个量级这条推理成立。
 *
 * ⚠️ 浅色档**不要**跟着用：那一档的底色被 `pushAwayFrom` 钉在固定明度上
 * （相对亮度 ≈ 0.616），本来就不受壁纸影响，加模糊只会白白引入邻格的干扰。
 */
val CourseCellBlurRadius = 18.dp

/**
 * 深色档课程格**面**的强度，也就是背后有多少透上来：`1 - 0.94` = 6%。
 *
 * 它同时是主题色偏移和文字对比度的**同一个旋钮**：透上来的壁纸越多，卡面越容易被
 * 壁纸带偏，而白字也越读不出来。实测这一档取 0.94 时卡面各通道变亮 3~12 级，
 * 白字对比度从实色时的 3.8:1 降到约 3.4:1（默认浅色壁纸下算得最不利）。
 *
 * 为什么不给 [CardBaseCoatAlpha] 那种白衬底：白衬底是往**上**提亮，而这一档是
 * 深色底配白字，任何提亮都会直接吃掉白字的对比度。
 *
 * **别再往下压**：0.94 已经贴着 3:1 的线，再透一点白字就过线了。要更强的壁纸
 * 抑制力就去加深 [com.example.ui.theme.deepEnoughForWhite] 的目标，
 * 而不是把这层面调透——那两个方向的效果相反。
 */
const val CourseCellDeepFaceAlpha = 0.94f

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
//
// 这两样都不碰页面底色，纯白背景不动。
//
// 第三样「边缘折射高光」（0.5dp 白内线）**已删**：白边在浅色壁纸上勉强算材质，
// 在深色或花纹壁纸上是硬邦邦的一圈亮线，而卡片边界现在由两件事承担——
// 面本身更实（见 CardBaseCoatAlpha）和更大的模糊半径。别再把它加回来。
// =========================================================================

/**
 * 卡片级真背景模糊半径。
 *
 * 16.dp → 30.dp → 44.dp → **28.dp**。44.dp 实机看过之后用户判为糊过头（颜色被摊平、
 * 卡片与卡外的差别被吃掉），回退到 **25~30 区间**，取中值 28.dp。
 *
 * 这是手感值，落在 25~30 之间都成立；**用户给的区间不要越到 30 以上去**。
 *
 * 真正兜住「卡片看起来还是一张卡」的是白衬底（[CardBaseCoatAlpha]）：卡面与卡外
 * 那片壁纸的明暗差别由衬底决定，不依赖模糊半径。所以模糊和衬底是两个独立旋钮——
 * 觉得糊就动这个，觉得「卡片边界不清楚」动衬底，别拿其中一个去补另一个的短板。
 */
val GlassBlurRadius = 28.dp

/** 磨砂颗粒强度。之前写死 0f，理由是「像老照片」，现在反过来了——没有颗粒就不是玻璃。 */
private const val GlassGrain = 0.055f

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
 *
 * ⚠️ **2026-10-26 起，本 App 里已经没有它的使用者了** ——
 * 清单页那十颗筛选 chip 的描边去掉了（见 ChecklistScreen 的 `SelectableChip`）：
 * 一道浅色发丝边压在同样浅的玻璃面上只会比面更亮，于是读成了一圈**白边**；
 * 换到深色或花纹壁纸，它又变成硬亮的一圈。它跟着 `glassSurface` 自己那条
 * 0.5.dp 白描边一起退出了这套玻璃语言。
 *
 * 常量留着是为了不破坏 [web/styles.css] 里同名变量（`--glass-edge`）的对应关系，
 * 也留着给将来真正需要「冷调发丝边」的场合（比如深色底上的分隔线）。
 * **别再拿它去给浅色玻璃面描边** —— 想要边界，改面本身，或者把字加深。
 */
val GlassEdgeBottom = Color(0xFFE3E9EF)

/** 页面级 chip / 小控件的底色。比卡片面浅一档：它们是筛选器，不该和内容卡抢层。 */
val ChipSurfaceNeutral = Color(0xFFF1F5F9)

/**
 * 页面左右留白，四个主页面（总览 / 课表 / 清单 / 设置）共用一个值，顶栏也跟着它对齐。
 *
 * 沿革：课表 12.dp、其余三页 16.dp → 收成共享的 10.dp → **6.dp**。
 *
 * 这一版把卡片重新交还给壁纸：留白窄，卡片的圆角和玻璃才像「浮在壁纸上」，
 * 而不是一张张摆在白纸上的名片。深色壁纸上的可读性由自适应白衬底解决，
 * 不靠把页面四周留白撑大来获得呼吸。
 *
 * 弹层里那个 16.dp 不跟着改（待办编辑弹层 / NewTaskTypeDialog）：
 * 那是弹层自己离屏幕多远，不是页面边距。
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
 * 页面底层：默认壁纸 + 内容。
 *
 * 放在 Scaffold 之外、内容之下，且 Scaffold 的 containerColor 必须是透明。
 *
 * ### 壁纸为什么不需要「屏幕对齐重绘」
 *
 * 早期版本页面底是 Mesh Gradient，玻璃面板得在自己的 drawBehind 里按本面板在根坐标的
 * 偏移把同一张背景**再画一遍**，颜色才透得出来。现在壁纸是真实位图，被正常的绘制顺序
 * 挡在玻璃面**下面**，本来就该透过半透明面显出来——重画一遍只会让它对不上位图本身。
 * 所以那套机制不必接回来。
 *
 * ### 但它必须进 haze 源层（这条容易漏）
 *
 * `hazeSource` 挂在 MainActivity 里内容那个 Box 上，只录**内容**。壁纸画在它外面，
 * 于是玻璃面背后那片壁纸在源层里是全透明的，haze 会拿 [HazeFallbackBg] 去填，
 * 浮岛底下就会浮出一块和壁纸对不上的死板色块。**换壁纸不改这里 = 底部浮岛周围一圈脏边。**
 *
 * 代价是壁纸要跟着内容一起被录进模糊源。但壁纸本身是极平滑的渐变（相邻像素最大差 2/255），
 * 糊它等于没糊，所以这笔开销买不到任何视觉收益，却换来了「岛下看到的就是壁纸」。
 * 值。
 *
 * @param hazeSourceState 整页的 haze 状态。传 null = 不把壁纸录进源层（仅调试用，
 *   正常入口一律传 MainActivity 那个进程级实例，见 [OverlayHazeState]）。
 */
@Composable
fun AppBackgroundLayer(
    wallpaperConfig: WallpaperConfig = WallpaperConfig(),
    hazeSourceState: HazeState? = OverlayHazeState,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val customBitmap: ImageBitmap? = remember(wallpaperConfig.type, wallpaperConfig.customPath) {
        if (wallpaperConfig.type == WallpaperType.CUSTOM && !wallpaperConfig.customPath.isNullOrBlank()) {
            val file = File(wallpaperConfig.customPath)
            if (file.exists() && file.canRead()) {
                try {
                    BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
                } catch (_: Throwable) {
                    null
                }
            } else {
                null
            }
        } else {
            null
        }
    }

    CompositionLocalProvider(
        LocalWallpaperIsCustom provides (wallpaperConfig.type == WallpaperType.CUSTOM)
    ) {
        Box(
            modifier = modifier
                .fillMaxSize()
                // 壁纸之下垫一层不透明白，保证任何一帧（解码前、加载中）页面底都不是透明的。
                .background(CanvasPureWhite)
        ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (hazeSourceState != null) {
                        Modifier.hazeSource(hazeSourceState)
                    } else {
                        Modifier
                    }
                )
        ) {
            when {
                wallpaperConfig.type == WallpaperType.CUSTOM && customBitmap != null -> {
                    // 用户照片先柔焦，再给卡片当背景：毛玻璃后面是一张被糊开的照片，
                    // 不是一张清晰的人像。滑杆 0%~100% 对应 0~18dp。
                    val blurDp = (wallpaperConfig.softFocus * 18f).dp
                    val blurModifier = if (
                        wallpaperConfig.softFocus > 0.01f && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    ) {
                        Modifier.blur(blurDp)
                    } else {
                        Modifier
                    }
                    Image(
                        bitmap = customBitmap,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .then(blurModifier)
                    )
                }
                wallpaperConfig.type == WallpaperType.PRESET -> {
                    Image(
                        painter = painterResource(WallpaperPresets.getResOrDefault(wallpaperConfig.presetId)),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                else -> {
                    Image(
                        painter = painterResource(DefaultWallpaperRes),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            // 只保留用户手动拉的文字保护遮罩。不做深色模式，也不按壁纸亮度自动切换；
            // 深色照片要不要压白，由用户在壁纸设置里决定。
            if (wallpaperConfig.dimOpacity > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.White.copy(alpha = wallpaperConfig.dimOpacity.coerceIn(0f, 0.6f)))
                )
            }
        }
            content()
        }
    }
}

/**
 * 壁纸真·模糊：把玻璃面**背后那片壁纸**糊开，并提一点饱和度。
 *
 * ### 为什么不是「在背后画几团颜色」
 *
 * 试过。做法是在卡片后面画三团柔色斑，让 16.dp 的 hazeEffect 有东西可糊
 * （因为默认壁纸是极平滑渐变，相邻像素最大差 2/255，糊了等于没糊）。
 * 效果确实出来了，但方向是错的：参考图里那些粉、蓝、青的柔和色晕
 * **是壁纸本身被糊开的样子**，颜色全部来自壁纸；而画色斑等于自己编了一套颜色，
 * 换壁纸就对不上——这正是用户否掉它的原因。**色斑是在演，不是真的模糊。**
 *
 * ### 那怎么让平滑渐变糊出东西来
 *
 * 平滑渐变确实糊不出细节，但模糊仍然有两个可见作用，参考图正是靠这两个：
 *
 * 1. **半径够大**：把壁纸上大尺度的色相跨度摊开。这张壁纸左上是暖黄、右下是淡紫，
 *    半径 16.dp 时这个跨度被压在玻璃面边缘之外，一眼看去还是一块平色；
 *    半径拉到 64.dp，那条暖黄→淡紫的轴才被真正摊进卡面里。
 *    （下面所有取样都是在 **Kind Steel** 这张上量的，见下。）
 * 2. **提饱和**：模糊会把颜色平均掉、整体变灰糊。参考图那种「色晕仍然是彩的」
 *    靠的是模糊**之后**再提饱和 —— Windows 的 acrylic 就是这么做的，
 *    也是它和普通毛玻璃一眼能分辨的原因。
 *
 * haze 1.3.1 的 `HazeStyle` 只有 blur / noise / tints，**没有饱和度参数**，
 * 所以提饱和只能用 Compose 原生的 [RenderEffect] 单独叠一道颜色矩阵。
 *
 * ### 为什么面必须压到 0.10（2026-10-22 实测）
 *
 * 第一版效果「看不出来」，查下来**不是模糊半径不够，是上面那层面把颜色洗掉了**。
 * 装机实测同一张截图上取样：
 *
 * | 位置 | 取样色 | 饱和度 |
 * |---|---|---|
 * | 卡片外侧的壁纸 | `#F7F6E1` | 9.7% |
 * | 卡片内部（7 个点） | `#F1F3EC` … `#EFEFEF` | **1%~3%** |
 *
 * 卡片比它背后的壁纸**更不饱和**——面板在主动洗掉壁纸的颜色。两个叠加原因：
 *
 * 1. `glassSurface` 默认那层面是 `#EDF1F5` × alpha 0.48，而模糊已经把壁纸的饱和度
 *    摊薄过一次。实测这层单独就能把 9.7% 压到约 3.7%（约**砍一半**），
 *    两层一叠，内层 [WallpaperBlurSaturation] 提上去的饱和度被原样抵消。
 *    → 配套的面见 [WallpaperCardFaceTint] / [WallpaperCardFaceAlpha]。
 * 2. `wallpaperBlur` 和 `glassSurface` 各带一个 `hazeEffect`，两个模糊 pass 叠在同一个
 *    节点上，**后画的 16.dp 那一层会盖住 64.dp 的结果**。→ 调用点必须给
 *    `glassSurface(blurRadius = 0.dp)` 关掉第二个 pass（磨砂颗粒不会丢，
 *    它已经挂在 `wallpaperBlur` 的 `noiseFactor` 上）。
 *
 * 这两条都不是某张壁纸的特例，换任何壁纸都成立，所以改在 [wallpaperBlur] 的配套参数里，
 * 而不是把饱和度再往上调去硬凑——那样只会让本来就鲜艳的壁纸过饱和。
 *
 * ### 这张壁纸的物理上限（别拿它当效果没做好的证据）
 *
 * ⚠️ **下面这些取样是 Kind Steel（`wp_default_kind_steel`）的**：
 * 全图 R 233~251 / G 222~252 / B 219~250，**最大饱和度 13.1%**
 * （`#FBFCDB`，最大色差 32/255），左上暖黄、右下淡紫。
 *
 * **App 现在的默认壁纸是 Cloudy Knoxville**（`wp_default_cloudy_knoxville`），
 * 它比 Kind Steel 还平：全图 R 235~251 / G 237~251 / B 238~251，
 * **最大饱和度只有 1.3%**（`#EBEDEE`）——那条暖黄→淡紫的轴几乎不存在，
 * 通体是一张近白的中性渐变。
 *
 * 模糊不会创造颜色，它只能把已有的颜色摊开——壁纸本身没颜色可摊。
 * 所以在**这两张**壁纸上的正确结果都只是「一片极淡的柔光」
 * （在 Kind Steel 上是暖黄→淡紫，在默认的 Cloudy Knoxville 上近乎无色），
 * 达不到参考图那种明确的彩色晕染，这不是实现问题，是素材的物理上限。
 * 换成有颜色的壁纸（实测深色壁纸最大饱和度 79.3%）同一个方案会明显得多。
 *
 * 这条接缝是真正管住的：**带色彩度的壁纸才有效果**，而不是「换了壁纸仍然一样」。
 * 已实测：换成深色壁纸后卡面从 `#F1F3EC` 变成 `#2B2F52`，壁纸的深蓝明显透上来、
 * 卡片和卡外的明暗差一眼能看出来——模糊本身是通用的。
 *
 * ### 但整屏文字可读性**不是**通用的（2026-10-22 实测，已知未解决）
 *
 * 同一张深色壁纸上，正文与标题仍是**深色**的：主卡背景亮度约 49，
 * 大标题「2026年10月17日 星期六」的笔画实测几乎全在亮度 60 以下，
 * **对比度约 1.1:1，字直接读不出来**（顶栏学期名、卡片副标题、灰色辅助字同样受影响）。
 *
 * 也就是说：模糊这一层是壁纸无关的，但**配色方案是按浅色壁纸写死的**。
 * 真要支持深色壁纸，得让文字色跟着壁纸的亮度走（加一层判定，或给壁纸配遮罩/描边），
 * 那是整屏的改动，不在这次范围内。**别把这个当作「已经支持任意壁纸」来宣传。**
 *
 * @param blurRadius 真实背景模糊半径。必须够大才有效果（见上）。
 * @param saturation 模糊后的饱和度倍率。1f = 不变。取 1.35~1.6 这个档：
 *   低于 1.2 看不出 acrylic 味，高于 2.0 壁纸的浅色会糊成一片假荧光。
 */
@Composable
fun Modifier.wallpaperBlur(
    blurRadius: Dp = WallpaperBlurRadius,
    saturation: Float = WallpaperBlurSaturation,
    noiseFactor: Float = GlassGrain,
    hazeState: HazeState? = LocalGlassHazeState.current,
): Modifier {
    val saturationEffect = remember(saturation) {
        if (saturation == 1f) {
            null
        } else {
            // 亮度加权的饱和度矩阵（Rec.709）：
            //   out = (1-s)·lum + s·in，其中 lum = 0.2126R + 0.7152G + 0.0722B。
            // 不做这一步的话模糊会把壁纸洗成一片灰白，参考图那种彩色的晕就没了。
            //
            // 用 android.graphics.RenderEffect 再经 asComposeRenderEffect 转过来：
            // Compose 自带的 RenderEffect 只有 BlurEffect / OffsetEffect，
            // **没有颜色矩阵**（查过 RenderEffectKt），而 haze 1.3.1 的 HazeStyle
            // 也只有 blur / noise / tints。所以这里只能走平台 API。
            val s = saturation
            val lr = (1f - s) * 0.2126f
            val lg = (1f - s) * 0.7152f
            val lb = (1f - s) * 0.0722f
            android.graphics.RenderEffect.createColorFilterEffect(
                android.graphics.ColorMatrixColorFilter(
                    floatArrayOf(
                        lr + s, lg, lb, 0f, 0f,
                        lr, lg + s, lb, 0f, 0f,
                        lr, lg, lb + s, 0f, 0f,
                        0f, 0f, 0f, 1f, 0f,
                    )
                )
            ).asComposeRenderEffect()
        }
    }

    return this
        .then(
            if (blurRadius > 0.dp && hazeState != null) {
                Modifier.hazeEffect(
                    state = hazeState,
                    style = HazeDefaults.style(
                        backgroundColor = HazeFallbackBg,
                        tint = HazeDefaults.tint(Color.Transparent),
                        blurRadius = blurRadius,
                        noiseFactor = noiseFactor,
                    )
                )
            } else {
                Modifier
            }
        )
        // 提饱和叠在 hazeEffect **之后**：顺序反了就是「先提饱和再糊」，
        // 而模糊会立刻把刚提上去的饱和度又平均掉，等于白做。
        .then(
            if (saturationEffect != null) {
                Modifier.graphicsLayer { renderEffect = saturationEffect }
            } else {
                Modifier
            }
        )
}

/**
 * 壁纸模糊半径。64.dp 是试出来的下限档：16.dp 时暖黄→淡紫那条色相轴
 * 完全摊不进卡面，看起来和没模糊一样；64.dp 才看得出「背后是一张被糊开的图」。
 * 别再往上加——半径过大时卡面与卡外的差别会小到看不出这是一块玻璃。
 */
val WallpaperBlurRadius = 64.dp

/** 模糊后的饱和度倍率。1.35~1.6 是 acrylic 味的区间，见 [wallpaperBlur]。 */
const val WallpaperBlurSaturation = 1.45f

/**
 * 「壁纸玻璃」那层面的染色与强度。给 [wallpaperBlur] 配套用，见该函数的「为什么面必须压到
 * 0.10」一节。
 *
 * 这里**不能**用 [GlassTintNeutral] 那档 0.48：面是盖在模糊结果**上面**的，
 * 而模糊已经把壁纸本来的饱和度摊薄过一次，再压一层 0.48 的中性冷灰等于把颜色洗没。
 */
val WallpaperCardFaceTint = Color(0xFFFFFFFF)
const val WallpaperCardFaceAlpha = 0.10f

/** 玻璃表面（Glassmorphism），扁平版 + 粉彩染色。
 *
 * 扁平化：不描边、不投影、不做上亮下暗的受光面，全靠大圆角 + 一块半透明面撑着层次。
 * 面里也没有任何渐变——渐变本身就是一种「光影」，要扁平就别在面里藏一条明暗轴。
 *
 * 只有两层：
 * 1. blurRadius > 0 且拿得到 haze 状态：hazeEffect 真·背景模糊（身后是别的 UI 元件，
 *    浮岛就属于这种）；否则背后只有纯白，不需要糊，直接平涂；
 * 2. 一块半透明的 [tint] 面 —— 纯白从底下透上来，所以是「染色」而不是实色块。
 *
 * @param tint 面的染色。默认中性冷灰；**课程卡要传 [cardTint] 解出来的那支颜色**
 *   （`cardTint(课程色, tintAlpha)`），不是课程原色——理由见 Color.kt 的
 *   「卡片染色」那一节：直接拿原色按 alpha 叠，八门课的卡面会深浅不齐。
 * @param tintAlpha 染色强度。中性面用 GLASS_FACE_ALPHA（要够实才托得住），
 *   课程色用 [CourseTintAlpha]。它同时是 `tint` 的反解输入，两者必须一致。
 * @param baseCoatAlpha 染色**下面**那层不透明白衬底的强度，默认 0 = 不铺。
 *   课程卡 / 待办卡传 [CardBaseCoatAlpha]：卡片自身的明度主要由它决定，
 *   换壁纸才不会连着变（深色壁纸上文字直接读不出来，见该常量的注释）。
 *   它和 [tintAlpha] 是两件事：白衬底管「这张卡够不够亮」，染色管「它是哪门课」，
 *   合成顺序固定为 模糊 → 白衬底 → 染色。
 * @param blurRadius 真背景模糊半径。> 0 才生效，且需要祖先上有 hazeSource。
 *   曾经试过把默认值反过来（默认 0，真·需要糊的地方才显式传），
 *   **实测没有任何性能收益，已回退** —— 理由见下面「逐卡模糊不是瓶颈」一节。
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
 *
 * ### 逐卡模糊不是瓶颈（2026-10-15 实测，已回退过一次反转默认值）
 *
 * 上面第 1 层写着「背后没东西时不需要糊」，而全项目 14 个 `glassSurface` 调用点里
 * **只有 2 个**（课程格徽标）传了 `blurRadius = 0.dp`——清单页每张待办卡、
 * 总览页的学期卡/课程卡/空态卡、课表课程卡、设置页每张功能卡，都在跑各自独立的
 * `hazeEffect` pass，而它们背后 90% 是纯白页面底。
 *
 * 于是做过一次实验：把 `blurRadius` 的默认值反转成 0（只有浮岛、课表内层待办卡、
 * 弹层面、抽屉四处显式传回模糊），编出两个 APK **在同一会话内交替安装**测同一组脚本，
 * 抵消环境漂移。结论是**完全测不出差别**：
 *
 * | 场景 | 帧P50 | 尾段P50 | slowDraw |
 * |---|---|---|---|
 * | 清单页滚动 · 原版 | 29ms | — | 41 |
 * | 清单页滚动 · 关模糊 | 28ms | — | 39 |
 * | 切页 ×15 · 原版 | 30.87ms | 16.51ms | 67 |
 * | 切页 ×15 · 关模糊 | 30.96ms | 16.96ms | 65 |
 *
 * 环境：emulator-5554，Intel Arc 140T 真实 GPU 直通，API 36，60Hz（预算 16.67ms）。
 *
 * 所以默认值的反转**已回退**：它换不来任何性能，却实打实丢掉了磨砂颗粒
 * （`noiseFactor` 绑在 `hazeStyle` 上，`useHaze` 为 false 时颗粒一并消失），
 * 而颗粒是玻璃感三件套之一。**留着那份「背后没东西」的代码不是 bug。**
 *
 * 真正的墙在别处，见下一节。
 *
 * ### 那条恒定尾段是什么：不是这个文件，也不是重组，是显示管线
 *
 * 测量中三个完全不同的交互给出同一条尾段（SwapBuffers → FrameCompleted）：
 * 清单页滚动 16.38ms · 切页 16.51 / 16.96ms · 分组展开收起 16.76ms。
 * **不随交互类型变化。** 于是按嫌疑大小逐个做了隔离实验，每个都编出独立 APK、
 * 在同一会话内**交替安装**测同一组脚本（抵消环境漂移），结论是全部出局：
 *
 * | 隔离实验 | 滚动帧P50 | 切页帧P50 | 结论 |
 * |---|---|---|---|
 * | 原版 | 17.73 / 17.78ms | 23.16 / 23.19ms | 基准 |
 * | 逐卡模糊全关（14 处只剩 4 处） | 28 / 22ms | 30.96ms | **测不出差别** |
 * | 整页 `hazeSource` 整个摘掉 | 17.60 / 17.64ms | 22.99ms | **测不出差别，尾段还高 0.5ms** |
 *
 * 重组也被数据直接洗清：切页 15 次、每次完整拆掉并重建一整棵屏幕树并走完
 * 785ms 三段式转场，**UI 线程 P50 只有 0.16ms**（P90 1.66~6.19ms）。
 * 全项目 0 个 `@Immutable`/`@Stable` + 70 字段状态对象整体传参是真隐患，
 * 但动它**收益为零**，别拿它当性能项。
 *
 * 环境处于正常档时，清单页滚动是 **帧P50 17.7ms、janky 0.57~0.94%、UI 线程约 1ms**
 * ——对这个规模的 App 已经够好。测到的 30ms / 8~12% janky 那一档，
 * 两个构建**同时**变差，是模拟器宿主的劣化状态（切换点在 `GPU提交` 段
 * 2.4ms ↔ 16ms），与 App 无关。
 *
 * **所以：这里没有可优化的性能瓶颈，别再从毛玻璃下手了。**
 * 真要追帧率，先在真机上量，别在模拟器上量。
 */
@Composable
fun Modifier.glassSurface(
    shape: Shape = GlassCardShape,
    tint: Color = GlassTintNeutral,
    tintAlpha: Float = GLASS_FACE_ALPHA,
    baseCoatAlpha: Float = 0f,
    blurRadius: Dp = GlassBlurRadius,
    faceAlphaScale: Float = 1f,
    hazeWash: Float = 0f,
    noiseFactor: Float = GlassGrain,
    hazeFloor: Float = 0f,
    hazeState: HazeState? = LocalGlassHazeState.current,
): Modifier {
    val useHaze = blurRadius > 0.dp && hazeState != null

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
        // B. 白衬底（可选）：糊完之后、染色之前。它必须在染色**下面**——
        // 反过来就成了「在粉彩上再刷一层白」，等于把课程色洗淡。
        // 也跟着 [faceAlphaScale] 走：已完成待办要整张卡一起变淡，
        // 只有染色跟着变的话那张卡会一边变白一边还留着原来的色。
        .then(
            if (baseCoatAlpha > 0f) {
                Modifier.background(
                    color = Color.White.copy(
                        alpha = (baseCoatAlpha * faceAlphaScale).coerceIn(0f, 1f)
                    ),
                    shape = shape
                )
            } else {
                Modifier
            }
        )
        // C. 染色面。纯白（白衬底 + 壁纸）从底下透上来，所以是「染色」而不是实色块。
        .background(
            color = tint.copy(alpha = (tintAlpha * faceAlphaScale).coerceIn(0f, 1f)),
            shape = shape
        )
        // 这里原来还挂了一条 0.5dp 的白描边（玻璃边缘折射高光），已删：
        // 白边在浅色壁纸上勉强算材质，在深色或花纹壁纸上是硬亮的一圈，
        // 而卡片与卡面的分界现在由白衬底 + 更大的模糊半径承担。
}

/**
 * 小控件的模糊半径：顶栏图标按钮、课表分段控件、周几表头共用这一档。
 *
 * 这三处原来都是**平涂**的实色（`.background(color)`，alpha 1.0），背后什么都没有参与，
 * 所以半径 0 就是它们的真实起点，「增加到 18」是从无到有而不是调大。
 *
 * 为什么是 18 而不是跟着卡片走 [GlassBlurRadius] = 28：小控件本身只有 30~44dp 高，
 * 一张 28.dp 的核会把它自己下缘以外的东西全拉进来，控件读起来是「一块模糊的斑」而不是
 * 「一块磨砂的片」。18.dp 是能看出背后那片壁纸被摊开、又不至于吃掉控件自身边界的档。
 * 底色同时降到半透明，于是「看得见的东西」从实色块换成了背后的壁纸。
 *
 * ⚠️ **不要拿这一档去改浮岛**（[com.example.ui.components.FloatingIslandNav]）：
 * 浮岛背后是正在滚过去的内容，96.dp 那一档是专门用来压住频闪的，见 Surfaces.kt 的注释。
 */
val ControlBlurRadius = 18.dp

/**
 * 小控件**中性面**的不透明度（搜索按键、分段控件外框、非今天的周几表头）。
 *
 * 这些底色本来就是浅冷灰（`#F1F5F9` / `surfaceVariant`），满不透明度时是一块实色片；
 * 压到 0.60 之后它只负责把控件从壁纸上「托」出来一点，透上来的颜色交给 18.dp 那层模糊。
 *
 * 别再往下压：中性色与默认壁纸的色差本身只有 6 个色阶（实测 `#F1F5F9` vs `#F7F6E1`），
 * alpha 每降 0.1 只剩 2~3 个色阶，到 0.4 附近控件的边界就基本并进壁纸里了。
 */
const val ControlNeutralFaceAlpha = 0.60f

/**
 * 小控件**主色面**的不透明度（生成按键、分段控件的选中块、今天的周几表头）。
 *
 * 比中性面高一档，因为这三处要靠颜色本身被认出（尤其「今天」那一格是整列的唯一标识，
 * 分段控件的选中块是一块会动的色，糊了它就没人知道当前在哪一档），透明度的余量
 * 不能和「只是托一下」的中性面一样给。
 *
 * 浅绿 `#BCE472` 本身亮度很高，压到 0.72 之后仍然是可辨的绿，而深墨前景 `#1E2D0E`
 * 在其上的对比度几乎不变——透明的是底色，压不透明度不会吃掉文字。
 */
const val ControlAccentFaceAlpha = 0.72f

/**
 * 小控件的玻璃面：[glassSurface] 在「小而扁」这一档上的封装。
 *
 * 四个调用点（顶栏图标按钮、课表分段控件、周几表头、清单页的十颗筛选 chip）此前各写各的 `.background(color)`，
 * 实色、无模糊、alpha 1.0，是全 App 唯一没有参与玻璃语言的几块面。收到这里之后：
 *
 *   * 模糊半径统一 [ControlBlurRadius]（18.dp）；
 *   * 面统一降到 [ControlNeutralFaceAlpha] / [ControlAccentFaceAlpha]；
 *   * 顺带把磨砂颗粒（[GlassGrain]）带上——它绑在 hazeStyle 上，不走 haze 就没有颗粒，
 *     而没有颗粒的半透明面只是一块淡色板，怎么调 alpha 都出不来玻璃感。
 *
 * 顶栏在内容的 hazeSource **之外**（它属于 Scaffold 的 topBar），但壁纸那一层在源层里，
 * 所以它糊的正是背后那张壁纸；课表页内那两处在源层**之内**，各自是独立节点、
 * 不是自绘反馈，因此一样能取到纹理。
 */
@Composable
fun Modifier.controlSurface(
    shape: Shape,
    color: Color,
    accent: Boolean = false,
    hazeState: HazeState? = LocalGlassHazeState.current,
): Modifier = glassSurface(
    shape = shape,
    tint = color,
    tintAlpha = if (accent) ControlAccentFaceAlpha else ControlNeutralFaceAlpha,
    blurRadius = ControlBlurRadius,
    hazeState = hazeState,
)

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
