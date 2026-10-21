package com.example.ui.components

import android.view.WindowManager
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
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.example.ui.theme.GlassTintNeutral
import com.example.ui.theme.LocalEffectiveFontScale
import com.example.ui.theme.LocalLayoutScale
import com.example.ui.theme.OverlayHazeState
import com.example.ui.theme.adaptivePanelPadding
import com.example.ui.theme.glassSurface
import com.example.ui.theme.panelSafeArea
import com.example.ui.theme.rememberContentMaxWidth
import com.example.ui.theme.scaledWithText
import kotlinx.coroutines.launch

/** 弹层圆角。原来所有 AlertDialog 都写死 20.dp，页内卡片也是同一档。 */
val GlassDialogShape = RoundedCornerShape(20.dp)

/** 底部弹层的圆角：只圆上面两角，和 M3 底部抽屉的默认形状一致。 */
val GlassSheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)

/**
 * 弹层玻璃面的取值。**和页内卡片那套不是同一组参数**，原因有三个：
 *
 * 1. 背后不是纯白页面底，而是**一整屏有字有色的内容**。页内卡片背后可能是空白，
 *    弹层背后永远有东西，所以模糊半径要大于页内卡片那一档，糊到只剩色块为止。
 *    但**居中弹窗和底部弹层不是同一个数**：前者 40.dp（背后是页面底，糊狠一点更稳），
 *    后者 24.dp（背后是满屏彩色格子，糊狠了就成了白板），见 [GlassSheetBlurRadius]。
 * 2. 必须叠一层洗白（wash）。玻璃面是半透明的，背后压暗过的文字会直接透上来，
 *    而弹层里全是 13~17sp 的正文，它扛不住。洗白是把背光整体往白拉，
 *    等于给正文重新铺了一层底。
 * 3. 要亮度地板（floor）。同 Mica.kt 里 HazeFloorColor 那条理由：背后内容滚动/刷新时，
 *    深色文字进出会让玻璃面一闪一闪，地板把这段动态钳平。
 *
 * 面不透明度也高于页内卡片（0.72 vs 0.48）：页内卡片要透，弹层要稳，
 * 这里的取舍是「透出颜色，不透出内容」。
 */
/**
 * 弹层遮罩的不透明度。给得很轻：遮罩是**压暗背后那层**的，压重了玻璃面就白做了——
 * 玻璃的意义就是「透」，背后被压成一片黑就没有可透的东西了。
 * M3 的 Dialog 默认给 0.32，这里砍掉一半还多。
 *
 * 提成文件级常量而不是只放在 [GlassOverlay] 里：还有几处弹窗因为嵌套关系
 * 仍然是独立窗口（独立窗口里的 haze 读不到页面那层源层，做不了毛玻璃），
 * 它们拿不到 [GlassOverlayHost]，但遮罩得跟全 App 对齐，只能直接引用这个数。
 */
const val GlassOverlayScrimAlpha = 0.14f

private object GlassOverlay {
    const val FACE_ALPHA = 0.72f
    val BLUR_RADIUS = 40.dp
    const val WASH = 0.42f
    const val FLOOR = 0.5f
}

/**
 * **底部弹层**的玻璃面参数，**和居中弹窗那一套刻意不同**。
 *
 * 底部弹层要满足一件居中弹窗不需要的事：**背后那一屏该透的透上来**。
 *
 * 上一版的实测把这件事卡得很死（`GlassOverlay` 那一套 0.72 / 0.42）：以周课表上打开课程弹层为例，
 * 弹层面板覆盖的那块区域里 **30.6% 的采样点背后本来就有彩色内容**（课表格子、FAB、底部浮岛），
 * 可面板一亮，那 30.6% 全都读成同一个 `(239,241,244)`——背后有东西，可一点都透不出来。
 *
 * 于是两件事必须分开记：
 *
 * 1. **背后有东西** 是打开位置决定的，不是参数决定的。课表下半屏本来就是空白网格，
 *    那个画面再怎么调参也透不出花来（见 [GlassSheetBlurRadius]）。
 * 2. **透不透得出来** 是这两个数决定的：`FACE_ALPHA` 是「不透明面盖住多少」，
 *    `WASH` 是「糊完之后往白拉多少」。0.72 + 0.42 叠起来只剩 28% 的背光，
 *    糊过一轮的低频颜色基本归零，于是无论背后有什么都读成一块白板。
 *
 * 0.72 → 0.55、0.42 → 0.30：留 45% 背光，把糊过的颜色放回来，又不至于让弹层里
 * 13~17sp 的正文直接坐在花底上（正文全是深色，落在最浅的一档粉彩上仍有 10:1 以上对比）。
 * 模糊半径仍走 [GlassSheetBlurRadius]，它管「透出多少结构」，这两个数管「透出多少对比」。
 */
private object GlassSheetFace {
    const val FACE_ALPHA = 0.55f
    const val WASH = 0.30f
    const val FLOOR = GlassOverlay.FLOOR
}

/**
 * **底部弹层**的模糊半径，24.dp。
 *
 * 它和居中弹窗那一档（[GlassOverlay.BLUR_RADIUS] 40.dp）不是同一个数，两个原因：
 *
 * 1. **弹层更高、背后更有结构**。居中弹窗只占屏幕中间一小块，两侧露出来的仍是页面底；
 *    底部弹层从屏幕底部一路铺上来，背后压着的通常是周课表那种满屏彩色格子。
 * 2. **同一个底不该配两个半径**。全 App 的弹层面只此两处（课程详情 / 待办详情），
 *    它们共用 [GlassBottomSheetHost]，所以这个数只需要一处真源。
 *
 * 别把它当「模糊强度的旋钮」用：2026-10-18 实测，40 → 24 在课表这一屏上面板区域
 * 取 18000 个采样点，**最大通道差 0**，两个构建逐像素一致——因为弹层底下多半是空白网格，
 * 糊白底等于没糊。改这个数之前先看背后有没有东西（见 [GlassSheetFace] 的注释）。
 */
val GlassSheetBlurRadius = 24.dp

/**
 * 弹层玻璃面。haze 状态必须显式给 [OverlayHazeState]——弹层虽然和页面同窗口了，
 * 但它是 MainApp 里独立的一段 Composition，拿不到页面那层挂的 CompositionLocal。
 *
 * @param blurRadius 背景模糊半径。居中弹窗走 [GlassOverlay.BLUR_RADIUS]，
 *   底部弹层走 [GlassSheetBlurRadius]。
 * @param faceAlpha / wash 不透明面与洗白的强度。居中弹窗走 [GlassOverlay] 那一档，
 *   底部弹层走 [GlassSheetFace] 那一档。
 */
@Composable
fun Modifier.glassOverlaySurface(
    shape: Shape,
    blurRadius: Dp = GlassOverlay.BLUR_RADIUS,
    faceAlpha: Float = GlassOverlay.FACE_ALPHA,
    wash: Float = GlassOverlay.WASH,
    hazeFloor: Float = GlassOverlay.FLOOR,
): Modifier = glassSurface(
    shape = shape,
    tint = GlassTintNeutral,
    tintAlpha = faceAlpha,
    blurRadius = blurRadius,
    hazeWash = wash,
    hazeFloor = hazeFloor,
    hazeState = OverlayHazeState,
)

/**
 * 把**当前这个独立窗口**的遮罩压暗程度对齐全 App 弹层（[GlassOverlayScrimAlpha]）。
 *
 * 给那些因为嵌套关系必须是独立窗口、拿不到 haze 因而做不了玻璃面的弹层用
 * （`DatePickerDialog`、包时间选择器的裸 `Dialog`）：玻璃做不到，**压暗至少要对齐**，
 * 否则同一个 App 里会同时存在 0.14 和 0.32 两种遮罩。
 *
 * 为什么不能靠 `DialogProperties`：当前 Compose（UI 1.7.x）的 `DialogProperties`
 * 只有 `dismissOnBackPress` / `dismissOnClickOutside` / `securePolicy` /
 * `usePlatformDefaultWidth` / `decorFitsSystemWindows`，**没有任何遮罩参数**——
 * 弹层遮罩是平台按主题给窗口的。只能自己拿到底层 `Window` 设 dimAmount。
 *
 * 必须在 `Dialog` 的**内容**里调用：那时 `LocalView.current.parent` 才是那个窗口。
 */
@Composable
fun AlignDialogWindowScrim() {
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window
    SideEffect {
        if (window != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window.setDimAmount(GlassOverlayScrimAlpha)
        }
    }
}

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
    /**
     * 挂在**面板**（那个 Column）上的 modifier。默认 no-op，不影响任何现有调用点。
     *
     * M3 `AlertDialog` 的 `modifier` 挂在面板上，所以从它迁移过来的调用点想挂
     * testTag 时要走这里，而不是上面的 `modifier`：后者挂在遮罩那个 fillMaxSize 的
     * Box 上，挂上去 bounds 会变成全屏，语义节点指向的是「点一下就关弹窗」的那一层。
     */
    panelModifier: Modifier = Modifier,
    dismissButton: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    /**
     * 内容区之后、按钮行之前的一小块**不滚动**区域。
     *
     * 给「保存被拦下时要说的话」这类东西用：表单内容一长就被推进滚动区看不见了，
     * 而用户点完保存正盯着按钮 —— 提示出现在视口外，表现就和「点了没反应」一样。
     * 默认 null，其它弹层不受影响。
     */
    belowContent: @Composable (() -> Unit)? = null,
    shape: Shape = GlassDialogShape,
    scrimAlpha: Float = GlassOverlayScrimAlpha,
    horizontalPadding: Dp = 40.dp,
    alignment: Alignment = Alignment.Center,
) {
    val coroutineScope = rememberCoroutineScope()
    var isDismissing by remember { mutableStateOf(false) }
    val enterAnimatable = remember { Animatable(0f) }
    val density = LocalDensity.current

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
    // 面板宽度上限跟着**内容列**走：宽屏上弹层比底下那一列还宽，
    // 看起来就像弹层不属于这个 App；而内容列本身现在会随窗口放宽
    // （手机 480dp、平板最高 680dp，见 Responsive.kt）。
    val contentMaxWidth = rememberContentMaxWidth()
    val effectiveFontScale = LocalEffectiveFontScale

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
        // 弹层面板只在**安全区**内排布。
        //
        // 之前 maxHeight 直接取整屏高度、面板垂直居中，于是高弹层（教务同步、
        // 课程编辑这些表单长的）会一头钻进状态栏、一头压住导航栏。异形屏上
        // 刘海/挖孔正好落在那儿，标题被系统图标压住，底部按钮被导航条切掉。
        //
        // 现在把面板整体放进安全区（状态栏 + 导航栏 + 刘海/挖孔 + 键盘），
        // 并用**安全区后的可用高度**作为上限。
        val safeInsets = panelSafeArea
        val layoutDirection = LocalLayoutDirection.current
        val safeTopPx = safeInsets.getTop(density)
        val safeBottomPx = safeInsets.getBottom(density)
        // 左右两侧要按布局方向取：RTL 下 getLeft 会变成右边。
        val safeLeftPx = safeInsets.getLeft(density, layoutDirection)
        val safeRightPx = safeInsets.getRight(density, layoutDirection)
        val safeTop = with(density) { safeTopPx.toDp() }
        val safeBottom = with(density) { safeBottomPx.toDp() }
        val safeLeft = with(density) { safeLeftPx.toDp() }
        val safeRight = with(density) { safeRightPx.toDp() }
        // 可用高度 = 整屏 - 上下安全区，再留 32dp 呼吸。
        val availableHeight =
            (maxHeight - safeTop - safeBottom - 32.dp).coerceAtLeast(0.dp)
        // 屏幕宽度在**外层**取：内层 Box 拿不到 BoxWithConstraints 的 scope。
        val screenWidth = maxWidth
        // 面板左右留白：基准屏 40dp；窄屏按屏宽收；
        // **大字号时再收到 16dp**——40dp 在系统字号 2.0 时会把 411dp 的屏
        // 压成 331dp 的面板，标题、按钮全被挤成两三行。
        // 调用点显式给了更小值的（长表单那三个）仍然照它自己的来。
        val horizontalMargin =
            if (effectiveFontScale > 1.3f) {
                minOf(horizontalPadding, 16.dp)
            } else {
                minOf(horizontalPadding, adaptivePanelPadding(screenWidth))
            }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = safeLeft, end = safeRight, top = safeTop, bottom = safeBottom),
            contentAlignment = alignment
        ) {
            // 把「这块面板还剩多少高度」告诉内容区：
            // 弹层里那些**写死高度**的可滚动区（课程详情的关联待办列表、
            // 快速生成的预览、导入预览）要按它封顶 ——
            // 写死的 dp 在大字号下装不下放大的内容，在矮屏上又白占一截。
            CompositionLocalProvider(LocalPanelAvailableHeight provides availableHeight) {
            Column(
                modifier = panelModifier
                    .padding(horizontal = horizontalMargin)
                    // 面板宽度上限跟着**内容列**走（见上面的 contentMaxWidth）：
                    // 宽屏上弹层比底下那一列还宽，看起来就像弹层不属于这个 App。
                    .widthIn(max = contentMaxWidth)
                    .heightIn(max = availableHeight)
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
                    // 文本区**按内容高度占位**（fill = false）：
                    //
                    // - 内容短时面板跟着收，不留一大片空白（周次选择这种三行弹层
                    //   以前会被撑到整屏高，按钮孤零零贴在底部）；
                    // - 内容长时会被约束在剩余高度内，由内层的 verticalScroll 滚动，
                    //   按钮行始终在面板底部可见（这一条各表单弹层自己保证）。
                    //
                    // 直接写死 `fill = true` 会把「始终占满」和「按钮不被裁」
                    // 当成同一件事：前者其实只是让短内容的面板变高，不良观感。
                    Box(modifier = Modifier.fillMaxWidth().weight(1f, fill = false)) { it() }
                }
                belowContent?.let {
                    Box(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { it() }
                }
                // 按钮行：大字号时**竖排**。
                //
                // 横排时两个按钮各自按内容宽度量，字号 2.0 下「修改账号/学期」
                // 与「清除已存凭据」加起来超过面板宽，于是各自在按钮内部折成
                // 两三行（实测截图）。竖排让每个按钮独占一行、文字不折，
                // 顺序仍是「取消/次要 → 确认/主要」，和横排时从左到右一致。
                if (effectiveFontScale > 1.3f) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        dismissButton?.invoke()
                        confirmButton()
                    }
                } else {
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
        }
    }
}

/**
 * 当前弹层面板的**可用高度**（dp）。
 *
 * 弹层里那些「写死高度的可滚动区」要按它封顶：
 * 写死的 dp 在大字号下装不下放大的内容，在矮屏上又会白占一截。
 *
 * 不在弹层里时是 [Dp.Unspecified] —— 调用方按「没有上限」处理。
 */
val LocalPanelAvailableHeight: ProvidableCompositionLocal<Dp> =
    staticCompositionLocalOf { Dp.Unspecified }

/**
 * 弹层内**可滚动区**的高度上限：跟着字号放大，但不许超过面板的可用高度。
 *
 * ## 为什么两个都要
 *
 * 只按字号放大：矮屏上这块区会顶出面板（面板自己会裁，裁掉的就是内容）。
 * 只按可用高度：大字号时字变高了、框没变，条目被挤得只剩半行。
 *
 * @param designHeight 基准字号下这块区的高度（dp）。
 */
@Composable
fun panelScrollMaxHeight(designHeight: Dp): Dp {
    val scaled = designHeight.scaledWithText()
    val panel = LocalPanelAvailableHeight.current
    return if (panel == Dp.Unspecified) scaled else minOf(scaled, panel)
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
    scrimAlpha: Float = GlassOverlayScrimAlpha,
    showDragHandle: Boolean = true,
    /**
     * 面板的模糊半径，默认 [GlassSheetBlurRadius]（24.dp）。
     *
     * 这一层目前只有课程详情和待办详情两个弹层在用，所以默认值就是这两个弹层的值；
     * 参数留着是为了以后若有第三种弹层（比如更高的抽屉）需要另一档时，不必再改公共件。
     */
    blurRadius: Dp = GlassSheetBlurRadius,    content: @Composable () -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    // 屏幕高度要**物理**像素，不能拿缩放后的 density 去换算：
    // `screenHeightDp` 是物理 dp，而 density 现在带着版式缩放系数（窄屏 0.82），
    // 直接 `.dp.toPx()` 会得到「物理高度 × 系数」——比真实屏幕高一截，
    // 于是首帧起始位置偏低、拖拽阈值（屏高的 28%）也跟着偏。
    //
    // 物理密度 = 现在的 density ÷ 版式缩放系数。
    val layoutScale = LocalLayoutScale.current
    val screenHeightPx = configuration.screenHeightDp * (density.density / layoutScale)

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
                // 底部弹层原来只避让导航栏。异形屏横过来时，刘海/挖孔在**左右两侧**，
                // 导航栏 inset 管不到那儿，弹层的内容会压在挖孔下面。
                // 改用完整安全区：底部仍是导航栏，左右补上挖孔。
                .windowInsetsPadding(
                    WindowInsets.navigationBars
                        .union(WindowInsets.displayCutout)
                        .only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)
                )
                .onGloballyPositioned { coordinates ->
                    if (coordinates.size.height > 0) {
                        sheetHeightPx = coordinates.size.height.toFloat()
                    }
                }
                .graphicsLayer {
                    translationY = currentOffsetY
                    alpha = (0.2f + 0.8f * currentFraction).coerceIn(0f, 1f)
                }
                .glassOverlaySurface(
                    shape = shape,
                    blurRadius = blurRadius,
                    // 底部弹层走自己那一档面参数（比居中弹窗透），理由见 [GlassSheetFace]。
                    faceAlpha = GlassSheetFace.FACE_ALPHA,
                    wash = GlassSheetFace.WASH,
                    hazeFloor = GlassSheetFace.FLOOR,
                )
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