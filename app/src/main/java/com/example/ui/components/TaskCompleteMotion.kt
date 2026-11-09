package com.example.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.util.lerp
import com.example.ui.theme.MiuSurface
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 勾掉一条待办之后的动效节奏。
 *
 * ## 这段动效要回答的问题
 *
 * 默认筛选是「待完成」，所以勾掉一条 = 它**从清单里消失**。原来的写法是数据一变，
 * 行就没了（列表的 `animateItem` 特意只留位移、关掉淡出，见清单页的注释），
 * 用户看到的是「点了一下，那条待办凭空少了」——既没看见「已勾上」，
 * 也没看见它是怎么走的。
 *
 * 所以这段动效分四拍，按顺序回答四个问题：
 *
 * 1. **勾上了吗？** 圈填成主题绿、勾从无到有画出来（[TickDrawMs]），
 *    配一次轻触反馈。这一拍足够短，但必须是「画」而不是「啪地出现」。
 * 2. **它变成已完成了。** 卡片在这一拍里已经进入完成态：标题划线、卡面压暗。
 *    这里刻意**不另造一套「正在消失」的样式**——已完成的样式本来就存在，
 *    离场动画只是把它演一遍，用户下次在「已完成」筛选里看到的还是同一张卡。
 * 3. **它走了吗。** 卡片**原地**淡出，同时轻轻缩到 [ExitScale]：没有位移、
 *    锚点不挪、不朝任何一个方向跑。这张纸是被「抽走」的，不是被推开的——
 *    拿走它的信息量全在透明度上，再给它一个方向，反而是多此一举（见下节）。
 * 4. **空位怎么补。** 演完才把这一行从数据里去掉，剩下的卡片由列表的位移动画
 *    （[RiseSpec]）**滚上来**。这一拍不在这段代码里，但节奏写在这儿：
 *    它必须晚于第 3 拍起步。两拍同时开始的话，卡片还看得见、下面的卡已经在挪，
 *    空位读起来是被「挤」开的；分成先后两拍，才是「纸被抽走、剩下的纸自己落回原位」。
 *
 * ## 为什么不是「朝那颗圈收走」
 *
 * 早先的版本让卡片朝左侧勾选圈收走（锚点 6%、横向缩到 0.84、左移 12dp），
 * 想表达「动作从被按下的地方出发」。实机上这个位移太大、方向太明确：
 * 卡片宽 400dp 上下，横向缩 16% 等于右边缘内缩 60dp，整列读起来是「这张卡被压扁了」，
 * 而不是「这张纸被拿走了」；而且一张卡变形，旁边的卡不动，视线全被那一下侧移带走。
 * 现在收成「原地淡出 + 略微缩小」：手指按的地方由勾和触感回答，
 * 「它没了」由透明度回答，两件事各归各。
 *
 * ## 为什么不自己收行高
 *
 * 「卡片淡出的同时行高收到 0」看着更连贯，但那要每一帧改布局：LazyColumn 会重测整列、
 * 行内文字跟着重排，滚动中还会抖。而这一行本来就要从数据里消失，
 * 交给列表自己的位移动画收尾更稳，也留住了「先淡出、再让位」两拍之间的那一顿。
 *
 * ## 减少动态效果
 *
 * 进度用 [Animatable] + `animateTo`，动画时长跟着系统的「动画时长缩放」走：
 * 关掉时这条路直接跳到终点——卡片立刻进入完成态并被移除，信息一条不少，只是不演。
 */
object TaskCompleteMotion {

    /**
     * 勾从 0 描到 1 的时长。
     *
     * 160ms 是这一支里唯一「慢」的一点：再快就只剩闪一下，读不出「画」；
     * 再慢，连勾几条时第一批还没画完第二批就来了。
     */
    const val TickDrawMs = 160

    /** 勾晚 20ms 起步：先让圈填色领先半帧，勾是**落在**绿底上的，不是和底色一起变。 */
    const val TickDrawDelayMs = 20

    /**
     * 勾完到开始淡出之间的停顿。
     *
     * 这一拍是整段动效的「分量」所在，不省：没有它，勾和淡出叠在一起，
     * 看起来是「一闪就没」。90ms 刚好够眼睛确认那一下勾。
     *
     * 它同时兼着一件事：**完成确认弹窗开着时**，确认之后弹窗要先淡出，
     * 这段时间卡片在后面淡出用户根本看不见。所以淡出的起步整段后移到
     * [ExitLeadInMs]，弹窗退场和卡片淡出不会互相盖住。
     */
    const val BeatMs = 90

    /** 卡片开始淡出之前的静置时长（勾画完 + 一拍 + 余量）。 */
    const val ExitLeadInMs = TickDrawMs + TickDrawDelayMs + BeatMs + 20

    /**
     * 划线扫过去的时长。
     *
     * 勾画完之后，一条线从标题左端**扫**到右端——「做完」这件事除了打勾，
     * 还需要一个「划掉」的仪式。原来这里是 `TextDecoration.LineThrough`，
     * 啪地出现；现在这条线要看得见它走过去。
     */
    const val StrikeDrawMs = 180

    /**
     * 划线晚于勾半步起步。
     *
     * 勾是 20–180ms，划线 90–270ms：先看见勾，再看见线扫过去。
     * 它正好落在原来那段「静置一拍」（[BeatMs]）里，这一段于是不再是干等。
     */
    const val StrikeDrawDelayMs = 90

    /**
     * 淡出的时长。
     *
     * 170ms 和「勾」一样是一口气的量：从点下去到卡片消失总共 460ms，
     * 连勾十条也不会觉得在等。缓动取加速（`FastOutLinearIn`）而不是减速——
     * 离场是「被拿走」，越走越快才像被抽走；减速会停在原地收尾，像犹豫。
     */
    const val ExitFadeMs = 170

    /**
     * 淡出时先**洗白**：卡片在消失之前褪成一张白纸。
     *
     * 这是「清新淡彩」的另一半——离场不是变灰变暗，而是**变白、变透**。
     * 白是画在卡片自己身上的一层（见 `taskExitWhite`），所以它跟着同一层的 alpha
     * 一起淡，最后落回壁纸；不会出现「卡没了、白斑还在」。
     */
    const val ExitWhiteMs = 170

    /** 洗白的终点强度。0.55 只够把它洗成一张纸，再加就盖掉那道勾了。 */
    const val ExitWhiteAlpha = 0.55f

    /**
     * 组头淡出的时长。
     *
     * 一个组的待办全勾完了，组头不该「啪」地消失——它跟着最后一张卡走，
     * 但**晚一步**：卡先淡完，标题再退。两件事同时结束的话，读起来是
     * 「组名先没了，卡还赖在那儿」，顺序反了。
     */
    const val HeaderFadeMs = 160

    /**
     * 淡出时的终点缩放。
     *
     * 只缩 4%：**略微**缩小是给「它在退场」一个质感，不是给动作一个方向。
     * 再大就重新变成「这张卡在变形」——那正是上一版朝圆圈收走时的问题。
     * 缩放锚点保持默认的中心：卡片原地缩，四周同时退，不偏向任何一侧。
     */
    const val ExitScale = 0.96f

    /**
     * 下方卡片滚上来补位的时长。
     *
     * 240ms 比淡出（170ms）长一截，是故意的：这一拍要**看得见**。
     * 空位有大半张卡高，补位太快就成了一次跳变；慢下来，才读得出
     * 「剩下的纸自己落回原位」。缓动取减速收尾（`FastOutSlowIn`）——
     * 滚上来该是「落定」，不是「撞到位」。
     */
    const val RiseMs = 240

    /**
     * 补位动画的 spec，交给清单页 `LazyColumn` 的 `animateItem(placementSpec = ...)`。
     *
     * 列表默认的补位是 `spring(StiffnessMediumLow)`：弹性会让卡片在落位时回弹一两像素，
     * 大面积的卡回弹一次就是「橡皮」。这里换成定长的 tween，落位就是落位。
     * 组头折叠时下方卡片的让位走的是同一条 spec——同一个列表里，位移只有一种语法。
     */
    val RiseSpec: FiniteAnimationSpec<IntOffset> =
        tween(durationMillis = RiseMs, easing = FastOutSlowInEasing)

    /**
     * 「刚勾完」这条信号的有效期。
     *
     * 信号是全局的（ViewModel 记、界面消费），但**只有当时在看的那个列表**该演一遍。
     * 超过这个时长的信号直接丢掉：多半是用户在别的页面勾的，切回来时不该排队补演。
     *
     * 总览页和清单页共用这一档，两个页面才不会对「多久以内算刚勾完」各有一说。
     */
    const val CompletionSignalTtlMs = 2_000L
}

/**
 * 一行待办的离场动画。
 *
 * 只在「这一行刚刚被勾成已完成、但还要演完再走」时把 [playing] 置 true。
 * 演完调 [onFinished]，由调用方把这一行从数据里去掉，空位交给列表的位移动画。
 *
 * @param playing 是否正在离场。转成 false 不会倒退（行这时候已经该消失了）。
 * @param onFinished 演完之后的通知，**每次 true 只调一次**。
 */
@Composable
fun TaskExitAnimation(
    playing: Boolean,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    // 三个进度分开走：透明度加速离场（越走越快），缩放缓下来收（落定），
    // 洗白领先半步（先把纸洗白，再让它淡掉）。
    // 合成一个进度也能做，但三条曲线就得三选一，淡出会跟着缩放一起犹豫。
    val fade = remember { Animatable(1f) }
    val shrink = remember { Animatable(0f) }
    val white = remember { Animatable(0f) }
    // 回调用 rememberUpdatedState 收一下：调用方每次重组都会传一个新的 lambda，
    // 直接捕进来会让 LaunchedEffect 拿到的还是第一次那个，拿错作用域。
    val finished by rememberUpdatedState(onFinished)
    val stillPlaying by rememberUpdatedState(playing)
    var handedOff by remember { mutableStateOf(false) }

    LaunchedEffect(playing) {
        if (!playing) {
            // 演完了、或者调用方提前放行：三个进度都必须归位。
            // 这一行是**带 key 复用**的，remember 出来的 Animatable 会一路留着终点值——
            // 那样这一行就永远停在 alpha=0 上（看得见占位、看不见卡片）。
            fade.snapTo(1f)
            shrink.snapTo(0f)
            white.snapTo(0f)
            return@LaunchedEffect
        }
        delay(TaskCompleteMotion.ExitLeadInMs.toLong())
        // 三段同时跑、同一刻结束：卡片是在「退」的过程里一起变白、变淡、变小，
        // 不是先洗白再淡（那样会看到一张白卡在那里停一下）。
        coroutineScope {
            launch {
                fade.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(
                        durationMillis = TaskCompleteMotion.ExitFadeMs,
                        easing = FastOutLinearInEasing
                    )
                )
            }
            launch {
                shrink.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(
                        durationMillis = TaskCompleteMotion.ExitFadeMs,
                        easing = FastOutSlowInEasing
                    )
                )
            }
            launch {
                // 洗白取减速：一开始就迅速褪色，后半程几乎停在「白纸」上，
                // 于是透明度那一半消失时，看到的是「一张越来越透的白纸」。
                white.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(
                        durationMillis = TaskCompleteMotion.ExitWhiteMs,
                        easing = LinearOutSlowInEasing
                    )
                )
            }
        }
        handedOff = true
        finished()
    }

    // 兜底：这一行在演完之前被滚出视口、或者页面被切走时，动画协程会被取消，
    // [onFinished] 就不会来 —— 那一行会一直留在调用方的冻结表里（整张列表跟着僵住）。
    // 所以离场这一层要保证「只要走了，就一定交卷」，哪怕是被动的。
    DisposableEffect(Unit) {
        onDispose {
            if (stillPlaying && !handedOff) finished()
        }
    }

    // Animatable 本身不是 State：包一层再往下传，下面的 graphicsLayer 才能
    // 在绘制阶段读它（而不是在组合阶段读、每帧重组一次）。
    val fadeState = remember(fade) { fade.asState() }
    val shrinkState = remember(shrink) { shrink.asState() }
    val whiteState = remember(white) { white.asState() }

    Box(
        modifier = modifier
            .then(Modifier.taskExitLayer(fadeState, shrinkState))
            .then(Modifier.taskExitWhite(whiteState))
    ) {
        content()
    }
}

/**
 * 一行**纯淡出**：只动 alpha，不位移、不缩放、不洗白。
 *
 * 组头用得着它。组头不是卡片：跟着缩会像「被捏了一下」，洗白会在文字行上糊成一块白斑，
 * 它只要安静地退场。用完调 [onFinished]，由调用方把这一行从表里摘掉。
 */
@Composable
fun TaskRowFadeOut(
    playing: Boolean,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val fade = remember { Animatable(1f) }
    val finished by rememberUpdatedState(onFinished)
    val stillPlaying by rememberUpdatedState(playing)
    var handedOff by remember { mutableStateOf(false) }

    LaunchedEffect(playing) {
        if (!playing) {
            // 同 [TaskExitAnimation]：带 key 复用的行会把终点值一直留着，
            // 不归位的话这一行就永远停在 alpha=0 上。
            fade.snapTo(1f)
            return@LaunchedEffect
        }
        fade.animateTo(
            targetValue = 0f,
            animationSpec = tween(
                durationMillis = TaskCompleteMotion.HeaderFadeMs,
                easing = FastOutLinearInEasing
            )
        )
        handedOff = true
        finished()
    }

    // 被滚出视口时协程会被取消，回调不会来 —— 这一行就会永远留在调用方的表里。
    DisposableEffect(Unit) {
        onDispose {
            if (stillPlaying && !handedOff) finished()
        }
    }

    val fadeState = remember(fade) { fade.asState() }
    Box(modifier = modifier.graphicsLayer { alpha = fadeState.value.coerceIn(0f, 1f) }) {
        content()
    }
}

/**
 * 离场淡出 + 略微缩小。
 *
 * 两个进度都以 [State] 的形式传进来、在 `graphicsLayer` 的 lambda 里读：
 * 那是在**绘制阶段**执行的，读 state 只让这一层重绘，
 * 不会让整张卡片（连带里面所有 Text）跟着重组——和列表的 `scrollRevealAt` 同一个理由。
 * 反过来，在组合里写 `fade.value` 就会让这张卡每帧重组一次。
 *
 * 只动 `alpha` 和 `scale`，不动 `translationX/Y`：这一拍的全部意思就是「原地没了」。
 */
private fun Modifier.taskExitLayer(fade: State<Float>, shrink: State<Float>): Modifier =
    graphicsLayer {
        val alphaNow = fade.value
        val shrinkNow = shrink.value
        if (alphaNow >= 1f && shrinkNow <= 0f) return@graphicsLayer
        alpha = alphaNow.coerceIn(0f, 1f)
        val scale = lerp(1f, TaskCompleteMotion.ExitScale, shrinkNow.coerceIn(0f, 1f))
        scaleX = scale
        scaleY = scale
    }

/**
 * 离场洗白：在卡片自己的形状上盖一层白，让它褪成一张白纸再消失。
 *
 * 三个要点：
 *
 * - **画在卡片这一层里**（`drawWithContent` 接在 graphicsLayer 之后），
 *   所以它跟着同一份 alpha 一起淡，不会出现「卡没了、白斑还在」。
 * - **按下卡片的真实形状裁**：直接铺一个矩形会在四个圆角外露出白角 ——
 *   卡片是 18dp 圆角，白必须顺着同一个轮廓。
 * - **进度在绘制阶段读**：和透明度、缩放走同一套，读 state 只重绘这一层，
 *   不会让整张卡每帧重组。
 */
private fun Modifier.taskExitWhite(white: State<Float>): Modifier = drawWithContent {
    drawContent()
    val w = white.value.coerceIn(0f, 1f)
    if (w <= 0f) return@drawWithContent
    // 轮廓直接问形状要：卡片的圆角以后改了，这里跟着改，不会露出直角的边。
    val outline = MiuSurface.CardShape.createOutline(size, layoutDirection, this)
    val color = Color.White
    val alpha = TaskCompleteMotion.ExitWhiteAlpha * w
    when (outline) {
        is Outline.Rounded -> drawPath(
            path = Path().apply { addRoundRect(outline.roundRect) },
            color = color,
            alpha = alpha
        )
        is Outline.Generic -> drawPath(path = outline.path, color = color, alpha = alpha)
        is Outline.Rectangle -> drawRect(color = color, alpha = alpha)
    }
}
