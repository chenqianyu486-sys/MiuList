package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * 总览页这一版设计定的字阶与表面常量。
 *
 * 不直接改 MaterialTheme.typography：那套是全局默认值，一改会把还没轮到的页面
 * 一起卷进来。这里先放在设计层，等清单 / 课表也换完，再决定哪些升级为全局。
 *
 * 字阶只有四档：23（主角）、15（课程名）、14（待办标题）、12–13（正文与标签）。
 * 层级靠字号、字重和留白三件事一起给，不靠把所有字都加粗。
 */
object MiuType {
    /** 主卡上那句「还有 14 分下课」。全页唯一的主角。 */
    val HeroStatus = TextStyle(
        fontSize = 23.sp,
        lineHeight = 30.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.3).sp
    )

    /** 主卡上的辅助事实：课程名、教室、第几节。 */
    val HeroDetail = TextStyle(
        fontSize = 12.5.sp,
        lineHeight = 17.sp,
        fontWeight = FontWeight.Medium
    )

    /**
     * 主卡上的日期**读数**（13sp）。
     *
     * ⚠️ 当前主卡的日期走的是 [HeroDateLarge]，不是这个。
     * V3 定稿要的是 22sp 大号日期（见下），这个 13sp 是当初设计层
     * 给「日期是读数」那版留的档位。
     *
     * **别因为它小就拿它去改主卡**：V1/V2 把日期改小被用户否过，
     * 「日期天气栏要回原样」。窄屏上放不下时的正确做法是
     * [heroDateFontSize] 逐档降，而不是换成这个固定的小字号。
     */
    val HeroDate = TextStyle(
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.SemiBold
    )

    /**
     * 主卡上的**大号日期**（V3 定稿：日期天气栏回原版，22sp）。
     *
     * 这是「回原版」的结果，不是随手定的：V1/V2 曾把日期改小，被否，
     * 用户要的是「日期天气栏回原样」。所以基准宽屏上它就是 22sp。
     *
     * 窄屏上 22sp 放不下（见 [heroDateFontSize]），那不是推翻定稿，
     * 而是定稿在更窄的屏上**放不下**——取舍仍然是「保留 22sp 的观感」优先。
     */
    val HeroDateLarge = TextStyle(
        fontSize = 22.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = (-0.3).sp
    )

    /** 分区标题。比正文重一档，但不再用 18sp 去和主卡抢。 */
    val SectionTitle = TextStyle(
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.3.sp
    )

    val SectionMeta = TextStyle(
        fontSize = 11.5.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Normal
    )

    val SectionAction = TextStyle(
        fontSize = 11.5.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.SemiBold
    )

    /**
     * **卡片标题**：待办卡、课程卡、考试卡三种卡的标题是同一族东西 ——
     * 「这张卡叫什么」。
     *
     * 之前三种卡各写各的字号（14.5 / 16 / 17），而它们在总览页里上下相邻：
     * 同屏里标题的大小跟着**卡的种类**跳，扫读时读不出「这一行是标题」。
     * 现在收成两档，按**标题在卡里占多少地方**分：
     *
     * - [CardTitleCompact] 紧凑档：标题与别的东西挤在同一行
     *   （待办卡、总览「今日课程」列表卡 —— 那里课名与教室胶囊同行）；
     * - [CardTitleRegular] 常规档：标题独占一行
     *   （考试卡、课表页课程卡）。
     *
     * 加高卡（总览「下一节 / 下一场」）另有一档，见 DashboardScreen 的
     * `CourseCardTitleFontSize`。
     *
     * ## 为什么两档都比改版前大 1.5sp
     *
     * 紧凑档 14.5 → 16，常规档 16 → 17.5 —— 也就是「加大一号」。
     * 卡上的字本来就挤（课名、教室、节次、教师、徽标同屏），
     * 标题不抬头，整张卡就只剩一块色板；标题抬起来之后，
     * 与下面 11.5sp 那行 meta 的差距也从 3sp 拉到 4.5sp，
     * 「哪一行是标题」不用靠颜色和加粗去说。
     */
    val CardTitleCompact = TextStyle(
        fontSize = 16.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.1).sp
    )

    val CardTitleRegular = TextStyle(
        fontSize = 17.5.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.1).sp
    )

    val CourseMeta = TextStyle(
        fontSize = 11.5.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Normal
    )

    val TaskMeta = TextStyle(
        fontSize = 11.5.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Normal
    )
}

/**
 * 主卡日期的样式：字号按可用宽选，行高跟着字号走。
 *
 * ## 为什么行高要跟着字号走，而不是固定 28.sp
 *
 * [MiuType.HeroDateLarge] 的行高 28.sp 是配 22.sp 定的。宽屏上日期放到 26.sp，
 * 而中文回退字体的 ascent + descent 大约是 1.16em —— 26.sp 的字实际要 30.sp 高，
 * **行盒只有 28.sp 时上下会被裁掉**（字顶和字底各差 1.sp）。
 *
 * 所以行高按字号的比例算（[HeroDateLineHeightRatio] = 28 / 22 ≈ 1.27），
 * 22.sp 时正好还是 28.sp —— **基准屏的观感一分不变**。
 *
 * ## 为什么行高比例是 1.27 而不是 1.16
 *
 * 1.16 是字形自己的高度；1.27 是**行盒**——行盒要比字形高一点，
 * 才放得下同一行里不同字号的混排（数字是拉丁字形，基线与 CJK 不同）。
 * 22.sp × 1.27 = 27.9 ≈ 28.sp，和原来那个数对得上。
 */
const val HeroDateLineHeightRatio = 1.27f

/**
 * 主卡日期的样式。
 *
 * @param fontSize 字号（sp）。由 [heroDateFontSize] 按左列可用宽选出来。
 */
fun heroDateStyle(fontSize: TextUnit): TextStyle = MiuType.HeroDateLarge.copy(
    fontSize = fontSize,
    // 行高按字号比例算，但要**取整到 sp**：
    // 22 × 1.27 = 27.94，不取整的话基准屏的行盒是 27.94sp 而不是 28.sp ——
    // 差 0.06sp，肉眼看不见，但「基准屏一分不变」这句话就不成立了。
    // 取整后 22.sp → 28.sp（和改版前一模一样），26.sp → 33.sp。
    lineHeight = (fontSize.value * HeroDateLineHeightRatio).roundToInt().sp
)

/**
 * 形状常量。不做深色模式，也不按壁纸亮度自动切换；
 * 透光粉彩固定走原版的 0.55 白衬底 + 30% 课程色。
 */
object MiuSurface {

    /** 普通内容卡。 */
    val CardShape = RoundedCornerShape(18.dp)

    /** 主卡 / Hero，比列表卡圆一档。 */
    val HeroShape = RoundedCornerShape(22.dp)
}

/**
 * 当前壁纸是不是用户自己的照片。
 *
 * 预设壁纸的渐变是设计过的，原版那层 0.55 玻璃就够；
 * 用户照片的细节/对比度不可控，需要更厚的玻璃和局部保护。
 * 这不是深色模式：两套都是浅色，只是「预设」和「自定义照片」两种承托。
 */
val LocalWallpaperIsCustom = staticCompositionLocalOf { false }
