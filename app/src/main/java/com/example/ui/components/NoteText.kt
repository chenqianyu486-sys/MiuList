package com.example.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import com.example.ui.theme.paragraphGapFor
import com.example.ui.theme.withNoteLineHeight

/**
 * 一段「备注类文字」：行高 1.25 倍字号，**段间距 0.7 个字**（见 [com.example.ui.theme.ParagraphGapEmFactor]）。
 *
 * ## 两种换行，两种间距
 *
 * 正文里只有两种断行，它们说的是两件不同的事：
 *
 * - **回车换行**（作者自己敲的那个换行）：这是**另起一段**，
 *   段与段之间空一个 [com.example.ui.theme.paragraphGapFor]。
 * - **被动换行**（文字被容器宽度折断）：这还是同一段，
 *   两行之间只隔一个行高（`TextStyle.lineHeight`）。
 *
 * ## 为什么段落要拆开排
 *
 * Compose 的 `Text` 不认段落间距：几个段落写在一个 `Text` 里，
 * 中间那个换行只值**一个**行高，读起来两段还是连着的。
 * 所以这里把正文按**回车换行**拆成若干段，每段一个 `Text`，
 * 段与段之间插那一截空白。
 *
 * ## 规则跟来源无关
 *
 * 无论正文来自哪里（学在浙大的作业说明、用户自己写的课程备注），
 * 规则都是同一条：**作者敲出来的换行算段，屏幕折出来的算行**。
 *
 * 早先这里分过两套规则（空行才算段 / 每个换行都算段），
 * 结果同一份排版在不同作业上表现完全不同：说明里恰好有空行的
 * （老师多敲了一次回车）拿到一大截空白，而上游用段落标签排出来的
 * 则完全没有段间距，两段读起来还是连着的。
 * 分界只按「作者有没有敲回车」判断，才不会有这种一课一个样。
 */
@Composable
fun NoteText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium.withNoteLineHeight(),
    color: Color = MaterialTheme.colorScheme.onSurface
) {
    val paragraphs = remember(text) {
        splitNoteParagraphs(text)
    }
    val gap = paragraphGapFor(style)
    Column(modifier = modifier) {
        paragraphs.forEachIndexed { index, paragraph ->
            if (index > 0) Spacer(modifier = Modifier.height(gap))
            Text(
                text = paragraph,
                style = style,
                color = color
            )
        }
    }
}

/**
 * 按**回车换行**把正文拆成若干段（空段丢掉）。
 *
 * 抽成纯函数是为了能**离线**单测：分段规则是这套排版最容易悄悄走偏的一环，
 * 而它不依赖任何 Composition，没有理由非要跑起来才验证得了。
 */
internal fun splitNoteParagraphs(text: String): List<String> =
    text.split(LINE_BREAK)
        .map { it.trim() }
        .filter { it.isNotEmpty() }

/** 回车换行：含 Windows 的 \r\n 与老式 \r。 */
private val LINE_BREAK = Regex("\\r\\n|\\n|\\r")
