package com.example.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 正文行高：**1.25 倍字号**。
 *
 * M3 默认的 bodyMedium 是 14sp / 20sp（1.43 倍）；中文正文读着偏松，
 * 而正文块又常常落在一块半透明的淡彩上，松行距会把那一块撑得又高又散。
 * 1.25 是这个 App 里「备注类文字块」统一的档：够读、够紧。
 *
 * 这里管的是**被动换行**（文字被容器宽度折断）：相邻两行之间隔多远。
 * 作者敲的**回车换行**是另起一段，走的是 [ParagraphGapEmFactor]。
 */
const val NoteLineHeightFactor = 1.25f

/**
 * 段间距：**字号的 0.7 倍**（0.6 ~ 0.8 em 这一段里取中间值）。
 *
 * 这里的「段间距」指两段之间的**额外空白**，不含两段各自的行高：
 * 一段的最后一行到下一段第一行，实际空出来的是「行高 + 这一档」。
 *
 * 两种换行对应两种间距，这是全 App 统一的定义：
 *
 * - **回车换行**（作者敲的换行）= 另起一段 → 用这一档；
 * - **被动换行**（被宽度折断）= 还是同一段 → 用行高（见 [withNoteLineHeight]）。
 *
 * 为什么按**字号**（em）算，而不是按行高算：段间距要跟着字号的比例走
 * （12sp 的备注和 14sp 的正文，段间距差一个字号的比例，而不是差一个绝对值），
 * 而且这一档本来就比行高小得多 —— 0.7em 的额外空白压在 1.25em 的行高上，
 * 段与段之间刚好分开，又不会把一段话撑散。
 *
 * 另外，小节之间的分界交给**小胶囊**去做
 * （见学在浙大待办弹窗的 [com.example.ui.components.CoursesTodoDetailDialog]），
 * 这一档只负责让**段内**的呼吸匀。
 */
const val ParagraphGapEmFactor = 0.7f

/**
 * 把一段文字的行距改成 **1.25 倍字号**。
 *
 * 「备注类文字块一律这样处理」：待办备注、课程备注、作业说明、完成条件 ——
 * 都是用户自己写的段落，行距跟着字号走（不是写死多少 sp），
 * 于是系统字号放大时它照样是 1.25 倍。
 *
 * 这个行高管的是**被动换行**：文字被容器宽度折断时，相邻两行之间隔多远。
 * 作者自己敲的**回车换行**是另起一段，走的是 [ParagraphGapEmFactor]。
 */
fun TextStyle.withNoteLineHeight(): TextStyle =
    copy(lineHeight = fontSize * NoteLineHeightFactor)

/**
 * 一段正文里，段落之间要空出多少高度（dp）。
 *
 * 字号写在 [TextStyle.fontSize] 里（单位 sp），它在屏幕上的**视觉**大小
 * 是「字号 × 有效字号缩放」（系统字号 2.0 时，14.sp 的字就是 28dp 视觉大小）。
 * 段间距按这个视觉大小算，段落之间的空档才真的等于「0.7 个字」——
 * 直接拿 sp 当 dp 用，在大字号那档会短得几乎看不出来。
 */
@Composable
fun paragraphGapFor(style: TextStyle): Dp {
    val fontSize = style.fontSize
    if (fontSize == TextUnit.Unspecified || fontSize.value <= 0f) return 10.dp
    return paragraphGapDp(fontSize.value, LocalEffectiveFontScale)
}

/**
 * 段间距的纯算法：字号的视觉大小 × [ParagraphGapEmFactor]。
 *
 * 抽出来是为了能直接单测（它不依赖任何 Composition）。
 *
 * @param fontSizeSp 字号（sp）。
 * @param fontScale 有效字号缩放，见 [LocalEffectiveFontScale]。
 */
fun paragraphGapDp(fontSizeSp: Float, fontScale: Float): Dp =
    (fontSizeSp * fontScale).dp * ParagraphGapEmFactor

// Set of Material typography styles to start with
val Typography =
  Typography(
    bodyLarge =
      TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp,
      )
    /* Other default text styles to override
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    )
    */
  )
