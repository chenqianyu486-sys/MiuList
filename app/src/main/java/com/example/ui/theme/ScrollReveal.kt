package com.example.ui.theme

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first

/**
 * 「从下方滚入」的浮现带。
 *
 * 单位是**卡片中心离视口下缘还有多远**：卡片中心还在视口下缘上时进度为 0，
 * 中心高过视口下缘 [ScrollRevealBand] 这么多时进度为 1。
 *
 * 为什么锚在**中心**而不是上缘或下缘：
 *
 *  * 锚上缘 → 卡片要完全进入视口才不透明，于是「露头—透明」的那一截最难看；
 *  * 锚下缘 → 卡片下半截还在屏幕外就已经全不透明了，渐入被裁掉一半；
 *  * 锚中心 → 卡片**基本已经整张在屏幕上**的时候它才由虚转实，
 *    读起来是「一张卡浮出来了」，而不是「一张卡滑进来了」。
 *
 * 带子取 132.dp：浮岛（底部那枚毛玻璃胶囊）本身就占掉下缘约 72.dp，
 * 带子比它高出一截，渐入的那一段才有**露在浮岛之上**的部分可看 ——
 * 全藏在浮岛后面的话，用户只看到卡片「啪」地一下出现，等于没做。
 */
val ScrollRevealBand: Dp = 132.dp

/**
 * 浮现起点的缩放：卡片刚够到视口下缘时缩到这么大，再随进度涨回 1。
 *
 * 只缩 4%：再大一点，卡片在淡入的同时会明显「长大」，读成「弹了一下」；
 * 这道效果要的是**浮出**，不是**弹出**。
 */
const val ScrollRevealStartScale = 0.96f

/**
 * 浮现进度的纯函数版。
 *
 * @param centerToBottomPx 卡片中心到视口下缘的距离（像素）。负数 = 中心还在屏幕外。
 * @param bandPx 浮现带的高度（像素），见 [ScrollRevealBand]。
 */
fun scrollRevealProgress(centerToBottomPx: Float, bandPx: Float): Float {
    if (bandPx <= 0f) return 1f
    return (centerToBottomPx / bandPx).coerceIn(0f, 1f)
}

/**
 * 卡片的「从下方滚入」浮现修饰符：淡入 + 轻微放大。
 *
 * **进度由滚动位置决定，不由时间决定。** 这是它和「时间驱动的入场动画」最要紧的区别：
 * 时间驱动的版本在快速甩动列表时会看到卡片半透明地停在屏幕中间；
 * 而这里卡片走到哪一步就是哪一步，甩多快都不会穿帮。
 *
 * `.value` 与 `layoutInfo` 都刻意写在 `graphicsLayer` 的 lambda 里：
 * 那是在 draw 阶段执行的，读 snapshot state 只会让这一层重绘，
 * 不会让整张卡（连带它里面所有 Text 和图形）重组。理由同 [cardRevealAt]。
 *
 * @param listState 所在列表的状态。
 * @param key 这张卡在列表里的 key。**必须与喂给 `items` / `item` 的 key 一致**，
 *   否则在 `layoutInfo` 里找不到它（找不到就当它不在屏幕上，不做处理）。
 * @param enabled 见 [rememberScrollRevealEnabled]：用户还没滚过的时候整条效果不生效。
 * @param band 浮现带的高度。
 */
fun Modifier.scrollRevealAt(
    listState: LazyListState,
    key: Any,
    enabled: State<Boolean>,
    band: Dp = ScrollRevealBand
): Modifier = graphicsLayer {
    // `enabled` 刻意也是 draw 阶段读：它从 false 翻成 true 的那一刻正好是用户
    // **正在滚**的时候，那一刻不该让整屏重组一遍。
    if (!enabled.value) return@graphicsLayer
    val info = listState.layoutInfo
    // 找不到 = 这张卡不在视口里（还没滚进来，或者已经滚过去了）。
    // 不在视口里的卡片本来就不会被画，改不改透明度都一样，
    // 所以这里直接跳过：顺带避开了「首帧 layoutInfo 还是空的 → 整屏空白」那个坑。
    val item = info.visibleItemsInfo.firstOrNull { it.key == key } ?: return@graphicsLayer
    val centerToBottom = info.viewportEndOffset - (item.offset + item.size / 2)
    val progress = scrollRevealProgress(centerToBottom.toFloat(), band.toPx())
    alpha = progress
    val scale = scrollRevealScale(progress)
    scaleX = scale
    scaleY = scale
}

/**
 * 浮现进度 → 缩放。
 *
 * 起点是 [ScrollRevealStartScale]（缩着），终点是 1（正常大小），中间线性插值。
 */
fun scrollRevealScale(progress: Float): Float =
    ScrollRevealStartScale + (1f - ScrollRevealStartScale) * progress.coerceIn(0f, 1f)

/**
 * 「用户真的滚过没有」—— 粘滞标志，只从 false 变 true。
 *
 * 为什么要有这道闸：**刚打开页面时底部那张卡本来就在屏幕上**。
 * 若此时就按位置算进度，它的中心正压在视口下缘上、进度接近 0，
 * 于是这张卡**一直是透明的** —— 用户看到的是「页面底部少了一张卡」，
 * 像出了 bug，而不是一个动画。
 *
 * 所以这条效果只在用户**自己滚过之后**才启用。粘滞（滚回顶部也不关掉）是因为
 * 「滚回去」不该把已经浮现过的卡片重新变透明。
 *
 * 用 `remember` 而不是 `rememberSaveable`：这个标志要跟着**页面实例**走。
 * `rememberSaveable` 会在切页再切回来时把 true 恢复回来，于是那一屏又会缺一张卡。
 */
@Composable
fun rememberScrollRevealEnabled(listState: LazyListState): State<Boolean> {
    val enabled = remember { mutableStateOf(false) }
    LaunchedEffect(listState) {
        snapshotFlow {
            // firstVisibleItemScrollOffset 是第一个可见条目被滚过去的那一段；
            // firstVisibleItemIndex 是它的下标。两者取或：
            // 只滚了几像素时偏移已经变了，而只滚了一整条时下标才变。
            listState.firstVisibleItemScrollOffset > 0 || listState.firstVisibleItemIndex > 0
        }
            .filter { it }
            .first()
        enabled.value = true
    }
    return enabled
}
