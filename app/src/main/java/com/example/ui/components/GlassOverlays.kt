package com.example.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.example.ui.theme.GlassTintNeutral
import com.example.ui.theme.OverlayHazeState
import com.example.ui.theme.glassSurface
import kotlinx.coroutines.launch

/** 弹层圆角。原来所有 AlertDialog 都写死 20.dp，页内卡片也是同一档。 */
val GlassDialogShape = RoundedCornerShape(20.dp)

/** 底部弹层的圆角：只圆上面两角，和 M3 底部抽屉的默认形状一致。 */
val GlassSheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)

/**
 * 弹层玻璃面的取值。**和页内卡片那套不是同一组参数**，原因有三个：
 *
 * 1. 背后不是纯白页面底，而是**一整屏有字有色的内容**。页内卡片背后可能是空白，
 *    弹层背后永远有东西，所以模糊半径要大得多（40 vs 16），糊到只剩色块为止——
 *    糊到还能认出字就等于没糊。
 * 2. 必须叠一层洗白（wash）。玻璃面是半透明的，背后压暗过的文字会直接透上来，
 *    而弹层里全是 13~17sp 的正文，它扛不住。洗白是把背光整体往白拉，
 *    等于给正文重新铺了一层底。
 * 3. 要亮度地板（floor）。同 Mica.kt 里 HazeFloorColor 那条理由：背后内容滚动/刷新时，
 *    深色文字进出会让玻璃面一闪一闪，地板把这段动态钳平。
 *
 * 面不透明度也高于页内卡片（0.72 vs 0.48）：页内卡片要透，弹层要稳，
 * 这里的取舍是「透出颜色，不透出内容」。
 */
private object GlassOverlay {
    const val FACE_ALPHA = 0.72f
    val BLUR_RADIUS = 40.dp
    const val WASH = 0.42f
    const val FLOOR = 0.5f
    /**
     * 遮罩。给得很轻：遮罩是**压暗背后那层**的，压重了玻璃面就白做了——
     * 玻璃的意义就是「透」，背后被压成一片黑就没有可透的东西了。
     * M3 的 Dialog 默认给 0.32，这里砍掉一半还多。
     */
    const val SCRIM = 0.14f
}

/**
 * 弹层玻璃面。haze 状态必须显式给 [OverlayHazeState]——弹层虽然和页面同窗口了，
 * 但它是 MainApp 里独立的一段 Composition，拿不到页面那层挂的 CompositionLocal。
 */
@Composable
fun Modifier.glassOverlaySurface(shape: Shape): Modifier = glassSurface(
    shape = shape,
    tint = GlassTintNeutral,
    tintAlpha = GlassOverlay.FACE_ALPHA,
    blurRadius = GlassOverlay.BLUR_RADIUS,
    hazeWash = GlassOverlay.WASH,
    hazeFloor = GlassOverlay.FLOOR,
    hazeState = OverlayHazeState,
)

/**
 * 毛玻璃弹层宿主，参数与 M3 的 `AlertDialog` 一致，替换时只需改函数名、删掉 containerColor。
 *
 * **为什么不用 M3 的 AlertDialog**（两条，都是实测出来的）：
 *
 * 1. 它的容器 Surface 由 M3 内部绘制，内容组件 `AlertDialogContent` 是 **internal** 的，
 *    外面拿不到，也就挂不上 `hazeEffect`。而毛玻璃要的恰恰是模糊，
 *    半透明不透明色块不叫毛玻璃。
 * 2. 更根本的一条：`Dialog` / `ModalBottomSheet` 跑在**独立窗口**里，而 haze 1.3.1
 *    读不到别的窗口的源层。实测把玻璃面挂进 Dialog 之后，背后的字清清楚楚透上来，
 *    一个像素都没糊。所以弹层改成和页面同窗口的 overlay，haze 才接得上——
 *    这条和 MoreDrawer 是同一个前提，只是那里本来就做对了。
 *
 * 代价是原来由 Dialog 窗口白送的三件事要自己补：返回键、点外面关闭、点击不穿透。
 * 三件都在下面实现了，缺一件的表现都是「像逻辑 bug 而不是渲染 bug」。
 */
@Composable
fun GlassOverlayHost(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    shape: Shape = GlassDialogShape,
    scrimAlpha: Float = GlassOverlay.SCRIM,
    horizontalPadding: Dp = 40.dp,
    alignment: Alignment = Alignment.Center,
) {
    val coroutineScope = rememberCoroutineScope()
    var isDismissing by remember { mutableStateOf(false) }
    val enterAnimatable = remember { Animatable(0f) }

    val dismissWithAnimation: () -> Unit = {
        if (!isDismissing) {
            isDismissing = true
            coroutineScope.launch {
                enterAnimatable.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(durationMillis = 160, easing = FastOutLinearInEasing)
                )
                onDismissRequest()
            }
        }
    }

    LaunchedEffect(Unit) {
        enterAnimatable.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = 0.85f,
                stiffness = 450f
            )
        )
    }

    BackHandler(enabled = true) {
        dismissWithAnimation()
    }

    val currentProgress = enterAnimatable.value.coerceIn(0f, 1f)
    val currentScrimAlpha = scrimAlpha * currentProgress

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = currentScrimAlpha))
            // 遮罩层自己吃掉点击：外面那一大片空白就是「点外面关闭」的区域。
            // indication 置 null，只消费事件、不留涟漪。
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { dismissWithAnimation() },
        contentAlignment = alignment,
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = horizontalPadding)
                .padding(bottom = imeCompensation(maxHeight))
                .widthIn(max = 560.dp)
                .graphicsLayer {
                    alpha = currentProgress
                    val scale = 0.92f + 0.08f * currentProgress
                    scaleX = scale
                    scaleY = scale
                }
                .glassOverlaySurface(shape)
                // 面板根吃掉点击，否则点面板的空白会当成「点外面」把弹层关掉。
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) {}
                .selectableGroup()
                .padding(24.dp),
        ) {
            title?.let {
                Box(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) { it() }
            }
            text?.let {
                Box(modifier = Modifier.fillMaxWidth()) { it() }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                dismissButton?.invoke()
                confirmButton()
            }
        }
    }
}

/**
 * 键盘顶起的高度，**只在窗口没被系统收缩过时才补**。
 *
 * 这一段是 Dialog 换成 overlay 之后最容易埋雷的地方：Compose 的 `imePadding()` 无条件
 * 加上 IME inset，但 Activity 若还是 `adjustResize`，窗口已经先被系统缩过一次了，
 * 两笔一起加，弹层会被顶出屏幕顶部（而且是「越高越离谱」，因为 inset 是固定的）。
 *
 * 所以改成先量：这个 overlay 的高度明显小于屏幕高度，就说明系统已经 resize 过了，
 * 这时 `maxHeight` 本身就是可见区，补 0；否则才用 IME inset 顶上去。
 * 判据用 24.dp 容差，避开状态栏/导航栏那点零头。
 */
@Composable
private fun imeCompensation(overlayHeight: Dp): Dp {
    val density = LocalDensity.current
    val screenHeight = with(LocalConfiguration.current) { screenHeightDp.dp }
    if (overlayHeight < screenHeight - 24.dp) return 0.dp
    return with(density) { WindowInsets.ime.getBottom(this).toDp() }
}

/**
 * 毛玻璃底部弹层（替代 `ModalBottomSheet`）。
 *
 * 展开与收起动效设计：
 * 1. 物理弹簧展开：初始帧定位在屏幕底部之外（结合首帧兜底保证零闪烁），以物理阻尼弹簧平滑升起；
 * 2. 遮罩层（Scrim）与透明度渐变同步：随弹层升起同步淡入，随收起或拖拽平滑淡出；
 * 3. 拖拽胶囊指示条（Drag Handle）：顶部居中展示 36×4.5dp 圆角胶囊，直观表达弹层交互性；
 * 4. 下拉手势收起（Drag-to-dismiss）：支持顶部指示条直接拖拽，并配合 NestedScrollConnection 支持内容联动；
 * 5. 阻尼与弹性复位：向上推带有橡胶阻尼感，下拉未达阈值松手时自然回弹，超过阈值顺势滑出屏幕；
 * 6. 返回键/点击遮罩平滑退场：播放顺滑的下沉淡出动画后再触发销毁。
 */
@Composable
fun GlassBottomSheetHost(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = GlassSheetShape,
    scrimAlpha: Float = GlassOverlay.SCRIM,
    showDragHandle: Boolean = true,
    content: @Composable () -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }

    // 弹层高度（像素），测量完成后更新
    var sheetHeightPx by remember { mutableFloatStateOf(0f) }

    // 展开动画进度：0f = 完全在屏幕底部外，1f = 完全展开就位
    val enterAnimatable = remember { Animatable(0f) }

    // 实时下拉拖拽偏移量（向下拖为正）
    var dragOffsetY by remember { mutableFloatStateOf(0f) }

    // 防止多次触发关闭回调
    var isDismissing by remember { mutableStateOf(false) }

    val dismissWithAnimation: () -> Unit = {
        if (!isDismissing) {
            isDismissing = true
            coroutineScope.launch {
                enterAnimatable.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(durationMillis = 220, easing = FastOutLinearInEasing)
                )
                onDismissRequest()
            }
        }
    }

    LaunchedEffect(Unit) {
        enterAnimatable.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = 0.82f,
                stiffness = 380f
            )
        )
    }

    BackHandler(enabled = true) {
        dismissWithAnimation()
    }

    // 在第 0 帧尚未完成测量时，默认使用 screenHeightPx，确保首帧绝对在屏幕外，无任何闪烁
    val effectiveHeight = if (sheetHeightPx > 0f) sheetHeightPx else screenHeightPx
    val enterOffsetY = effectiveHeight * (1f - enterAnimatable.value)
    val currentOffsetY = (enterOffsetY + dragOffsetY).coerceAtLeast(0f)

    val currentFraction = if (effectiveHeight > 0f) {
        (1f - (currentOffsetY / effectiveHeight)).coerceIn(0f, 1f)
    } else {
        enterAnimatable.value
    }

    val currentScrimAlpha = scrimAlpha * currentFraction

    val velocityTracker = remember { VelocityTracker() }

    val defaultDismissThreshold = with(density) { 120.dp.toPx() }
    val dismissVelocityThreshold = with(density) { 1000.dp.toPx() }

    val handleDragEnd: (Float) -> Unit = { velocityY ->
        val dismissThreshold = if (sheetHeightPx > 0f) sheetHeightPx * 0.28f else defaultDismissThreshold
        if (dragOffsetY > dismissThreshold || velocityY > dismissVelocityThreshold) {
            dismissWithAnimation()
        } else {
            coroutineScope.launch {
                val anim = Animatable(dragOffsetY)
                anim.animateTo(
                    targetValue = 0f,
                    animationSpec = spring(
                        dampingRatio = 0.82f,
                        stiffness = Spring.StiffnessMediumLow
                    )
                ) {
                    dragOffsetY = value
                }
            }
        }
    }

    val nestedScrollConnection = remember(sheetHeightPx) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val delta = available.y
                if (delta < 0 && dragOffsetY > 0f) {
                    val consumed = delta.coerceAtLeast(-dragOffsetY)
                    dragOffsetY += consumed
                    return Offset(0f, consumed)
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                val delta = available.y
                if (delta > 0 && source == NestedScrollSource.UserInput) {
                    dragOffsetY += delta
                    return Offset(0f, delta)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (dragOffsetY > 0f) {
                    handleDragEnd(available.y)
                    return available
                }
                return Velocity.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                handleDragEnd(available.y)
                return available
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = currentScrimAlpha))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { dismissWithAnimation() },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .onGloballyPositioned { coordinates ->
                    if (coordinates.size.height > 0) {
                        sheetHeightPx = coordinates.size.height.toFloat()
                    }
                }
                .graphicsLayer {
                    translationY = currentOffsetY
                    alpha = (0.2f + 0.8f * currentFraction).coerceIn(0f, 1f)
                }
                .glassOverlaySurface(shape)
                .nestedScroll(nestedScrollConnection)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) {}
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (showDragHandle) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .pointerInput(Unit) {
                                detectVerticalDragGestures(
                                    onDragStart = {
                                        velocityTracker.resetTracking()
                                    },
                                    onVerticalDrag = { change, dragAmount ->
                                        change.consume()
                                        velocityTracker.addPosition(change.uptimeMillis, change.position)
                                        if (dragAmount > 0) {
                                            dragOffsetY += dragAmount
                                        } else {
                                            if (dragOffsetY > 0) {
                                                dragOffsetY = (dragOffsetY + dragAmount).coerceAtLeast(0f)
                                            } else {
                                                dragOffsetY += dragAmount * 0.2f
                                            }
                                        }
                                    },
                                    onDragEnd = {
                                        handleDragEnd(velocityTracker.calculateVelocity().y)
                                    },
                                    onDragCancel = {
                                        handleDragEnd(0f)
                                    }
                                )
                            }
                            .padding(top = 10.dp, bottom = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(width = 36.dp, height = 4.5.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f))
                        )
                    }
                }
                content()
            }
        }
    }
}

/** 底部弹层里的标准内容块：留出底部呼吸、左右对齐，支持内容超出时优雅滚动。 */
@Composable
fun SheetContent(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 4.dp, bottom = 28.dp)
    ) {
        content()
    }
}

/** 两个内容块之间的一行呼吸。 */
@Composable
fun SheetGap(height: Dp) {
    Spacer(modifier = Modifier.height(height))
}