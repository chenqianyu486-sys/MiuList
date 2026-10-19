package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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

    /** 主卡上的日期读数。 */
    val HeroDate = TextStyle(
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.SemiBold
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

    val CourseTitle = TextStyle(
        fontSize = 15.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.1).sp
    )

    val CourseMeta = TextStyle(
        fontSize = 11.5.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Normal
    )

    val TaskTitle = TextStyle(
        fontSize = 14.5.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.1).sp
    )

    val TaskMeta = TextStyle(
        fontSize = 11.5.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Normal
    )
}

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
