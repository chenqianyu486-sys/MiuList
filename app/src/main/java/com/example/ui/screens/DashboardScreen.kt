package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.School
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import com.example.ui.theme.cardRevealAt
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.CourseEntity
import com.example.data.local.CourseSlotEntity
import com.example.data.local.CourseWithSlots
import com.example.data.local.TaskEntity
import com.example.data.model.ExamEntry
import com.example.data.source.zju.CoursesTodoItem
import com.example.ui.components.islandBottomInset
import com.example.ui.components.TaskItemCard
import com.example.ui.components.WeatherBlock
import com.example.ui.components.WeatherLayout
import com.example.ui.components.weatherStripHeight
import com.example.ui.components.BlockWidth
import com.example.ui.components.formatPeriodRange
import com.example.ui.components.courseCardWash
import com.example.ui.components.ExamCard
import com.example.ui.components.ExamKindBadge
import com.example.ui.components.PendingPill
import com.example.ui.components.examClockRange
import com.example.ui.components.DayItem
import com.example.ui.components.FeaturedPick
import com.example.ui.components.pickFeatured
import com.example.ui.components.isExamToday
import com.example.ui.components.lazyKey
import com.example.ui.components.mergeDayItems
import com.example.ui.components.formatRemaining
import com.example.ui.components.periodStartMillis
import com.example.ui.components.todayStatusLabel
import com.example.ui.components.todayCalendar
import com.example.data.calendar.AppZone
import com.example.data.calendar.DayOffReason
import com.example.data.calendar.EpochDays
import com.example.data.timetable.formatMinute
import com.example.data.calendar.ScheduleResolver
import com.example.ui.components.parseColor
import com.example.ui.theme.CardBlockGap
import com.example.ui.theme.PageHInset
import com.example.ui.theme.MiuSurface
import com.example.ui.theme.MiuType
import com.example.ui.theme.accentBarColor
import com.example.ui.theme.accentOnCourse
import com.example.ui.theme.cardTint
import com.example.ui.theme.TeaColor
import com.example.ui.theme.TeaColorBorder
import com.example.ui.theme.TeaOnColor
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.glassSurface
import com.example.ui.theme.heroDateFontSize
import com.example.ui.theme.heroDateMaxSpFor
import com.example.ui.theme.heroDateStyle
import com.example.ui.theme.heroMinDateColumnWidth
import com.example.ui.theme.HeroDateMinTierSp
import com.example.ui.theme.nextSmallerHeroDateTier
import com.example.ui.theme.contentMaxWidthFor
import com.example.ui.theme.rememberLayoutWidthDp
import com.example.ui.theme.rememberIsShortWindow
import com.example.ui.theme.widthClassFor
import com.example.ui.theme.typeScaleFor
import com.example.ui.theme.WindowWidthClass
import com.example.ui.theme.rememberWindowWidthClass
import com.example.ui.theme.HeroDateMaxSpCompact
import com.example.ui.theme.LocalEffectiveFontScale
import com.example.ui.theme.LocalEffectiveFontScale
import com.example.ui.theme.cardHeightAnchor
import com.example.ui.theme.measureTextHeight
import com.example.ui.theme.measureTextSize
import com.example.ui.theme.scaledWithText
import com.example.ui.theme.wallpaperBlur
import com.example.ui.theme.LocalCardRevealProgress
import com.example.ui.theme.LocalWallpaperIsCustom
import com.example.ui.viewmodel.MainUiState
import kotlinx.coroutines.delay
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 主卡的**设计高度**（dp）：基准屏、系统字号 1.0 时的那一版。
 *
 * 这不是写死的「卡就该这么高」，而是**设计的锚点**：卡里的内容在基准屏上占 112dp，
 * 剩下 28dp 是设计留白。主角（日期）的字号变了时，留白要跟着放大
 * （见 [cardHeightAnchor]），否则字在卡里越占越满 —— 同一张设计在不同字号下长得不一样。
 *
 * 真正的卡高 = max(本锚点 × 锚点系数, 实测内容高)，见 [heroCardHeightFor]。
 */
private val HeroCardHeight = 140.dp

/**
 * 主卡正面与天气块之间的间隔（并排排法）。
 *
 * 与改版前一致：基准屏上这一行左右各 12dp 的空档是量过的，不是随手写的。
 */
internal val HeroWeatherGap = 12.dp

/**
 * 窄屏上下两段之间，日期段与天气条之间的间距（dp）。
 *
 * 10dp：比卡内边距（16dp）小一档，让这两段读起来是**一张卡里的两行**，
 * 而不是「上面一块、下面一块」两个东西。
 *
 * ## 这一段只在「缩字救不了」时才会出现
 *
 * 常态是并排（日期降档即可）；只有窄屏 + 用户把系统字号放大、
 * 或者任何宽度上的极端字号，才需要把天气挪到第二段。
 *
 * ## 间距随字号放大
 *
 * 这一段是**垂直**间距：字大了，两段之间就该隔开一点，
 * 否则它们会挤成一坨。放大倍数见 [LocalEffectiveFontScale]。
 */
internal val HeroStripGap = 10.dp

/** 主卡的上下内边距（设计值，dp）。 */
private val HeroCardPaddingV = 16.dp

/** 主卡左列里「周次行 → 日期」的间距（设计值，dp）。 */
private val HeroMetaGap = 8.dp

/** 主卡左列里「日期 → 副标题」的间距（设计值，dp）。 */
private val HeroDateMetaGap = 4.dp

/** 周次 chip 的上下内边距（设计值，dp）。 */
private val HeroWeekChipPadV = 3.5.dp

// =========================================================================
// 主卡背面（「学在浙大」待交作业）那一面的尺寸
//
// 这些数字原本散落在背面的各个 Composable 里（写成 26.dp / 30.dp / 64.dp …），
// 而**卡高是按正面算的** —— 背面要多少高度没人算，于是窄屏 / 大字号下
// 背面那排小卡会被卡的下沿裁掉（实测：332dp + 1.16 倍字号，小卡下面那行
// 「12月3日截止 · 数字电路」只剩半个字）。
//
// 所以这里把它们提成常量：背面画的时候用它们，**算卡高的时候也用它们**，
// 两边必须是同一份数。
// =========================================================================

/** 背面标题行的徽标直径（跟着字放大）。 */
private val HeroTodoLogoSize = 26.dp

/** 背面标题行徽标的字（「未绑定 / 38 篇待交 / 已交清」）。 */
private val HeroTodoBadgeFontSize = 11.sp

/** 背面标题行徽章的上下内边距（跟着字放大）。 */
private val HeroTodoBadgePadV = 2.dp

/** 背面标题行右侧那个一键刷新按钮的直径（**固定**尺寸，不跟字缩放）。 */
private val HeroTodoRefreshSize = 30.dp

/** 背面那排待办小卡的高度下限（跟着字放大）。 */
private val HeroTodoMiniCardMinHeight = 64.dp

/** 背面那排待办小卡的固定宽（跟着字放大）。 */
private val HeroTodoMiniCardWidth = 156.dp

/** 背面小卡的左右内边距（**固定**，不跟字缩放）。 */
private val HeroTodoMiniCardPadH = 10.dp

/** 背面小卡左侧那条课程色条的宽度（固定）。 */
private val HeroTodoMiniCardBarWidth = 3.5.dp

/** 背面小卡里色条与文字之间的间距（固定）。 */
private val HeroTodoMiniCardBarGap = 8.dp

/**
 * 背面小卡主行（作业名）最多折几行。
 *
 * 两行是上限，不是「只准一行」：作业名普遍比一行长
 * （实测「HW04 动态电路的时域分析」在 156dp 的卡里就要两行），
 * 锁死一行的话名字总被省略号吃掉一半 —— 读不全就不算标题。
 *
 * 封顶两行是因为这是一张**小卡**：再往下它就比一条待办卡还高了，
 * 主卡背面那一排小卡会被撑成一堵墙。
 */
private const val HeroTodoMiniCardTitleMaxLines = 2

/** 背面小卡主行（作业名）的字号。 */
private val HeroTodoMiniCardTitleSp = 12.5.sp

/** 背面小卡备注行（截止时间 · 课程名）的字号。 */
private val HeroTodoMiniCardRemarkSp = 10.sp

/** 背面小卡的上下内边距（跟着字放大）。 */
private val HeroTodoMiniCardPadV = 8.dp

/** 背面小卡里「主行 → 备注行」的间距（跟着字放大）。 */
private val HeroTodoMiniCardGap = 3.dp

/**
 * 窄屏上下两段之间的实际间距：随字号放大。
 */
@Composable
internal fun heroStripGap(): Dp = HeroStripGap * LocalEffectiveFontScale

/**
 * 宽屏两栏排法下，日期列的宽度上限（dp）。
 *
 * 360dp 是「26.sp 的日期（324dp）+ 一点余量」：26.sp 那句在宽屏字阶 1.18 下
 * 渲染宽 324dp，所以 360 刚好装下它、又不至于把右边那一栏推得太远。
 */
internal val HeroWideMetaMax = 360.dp

/** 宽屏两栏排法下，两栏之间的间距（dp）。 */
internal val HeroWideGap = 32.dp

/**
 * 宽屏两栏排法下，天气那一栏的宽度（dp）。
 *
 * 160dp：宽屏字阶 1.18 之后，那句最长的「杭州 · 空气轻度污染 138」
 * 要 105.9 × 1.18 ≈ 125dp，160 留得下余量。
 * 比竖排版的 108.dp 宽，因为宽屏上这一栏是**独占一栏**的，不再和日期抢位置。
 */
internal val HeroWideWeatherWidth = 160.dp

/**
 * 宽屏两栏排法需要的最小卡内容宽（dp）。
 *
 * 600dp：两栏排法的整组宽度上限是 360 + 32 + 160 = 552dp，
 * 卡内容宽至少要有 600dp 才不会挤在一起。
 * 换算成窗口宽度约 790dp —— 也就是说 600–790dp 的窗口仍是并排排法，
 * 只是字号跟着宽屏字阶走了一档。
 */
internal val HeroWideMinInner = 600.dp

/**
 * 主卡正面的排法结果。
 *
 * 由 [heroLayoutFor] 一处算出，界面与单测共用同一份 ——
 * 排法有三个维度（上下两段 / 两栏 / 并排）各自带一个宽度，
 * 分成三个函数各算各的，早晚会对不上，而对不上的那一天
 * 界面和测试会各说各话。
 */
internal data class HeroLayout(
    /** 窄屏上下两段：日期与天气各占一段，日期拿整行宽度。 */
    val stacked: Boolean,
    /** 宽屏两栏：日期与天气不再被 `SpaceBetween` 推到两端。 */
    val wide: Boolean,
    /** 天气块竖排的宽度。 */
    val weatherWidth: Dp,
    /** 日期那一列的宽度。 */
    val dateColumnWidth: Dp
)

/**
 * 窗口宽度 → 主卡排法。
 *
 * ## 判据只有一条：**缩字够不够**
 *
 * 自适应的基础是字体自适应（见 [contentScaleFor]）：屏窄了先让字等比缩小，
 * 整页的比例和留白关系就都保持住了，**版式一个字都不用改**。
 *
 * 所以这里的顺序是：
 *
 * 1. 先按屏宽算出**并排**时日期列有多宽；
 * 2. 再问一句：日期缩到**最小档** [HeroDateMinTierSp] 都放不下吗？
 *    - 放得下 → **并排**，一个字都不改版式。窄屏上日期会降档
 *      （320dp 上 22.sp → 16.sp，渲染 138.8dp，落在 152dp 的列里），
 *      但整页仍然是「411dp 的设计等比缩进小屏」。
 *    - 放不下 → 缩字救不了（窄屏 + 用户把系统字号放大，或任何宽度上的
 *      极端字号），这时才把天气块挪到第二段去（**上下两段**）。
 *
 * ## 为什么改版式只能当兜底
 *
 * 改版式 = 主卡多出一行 = 卡片变高、这一屏的信息密度变了，
 * 整页比例就和设计时的那一版对不上了。缩字则不会：字变小，围着它的
 * 框、卡片、胶囊跟着变小，整页还是那个设计。
 *
 * ## 天气块的宽度跟着**有效字号缩放**走
 *
 * 竖排版固定 108.dp 是因为最坏那句「杭州 · 空气轻度污染 138」在字号 1.0 时
 * 要 105.9.dp。字号放大之后它要 105.9 × 有效缩放 —— **108 装不下了，
 * 指数会被 Ellipsis 截掉**（指数恰恰是最需要看全的那个数）。
 *
 * 所以这里按有效缩放**等比**放大块宽：108 × 有效缩放。比例放大保留了原来那 2dp 余量。
 *
 * ## 有效缩放 = 系统字号 × 宽屏字阶，两者都要跟
 *
 * 早先这里只跟**宽屏字阶**走，理由是「用户的字号放大时块本来就该跟着重排」。
 * 那是在「用户字号一放大，版式整体让位」的前提下说的 ——
 * 版式不缩、整页都放大，块宽不跟就必然截断指数。
 *
 * 现在版式缩放与字号缩放**各管一件事**（见 [effectiveLayoutScaleFor]），
 * 块宽就必须把**两个**乘数都算上：系统字号（用户自己设的）与宽屏字阶。
 * 只跟其中一个走，另一档就会在那个组合下把指数截掉。
 *
 * @param physicalWidthDp 窗口的**物理**宽度（dp）。决定设备档位：宽屏字阶、
 *   日期字号上限档都按它判。
 * @param layoutWidthDp 窗口的**排版**宽度（dp）。决定版面里实际有多宽：
 *   缩放之后它比物理宽大（320dp 的屏 → 排版 390dp），
 *   拿物理宽去比排版空间会算错一段。
 * @param effectiveFontScale 有效字号缩放（系统 × 宽屏字阶）。
 */
internal fun heroLayoutFor(
    physicalWidthDp: Int,
    layoutWidthDp: Int,
    effectiveFontScale: Float
): HeroLayout {
    val inner = heroInnerWidthOf(layoutWidthDp)
    val widthClass = widthClassFor(physicalWidthDp)
    val typeScale = typeScaleFor(physicalWidthDp)
    val wide = widthClass != WindowWidthClass.COMPACT && inner >= HeroWideMinInner
    // 竖排版：块宽按有效缩放等比放大（108 × 有效缩放），保证最坏那句放得下。
    // 宽屏版：那一栏本来就比竖排版宽得多，它的基准宽里已经含了宽屏字阶的余量，
    // 所以这里只按**系统字号**再放大一次 —— 否则系统字号一放大就又截断。
    val weatherWidth = if (wide) {
        HeroWideWeatherWidth * effectiveFontScale / typeScale
    } else {
        BlockWidth * effectiveFontScale
    }
    // 并排时日期列的宽度
    val sideBySideColumn = inner - HeroWeatherGap - weatherWidth
    // 缩字缩到底（最小档）还放不下 → 只能改版式
    val minColumnNeeded = heroMinDateColumnWidth(effectiveFontScale)
    val stacked = !wide && sideBySideColumn < minColumnNeeded
    val layout = HeroLayout(
        stacked = stacked,
        wide = wide,
        weatherWidth = weatherWidth,
        dateColumnWidth = 0.dp
    )
    return layout.copy(dateColumnWidth = heroDateColumnWidth(inner, layout))
}

/**
 * 日期那一列实际能拿到的宽度（dp）。
 *
 * 三种排法各算各的：并排时是「卡内容宽 − 间隔 − 天气块」，
 * 上下两段时是整行，宽屏两栏时是「卡内容宽 − 间距 − 天气栏」并封顶 [HeroWideMetaMax]。
 *
 * 界面里算两次：一次用**估算**的卡内容宽（在卡片外面定排法与字号时用），
 * 一次用 `BoxWithConstraints` 拿到的**实际**卡内容宽（复核「这句字放不放得下」时用）。
 * 两处共用这一个公式，所以它们不会打架。
 */
internal fun heroDateColumnWidth(innerWidth: Dp, layout: HeroLayout): Dp = when {
    layout.stacked -> innerWidth
    layout.wide -> minOf(innerWidth - HeroWideGap - layout.weatherWidth, HeroWideMetaMax)
    else -> innerWidth - HeroWeatherGap - layout.weatherWidth
}

/**
 * 总览页。
 *
 * 原来还有 `onGenerateTodayTasks` / `onPreviewGenerateTasks` 两个参数 ——
 * 它们**从来没有被调用过**：「生成本周待办」早就上顶栏成了常驻图标，
 * 总览页上那个入口撤掉时参数忘了删。留着它们等于对外宣称「这一页上还有个生成入口」，
 * 读代码的人会去找一个不存在的东西。
 *
 * 现在「生成待办」只有两个入口：顶栏那颗 ✨（直接生成）与
 * 上手引导第 4 步（先看预览再收）。
 */
@Composable
fun DashboardScreen(
    state: MainUiState,
    onToggleTaskComplete: (TaskEntity) -> Unit,
    onToggleTaskStar: (TaskEntity) -> Unit,
    onInspectTask: (TaskEntity) -> Unit,
    onInspectCourse: (CourseWithSlots) -> Unit,
    /** 点考试卡 → 打开考试编辑弹层（本地可改考场 / 座位 / 时刻）。 */
    onEditExam: (ExamEntry) -> Unit = {},
    onOpenWeekPicker: () -> Unit,
    onRefreshCoursesTodo: () -> Unit = {},
    onOpenQuickLoginDialog: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val todayCalendar = remember { todayCalendar() }
    val todayDateStr = remember {
        // 顶部的「2026年10月19日 星期一」是**北京**的日期。
        // 无参 SimpleDateFormat 走设备时区，GMT 设备在北京凌晨会显示昨天的星期几。
        AppZone.format("yyyy年M月d日 EEEE", System.currentTimeMillis(), Locale.CHINESE)
    }
    // 大字号 / 窄屏下完整日期放不进左列时的**降级写法**。
    //
    // 原先只有「按宽度逐档降字号」一条路：332dp + 2.0 字号时 14sp 的完整日期
    // 仍要 296dp，而左列只有 243dp，最后被截成「2026年10月21日 星…」——
    // 日期是主卡的主角，读不出星期几比字号小一点更糟。
    // 所以先降字号，再降**信息密度**：保留月日 + 短星期，最后才保留月日。
    val compactDateStr = remember {
        AppZone.format("M月d日 EEE", System.currentTimeMillis(), Locale.CHINESE)
    }
    val shortDateStr = remember {
        AppZone.format("M月d日", System.currentTimeMillis(), Locale.CHINESE)
    }

    // 课程状态每 30 秒自己走一次，不能等用户碰一下屏幕才更新。
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000L)
            nowMillis = System.currentTimeMillis()
        }
    }
    // 不做深色模式：两套都是浅色。预设壁纸保留原版的通透；
    // 用户照片的细节不可控，换一档更厚的玻璃，并把主卡垫起来。
    val customWallpaper = LocalWallpaperIsCustom.current
    val contentBase = if (customWallpaper) 0.72f else 0.55f
    val courseTint = if (customWallpaper) 0.26f else 0.30f
    val heroBase = if (customWallpaper) 0.55f else 0f

    // 今日课程：判定权交给 resolver（开课周次 / 停课 / 补课都在它里面）。
    val resolver = remember(state.calendar) { ScheduleResolver(state.calendar) }
    val todaySlots = remember(state.courseSlots, state.slotWeeks, state.calendar) {
        resolver.slotsOn(EpochDays.today(), state.courseSlots, state.slotWeeks)
    }
    // 「今天停课」只在**校历明确记的停课日**出现。
    //
    // 以前这里用的是 `!calendar.isSchoolDay(today)`，而 isSchoolDay 把**周末**
    // 也判成「不上课」—— 于是每个周末的总览都写着「今天停课」，把放假停课
    // 和周末混成同一件事。用户原话：「放假停课与周末还是有区别的」。
    //
    // 现在按**原因**分档，见 [todayEmptyStateLabel]。
    val todayOffReason = remember(state.calendar, state.noClassDayCount) {
        state.calendar.dayOffReason(EpochDays.today())
    }

    // 考试：独立于课表的一份数据（另一个接口、另一张表），
    // 但**摆进今天的列表**，和课按时刻混在一起 —— 不另起一段。
    //
    // 只取**今天**的：这一段是「今天」，放三周后的考试进来会让它既不是今天、
    // 也不全是考试。后面几天的考试在课表页的列表视图里（那边按天分组）。
    //
    // **日期未知的考试不归今天**：教务网先公布考试周、后公布具体日期，
    // 那种记录没有日历上的一天，硬塞进今天就是编造。
    val todayExams = remember(state.exams, nowMillis) {
        state.exams.filter { isExamToday(it, nowMillis) }
    }
    // 今天的「条目」：课 + 考试，按时刻排好（合并规则见 `mergeDayItems`）。
    val todayItems = remember(todaySlots, todayExams, state.periodTable) {
        mergeDayItems(todaySlots, todayExams, state.periodTable)
    }
    // 今天的考试时间窗，给组头算「下一件事」用。
    val todayExamWindows = remember(todayExams) {
        todayExams.mapNotNull { exam ->
            if (exam.startAt > 0L && exam.endAt > exam.startAt) exam.startAt to exam.endAt else null
        }
    }

    // 当前/下一节：用来在原位置把其中一张卡加高。
    // 同一时间有多节重叠时取开始更早的那节；不改变列表顺序（用户选的是方案 A）。
    val nowMinute = remember(nowMillis) {
        AppZone.calendar(nowMillis).let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }
    }
    val slotWindows = remember(todaySlots, state.periodTable) {
        todaySlots.mapNotNull { slot ->
            val start = state.periodTable.startMinuteOf(slot.startPeriod)
                ?: return@mapNotNull null
            val end = state.periodTable.endMinuteOf(slot.endPeriod)
                ?: return@mapNotNull null
            SlotWindow(slot, start, end)
        }
    }
    val currentWindow = slotWindows.firstOrNull { nowMinute >= it.startMinute && nowMinute < it.endMinute }
    val nextWindow = slotWindows.firstOrNull { it.startMinute > nowMinute }

    // 「下一件事」只有一个：课与考试**抢同一个位置**。
    //
    // 两边各挑一个加高的话，屏幕上会同时出现两张「下一节 / 下一场」，
    // 用户得自己判断哪个才是真的下一件 —— 而时间线上明明只有一个下一件。
    // 判据见 `pickFeatured`。
    val examsWithTime = remember(todayExams) {
        todayExams.filter { it.startAt > 0L && it.endAt > it.startAt }
    }
    val currentExamEntry = examsWithTime.firstOrNull {
        nowMillis >= it.startAt && nowMillis < it.endAt
    }
    val nextExamEntry = examsWithTime.firstOrNull { it.startAt > nowMillis }
    val nextClassStart = nextWindow?.let {
        periodStartMillis(todayCalendar, it.slot, state.periodTable)
    }
    val featuredPick = pickFeatured(
        currentClassStart = currentWindow?.let {
            periodStartMillis(todayCalendar, it.slot, state.periodTable)
        },
        currentExamStart = currentExamEntry?.startAt,
        nextClassStart = nextClassStart,
        nextExamStart = nextExamEntry?.startAt
    )
    val featuredExam = if (featuredPick == FeaturedPick.EXAM) {
        currentExamEntry ?: nextExamEntry
    } else {
        null
    }
    // 考试抢到时，课就不加高（同一时间只能有一张加高卡）。
    val featuredWindow = if (featuredExam == null) (currentWindow ?: nextWindow) else null
    val featuredSlotId = featuredWindow?.slot?.id
    val featuredIsCurrent = currentWindow != null
    // 下一节的倒计时和组头共用同一个 nowMillis，避免组头 53 分、卡片 54 分。
    val featuredStartMillis = featuredWindow?.let {
        periodStartMillis(todayCalendar, it.slot, state.periodTable)
    } ?: 0L
    val featuredCountdown = if (featuredWindow != null && !featuredIsCurrent) {
        // 只说「还有 51 分」，不接「开始」：组头已经说过「下节课还有 51 分 开始」，
        // 卡片右栏那枚「开课」标签又把「开始」说了一遍——同一个词在一屏里出现三次，
        // 就得砍到只剩最该说的那处。卡片这一行接上右栏正好读成
        //「还有 51 分 · 开课 13:25」。
        "还有 ${formatRemaining((featuredStartMillis - nowMillis).coerceAtLeast(0L))}"
    } else {
        ""
    }
    // 下一场考试的倒计时，理由与课程那张完全一样。
    val featuredExamIsCurrent = featuredExam != null && currentExamEntry === featuredExam
    val featuredExamCountdown = if (featuredExam != null && !featuredExamIsCurrent) {
        "还有 ${formatRemaining((featuredExam.startAt - nowMillis).coerceAtLeast(0L))}"
    } else {
        ""
    }

    val courseMap = remember(state.coursesWithSlots) {
        state.coursesWithSlots.associateBy { it.course.id }
    }

    // 总览那一串待办：未完成的，**标星的在前**（见 [dashboardPendingTodos]）。
    //
    // 这一层排序是决定性的 —— 下面只显示前 [DASHBOARD_TODO_LIMIT] 条，
    // 截止时间靠后的待办按「截止排」根本挤不进这个窗口，哪怕用户给它标了星。
    // 所以标星必须参与这里的排序，否则「标星」在总览里等于没用。
    val pendingTasks = remember(state.tasks) {
        dashboardPendingTodos(state.tasks)
    }


    // 本周课时：走 slotsInWeek 让单双周过滤生效，否则周课表上只上单周，
    // 副标题却按全量算出一个对不上的小时数。
    val weekHoursText = remember(
        state.courseSlots,
        state.slotWeeks,
        state.currentWeek,
        state.periodTable,
        state.calendar
    ) {
        val weekSlots = resolver.slotsInWeek(
            state.currentWeek,
            state.courseSlots,
            state.slotWeeks
        ).values.flatten()
        val minutes = weekSlots.sumOf {
            state.periodTable.durationMinutesOf(it.startPeriod, it.endPeriod)
        }
        String.format(Locale.getDefault(), "%.1f 小时", minutes / 60.0)
    }

    // 组头文案沿用原来的 todayStatusLabel：
    // 正在上课 →「还有 X 分 下课」；还没到下一节 →「下节课还有 X 开始」；
    // 全部上完 / 今天没课 →「课都上完啦，休息一下吧~」。
    // 大字号下用短句，否则长句会断成「…开 / 始」两行。
    val compactStatus = LocalEffectiveFontScale > 1.3f
    val coursesHeader = todayStatusLabel(
        todayCalendar,
        todaySlots,
        nowMillis,
        state.periodTable,
        compact = compactStatus,
        // 考试与课在同一条时间线上，组头必须一起看：
        // 只盯着课说「下节课还有 3 小时开始」，而考试两小时后就要考，是假话。
        examWindows = todayExamWindows,
        examCountToday = todayExams.size
    )

    // 主卡的**排法**要在卡片外面定：卡片的高度得先知道，而高度又取决于排法
    // （窄屏的上下两段比并排多一段天气条）。
    //
    // 所以这里用与卡内**同源**的宽度推算排法与字号（见 [heroInnerWidthOf]），
    // 卡内再用 BoxWithConstraints 拿到的实际宽度复核「这句字放不放得下」。
    // 两处算的是同一个数（都来自排版宽与 contentMaxWidthFor），不会打架。
    // **物理宽**：设备是什么样（宽屏分档、字阶、日期上限档都按它判）。
    val physicalWidthDp = LocalConfiguration.current.screenWidthDp
    // **排版宽**：版面里实际有多宽 —— 主卡的排法与日期列宽都按它算。
    // 版式被缩放之后这两者不再相等（320dp 的屏 → 排版里是 390dp），
    // 拿物理宽去比排版空间会凭空算错一段。
    val layoutWidthDp = rememberLayoutWidthDp()
    // 排法要问「缩字够不够」，所以得带上**有效字号缩放**：
    // 同样的屏宽，用户把系统字号放到 2.0 时，缩字救不了 → 改版式。
    val effectiveFontScale = LocalEffectiveFontScale
    val heroLayout = remember(physicalWidthDp, layoutWidthDp, effectiveFontScale) {
        heroLayoutFor(physicalWidthDp, layoutWidthDp, effectiveFontScale)
    }
    val heroStacked = heroLayout.stacked
    val heroWide = heroLayout.wide
    // 矮屏（横屏 / 分屏窄条）：高度是最贵的资源，主卡与浮岛都按矮版排。
    val shortWindow = rememberIsShortWindow()
    val widthClass = rememberWindowWidthClass()
    val dateColumnWidth = heroLayout.dateColumnWidth
    // 选档要把**有效字号缩放**算进去：系统字号 1.5 时，
    // 22.sp 实际渲染成 1.5 倍的宽度，只比 232.8dp 会判成「放得下」，
    // 然后被 maxLines=1 截成「2026年10月20日 ...」（实测过）。
    val dateFontSize = heroDateFontSize(
        leftColumnWidth = dateColumnWidth,
        fontScale = LocalEffectiveFontScale,
        maxSp = heroDateMaxSpFor(widthClass)
    )
    // 行高跟着字号走（22.sp × 1.27 = 28.sp，基准屏观感不变；26.sp 时不再被裁）。
    // 这一份是**卡外**的：它只用来算卡高（卡要先知道多高才能排版）。
    // 真正渲染用的档位在卡内按**实际列宽**再修一道（见 heroDateTierWithMeasuredFit）——
    // 估算值与实际值可能差 1dp，而字宽正好卡在两者之间时，判定就成了掷硬币。
    val dateStyle = heroDateStyle(dateFontSize)

    // 主卡高度：**按这一屏真实要渲染的那几行字量出来**，不是写死 140dp。
    //
    // 140dp 是按 411dp 屏 + 系统字号 1.0 定的。系统字号 2.0 时，同一张卡里
    // 日期、学期名、副标题三行都跟着长，140dp 装不下——实测副标题「12 门课 ·
    // 31.3 小时」被裁掉下半截。这不是「再加两 dp」能修的，得让卡高跟着内容走。
    //
    // 主卡高度：**正反两面取更高的那个**。
    //
    // 正面（日期 + 天气）按它自己那几行字量出来，背面（学在浙大待交作业）
    // 那一排小卡同样要装得下 —— 以前只算正面，于是窄屏 / 大字号下
    // 背面的小卡会被卡的下沿裁掉（用户真机 332dp + 1.16 倍字号：
    // 背面要 141.5dp、正面只要 124.8dp，差的 17dp 全从小卡身上裁）。
    // 翻转时两面同高，下面的列表才不跳 —— 所以只能取高的那个。
    //
    // 背面那排小卡的**统一高度**：作业名允许折行，所以它取决于**实际那些名字**
    // 在**实际那个宽度**里折几行 —— 名字都短的时候不必给两行留位。
    // 算主卡高与画小卡用的是同一个数（差的那一截会被主卡下沿裁掉）。
    val heroTodoTitles = remember(state.coursesTodoList) {
        state.coursesTodoList.map { it.title }
    }
    val heroMiniCardHeight = heroTodoMiniCardHeight(heroTodoTitles)

    val heroHeight = heroCardHeightFor(
        weekText = "第 ${state.currentWeek} 周",
        termText = state.semester?.name?.ifBlank { null } ?: "本学期",
        dateText = todayDateStr,
        metaText = "${state.courses.size} 门课 · $weekHoursText",
        // 卡高用**卡外**的档位算（公式估的那个）：卡内按实际列宽修出来的档位
        // 只在真正放不下时更小一档，卡高按大的算 → 只会略宽，不会裁到内容。
        dateStyle = dateStyle,
        stacked = heroStacked,
        compact = shortWindow,
        mainSp = dateFontSize.value,
        backNeeded = heroBackFaceHeightFor(heroMiniCardHeight)
    )

    val cardRevealProgress = LocalCardRevealProgress.current

    // 入场序号是**整页**一条，从上往下数：学期卡 → 今日课程 → 待办。
    // 每段的起点由前面段落的条数推出来，而不是三个各从 0 开始——各从 0 开始的话
    // 「今日没课」的空态卡和学期卡同时亮，五张待办卡又和学期卡同时亮，整页就变成
    // 三批一起亮，看不出是从上往下的。
    val courseOrderBase = 1
    // 今天的条目里课与考试**混在一起**，所以条数是一起算的。
    val taskOrderBase = courseOrderBase + todayItems.size

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = PageHInset),
        verticalArrangement = Arrangement.spacedBy(CardBlockGap),
        // 底部给浮岛导航留出收尾空间，内容本身仍会滚到岛底下被毛玻璃糊住
        contentPadding = PaddingValues(bottom = islandBottomInset())
    ) {
        item {
            Spacer(modifier = Modifier.height(6.dp))

            var isHeroFlipped by rememberSaveable { mutableStateOf(false) }
            // 引导最后一步「学在浙大翻面」的「去看看」：把人送到总览页，
            // 并替他把卡翻到**背面**（学在浙大待交作业那一面）。
            //
            // 用**序号**而不是布尔开关：布尔只能表达「翻过一次」，
            // 第二次点「翻到背面」时值没变，这边就收不到新的一次请求。
            // 序号 +1 就是「又来一次」的信号。
            LaunchedEffect(state.heroFlipRequest) {
                if (state.heroFlipRequest > 0) isHeroFlipped = true
            }
            val heroFlipRotation by animateFloatAsState(
                targetValue = if (isHeroFlipped) 180f else 0f,
                animationSpec = tween(durationMillis = 450, easing = FastOutSlowInEasing),
                label = "hero_flip_rotation"
            )

            // Semester Overview & Progress Hero Card (3D Flip: 正面=日期天气，反面=学在浙大待交作业)
            // 正反两面用同一个 heroHeight（见上方计算），翻转过程中高度绝对一致，
            // 下方列表不会跳。基准字号下它仍然是 140.dp。
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(heroHeight)
                    .cardRevealAt(cardRevealProgress, 0)
                    .testTag("dashboard_semester_card")
                    .graphicsLayer {
                        rotationY = heroFlipRotation
                        cameraDistance = 14f * density
                    },
                shape = MiuSurface.HeroShape,
                colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                if (heroFlipRotation <= 90f) {
                    // 正面：日期与天气卡片（点击卡片主体翻转至背面学在浙大）。
                    // 浅色壁纸不铺白衬底，只保留原来那层 10% 白玻璃；
                    // 深色壁纸才按亮度把衬底推到 0.90，保证日期和正文读得出来。
                    //
                    // 用 BoxWithConstraints 是为了拿到主卡内容区的**实际宽度**：
                    // 日期字号要按左列实际能放多宽来选档（见 heroDateFontSize），
                    // 写死一个值在窄屏上必然放不下。
                    BoxWithConstraints(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(MiuSurface.HeroShape)
                            .wallpaperBlur()
                            .glassSurface(
                                shape = MiuSurface.HeroShape,
                                tint = Color.White,
                                tintAlpha = 0.10f,
                                baseCoatAlpha = heroBase,
                                blurRadius = 0.dp
                            )
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            ) {
                                isHeroFlipped = true
                            }
                            .padding(
                                horizontal = 18.dp,
                                // 垂直内边距随字号放大：字大了，卡里的呼吸也要跟着大。
                                vertical = HeroCardPaddingV * LocalEffectiveFontScale
                            ),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        // 卡内实际拿到的宽度，用来复核「这句字放不放得下」。
                        // 排法与字号在外面按同源的宽度定好了（见上），
                        // 这里只用实际宽度做最后一次确认。
                        val actualDateColumnWidth = heroDateColumnWidth(
                            innerWidth = maxWidth,
                            layout = heroLayout
                        )
                        // 档位按**实际列宽**再修一道。
                        //
                        // 为什么不能信外面的估算值：估算的列宽（卡外算的）与
                        // 卡内的实际列宽可能差 1dp（实测 332dp 上是 237.0 vs 236.1），
                        // 而 22.sp 那句日期的实测宽是 236.59 —— 正好卡在两者之间。
                        // 这时候「放不放得下」的判定就成了掷硬币：估得下、实际放不下，
                        // 日期就直接从完整写法跳成「只有月日和星期」。
                        //
                        // 修法是**按实际列宽降一档字号**：
                        // 宁可字号小一点，也保住完整日期
                        // （原则见 Responsive.kt：读不出星期几比字号小一点更糟）。
                        val dateTier = heroDateTierWithMeasuredFit(
                            startTier = dateFontSize.value,
                            text = todayDateStr,
                            columnWidth = actualDateColumnWidth
                        )
                        val actualDateStyle = heroDateStyle(dateTier.sp)
                        // 字号降到最小档之后如果还放不下，就**降信息密度**：
                        // 完整日期 → 月日 + 短星期 → 只有月日。三档都按实际渲染宽度
                        // 量过再选，仍然放不下才交给省略号。
                        val dateText = when {
                            measureTextSize(todayDateStr, actualDateStyle).width <= actualDateColumnWidth ->
                                todayDateStr
                            measureTextSize(compactDateStr, actualDateStyle).width <= actualDateColumnWidth ->
                                compactDateStr
                            measureTextSize(shortDateStr, actualDateStyle).width <= actualDateColumnWidth ->
                                shortDateStr
                            else -> todayDateStr
                        }
                        if (heroStacked) {
                            // 上下两段：**缩字救不了时的兜底**。
                            //
                            // 常态是并排 —— 日期按左列宽度降档即可，整页版式一个字不改。
                            // 只有当日期缩到最小档（14.sp）仍放不下（窄屏 + 用户把系统字号
                            // 放大，或任何宽度上的极端字号），才把天气挪到第二段。
                            //
                            // 为什么值得多这一行：并排时日期列是 `W − 168`，
                            // 而最小档 14.sp × 有效缩放 2.0 = 296dp，320dp 的屏上
                            // 左列只有 152dp —— 差着一倍多，缩字救不了，只能改版式。
                            //
                            // 挪到第二段之后日期拿整行宽度（`W − 48`），
                            // 天气那一行同时变宽：那句「杭州 · 空气轻度污染 138」
                            // 在 272dp 里不会再被 Ellipsis 截掉指数。
                            Column(modifier = Modifier.fillMaxWidth()) {
                                HeroMetaColumn(
                                    weekText = "第 ${state.currentWeek} 周",
                                    termText = state.semester?.name?.ifBlank { null } ?: "本学期",
                                    dateText = dateText,
                                    dateStyle = actualDateStyle,
                                    metaText = "${state.courses.size} 门课 · $weekHoursText",
                                    onOpenWeekPicker = onOpenWeekPicker,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(modifier = Modifier.height(heroStripGap()))
                                WeatherBlock(
                                    state = state.weather,
                                    hourOfDay = AppZone.hourOfDayNow(),
                                    layout = WeatherLayout.STRIP,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = if (heroWide) {
                                    // 宽屏两栏：**居中**并排，中间留一道固定间距。
                                    // 原来这里是 SpaceBetween，把天气甩到天边，
                                    // 656dp 的内容列上那个空洞能有 255dp ——
                                    // 读起来像坏了，而不是像宽。
                                    Arrangement.spacedBy(HeroWideGap, Alignment.CenterHorizontally)
                                } else {
                                    Arrangement.SpaceBetween
                                }
                            ) {
                                HeroMetaColumn(
                                    weekText = "第 ${state.currentWeek} 周",
                                    termText = state.semester?.name?.ifBlank { null } ?: "本学期",
                                    dateText = dateText,
                                    dateStyle = actualDateStyle,
                                    metaText = "${state.courses.size} 门课 · $weekHoursText",
                                    onOpenWeekPicker = onOpenWeekPicker,
                                    modifier = if (heroWide) {
                                        Modifier.widthIn(max = HeroWideMetaMax)
                                    } else {
                                        Modifier.weight(1f)
                                    }
                                )
                                if (!heroWide) {
                                    Spacer(modifier = Modifier.width(HeroWeatherGap))
                                }
                                WeatherBlock(
                                    state = state.weather,
                                    hourOfDay = AppZone.hourOfDayNow(),
                                    layout = WeatherLayout.COLUMN,
                                    blockWidth = heroLayout.weatherWidth
                                )
                            }
                        }
                    }
                } else {
                    // 背面：学在浙大待交作业卡片（抵消 180° 水平翻转，恢复正向文字渲染）
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(MiuSurface.HeroShape)
                            .wallpaperBlur()
                            .glassSurface(
                                shape = MiuSurface.HeroShape,
                                tint = Color.White,
                                tintAlpha = 0.10f,
                                baseCoatAlpha = heroBase,
                                blurRadius = 0.dp
                            )
                            .graphicsLayer {
                                rotationY = 180f
                            }
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            ) {
                                isHeroFlipped = false
                            }
                            .padding(
                                horizontal = 18.dp,
                                vertical = HeroCardPaddingV * LocalEffectiveFontScale
                            )
                    ) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.SpaceBetween
                        ) {
                            // 第一行：标题栏（点击本行任意空白翻回天气卡片）
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            // 徽标是固定盒子：它跟「学在浙大」那行字并排，
                                            // 字放大时徽标要跟着大，否则它缩成一个小点。
                                            .size(HeroTodoLogoSize.scaledWithText())
                                            .clip(CircleShape)
                                            .background(TeaColor)
                                            .border(0.5.dp, TeaColorBorder, CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.School,
                                            contentDescription = null,
                                            tint = TeaOnColor,
                                            modifier = Modifier.size(15.dp.scaledWithText())
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "学在浙大",
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    val todoCount = state.coursesTodoList.size
                                    val badgeText = if (!state.hasZjuCredentials) "未绑定"
                                        else if (state.isCoursesTodoLoading) "刷新中..."
                                        else if (!state.coursesTodoError.isNullOrBlank()) "同步失败"
                                        else if (todoCount > 0) "$todoCount 篇待交"
                                        else "已交清"
                                    val badgeColor = if (!state.hasZjuCredentials) TextSecondary
                                        else if (state.isCoursesTodoLoading) MaterialTheme.colorScheme.primary
                                        else if (!state.coursesTodoError.isNullOrBlank()) MaterialTheme.colorScheme.error
                                        else if (todoCount > 0) MaterialTheme.colorScheme.primary
                                        else TextSecondary
                                    val badgeBg = if (!state.hasZjuCredentials) MaterialTheme.colorScheme.surfaceVariant
                                        else if (state.isCoursesTodoLoading) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                        else if (!state.coursesTodoError.isNullOrBlank()) MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
                                        else if (todoCount > 0) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                        else MaterialTheme.colorScheme.surfaceVariant

                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(badgeBg)
                                            .padding(
                                                horizontal = 7.dp,
                                                vertical = HeroTodoBadgePadV.scaledWithText()
                                            )
                                    ) {
                                        Text(
                                            text = badgeText,
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                            color = badgeColor,
                                            fontSize = HeroTodoBadgeFontSize
                                        )
                                    }
                                }

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    val updateText = remember(state.coursesTodoLastUpdated) {
                                        formatLastUpdatedTime(state.coursesTodoLastUpdated)
                                    }
                                    if (updateText.isNotBlank()) {
                                        Text(
                                            text = updateText,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = TextSecondary,
                                            fontSize = 10.5.sp
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                    }

                                    // 一键刷新按钮（带加载旋转动效）
                                    val infiniteTransition = rememberInfiniteTransition(label = "courses_todo_spin")
                                    val spinAngle by infiniteTransition.animateFloat(
                                        initialValue = 0f,
                                        targetValue = 360f,
                                        animationSpec = infiniteRepeatable(
                                            animation = tween(900, easing = LinearEasing),
                                            repeatMode = RepeatMode.Restart
                                        ),
                                        label = "courses_todo_spin_angle"
                                    )

                                    Box(
                                        modifier = Modifier
                                            .size(HeroTodoRefreshSize)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
                                            .clickable(
                                                indication = null,
                                                interactionSource = remember { MutableInteractionSource() }
                                            ) {
                                                onRefreshCoursesTodo()
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.Refresh,
                                            contentDescription = "一键刷新",
                                            tint = if (state.isCoursesTodoLoading) MaterialTheme.colorScheme.primary else TextSecondary,
                                            modifier = Modifier
                                                .size(16.dp)
                                                .graphicsLayer {
                                                    if (state.isCoursesTodoLoading) {
                                                        rotationZ = spinAngle
                                                    }
                                                }
                                        )
                                    }
                                }
                            }

                            // 第二行：横向待办小卡片栏 (按 DDL 顺序从左往右滑动)
                            if (!state.hasZjuCredentials) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = HeroTodoMiniCardMinHeight.scaledWithText())
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                                        .padding(horizontal = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "尚未绑定浙大账号，无法同步作业",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextSecondary,
                                        fontSize = 12.sp,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Button(
                                        onClick = onOpenQuickLoginDialog,
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                        modifier = Modifier.heightIn(min = 32.dp)
                                    ) {
                                        Text("填写学号密码", fontSize = 11.5.sp)
                                    }
                                }
                            } else if (state.coursesTodoList.isEmpty()) {
                                val errorMsg = state.coursesTodoError
                                if (!errorMsg.isNullOrBlank()) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = HeroTodoMiniCardMinHeight.scaledWithText())
                                            .clip(RoundedCornerShape(14.dp))
                                            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f))
                                            .padding(horizontal = 14.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.ErrorOutline,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = errorMsg,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error,
                                            fontSize = 12.sp,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = HeroTodoMiniCardMinHeight.scaledWithText())
                                            .clip(RoundedCornerShape(14.dp))
                                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            // 原来写「当前没有未交作业，所有任务已结清」：
                                            // 前半句和后半句说的是同一件事，而「任务」
                                            // 是这个 App 里第四个称呼（待办 / 作业 /
                                            // 课程 / 任务），指的却是同一批东西。
                                            // 这一栏是「学在浙大」拉回来的作业，就只说作业。
                                            text = "✨ 暂无未交作业",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = TextSecondary,
                                            fontSize = 12.5.sp
                                        )
                                    }
                                }
                            } else {
                                LazyRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    items(state.coursesTodoList, key = { it.id }) { item ->
                                        CoursesMiniCard(
                                            item = item,
                                            cardHeight = heroMiniCardHeight,
                                            matchingCourse = courseMap.values.find {
                                                it.course.name.trim() == item.courseName.trim() ||
                                                    it.course.name.contains(item.courseName) ||
                                                    item.courseName.contains(it.course.name)
                                            }?.course
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Section: Today's Classes & Linked Tasks
        item {
            DashboardSectionHeader(
                title = coursesHeader,
                titleSize = 18.sp
            )
        }

        // 空态卡只在**一个条目都没有**时出现。
        //
        // 今天有课也有考试时当然不出现；今天**只有考试**时也不出现 ——
        // 上面那张「今天没课」的卡摆在一张考试卡前面是自相矛盾的：
        // 考试卡自己就说明了今天有事。
        if (todayItems.isEmpty()) {
            item {
                Card(
                    shape = MiuSurface.CardShape,
                    colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .cardRevealAt(cardRevealProgress, courseOrderBase)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            // 空态卡同样铺白衬底：它和课程卡、待办卡排在同一屏里，
                            // 不铺的话换深色壁纸时它会先变成一块发灰的板。
                            .glassSurface(shape = MiuSurface.CardShape, baseCoatAlpha = contentBase)
                            .padding(horizontal = 18.dp, vertical = 20.dp)
                    ) {
                        Column {
                            Text(
                                text = todayEmptyStateLabel(todayOffReason),
                                style = MiuType.CardTitleCompact,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(4.dp * LocalEffectiveFontScale))
                            Text(
                                text = todayEmptyStateSummary(pendingTasks.size),
                                style = MiuType.CourseMeta,
                                color = TextSecondary
                            )
                        }
                    }
                }
            }
        } else {
            // key 必须带前缀：下面那段「近期待办」用的是另一张表的 id，两段在**同一个**
            // LazyColumn 里，裸 `it.id` 会让时段 id 和待办 id 撞进同一个 key 命名空间，
            // 抛 `Key "N" was already used` 直接闪退。
            // 这个撞车平时看不出来——只有「今天有课」且「有待办」时才同时存在这两段，
            // 所以在周日（没排课）怎么测都复现不出来。
            //
            // 考试与课**在同一个列表里**，按时刻混排（见 `mergeDayItems`）。
            itemsIndexed(todayItems, key = { _, item -> item.lazyKey(0) }) { itemIndex, dayItem ->
                when (dayItem) {
                    is DayItem.ExamItem -> {
                        // 点考试卡 → **编辑考试**（本地可改考场 / 座位 / 时刻）。
                        // 不是跳去课程详情：考试卡上的信息是**考试**的，
                        // 而用户点它多半是因为「座位号没公布，我要自己补一个」。
                        val onClick = { onEditExam(dayItem.entry) }
                        val reveal = Modifier
                            .cardRevealAt(cardRevealProgress, courseOrderBase + itemIndex)
                            .clickable { onClick() }
                        // 考试抢到「下一件事」时用加高卡 —— 与课程那张同一套骨架，
                        // 只是换掉「下一节 / 开课 / 下课」这些**属于课**的词。
                        if (featuredExam != null && dayItem.entry.id == featuredExam.id) {
                            FeaturedExamCard(
                                entry = dayItem.entry,
                                isCurrent = featuredExamIsCurrent,
                                startMinute = dayItem.entry.startMinute,
                                endMinute = dayItem.entry.endMinute,
                                nowMinute = nowMinute,
                                countdownText = featuredExamCountdown,
                                contentBase = contentBase,
                                courseTint = if (customWallpaper) 0.28f else 0.32f,
                                modifier = reveal
                            )
                        } else {
                            ExamCard(
                                entry = dayItem.entry,
                                now = nowMillis,
                                onClick = onClick,
                                modifier = reveal
                            )
                        }
                    }
                    is DayItem.SlotItem -> {
                        val slot = dayItem.slot
                        val cWithSlots = courseMap[slot.courseId]
                        val course = cWithSlots?.course
                        if (course != null) {
                            val linked = state.tasks.filter { it.courseId == course.id }
                            val pendingCount = linked.count { !it.isCompleted }
                            val hasLinked = linked.isNotEmpty()
                            val courseColor = parseColor(course.colorHex)
                            val (timeStart, timeEnd) = state.periodTable.timeRange(slot.startPeriod, slot.endPeriod)
                                ?: ("--" to "--")
                            val room = slot.classroom.ifBlank { course.classroom }
                            // 白胶囊是玻璃的一部分：不做深色模式，所有壁纸都走同一档。
                            val isFeatured = slot.id == featuredSlotId

                            if (isFeatured && featuredWindow != null) {
                                FeaturedCourseCard(
                                    course = course,
                                    slot = slot,
                                    pendingCount = pendingCount,
                                    hasLinked = hasLinked,
                                    room = room,
                                    timeStart = timeStart,
                                    timeEnd = timeEnd,
                                    courseColor = courseColor,
                                    isCurrent = featuredIsCurrent,
                                    startMinute = featuredWindow.startMinute,
                                    endMinute = featuredWindow.endMinute,
                                    nowMinute = nowMinute,
                                    countdownText = featuredCountdown,
                                    contentBase = contentBase,
                                    courseTint = if (customWallpaper) 0.28f else 0.32f,
                                    modifier = Modifier
                                        .cardRevealAt(cardRevealProgress, courseOrderBase + itemIndex)
                                        .clickable { onInspectCourse(cWithSlots) }
                                )
                            } else {
                            Card(
                                shape = MiuSurface.CardShape,
                                colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .cardRevealAt(cardRevealProgress, courseOrderBase + itemIndex)
                                    .clickable { onInspectCourse(cWithSlots) }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .glassSurface(
                                            shape = MiuSurface.CardShape,
                                            tint = cardTint(courseColor, courseTint),
                                            tintAlpha = courseTint,
                                            baseCoatAlpha = contentBase,
                                        )
                                        .courseCardWash(
                                            courseColor = courseColor,
                                            shape = MiuSurface.CardShape,
                                            // 不描边：白边已取消，边界交给卡面本身。
                                            washAlpha = 0.10f,
                                            sheenAlpha = 0.11f
                                        )
                                        .padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(4.dp, 44.dp)
                                            .clip(RoundedCornerShape(2.dp))
                                            .background(
                                                Brush.verticalGradient(
                                                    listOf(
                                                        accentBarColor(courseColor),
                                                        accentBarColor(courseColor).copy(alpha = 0.55f)
                                                    )
                                                )
                                            )
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))

                                    Column(modifier = Modifier.weight(1f)) {
                                        // 这一行里挤着课程名与教室胶囊，两样都是**内容**，
                                        // 所以必须先知道这一行到底有多宽，才谈得上分配。
                                        //
                                        // 之前没有上限：胶囊是 Row 里没挂 weight 的子项，
                                        // Row 量它的时候给的是整行的约束，于是它按内容原宽铺开；
                                        // 课程名虽然挂了 weight(1f, fill=false)，但那份额是
                                        // 「整行减去胶囊实际占的宽」——胶囊把行占满了，
                                        // 课程名分到 0，而胶囊自己又溢出了这一列，
                                        // 直接画到右边的徽标底下（系统字号 1.5 时实测：
                                        // 「紫金港西2-105」压在「●1」上）。
                                        //
                                        // 现在给胶囊一个上限：它最多占这一行的 45%，
                                        // 剩下的留给课程名。45% 是按「课程名是主、教室是速查项」
                                        // 定的 —— 411dp 基准屏上最长的那颗胶囊约 120dp，
                                        // 在 305dp 的行里占 39%，所以 45% 不改变基准屏的观感，
                                        // 只在真的挤不下时才开始截断。
                                        BoxWithConstraints {
                                            val roomMaxWidth = maxWidth * 0.45f
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    text = course.name,
                                                    style = MiuType.CardTitleCompact,
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                    modifier = Modifier.weight(1f, fill = false),
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                if (room.isNotBlank()) {
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    val roomShape = RoundedCornerShape(6.dp)
                                                    Box(
                                                        modifier = Modifier
                                                            .widthIn(max = roomMaxWidth)
                                                            .clip(roomShape)
                                                            .background(courseColor.copy(alpha = 0.16f))
                                                            .border(0.5.dp, courseColor.copy(alpha = 0.28f), roomShape)
                                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                                    ) {
                                                        Text(
                                                            // 不带「@」：设计笔记的现状诊断第 6 条写着
                                                            // 「@ 教室胶囊的『@』是程序员语法，不是学生语言」，
                                                            // 加高主卡早就照这条改掉了，这一枚小胶囊
                                                            // 却还留着。同一个 App 里同一件事实用两种写法，
                                                            // 改掉一处就等于把另一处也标成了待办。
                                                            text = room,
                                                            color = accentOnCourse(courseColor),
                                                            fontSize = 10.sp,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(4.dp * LocalEffectiveFontScale))
                                        Text(
                                            text = "${formatPeriodRange(slot.startPeriod, slot.endPeriod)} ($timeStart-$timeEnd) · ${course.teacher}",
                                            style = MiuType.CourseMeta.copy(fontSize = 10.5.sp),
                                            color = TextSecondary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    CoursePendingBadge(
                                        pendingCount = pendingCount,
                                        hasLinked = hasLinked,
                                        courseColor = courseColor
                                    )
                                }
                            }
                            }
                        }
                        }
                    }
                }
        }


        // Section: Urgent / Today's Pending Tasks
        item {
            DashboardSectionHeader(
                title = "待办清单 · ${pendingTasks.size}",
                titleSize = 16.sp
            )
        }

        if (pendingTasks.isEmpty()) {
            item {
                Card(
                    shape = MiuSurface.CardShape,
                    colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .cardRevealAt(cardRevealProgress, taskOrderBase)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            // 同上：待办区的空态卡与待办卡同屏，衬底保持一致。
                            .glassSurface(shape = MiuSurface.CardShape, baseCoatAlpha = contentBase)
                            .padding(horizontal = 18.dp, vertical = 20.dp)
                    ) {
                        Column {
                            Text(
                                text = "全部完成",
                                style = MiuType.CardTitleCompact,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(4.dp * LocalEffectiveFontScale))
                            Text(
                                text = "这一周可以轻松一点了",
                                style = MiuType.CourseMeta,
                                color = TextSecondary
                            )
                        }
                    }
                }
            }
        } else {
            itemsIndexed(
                pendingTasks.take(DASHBOARD_TODO_LIMIT),
                key = { _, task -> "task_${task.id}" }
            ) { taskIndex, task ->
                TaskItemCard(
                    task = task,
                    customTypes = state.customTaskTypes,
                    onToggleComplete = { onToggleTaskComplete(task) },
                    onToggleStar = { onToggleTaskStar(task) },
                    onOpenDetail = { onInspectTask(task) },
                    modifier = Modifier.cardRevealAt(
                        cardRevealProgress,
                        taskOrderBase + taskIndex
                    )
                )
            }
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/**
 * 主卡日期的档位修正：按**实测**宽度往下修，差一点点就降一档字号。
 *
 * ## 为什么公式还不够
 *
 * [heroDateFontSize] 是按比例外推的（22.sp 那句的实测宽 × 档位比例 × 字号缩放），
 * 而渲染出来的真实宽度与外推值可能差 1–2dp —— 字距（-0.3.sp）、中文回退字形的
 * 取整都会带来这点差。
 *
 * 差这一点点的后果却不是「小一点」：日期的写法是三选一
 * （完整日期 → 月日+短星期 → 月日），一旦完整那句差 1dp 放不下，
 * 就直接跳到「月日+短星期」，**星期几没了**。
 *
 * ## 实测（332dp 屏，探针打出来的数）
 *
 * ```
 * 卡内实际列宽 = 236.10dp      ← 判定要用的数
 * 卡外估算列宽 = 237.00dp      ← 公式估的，差 0.9dp
 * 22.sp 实测字宽 = 236.59dp     ← 正好卡在两者之间
 * ```
 *
 * 估算值说「放得下」，实际值说「放不下」—— 判定成了掷硬币。
 * 所以修正一律用**卡内实际列宽**，不信任卡外的估算值。
 *
 * ## 判据
 *
 * 「读不出星期几比字号小一点更糟」——
 * 所以先降字号（最多降到 [HeroDateMinTierSp]），实在降不动了才降信息密度。
 *
 * @param startTier 公式估出来的档位（sp）。只会往下修，不会往上。
 * @param text 要保证放得下的那段文字（这里是完整日期）。
 * @param columnWidth 日期列的**实际**宽度（dp，排版 dp）。
 * @return 修正后的档位（sp）。
 */
@Composable
private fun heroDateTierWithMeasuredFit(
    startTier: Float,
    text: String,
    columnWidth: Dp
): Float {
    var tier = startTier
    while (tier > HeroDateMinTierSp &&
        measureTextSize(text, heroDateStyle(tier.sp)).width > columnWidth
    ) {
        tier = nextSmallerHeroDateTier(tier) ?: break
    }
    return tier
}

/**
 * 主卡正面左列：周次 chip + 学期名 / 大号日期 / 门课数。
 *
 * 抽出来是因为三种排法（并排 / 上下两段 / 两栏）都要这一段，
 * 抄三遍的话改一处就得记得改三处 —— 而这一段里的每个决定都是量过的。
 */
@Composable
private fun HeroMetaColumn(
    weekText: String,
    termText: String,
    dateText: String,
    dateStyle: TextStyle,
    metaText: String,
    onOpenWeekPicker: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable { onOpenWeekPicker() }
                    // chip 的上下内边距随字号放大，卡高算的时候用的是同一个值。
                    .padding(
                        horizontal = 9.dp,
                        vertical = HeroWeekChipPadV * LocalEffectiveFontScale
                    )
            ) {
                Text(
                    text = weekText,
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    fontSize = 11.5.sp
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            // 学期名是**用户可编辑的自由文本**（"2026-2027学年 秋冬 学期"），
            // 原来既没有 maxLines 也没有宽度上限：窄屏上它会折成三行，
            // 把下面整块内容顶出卡高 → 被裁。
            // 现在单行 + 省略号：宁可少看几个字，也不改卡的结构。
            Text(
                text = termText,
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary,
                modifier = Modifier.weight(1f, fill = false),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.height(HeroMetaGap * LocalEffectiveFontScale))

        // 日期是主卡的**主角**，V3 定稿就是 22sp
        // （用户明确否掉过"日期变小"的方案，见设计笔记）。
        // 窄屏上 22sp 放不下时**先改排法**（上下两段，日期拿整行），
        // 排法也救不回来才按可用宽降档 ——
        // 而不是让文字折行把卡高顶破、被裁掉半截。
        Text(
            text = dateText,
            style = dateStyle,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(modifier = Modifier.height(HeroDateMetaGap * LocalEffectiveFontScale))

        Text(
            text = metaText,
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 主卡**内容区**在当前窗口下的宽度（dp）。
 *
 * 宽度链是：窗口 → 内容列上限（[contentMaxWidthFor]）→ 页面左右留白
 * （[PageHInset] × 2）→ 卡内左右内边距（18dp × 2）。
 *
 * ## 为什么要在卡片**外面**算它
 *
 * 主卡的高度得先知道才能排版，而高度又取决于排法（缩字救不了时改成两段）。
 * 所以排法必须在进卡片之前就定下来 → 宽度也得在外面能算出来。
 * 这里的数与卡内 `BoxWithConstraints` 拿到的 `maxWidth` 是同一个来源
 * （排版宽 + contentMaxWidthFor），所以两边不会打架。
 *
 * @param layoutWidthDp 窗口的**排版**宽度（dp），见 [rememberLayoutWidthDp]。
 */
internal fun heroInnerWidthOf(layoutWidthDp: Int): Dp =
    (minOf(contentMaxWidthFor(layoutWidthDp).value, layoutWidthDp.toFloat()) - HeroChromeWidth)
        .coerceAtLeast(120f)
        .dp

/** 页面留白 6×2 + 卡内留白 18×2 = 48dp：从窗口宽到卡内容宽要吃掉的两层。 */
internal const val HeroChromeWidth = 48f

/**
 * 主卡需要多高：把正面那几行字的**实际排版高度**量出来，再补上内边距与间距。
 *
 * 为什么不按 `fontScale × 系数` 估：中文回退字体的行高比字号本身大不少，
 * 系数在大字号这一档正好估小——而大字号恰恰就是卡被裁掉的那一档。
 * 这里用的是和卡片里完全相同的 style（含 lineHeight），量多少算多少。
 *
 * 入参都是**这一屏真的要显示的文字**：周次 chip、学期名、日期、副标题。
 * 换文案（比如日期里带不带星期）高度会跟着重算，不需要谁记得回来改常量。
 *
 * @param dateStyle 日期那一行的实际样式（含随字号走的行高）。
 *   必须传进来而不是在这里重建：宽屏上日期是 26.sp，
 *   用 22.sp 那份量出来的高度会少一截，字被裁。
 * @param stacked 窄屏上下两段时为 true —— 那时多出一段天气条的高度。
 * @param compact 矮屏（横屏 / 分屏窄条）时为 true —— 那时高度是最贵的资源，
 *   主卡按**并排**内容的高度算，不占设计那 140dp。
 * @param mainSp 这一屏上日期实际用到的字号（sp）。它是这张卡的**主角**，
 *   高度锚点跟着它走 —— 窄屏降档、宽屏升档时，锚点跟着一起动，
 *   「主角在卡里占多少」才不变（见 [cardHeightAnchor]）。
 */
@Composable
private fun heroCardHeightFor(
    weekText: String,
    termText: String,
    dateText: String,
    metaText: String,
    dateStyle: TextStyle,
    stacked: Boolean,
    compact: Boolean,
    mainSp: Float,
    /** 背面（学在浙大）那一面要的高度，见 [heroBackFaceHeightFor]。 */
    backNeeded: Dp
): Dp {
    // 周次 chip：labelSmall + 11.5sp，上下各 3.5dp 内边距。
    val weekChipHeight = measureTextHeight(
        text = weekText,
        style = MaterialTheme.typography.labelSmall.copy(
            fontWeight = FontWeight.Bold,
            fontSize = 11.5.sp
        )
    ) + HeroWeekChipPadV * 2 * LocalEffectiveFontScale
    val termHeight = measureTextHeight(termText, MaterialTheme.typography.labelMedium)
    // 日期固定单行，档位只改 fontSize；行高由 [heroDateStyle] 按字号给，
    // 所以量出来的就是这一档真实占的高度。
    val dateHeight = measureTextHeight(dateText, dateStyle)
    val metaHeight = measureTextHeight(metaText, MaterialTheme.typography.bodySmall)

    val firstRow = maxOf(weekChipHeight, termHeight)
    // 卡内**垂直**间距随字号放大（见 [LocalEffectiveFontScale]）：
    // 字大了、行与行之间就该隔开一点，否则卡里挤成一坨、卡外空得慌。
    // 基准屏上系数是 1.0 —— 观感一分不变。
    val gap = LocalEffectiveFontScale
    // 左列（日期那一列）的高度：周次行 + 间距 + 日期 + 间距 + 副标题。
    val metaColumnHeight =
        firstRow + HeroMetaGap * gap + dateHeight + HeroDateMetaGap * gap + metaHeight
    // 窄屏两段时底下多一条天气条，高度得算进去，否则字压到条上。
    val stripHeight = if (stacked) heroStripGap() + weatherStripHeight() else 0.dp
    // 矮屏时主卡按**并排**内容的高度算：天气在右边，不占垂直空间。
    // 设计那 140dp 是竖排时定的（天气在下面一行）；横屏里它会吃掉半屏。
    val cardPaddingV = HeroCardPaddingV * gap
    val needed = if (compact) {
        cardPaddingV + metaColumnHeight + cardPaddingV
    } else {
        cardPaddingV + metaColumnHeight + stripHeight + cardPaddingV
    }
    // 矮屏不套 140dp 那个下限：那是竖排时定的，矮屏里它会吃掉半屏。
    //
    // 竖排时套的下限是**设计锚点**：设计高度按「主角（日期）比设计稿大了多少倍」放大，
    // 这样设计留白（140 − 内容 112 = 28dp）跟着字号一起放大，比例才守得住。
    // 基准屏上日期仍是 22.sp、字号 1.0 → 锚点 140.dp，**观感一分不变**。
    val designAnchor = cardHeightAnchor(
        designHeight = HeroCardHeight,
        designMainSp = HeroDateMaxSpCompact,
        mainSp = mainSp,
        effectiveFontScale = LocalEffectiveFontScale
    )
    return heroCardHeight(
        frontNeeded = needed,
        backNeeded = backNeeded,
        designAnchor = designAnchor,
        compact = compact
    )
}

/**
 * 主卡的最终高度：**正反两面取更高的那个**。
 *
 * ## 为什么是「取高」而不是「各算各的」
 *
 * 正面（日期 + 天气）与背面（学在浙大待交作业）共用**同一张卡** ——
 * 翻转时高度不变，下面的列表才不跳。所以只能有一个高度，
 * 而这一个高度必须**同时**装得下两面。
 *
 * ## 为什么以前会裁
 *
 * 以前卡高只按**正面**算，背面要多少高度没人管：窄屏 / 大字号下
 * 背面那排待办小卡会被卡的下沿裁掉（实测：用户真机 332dp + 1.16 倍字号，
 * 背面要 141.5dp、正面只要 124.8dp，差的 17dp 全从小卡身上裁）。
 *
 * ## 三个高度各是什么
 *
 * - [frontNeeded]：正面那几行字实测出来的高度；
 * - [backNeeded]：背面那两行（标题行 + 小卡行）实测出来的高度；
 * - [designAnchor]：设计锚点（140dp 按主角放大），竖排时保设计留白用的下限。
 *
 * 矮屏（横屏 / 分屏窄条）不套锚点 —— 那 140dp 是竖排时定的，
 * 矮屏里它会吃掉半屏 —— 但**两面取高**在矮屏上照样成立：
 * 否则矮屏翻到背面一样是裁的。
 *
 * 这是纯函数：值都从外面进来，所以「背面有没有参与这个取高」
 * 可以被单测直接钉住（见 `HeroCardHeightTest`）。
 */
internal fun heroCardHeight(
    frontNeeded: Dp,
    backNeeded: Dp,
    designAnchor: Dp,
    compact: Boolean
): Dp = if (compact) {
    maxOf(frontNeeded, backNeeded)
} else {
    maxOf(designAnchor, frontNeeded, backNeeded)
}

/**
 * 主卡**背面**（学在浙大待交作业）那一面需要多高。
 *
 * ## 为什么必须单独算
 *
 * 正面（日期 + 天气）与背面共用**同一张卡** —— 翻转时高度不变，下面的列表才不跳。
 * 以前卡高只按**正面**算，背面要多少高度没人管，于是窄屏 / 大字号下
 * 背面那排待办小卡会被卡的下沿裁掉：
 *
 * - 实测（用户真机 332dp + 1.16 倍系统字号）：背面要 141.5dp，正面只要 124.8dp，
 *   差的 17dp 全从小卡身上裁 —— 用户截图里「HW04 动态电路的时…」下面那行
 *   只剩半个字。
 *
 * 所以现在卡高取**两面里更高的那个**（见 `heroHeight` 的计算处）。
 *
 * ## 背面由三段组成
 *
 * 1. **标题行**：徽标（跟着字放大）、徽章、刷新按钮（固定 30dp），取最高的那个；
 * 2. **内容行**：横向待办小卡，高度下限 64dp（跟着字放大）；
 * 3. **卡片的上下内边距**（跟着字放大）。
 *
 * 每一项都用的是背面**真正画出来**的那份尺寸（[HeroTodoLogoSize] 等常量），
 * 所以这里算出来的高度就是背面实际要占的高度。
 */
@Composable
private fun heroBackFaceHeightFor(miniCardHeight: Dp): Dp {
    val gap = LocalEffectiveFontScale
    val cardPaddingV = HeroCardPaddingV * gap

    // 标题行：徽标、徽章、刷新按钮并排，取最高的那个。
    val badgeHeight = measureTextHeight(
        text = "未绑定",
        style = MaterialTheme.typography.labelSmall.copy(
            fontWeight = FontWeight.SemiBold,
            fontSize = HeroTodoBadgeFontSize
        )
    ) + HeroTodoBadgePadV * gap * 2
    val titleRowHeight = maxOf(
        HeroTodoLogoSize * gap,
        HeroTodoRefreshSize,
        badgeHeight
    )

    // 内容行：横向待办小卡。高度由 [heroTodoMiniCardHeight] 量好传进来 ——
    // 算卡高和画小卡必须是**同一个数**，否则差的那一截就从卡的下沿被裁掉。
    return cardPaddingV + titleRowHeight + miniCardHeight + cardPaddingV
}

/**
 * 背面那张小卡**内容**要占的高度（不含高度下限 64dp）。
 *
 * 小卡里是「作业名 + 截止时间 · 课程名」两段，所以高度 = 上下内边距 +
 * 主行 + 间距 + 备注行。每一项都用**小卡里真正用的那个样式**去量。
 */
@Composable
private fun heroTodoMiniCardContentHeight(titleHeight: Dp): Dp {
    val gap = LocalEffectiveFontScale
    val remarkHeight = measureTextHeight(
        text = "12月3日 · 数字电路",
        style = MaterialTheme.typography.labelSmall.copy(fontSize = HeroTodoMiniCardRemarkSp)
    )
    return HeroTodoMiniCardPadV * gap * 2 +
        titleHeight +
        HeroTodoMiniCardGap * gap +
        remarkHeight
}

/**
 * 背面那排小卡的**统一高度**。
 *
 * ## 为什么要「统一」
 *
 * 小卡横向排成一行（LazyRow），每张卡按自己的内容长高的话，这一排会变成
 * **参差不齐的下沿** —— 玻璃面一深一浅，读起来像坏掉了。
 * 所以量一次最长的那张，全排都用这个高度。
 *
 * ## 主行的高度从哪来
 *
 * 作业名**允许折行**（最多 [HeroTodoMiniCardTitleMaxLines] 行），
 * 所以「主行有多高」取决于**实际那些名字**在**实际那个宽度**里折几行，
 * 而不是拿一句示例文字量一次 —— 名字都短的时候没必要给两行留位。
 *
 * @param titles 这一屏要显示的作业名（空列表 = 没有作业）。
 */
@Composable
private fun heroTodoMiniCardHeight(titles: List<String>): Dp {
    val gap = LocalEffectiveFontScale
    val titleStyle = heroTodoMiniCardTitleStyle()
    val textWidth = heroTodoMiniCardTextWidth()

    val titleHeight = if (titles.isEmpty()) {
        // 没有作业时也要给出一个高度（背面那一行空态要用）。
        measureTextHeight(text = "HW04 动态电路的时域分析", style = titleStyle)
    } else {
        titles.maxOf { title ->
            measureTextHeight(
                text = title,
                style = titleStyle,
                maxWidth = textWidth,
                maxLines = HeroTodoMiniCardTitleMaxLines
            )
        }
    }

    return maxOf(
        HeroTodoMiniCardMinHeight * gap,
        heroTodoMiniCardContentHeight(titleHeight)
    )
}

/**
 * 背面小卡主行（作业名）的文字样式。
 *
 * 测量与渲染必须是**同一份**样式，否则量出来的高度不算数
 * （换一个字重，行高就变了）。
 */
@Composable
private fun heroTodoMiniCardTitleStyle(): TextStyle =
    MaterialTheme.typography.titleSmall.copy(
        fontWeight = FontWeight.Bold,
        fontSize = HeroTodoMiniCardTitleSp
    )

/**
 * 背面小卡里「作业名」那一列的可用宽（dp）。
 *
 * = 卡宽 − 左右内边距 − 左侧色条 − 色条与文字的间距。
 *
 * 卡宽跟着字放大，内边距 / 色条 / 间距是固定的 —— 所以**字越大，
 * 能放名字的地方越宽**（这也是小卡宽跟着放大、而不是只放高度的原因）。
 */
@Composable
private fun heroTodoMiniCardTextWidth(): Dp {
    val scale = LocalEffectiveFontScale
    return HeroTodoMiniCardWidth * scale -
        HeroTodoMiniCardPadH * 2 -
        HeroTodoMiniCardBarWidth -
        HeroTodoMiniCardBarGap
}

@Composable
private fun CoursesMiniCard(
    item: CoursesTodoItem,
    matchingCourse: CourseEntity?,
    /** 统一的小卡高度（[heroTodoMiniCardHeight] 量出来的那一个）。 */
    cardHeight: Dp,
    modifier: Modifier = Modifier
) {
    val courseColor = remember(matchingCourse) {
        matchingCourse?.colorHex?.let { parseColor(it) } ?: Color(0xFF8E9BAE)
    }
    val contentBase = if (LocalWallpaperIsCustom.current) 0.80f else 0.55f

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(0.dp),
        modifier = modifier
            // 宽度保持固定：这张卡在 LazyRow 里，横向测量是**无界**的，
            // 宽度写成 min 时行内的 weight 分不到确定的剩余宽度（标题会被压没）。
            // 高度则可以长：Row 上挂了 height(cardHeight)。
            //
            // 但「固定」不等于「不跟字走」：作业名 12.5.sp 在 156.dp 的卡里，
            // 字号一放大到 150% 名字就装不下（会被 Ellipsis 截掉）。
            // 所以宽度与高度都按文字放大倍数走（见 [scaledWithText]）——
            // 它在 LazyRow 里，卡变宽了用户横向滚就是了。
            .width(HeroTodoMiniCardWidth.scaledWithText())
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 高度是**外面算好的那一个**（[heroTodoMiniCardHeight]）：
                // 算主卡高和画小卡必须是同一个数 —— 差的那一截会从小卡身上
                // 或主卡的下沿被裁掉（这一类坏点以前真的发生过）。
                //
                // 高度下限原来挂在这里：Row 里的 glassSurface 需要覆盖
                // 「至少有 64dp」的那块面。不能再用 fillMaxSize —— 外层是
                // LazyRow，把高度下限写成 min 之后，fillMaxSize 会按 LazyRow
                // 的可用高度把每张小卡拉满（而不是 64dp）。
                .height(cardHeight)
                .glassSurface(
                    shape = RoundedCornerShape(14.dp),
                    tint = cardTint(courseColor, 0.08f),
                    tintAlpha = 0.08f,
                    baseCoatAlpha = contentBase
                )
                .padding(
                    horizontal = HeroTodoMiniCardPadH,
                    vertical = HeroTodoMiniCardPadV.scaledWithText()
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧课程色细条
            Box(
                modifier = Modifier
                    .size(HeroTodoMiniCardBarWidth, 36.dp.scaledWithText())
                    .clip(RoundedCornerShape(2.dp))
                    .background(accentBarColor(courseColor))
            )
            Spacer(modifier = Modifier.width(HeroTodoMiniCardBarGap))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                // 主行：作业名称
                Text(
                    text = item.title,
                    style = heroTodoMiniCardTitleStyle(),
                    color = MaterialTheme.colorScheme.onSurface,
                    // 名字**允许折行**（最多两行）：作业名普遍比一行长
                    // （实测「HW04 动态电路的时域分析」就要两行），
                    // 锁死一行的话末尾总被省略号吃掉一半 —— 读不全就不算标题。
                    maxLines = HeroTodoMiniCardTitleMaxLines,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(HeroTodoMiniCardGap * LocalEffectiveFontScale))

                // 备注行：DDL（紧急状态突出显示）· 课程名
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = item.dueFormatted,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = if (item.isUrgent) FontWeight.Bold else FontWeight.Normal,
                            fontSize = HeroTodoMiniCardRemarkSp
                        ),
                        color = if (item.isUrgent) Color(0xFFC84238) else TextSecondary,
                        maxLines = 1
                    )
                    Text(
                        text = " · ",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        fontSize = HeroTodoMiniCardRemarkSp
                    )
                    Text(
                        text = item.courseName,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        fontSize = HeroTodoMiniCardRemarkSp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }
            }
        }
    }
}

private fun formatLastUpdatedTime(timestamp: Long): String {
    if (timestamp <= 0L) return ""
    val diff = System.currentTimeMillis() - timestamp
    val minutes = diff / 60000
    return when {
        minutes < 1 -> "刚刚更新"
        minutes < 60 -> "${minutes}分钟前"
        else -> "${AppZone.format("HH:mm", timestamp)} 更新"
    }
}

/**
 * 组头：就是一行纯文字，回到原版的样子。
 *
 * 不做深色模式，也不给壁纸加自动磨砂底；深色照片需要保护时，
 * 用户自己在壁纸设置里拉「文字保护遮罩」。
 */
/** 主卡的当前/下一节时间窗：起止分钟一次算好。 */
private data class SlotWindow(
    val slot: CourseSlotEntity,
    val startMinute: Int,
    val endMinute: Int
)

/**
 * 课程卡的“色带玻璃”：课程色从左侧晕开、顶部一道很轻的高光。
 * 叠在 glassSurface 之上，不改变卡片尺寸和交互。
 *
 * **不描边**：白边已经取消——白边在浅色壁纸上只是勉强算材质，在深色/花纹壁纸上
 * 是一圈硬邦邦的亮线，而卡片边界本来就由「面更实 + 更大的模糊半径」这两件事撑着
 * （见 Mica.kt）。主卡那圈 1dp 课程色描边也跟着撤了：它原本是拿来当「这是主卡」
 * 的层级信号，现在主卡已经靠**加高 + 状态标记 + 右栏读数**站出来了，不必再描一圈。
 * 描边给出去就收回来，别留一个没人用的旋钮。
 *
 * 实现挪到了 `ui/components/CourseTimetableComponents.kt`：
 * 考试卡要用同一套卡面语言，而它原来在这里是 private 的，课表页拿不到。
 */
/**
 * 课程卡右侧的待办小签：同色系描边，不再用一块高白胶囊。
 */
@Composable
private fun CoursePendingBadge(
    pendingCount: Int,
    hasLinked: Boolean,
    courseColor: Color,
    modifier: Modifier = Modifier
) {
    if (pendingCount > 0) {
        Box(
            modifier = modifier
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.26f))
                .border(0.75.dp, courseColor.copy(alpha = 0.38f), CircleShape)
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(accentOnCourse(courseColor))
                )
                Text(
                    text = "$pendingCount",
                    color = accentOnCourse(courseColor),
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    } else if (hasLinked) {
        Box(
            modifier = modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.26f))
                .border(0.75.dp, courseColor.copy(alpha = 0.38f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "✓",
                color = accentOnCourse(courseColor),
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
    }
}

/**
 * 主卡上那个状态标记。
 *
 * 「现在」是**主题绿实心**，「下一节」是**磨砂白面 + 墨黑字**。
 *
 * 为什么不再用两档绿：旧版「下一节」那枚淡绿胶囊压在粉彩卡上，是全卡对比度
 * 最高的元素，比课名还响——可绿色跟「这门课」没有任何关系，纯粹是拿了一个
 * 不相干的颜色占了最贵的对比度（设计笔记的规矩：颜色是线索，不是装饰；
 * 紧迫度负责「现在看哪条」）。
 *
 * 现在两态靠**实心 / 磨砂**分轻重：「现在」是正在发生，主题绿是语义色，
 * 值得占一个实心块；「下一节」还没发生，给一枚中性标签就够——
 * 中性的好处是它落在任何一门课的粉彩卡面上都不撞色。
 */
@Composable
private fun FeaturedStateChip(
    isCurrent: Boolean,
    /** 「还没发生」那一态的文案。课是「下一节」，考试是「下一场」。 */
    nextLabel: String = "下一节",
    modifier: Modifier = Modifier
) {
    val background = if (isCurrent) {
        MaterialTheme.colorScheme.primary
    } else {
        Color.White.copy(alpha = 0.55f)
    }
    val foreground = if (isCurrent) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = if (isCurrent) "现在" else nextLabel,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.Bold,
            color = foreground,
            maxLines = 1
        )
    }
}

/**
 * 加高的主卡：当前课 / 下一节课。
 *
 * 排版按**信息成组**来：左边是这门课的事实（课名 / 教室 / 节次 / 教师），
 * 右边是一个时间读数，中间一根发丝线分开，底部那行按这一态有没有百分比来给。
 *
 * 相对旧版改了三处，都是为了把「响」让给该响的东西：
 *
 * 1. **状态标记不再跟课程色打架**（见 [FeaturedStateChip]）。
 * 2. **右栏改成「标签在上、读数在下」**。旧版把「开始」压在 13:25 底下，
 *    而这个词组头已经说过一次（组头：「下节课还有 51 分 开始」），
 *    卡里再说就是第三次。现在右栏只留一个标签说明右边那个数字是什么，
 *    文案也改成「开课」，跟组头的「开始」不撞词。
 * 3. **左轨满高，底部各归各位**。旧版左轨只有 56dp，在 112dp 的卡里像半截
 *    没画完。现在左轨贯通内容区。底部那行按**这一态到底有没有百分比**来给：
 *    「现在」有——进度条就是「这节课上了多少」的量表，所以画轨道 +「已上 92%」；
 *    「下一节」没有——还没开课，没有百分比可言，硬画一条空轨道只是拿一根
 *    没有含义的线占位置，所以那里只留倒计时本身。
 * 4. **教室升成速查项**（见下）。
 * 5. **不描边**。主卡那圈 1dp 课程色描边撤掉了：它原本是「这是主卡」的层级信号，
 *    现在主卡已经靠**加高 + 状态标记 + 右栏读数**站出来了，不必再描一圈。
 *
 * ### 教室为什么单独占一行
 *
 * 用户原话：「教室信息对用户来说是需要速查的信息，要明显一点」。
 * 旧版把教室塞成 meta 行里一枚 9.5sp 的小胶囊，压在 11sp 的节次/时间/教师旁边——
 * 那是整张卡上**最小**的一枚字，却是出门前最要紧的一个数。速查的东西不该
 * 藏在最细的一行里。
 *
 * 现在教室自己占一行：位置图标 + 14sp SemiBold + 主文字墨色，夹在课名和
 * meta 行之间，字号只比课名小一档。顺带把「@」去掉了——课程详情弹层里
 * 教室本来就用 [Icons.Outlined.LocationOn] 表示（见 CourseTimetableComponents），
 * 图标已经说明了「这是地点」，再挂一个「@」是重复；设计笔记的现状诊断也写着
 * 「@ 教室胶囊的『@』是程序员语法，不是学生语言」。
 *
 * 教室名写**全名**（带「紫金港」这样的校区前缀），不砍前缀——理由见
 * 下面 `room` 变量上方那段注释。
 *
 * ### 行高必须显式写
 *
 * Compose 的 `Text` 只改 `fontSize` 不改 `lineHeight` 时，会**继承默认主题的
 * lineHeight**（本项目是 24sp）——11sp 的小字和 18.5sp 的课名因此占着一样高的
 * 行框，24dp 一行是白白浪费的。这张卡要同时塞下四行事实，就把这几行的
 * lineHeight 逐条写死，行高立刻省出 20dp 上下，教室这一行才塞得进去而
 * 不必把卡继续加高。
 */
/**
 * 课程卡的**主角字号**：课名（sp）。
 *
 * 这张卡的主角是课名 —— 教室、节次、进度都是配角。高度锚点跟着它走
 * （见 [cardHeightAnchor]），所以这个数改了，锚点也要跟着改：
 * 两处写的是同一个常量，不会对不上。
 *
 * **加高卡的课名比列表卡更大一档**：17 → 18.5（和其他卡片标题同一个「加一号」）。
 * 加高卡本来就是这一屏的主角，标题再大一档，它与列表卡的层级差
 * 才是被看见的，而不是被读出来的。
 *
 * ⚠️ 代价：课名越长越容易在这一行被截断（这一行的右边还有状态 chip）。
 * 「体育训练与比赛（B类队-网球）」这类长名字本来就在临界点上，
 * 放大之后会更早一点省略。这是已知的取舍 —— 要它就别缩字号。
 */
private const val CourseCardTitleFontSize = 18.5f

@Composable
private fun FeaturedCourseCard(
    course: CourseEntity,
    slot: CourseSlotEntity,
    pendingCount: Int,
    hasLinked: Boolean,
    room: String,
    timeStart: String,
    timeEnd: String,
    courseColor: Color,
    isCurrent: Boolean,
    startMinute: Int,
    endMinute: Int,
    nowMinute: Int,
    countdownText: String,
    contentBase: Float,
    courseTint: Float,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(20.dp)
    val duration = (endMinute - startMinute).coerceAtLeast(1)
    val progress = if (isCurrent) {
        ((nowMinute - startMinute).toFloat() / duration).coerceIn(0f, 1f)
    } else {
        0f
    }
    val percent = (progress * 100).roundToInt()
    // 卡高锚点：这张卡的**最小**高度（内容装不下时才被撑大）。
    val cardMinHeight = cardHeightAnchor(
        designHeight = 124.dp,
        designMainSp = CourseCardTitleFontSize,
        mainSp = CourseCardTitleFontSize,
        effectiveFontScale = LocalEffectiveFontScale
    )
    // 内容区的高度 = 卡高 - 上下内边距。
    //
    // 这一行里有两根「贯通内容区」的竖条（左轨、中间那根发丝线），它们都靠
    // `fillMaxHeight()` 拉满。而**拉不满**：这一行的高度来自卡的 `heightIn(min=…)`，
    // 对行里的孩子来说高度是**无界**的 —— `fillMaxHeight()` 在无界高度上等于没写，
    // 那根空的 Box 就塌成 0 高，竖条直接消失（像素级查过：整条左缘都是卡面色，
    // 一根竖条都没有；中间那根发丝线同样不见）。
    //
    // 所以给一个**下限**：高度有界时 `fillMaxHeight()` 拉满，无界时靠这个下限撑着。
    val contentAreaHeight = cardMinHeight - 28.dp * LocalEffectiveFontScale
    // 教室写全名（带校区前缀）。
    //
    // 早先这里会砍掉「紫金港」前缀，为的是给同一行里的教师名腾宽度——那时教室是
    // meta 行里一枚 9.5sp 的小胶囊，挤得下不下全看教师名长不长。
    // 那个理由现在**正好反过来了**：教室已经提成速查项、自己独占一行，宽度根本不紧张；
    // 而被砍掉的「紫金港」恰恰是速查时最要紧的那部分——「西2-105」不写校区，
    // 学生分不清是紫金港还是玉泉。列表卡、课程详情、课表页也一直都是写全名的。
    //
    // 判据：**为省宽度砍掉关键字段，是把成本转嫁给最不该承担它的人**——
    // 砍掉的是要出门找教室的人要读的那几个字，省下的是一行的空白。

    Card(
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = modifier
            .fillMaxWidth()
            // 高度是**下限**：这张卡里叠着状态 chip、课程名、教室、节次、进度五样东西，
            // 系统字号放大后 124dp 装不下（实测 2.0 时最后一行被裁）。
            // 基准字号下内容刚好 124dp，观感不变。
            //
            // 下限本身是**设计锚点**：它跟着这张卡的主角（课名）放大，
            // 基准屏上 17sp 的课名占卡高 13.7%，2.0 倍字号下不放大下限就变成 17% ——
            // 同一张卡在不同字号下长得不一样。
            // 课名没有档位，主角放大倍数就是字号缩放本身。
            .heightIn(min = cardMinHeight)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .glassSurface(
                    shape = shape,
                    tint = cardTint(courseColor, courseTint),
                    tintAlpha = courseTint,
                    baseCoatAlpha = contentBase
                )
                .courseCardWash(
                    courseColor = courseColor,
                    shape = shape,
                    washAlpha = 0.10f,
                    sheenAlpha = 0.12f
                )
                .padding(
                    horizontal = 16.dp,
                    // 卡内垂直间距随字号放大（见 [LocalEffectiveFontScale]）。
                    vertical = 14.dp * LocalEffectiveFontScale
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左轨贯通整个内容区。原来只有 56dp，在 112dp 的卡里像半截没画完。
            //
            // `heightIn(min = contentAreaHeight)` 不是凑数：**没有它这根竖条是 0 高**
            // （高度无界时 `fillMaxHeight()` 拉不满，空 Box 直接塌掉）。
            Box(
                modifier = Modifier
                    .width(5.dp)
                    .fillMaxHeight()
                    .heightIn(min = contentAreaHeight)
                    .clip(RoundedCornerShape(2.5.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                accentBarColor(courseColor),
                                accentBarColor(courseColor).copy(alpha = 0.55f)
                            )
                        )
                    )
            )
            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FeaturedStateChip(isCurrent = isCurrent)
                    Spacer(modifier = Modifier.width(7.dp))
                    Text(
                        text = course.name,
                        fontSize = CourseCardTitleFontSize.sp,
                        lineHeight = 26.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }
                Spacer(modifier = Modifier.height(4.dp * LocalEffectiveFontScale))
                // 教室：速查项，单独一行。图标跟课程详情弹层用同一个（LocationOn）。
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (room.isNotBlank()) {
                        Icon(
                            imageVector = Icons.Outlined.LocationOn,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                            tint = accentOnCourse(courseColor)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = room,
                            fontSize = 14.sp,
                            lineHeight = 21.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            // 挂 weight(1f, fill=false)：教室名是用户输入的自由文本，
                            // 系统字号放大后可能比这一行还长。不给约束的话它会
                            // 画到卡片外面去（不是被截断，是直接溢出到隔壁卡上）。
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(3.dp * LocalEffectiveFontScale))
                Text(
                    text = "${formatPeriodRange(slot.startPeriod, slot.endPeriod)} ($timeStart-$timeEnd) · ${course.teacher}",
                    style = MiuType.CourseMeta.copy(fontSize = 11.sp),
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(8.dp * LocalEffectiveFontScale))
                // 底部：时间进度。**轨道只属于「现在」**——
                // 进度条是「这节课上了百分之多少」的量表，「下一节」还没有百分比可言，
                // 画一条空轨道只是拿一根没有含义的线占位置。
                // 「下一节」这一行只留倒计时本身。
                if (isCurrent) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(Color.White.copy(alpha = 0.55f))
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(progress)
                                    .height(4.dp)
                                    .background(
                                        Brush.horizontalGradient(
                                            listOf(
                                                accentBarColor(courseColor),
                                                cardTint(courseColor, 0.55f)
                                            )
                                        )
                                    )
                            )
                        }
                        Spacer(modifier = Modifier.width(9.dp))
                        Text(
                            text = "已上 $percent%",
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = accentOnCourse(courseColor),
                            maxLines = 1
                        )
                    }
                } else {
                    Text(
                        text = countdownText,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.width(10.dp))
            // 发丝线贯通内容区，把右边读数单独划成一列。
            // 同样要 `heightIn(min = contentAreaHeight)`：理由见上面 contentAreaHeight 那段。
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .heightIn(min = contentAreaHeight)
                    .background(Color.White.copy(alpha = 0.34f))
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(
                // 64dp 是基准字号下「08:00」加左右呼吸的宽度。系统字号放大后
                // 21sp 的时间会排到 40dp 宽，写死 64dp 只会显示成「08:」——
                // 实测 2.0 时就是这样。改成下限：装得下仍是 64dp，装不下按内容长。
                modifier = Modifier.widthIn(min = 64.dp),
                horizontalAlignment = Alignment.End
            ) {
                // 标签在上、读数在下：旧版把「开始」压在数字底下，
                // 那个词又被组头说过一次，现在右栏只说明右边这个数字是什么。
                Text(
                    text = if (isCurrent) "下课" else "开课",
                    fontSize = 10.5.sp,
                    lineHeight = 14.sp,
                    color = TextSecondary,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.height(1.dp))
                Text(
                    text = formatMinute(if (isCurrent) endMinute else startMinute),
                    fontSize = 21.sp,
                    lineHeight = 26.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.height(7.dp))
                CoursePendingBadge(
                    pendingCount = pendingCount,
                    hasLinked = hasLinked,
                    courseColor = courseColor
                )
            }
        }
    }
}

/**
 * 加高的考试卡：正在考的 / 下一场考试。
 *
 * ## 为什么考试也要一张加高卡
 *
 * 考试与课在**同一条时间线上**（见 [mergeDayItems]），
 * 而「下一件事」只能有一个 —— 两边各挑一个加高，屏幕上会同时出现
 * 两张「下一节」/「下一场」，用户得自己判断哪个才是真的下一件。
 *
 * 所以课与考试**抢同一个位置**：谁先来谁加高。
 * 考试抢到时，组头说的是「考试还有 X 开始」，卡片里也报同一个倒计时 ——
 * 两处说的是同一件事。
 *
 * ## 排版沿用课程加高卡
 *
 * 左边是这门考试的事实（课名 / 考场 / 时刻），右边是一个时间读数，
 * 中间一根发丝线分开，底部那行按这一态有没有百分比来给。
 * 考试卡与课程卡**必须长得像同一家的两张卡**，否则用户在两个信息块之间
 * 要重新学一遍阅读方式。
 */
@Composable
private fun FeaturedExamCard(
    entry: ExamEntry,
    isCurrent: Boolean,
    startMinute: Int,
    endMinute: Int,
    nowMinute: Int,
    countdownText: String,
    contentBase: Float,
    courseTint: Float,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(20.dp)
    val examColor = parseColor(entry.courseColorHex)
    val duration = (endMinute - startMinute).coerceAtLeast(1)
    val progress = if (isCurrent) {
        ((nowMinute - startMinute).toFloat() / duration).coerceIn(0f, 1f)
    } else {
        0f
    }
    val percent = (progress * 100).roundToInt()
    // 卡高锚点与课程加高卡一致：两张卡在同一屏里，高矮不同会显得是两套东西。
    val cardMinHeight = cardHeightAnchor(
        designHeight = 124.dp,
        designMainSp = CourseCardTitleFontSize,
        mainSp = CourseCardTitleFontSize,
        effectiveFontScale = LocalEffectiveFontScale
    )
    val contentAreaHeight = cardMinHeight - 28.dp * LocalEffectiveFontScale

    val room = entry.classroom.trim()
    val seat = entry.seat.trim()
    val clock = examClockRange(entry)

    Card(
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = cardMinHeight)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .glassSurface(
                    shape = shape,
                    tint = cardTint(examColor, courseTint),
                    tintAlpha = courseTint,
                    baseCoatAlpha = contentBase
                )
                .courseCardWash(
                    courseColor = examColor,
                    shape = shape,
                    washAlpha = 0.10f,
                    sheenAlpha = 0.12f
                )
                .padding(
                    horizontal = 16.dp,
                    vertical = 14.dp * LocalEffectiveFontScale
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(5.dp)
                    .fillMaxHeight()
                    .heightIn(min = contentAreaHeight)
                    .clip(RoundedCornerShape(2.5.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                accentBarColor(examColor),
                                accentBarColor(examColor).copy(alpha = 0.55f)
                            )
                        )
                    )
            )
            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 「下一节」在这里是「下一场」——考试不是一节课，
                    // 说成「节」会让用户去找教室。
                    FeaturedStateChip(isCurrent = isCurrent, nextLabel = "下一场")
                    Spacer(modifier = Modifier.width(7.dp))
                    Text(
                        text = entry.courseName,
                        fontSize = CourseCardTitleFontSize.sp,
                        lineHeight = 26.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    ExamKindBadge(kind = entry.kind, examColor = examColor)
                }
                Spacer(modifier = Modifier.height(4.dp * LocalEffectiveFontScale))
                // 考场：速查项，单独一行。图标跟课程卡用同一个（LocationOn）。
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.LocationOn,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                        tint = accentOnCourse(examColor)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    if (room.isNotEmpty()) {
                        Text(
                            text = if (seat.isNotEmpty()) "$room · 座位 $seat" else room,
                            fontSize = 14.sp,
                            lineHeight = 21.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    } else {
                        // 考场还没定：空心胶囊 = 还没填上的格子。
                        PendingPill(text = "考场待安排")
                    }
                }
                Spacer(modifier = Modifier.height(3.dp * LocalEffectiveFontScale))
                Text(
                    text = listOfNotNull(
                        clock.takeIf { it.isNotBlank() },
                        entry.dateLabel.takeIf { it.isNotBlank() && !entry.hasDate }
                    ).joinToString(" · ").ifEmpty { "时间待定" },
                    style = MiuType.CourseMeta.copy(fontSize = 11.sp),
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(8.dp * LocalEffectiveFontScale))
                // 底部：正在考时给进度，「下一场」只留倒计时本身。
                if (isCurrent) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(Color.White.copy(alpha = 0.55f))
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(progress)
                                    .height(4.dp)
                                    .background(
                                        Brush.horizontalGradient(
                                            listOf(
                                                accentBarColor(examColor),
                                                cardTint(examColor, 0.55f)
                                            )
                                        )
                                    )
                            )
                        }
                        Spacer(modifier = Modifier.width(9.dp))
                        Text(
                            text = "已考 $percent%",
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = accentOnCourse(examColor),
                            maxLines = 1
                        )
                    }
                } else {
                    Text(
                        text = countdownText,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.width(10.dp))
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .heightIn(min = contentAreaHeight)
                    .background(Color.White.copy(alpha = 0.34f))
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(
                modifier = Modifier.widthIn(min = 64.dp),
                horizontalAlignment = Alignment.End
            ) {
                // 「考完 / 开考」而不是「下课 / 开课」：考试不是一节课。
                Text(
                    text = if (isCurrent) "考完" else "开考",
                    fontSize = 10.5.sp,
                    lineHeight = 14.sp,
                    color = TextSecondary,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.height(1.dp))
                Text(
                    text = formatMinute(if (isCurrent) endMinute else startMinute),
                    fontSize = 21.sp,
                    lineHeight = 26.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun DashboardSectionHeader(
    title: String,
    titleSize: TextUnit,
    meta: String? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val customWallpaper = LocalWallpaperIsCustom.current
        Row(
            modifier = if (customWallpaper) {
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.White.copy(alpha = 0.62f))
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            } else {
                Modifier
            },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                fontSize = titleSize,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                // 两行：18sp 的组头文案（「下节课还有 7 小时 52 分 开始」）
                // 在系统字号 2.0 时一行放不下，单行会截成「下节课还有 7 小时 52 分开…」。
                // 基准字号下它本来就只占一行，这个上限不改变基准观感。
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (!meta.isNullOrBlank()) {
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = meta,
                    fontSize = 12.sp,
                    color = TextSecondary,
                    maxLines = 1
                )
            }
        }
    }
}

/**
 * 总览页「今天没课」空态卡第二行。
 *
 * 这一行只在**今天一个条目都没有**时出现（没课、也没考试），
 * 所以它不必提考试 —— 有考试时那张卡根本不出现。
 */
internal fun todayEmptyStateSummary(pendingCount: Int): String =
    if (pendingCount > 0) "还有 $pendingCount 条待办" else "今天没有待办"

/**
 * 总览「今天没课」空态卡的第一行文案。
 *
 * ## 为什么按**原因**分档，而不是一律「今天停课」
 *
 * 「放假停课」和「周末」是两件事，混着说就是**说假话**：
 * 校历上没记今天停课，只是今天恰好是周六，写「今天停课」是在宣称一件
 * 没发生的事。反过来，放假停课的日子写成「今天没课」又太轻 —— 那天本来是有课的。
 *
 * 四档：
 *
 * | 原因 | 文案 | 为什么是这句 |
 * |------|------|--------------|
 * | [DayOffReason.NO_CLASS] | 今天停课 | 校历明确记的停课日（放假） |
 * | [DayOffReason.VACATED] | 今天的课调到别的日子了 | 课没消失，是被搬走了；说「停课」会让人以为白放了一天 |
 * | [DayOffReason.WEEKEND] | 今天周末 | 周末默认没课，但不是停课 |
 * | 其余 | 今天没课 | 普通工作日没排课 / 学期外 |
 *
 * 抽成纯函数是为了能直接单测：文案档位是这次改动的核心语义，
 * 不该只能靠截图去看。
 */
internal fun todayEmptyStateLabel(reason: DayOffReason?): String = when (reason) {
    DayOffReason.NO_CLASS -> "今天停课"
    DayOffReason.VACATED -> "今天的课调到别的日子了"
    DayOffReason.WEEKEND -> "今天周末"
    else -> "今天没课"
}

