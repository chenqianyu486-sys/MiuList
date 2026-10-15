package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.LimeGreenOnPrimary
import com.example.ui.theme.LimeGreenPrimary
import com.example.ui.theme.GlassEdgeBottom
import com.example.ui.theme.GlassIslandShape
import com.example.ui.theme.GlassTintNeutral
import com.example.ui.theme.TextMuted
import com.example.ui.theme.glassSurface
import com.example.ui.viewmodel.NavigationTab

/**
 * 弹层里的筛选 chip 配色：选中平涂抹茶绿，未选中浅冷灰片，
 * 和清单页那套 chip 保持同一语言（Material 默认「选中=浅蓝」，会和浮岛撞色）。
 */
@Composable
fun micaChipColors() = FilterChipDefaults.filterChipColors(
    containerColor = GlassTintNeutral,
    labelColor = TextMuted,
    selectedContainerColor = LimeGreenPrimary,
    selectedLabelColor = LimeGreenOnPrimary,
)

/** 未选中的 chip 留一道几乎看不见的发丝边，选中时完全不描边。 */
@Composable
fun micaChipBorder(selected: Boolean) = FilterChipDefaults.filterChipBorder(
    enabled = true,
    selected = selected,
    borderColor = GlassEdgeBottom,
    selectedBorderColor = Color.Transparent,
    borderWidth = 0.5.dp,
    selectedBorderWidth = 0.dp,
)

/**
 * 浮岛面不透明度倍率。
 *
 * 浮岛是全 App 唯一压在**滚动内容之上**的面：普通卡片背后是纯白，浮岛背后是正在
 * 滚过去的卡片和文字。用户要求「不透明度降低一些」，于是给它单独降一档
 * （0.48 × 0.78 ≈ 0.37），底下的色块轮廓更隐约，浮起来的感觉更轻。
 *
 * 现在定在 0.85（实际面不透明度 0.48 × 0.85 ≈ 0.41）。这一档是**地板方案给的红利**：
 * 有了 `IslandHazeFloor` 把背光最暗的那一截钳住之后，面就不用再当闸门了——
 * 之前 0.48 / 0.41 的差别就是「多闪一点」和「少闪一点」，现在这个差别被地板吃掉了，
 * 于是可以把面调薄换通透，闪的那部分由地板单独负责。
 *
 * **不要改 `GLASS_FACE_ALPHA` 本身**——那是全 App 中性面的公共基准，一改所有卡片跟着变。
 *
 * 2026-10-12 再降到 0.72（实际面不透明度 0.48 × 0.72 ≈ 0.35）：课表取消底部垫白之后，
 * 浮岛直接压在网格上，底下的行线和课程卡要能透出轮廓才像「浮着」而不是「盖着」，
 * 0.41 时岛面还是偏实。
 */
private const val IslandFaceAlphaScale = 0.72f

/**
 * 浮岛的「亮度地板」强度，1 = 硬钳。
 *
 * 这一条和上面的洗白是**两种完全不同**的思路，别搞混：
 *
 *   * 洗白是**整体压缩**——背光不管明暗一律往白拉，代价是通透度一起被压掉，
 *     想不闪就得把面做实，于是越调越闷；
 *   * 地板是**限定范围**——只把低于地板的那截钳平，高于地板的原样透过。
 *     文字灰被钳成常数（变化范围归零，频闪的源头没了），粉彩卡片和纯白页面底
 *     完全不受影响，于是「透」和「不闪」不再互相打架。
 *
 * 钳完之后背光只剩 [地板 ≈ 0.89, 白 = 1.0] 这一小段区间，岛面亮度起伏
 * ≈ 0.39 × 0.11 ≈ 4%，且这一段变化来自卡片粉彩底色，是大尺度、慢的，
 * 看着是「玻璃后面有东西」而不是「一跳一跳」。
 *
 * 想再稳就把 `IslandHazeWash` 往上加，别动这个——它已经是硬钳了，唯一能调的是
 * `HazeFloorColor` 本身（写死在 `Mica.kt`），往上抬会连课程色一起钳平。
 *
 * 2026-10-12 降到 0.55：**这一条才是「浮岛有多透」的主旋钮**，别再只动面 alpha 和洗白。
 * floor = 1 是 `BlendMode.Lighten` 的满强度钳，所有通道一律抬到 ≥ #DEE4EA，
 * 于是岛下不管压着行线、数字还是课程卡，透出来的都是同一个近白色——
 * 面 alpha 从 0.41 降到 0.35、洗白从 0.34 降到 0.28，实测岛面只差 1~2 个色阶，
 * 肉眼几乎看不出来，因为能透出的信息早被地板钳光了。
 * 0.55 是插值钳：暗部往上抬一半多、亮部原样透过，格子走向和课程卡色块能读出来，
 * 但深色文字仍被压到认不出，闪的源头（文字行的明暗跳变）也还在钳制范围内。
 */
private const val IslandHazeFloor = 0.55f

/**
 * 浮岛糊完之后再洗的那层白。
 *
 * 原来 0.24，只够把底下卡片的文字糊成认不出的色块。但它还有个更要紧的活：
 * **压住背光的亮度起伏**。滚动时岛底下的东西一直在换——深色正文被糊成一片灰，
 * 一行字滚进岛的范围，岛面就暗一档，滚出去又亮回来，连续起来就是频闪。
 *
 * 洗白是线性把背光往白拉：0.34 洗完，地板那档 0.89 会被抬到 0.89 + 0.34 × 0.11
 * ≈ 0.93，纯白还是 1.0。它的活现在只剩两件：把岛整体提亮，以及把剩下那 11% 的
 * 起伏再削掉三分之一。
 *
 * 之前它是主力（0.66），代价是背光透出比例只剩 18%，玻璃闷得几乎看不出是玻璃。
 * 现在主力换成 `IslandHazeFloor`，它退回辅助位，透出比例回到 39%——
 * 更透、更亮，但起伏反而从 7% 降到 4%。
 *
 * 别把它降到 0.2 以下：地板之上的内容会显得有点脏，导航标签的对比度也跟着掉。
 *
 * 2026-10-12 降到 0.28：磨砂整体调轻的一环。洗白是线性把背光往白拉，洗得越多
 * 底下内容越认不出、岛面越实。0.34 时岛下方的行线只是「隐约」，0.28 能读出格子的
 * 走向但仍读不出文字——正好是「浮在课表上」而不是「浮在白板上」的分界。
 * 防频闪的主力仍然是 `IslandHazeFloor`，不是这一层，所以往下调不担心闪。
 */
private const val IslandHazeWash = 0.28f

/**
 * 浮岛的模糊半径。
 *
 * 72dp 只够把字糊到认不出，压不住闪：文字行间距约 20dp，72dp 的核一次只能盖住
 * 三两行，覆盖率随滚动剧烈变化，平均亮度就跟着抖。加到 96dp，核一次吃掉五行以上，
 * 糊出来的基本是「这一片内容的平均色」，几乎不随滚动变——剩下的起伏只有卡片
 * 粉彩底色的缓慢过渡，那正是毛玻璃该有的样子。
 *
 * 半径变大的性能代价是可接受的：模糊只作用在岛自己那块 ~290×92dp 的区域上。
 */
private val IslandBlurRadius = 96.dp

/**
 * 浮岛的磨砂颗粒强度。
 *
 * 卡片用满档 0.055 没问题，因为它们的背光是死的（纯白）。浮岛的背光在动，
 * 颗粒是叠在上面的高频噪点，背光一变麻点的明暗就跟着变，会加重「闪」的观感，
 * 所以之前压到过 0.02。
 *
 * 现在地板把背光的突变钳住了，背光稳定下来，颗粒就可以放回来——甚至比卡片还高一档
 * （0.065）：磨砂质感本来就该靠它撑，而它现在只会跟着那 11% 的慢变化轻轻走，
 * 不会再抖。这是「提升磨砂质感」这条要求唯一有效的旋钮。
 *
 * 2026-10-12 调回 0.045：用户要求「磨砂适当调低」。0.065 在纯白背光上够细腻，
 * 但浮岛现在压在课表的行线、数字和课程卡上，麻点是叠在这种高频细节上的，
 * 偏重会把底下本来就淡的格子搅得更看不清。0.045 保留颗粒感、不抢底下的内容。
 */
private const val IslandNoiseFactor = 0.045f

/**
 * 浮岛导航占用的高度（不含系统导航栏，那部分由 Scaffold 的 innerPadding 扣掉）。
 *
 * 所有滚动容器都要拿它当 contentPadding 的底部内边距：内容因此能从浮岛底下滚过去
 * （好让浮岛的毛玻璃真的能糊到东西），滚到底时最后一项又正好停在岛的上方。
 *
 * = 外侧上下各 14dp + 岛高 64dp + 8dp 余量。岛高一改这里就得跟着改，
 * 否则最后一项会埋进岛里，或者和岛之间空出一截。
 */
val IslandBottomInset = 100.dp

/** 浮岛本体高度，不含外侧 14dp 的上下留白。 */
private val IslandHeight = 64.dp

/** 浮岛入口的图标尺寸。 */
private val IslandIconSize = 22.dp

/**
 * 单个入口的最小宽度。
 *
 * 适当加宽按键，配合内侧更舒展的左右边距，给文字和图标更充足的横向呼吸感。
 * 72dp 配 64dp 岛高，比例协调舒展，触摸更舒适稳妥。
 */
private val IslandEntryMinWidth = 72.dp

/**
 * 浮岛两端最左、右侧边距（水平内边距）。
 *
 * 原先为 4dp，贴着胶囊圆角弧度过紧显得局促；
 * 适当增大到 12dp 后两端留出充分的圆角呼吸空间，按键与外壳更协调。
 */
private val IslandHorizontalPadding = 12.dp

/**
 * 入口之间的间距。
 *
 * 图标 22dp，间距取 10dp：三个入口散开呈独立的胶囊色块，
 * 比例适中，整颗岛宽约 260dp，居中悬浮呼吸感更自然。
 *
 * 重算整颗岛宽：3×72 + 2×10 + 2×12 = 260dp。
 *
 * **岛里只有三个入口**：设置已经挪到顶栏展开出来的抽屉（[MoreDrawer]）。
 * 一个入口在两个地方都能点到，用户会犹豫该点哪个，而「设置」这种低频入口
 * 也不值得在每一屏都占一个常驻位置。
 */
private val IslandEntryGap = 10.dp

/**
 * 底部浮岛导航。
 *
 * 形态：一颗浮在内容之上的云母胶囊，选中项在岛内用主题绿平涂色块表示。
 * 相比原来的 NavigationBar：
 * 1. 不占布局高度（Scaffold 不用 bottomBar），内容可以铺到屏幕底部，由浮岛压住；
 * 2. 岛是真·毛玻璃（haze）+ 冷调投影，滚过去的卡片会被真糊掉，这是「浮起来」的来源；
 * 3. 岛内不再有 1px 通栏分隔线，靠岛本身的圆角和投影切出层次。
 *
 * 宽度按内容自适应，不再 fillMaxWidth 撑成通栏——通栏的岛看着像一条底栏，
 * 不像「浮起来的一颗」。居中由调用方的 `Alignment.BottomCenter` 负责。
 *
 * 整颗岛的尺寸集中在本文件顶部的四个常量（IslandHeight / IslandIconSize /
 * IslandEntryMinWidth / IslandEntryGap），它们之间是有比例的，别只动一个：
 * 间距要一直是图标的一半，岛高变了 IslandBottomInset 也要跟着变。
 */
/**
 * 底部浮岛导航。
 *
 * 悬浮在屏幕底部的毛玻璃胶囊，底层包含平滑滑动的绿色高亮色块。
 * 动画非线性变化：平移滑动先快后慢、柔和停住（Emphasized Decelerate 曲线），自然美观不突兀。
 */
@Composable
fun FloatingIslandNav(
    current: NavigationTab,
    onSelect: (NavigationTab) -> Unit,
    modifier: Modifier = Modifier
) {
    val selectedIndex = when (current) {
        NavigationTab.DASHBOARD -> 0
        NavigationTab.SCHEDULE -> 1
        NavigationTab.CHECKLIST -> 2
        else -> -1
    }

    val density = LocalDensity.current
    val stepDistance = IslandEntryMinWidth + IslandEntryGap
    val targetOffsetPx = with(density) {
        if (selectedIndex >= 0) (stepDistance * selectedIndex).toPx() else 0f
    }

    // 平移动画：在 Draw/GPU 阶段通过 translationX 驱动，时长 150ms 与页面转场完全同频，杜绝拖沓与重绘残影
    val indicatorOffsetPx by animateFloatAsState(
        targetValue = targetOffsetPx,
        animationSpec = tween(
            durationMillis = 150,
            easing = CubicBezierEasing(0.2f, 0.0f, 0.1f, 1.0f)
        ),
        label = "island_indicator_offset"
    )

    Box(
        modifier = modifier
            .testTag("floating_island_nav")
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 14.dp)
            .glassSurface(
                shape = GlassIslandShape,
                blurRadius = IslandBlurRadius,
                hazeWash = IslandHazeWash,
                faceAlphaScale = IslandFaceAlphaScale,
                noiseFactor = IslandNoiseFactor,
                hazeFloor = IslandHazeFloor
            )
            // 拦截并消费整颗浮岛内部的点击事件，防止未命中按钮的点击（如按键间距、外壳内边距处）穿透触发底层卡片
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) {}
            .height(IslandHeight)
            .padding(horizontal = IslandHorizontalPadding, vertical = 4.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        // 1. 底层高亮平移滑块：严格在 RenderNode Draw 阶段平移，不触发组件 Relayout，轻盈丝滑无拖影
        if (selectedIndex >= 0) {
            Box(
                modifier = Modifier
                    .graphicsLayer {
                        translationX = indicatorOffsetPx
                    }
                    .width(IslandEntryMinWidth)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(20.dp))
                    .background(LimeGreenPrimary)
            )
        }

        // 2. 上层各按键入口行
        Row(
            modifier = Modifier.fillMaxHeight(),
            horizontalArrangement = Arrangement.spacedBy(IslandEntryGap),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IslandEntry(
                modifier = Modifier
                    .width(IslandEntryMinWidth)
                    .fillMaxHeight(),
                label = "总览",
                icon = Icons.Outlined.Dashboard,
                selectedIcon = Icons.Filled.Dashboard,
                selected = current == NavigationTab.DASHBOARD,
                onClick = { onSelect(NavigationTab.DASHBOARD) },
                testTag = "nav_tab_dashboard"
            )
            IslandEntry(
                modifier = Modifier
                    .width(IslandEntryMinWidth)
                    .fillMaxHeight(),
                label = "课表",
                icon = Icons.Outlined.CalendarMonth,
                selectedIcon = Icons.Filled.CalendarMonth,
                selected = current == NavigationTab.SCHEDULE,
                onClick = { onSelect(NavigationTab.SCHEDULE) },
                testTag = "nav_tab_schedule"
            )
            IslandEntry(
                modifier = Modifier
                    .width(IslandEntryMinWidth)
                    .fillMaxHeight(),
                label = "清单",
                icon = Icons.Outlined.Checklist,
                selectedIcon = Icons.Filled.Checklist,
                selected = current == NavigationTab.CHECKLIST,
                onClick = { onSelect(NavigationTab.CHECKLIST) },
                testTag = "nav_tab_checklist"
            )
        }
    }
}

/** 浮岛内的单个入口：前景文字与图标在滑块滑入时柔和变色。 */
@Composable
private fun IslandEntry(
    label: String,
    icon: ImageVector,
    selectedIcon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String? = null
) {
    val contentColor by animateColorAsState(
        targetValue = if (selected) LimeGreenOnPrimary else TextMuted,
        animationSpec = tween(
            durationMillis = 140,
            easing = FastOutSlowInEasing
        ),
        label = "island_entry_content"
    )

    Column(
        modifier = modifier
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .clip(RoundedCornerShape(20.dp))
            .clickable { onClick() }
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = if (selected) selectedIcon else icon,
            contentDescription = label,
            tint = contentColor,
            modifier = Modifier.size(IslandIconSize)
        )
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = label,
            fontSize = 11.5.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = contentColor
        )
    }
}
