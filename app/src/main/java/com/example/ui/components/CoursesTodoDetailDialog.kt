package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.CourseEntity
import com.example.data.source.zju.CoursesTodoDetail
import com.example.data.source.zju.CoursesTodoItem
import com.example.ui.theme.LimeGreenOnLight
import com.example.ui.theme.LimeGreenTileFaint
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.cardTint
import com.example.ui.theme.withNoteLineHeight

/**
 * 学在浙大待办的详情弹窗。
 *
 * 点开总览页主卡背面的那张作业小卡就到这里：作业说明、附件清单，
 * 以及「把这条加进 MiuList 待办」的入口。
 *
 * ## 为什么弹窗里先看、再加
 *
 * 用户点开一张小卡，多半是同一个动作的前半截：这条作业到底要交什么？
 * 说明和附件回答的是这个问题，而「加进待办」是决定要不要把它变成一条自己的事。
 * 两件事放在同一层里，用户看完就能决定走哪条路，不用先关掉弹窗再去找加待办的入口。
 *
 * ## 这一层长什么样：**毛玻璃上一列字，几颗小胶囊**
 *
 * ```
 * ┌ 面板（毛玻璃）───────────────────────────────┐
 * │ 第3章习题              ← 固定：这是什么        │
 * │ ● 高等数学                                    │
 * │ 截止 10月25日 23:59              [2天后]      │ ← 固定：什么时候
 * │ ─────────────────────────── ← 主题色浅线：固定/滚动分界
 * │ (作业说明)          ← 淡彩胶囊：分隔内容        │
 * │ 做完第 1-6 题，拍照上传。                        │
 * │ (完成条件)                                    │
 * │ 拍照上传到课程网站                               │
 * │ (附件（2）)                                    │
 * │ 习题.pdf                                        │
 * │ ─────────────────────────── ← 主题色浅线：固定/滚动分界
 * │                       关闭  [添加到待办]          │
 * └───────────────────────────────────────────────┘
 * ```
 *
 * ### 正文没有框
 *
 * 正文直接坐在玻璃上。给一整段文字套一块半透明板子，板子自己就成了
 * 这层里最大的一块「非文字」，眼睛会先看见板子、再去找板子里的字 ——
 * 而这层里真正要读的就是那些字。框留給**小胶囊**去当分隔符。
 *
 * ### 滚动区有两条边界
 *
 * 标题/截止与按钮行固定，正文会滚：两条**主题色浅发丝线**把「会滚的那块」
 * 上下框出来，正文滚多远边界都不动。线取主题色但压到低不透明度 ——
 * 它是结构边界，和课程色胶囊（哪门课）、实心绿按钮（动作）各说各的事。
 *
 * ### 分隔靠胶囊，不靠空白
 *
 * 段与小节之间的分界由「作业说明 / 完成条件 / 附件」这几颗**小胶囊**去做：
 * 胶囊底色是这门课的**淡彩**，文字保持中性，于是「这是哪门课的内容」
 * 和「这里换了一段」这两件事被同一颗胶囊一次说清，
 * 而一行里只留**一个**落点：课程身份归底色，文字只说「这是哪一段」。
 *
 * 段与段之间则靠 [com.example.ui.theme.ParagraphGapEmFactor]（0.7 个字）
 * 的空白 —— 屏幕很矮，空白是最贵的资源，不能拿它当分隔符反复用。
 *
 * ### 段落怎么切：只认「作者敲的回车」
 *
 * 说明是从 HTML 转来的：上游每个块级元素（段落、列表项、显式换行）
 * 都被换成**一个换行**，那个换行就是作者敲出来的位置。
 * 正文按回车换行拆段（见 [NoteText]）：只认空行的话，
 * 老师用段落标签排出来的说明会整片挤在一起，一段和下一段分不出来。
 *
 * ### 左边只有**一条**边缘
 *
 * 上一版出现过五条不同的左边缘（标题 56dp / 截止 42dp / 小节标题 0 /
 * 正文 14dp / 附件名 35dp），眼睛每往下走一行都要重新找一次起点。
 * 现在：标题、课程名、截止、胶囊、正文、附件名全部从面板同一条边起。
 *
 * ## 两种颜色，各司其职
 *
 * | 颜色 | 用在哪 | 说什么 |
 * |---|---|---|
 * | **课程色**（实色 + 淡彩） | 课程名那颗小圆点、几颗小胶囊 | 「这条属于哪门课」「这里换了一段」 |
 * | **品牌绿** | 「添加到待办」按钮、已添加状态 | 全 App 唯一饱和的颜色，只给「你要按的那个动作」 |
 *
 * **课程色不打在大段文字的背景上**：一整块正文染上课色，看着像一张彩纸，
 * 而不是一段话；课色落在**小胶囊**上就刚好 —— 有颜色，但不抢读字的注意力。
 */
@Composable
fun CoursesTodoDetailDialog(
    item: CoursesTodoItem,
    detail: CoursesTodoDetail?,
    isLoading: Boolean,
    error: String?,
    /** 这条作业是不是已经加进 MiuList 了（本次运行内）。 */
    isAdded: Boolean,
    /**
     * 本学期课程里匹配到的本地课程。
     *
     * **只用它的颜色，不显示它的名字**（见上面那段说明）。
     * null = 没匹配上（学在浙大没写课程名，或本学期实在没有这门课）：
     * 这时没有课程色，胶囊退回中性灰。
     */
    matchedCourse: CourseEntity?,
    onAddToMiuList: () -> Unit,
    onDismiss: () -> Unit
) {
    // 课程色：本地课程自己的颜色。没匹配上就是 null（不是品牌绿 ——
    // 绿在这个 App 里表示「状态/动作」，拿它当课程色会把两件事混成一件）。
    val courseColor = remember(matchedCourse) {
        matchedCourse?.colorHex?.takeIf { it.isNotBlank() }?.let { parseColor(it, Color.Transparent) }
    }

    GlassOverlayHost(
        onDismissRequest = onDismiss,
        horizontalPadding = 16.dp,
        // testTag 挂在面板上：挂到遮罩层的话 bounds 会是全屏，
        // 语义节点指向的是「点一下就关弹窗」的那一层，测试根本点不到内容。
        panelModifier = Modifier.testTag("courses_todo_detail_dialog"),
        title = {
            // 固定区：这是什么（标题 + 课程名）、什么时候（截止）。
            Column(modifier = Modifier.fillMaxWidth()) {
                CoursesTodoHeader(item = item, courseColor = courseColor)
                Spacer(modifier = Modifier.height(12.dp))
                ScrollBoundaryDivider()
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = panelScrollMaxHeight(420.dp))
                    .verticalScroll(rememberScrollState())
                    // testTag：正文是**滚动区**，固定区（标题、课程名、截止）在它外面。
                    // 测试靠这个 tag 确认截止行没有被塞进滚动区。
                    .testTag("courses_todo_detail_body")
            ) {
                // ---- 加载 ----
                if (isLoading) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "正在获取作业详情…",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }

                // ---- 出错 ----
                if (!error.isNullOrBlank()) {
                    Row(
                        verticalAlignment = Alignment.Top,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall.withNoteLineHeight(),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }

                // ---- 正文 ----
                if (detail != null) {
                    SectionCapsule(text = "作业说明", courseColor = courseColor)
                    Spacer(modifier = Modifier.height(CapsuleGap))
                    NoteText(
                        text = if (detail.hasDescription) detail.description else "老师没有写说明",
                        color = if (detail.hasDescription) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            TextSecondary
                        }
                    )

                    if (detail.hasCompletionCriterion) {
                        Spacer(modifier = Modifier.height(SectionGap))
                        SectionCapsule(text = "完成条件", courseColor = courseColor)
                        Spacer(modifier = Modifier.height(CapsuleGap))
                        NoteText(text = detail.completionCriterion)
                    }

                    if (detail.hasAttachments) {
                        Spacer(modifier = Modifier.height(SectionGap))
                        SectionCapsule(
                            text = "附件（${detail.attachments.size}）",
                            courseColor = courseColor
                        )
                        Spacer(modifier = Modifier.height(CapsuleGap))
                        detail.attachments.forEachIndexed { index, attachment ->
                            if (index > 0) Spacer(modifier = Modifier.height(AttachmentGap))
                            Text(
                                text = attachment.name,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        },
        belowContent = { ScrollBoundaryDivider() },
        confirmButton = {
            if (isAdded) {
                // 已经加过了：按钮变成状态，不再是动作。
                // 绿色在这里表示「已经生效」——绿色在这个 App 里只表示状态。
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(LimeGreenTileFaint)
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Check,
                        contentDescription = null,
                        tint = LimeGreenOnLight,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "已添加到待办",
                        style = MaterialTheme.typography.bodyMedium,
                        color = LimeGreenOnLight
                    )
                }
            } else {
                Button(
                    onClick = onAddToMiuList,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    ),
                    contentPadding = PaddingValues(horizontal = 22.dp, vertical = 10.dp),
                    modifier = Modifier.testTag("add_courses_todo_button")
                ) {
                    Text(
                        text = "添加到待办",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    )
}

/**
 * 固定区：作业名 + 课程名 + 截止。
 *
 * **这三样都不跟着正文滚**：它们是「看一眼就知道」的信息，
 * 正文滚多长都不该把它们带出屏幕。
 *
 * **左上角没有图标**：早先那里挂了一枚「作业」图标块，它不指向任何动作，
 * 也不带任何信息，只是把标题往右挤了 56.dp —— 删掉之后标题从面板左边起，
 * 整层透气得多。
 *
 * 课程名行前面那颗**课程色小圆点**是这层里唯一的课程身份标记：
 * 它不占地方，也不用写字，但「这条是蓝课/绿课的作业」一眼就知道。
 */
@Composable
private fun CoursesTodoHeader(item: CoursesTodoItem, courseColor: Color?) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = item.title,
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp
            ),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (item.courseName.isNotBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (courseColor != null) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(courseColor)
                    )
                    Spacer(modifier = Modifier.width(7.dp))
                }
                Text(
                    text = item.courseName,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        DueRow(item = item)
    }
}

/**
 * 截止时间：**一行元信息，不成框**。
 *
 * 早先它是自己那块半透明片子里的一行 —— 一个框里只有一行字，
 * 那块框提供的信息量抵不上它带来的边界（框 = 眼睛要重新定位一次）。
 * 现在它和标题、课程名共用同一条左边缘，就是一行字。
 *
 * 倒计时用中性灰（紧急才转红），不拿课程色去染：染了它就成了第二处课程标记，
 * 和课程名那颗圆点抢同一个意思。
 */
@Composable
private fun DueRow(item: CoursesTodoItem) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = "截止",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = item.dueFormatted,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (item.daysRemainingText.isNotBlank()) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = item.daysRemainingText,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = if (item.isUrgent) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (item.isUrgent) {
                            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                        }
                    )
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            )
        }
    }
}

/**
 * 小节胶囊：**这一层里唯一的面**。
 *
 * 「作业说明 / 完成条件 / 附件」做成一颗小小的胶囊，既是路标，也是分隔符 ——
 * 有了它，段与段之间就不必再靠大片空白去分界（手机屏上的空白很贵）。
 *
 * 形状是**小圆角矩形**（8dp），不是两头全圆的药丸 —— 12sp 的小字压在药丸上
 * 看着像一颗糖，不像一个标签；同一层里「2天后」那颗徽标也是这一档。
 *
 * 底色是这门课的**淡彩**（[cardTint] 解出来的那支），
 * **文字保持中性**（[TextSecondary]，和小胶囊之前那版小节标题同一个颜色）：
 * 一行里只留**一个**落点 —— 课程身份由底色说，文字只负责说「这是哪一段」。
 * 文字也染课色的话，一颗胶囊上就叠了两个意思（哪门课 + 哪一段），
 * 而标题下面那颗课程色圆点已经占住了「哪门课」这一层意思。
 *
 * 没匹配到本地课程时没有课色，就退回中性灰 ——
 * 不该凭空给一个颜色，那是在替用户断言它属于哪门课。
 *
 * **胶囊不描边**：玻璃/淡彩面上加一道边只会变成一圈亮边。
 */
@Composable
private fun SectionCapsule(text: String, courseColor: Color?) {
    val background = if (courseColor != null) {
        // 和课程卡面同一支淡彩（[cardTint] 解出来的实色），按同一强度铺：
        // 胶囊就是课程卡面缩小一号的样子，不是另一套配色。
        cardTint(courseColor, CapsuleTintAlpha).copy(alpha = CapsuleTintAlpha)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium.copy(
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp
        ),
        color = TextSecondary,
        modifier = Modifier
            // 圆角**不夸张**：早先用 percent=50 做成了药丸形（两头全圆），
            // 12sp 的小字压在药丸上看着像一颗糖，不像一个标签。
            // 这里退回小圆角矩形，和这一层里另一颗「2天后」徽标同一档（8dp）。
            .clip(RoundedCornerShape(CapsuleCornerRadius))
            .background(background)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    )
}

/** 胶囊底色（淡彩）的不透明度：浅到只剩色温，深到能看出是这门课。 */
private const val CapsuleTintAlpha = 0.30f

/** 胶囊圆角：小圆角矩形，不是药丸。 */
private val CapsuleCornerRadius = 8.dp

/** 胶囊与它下面正文之间的距离。 */
private val CapsuleGap = 8.dp

/**
 * 一节内容与下一颗胶囊之间的距离。
 *
 * 要**比段内的段间距更大**（段内是 0.7 个字，14sp 正文约 10dp）：
 * 胶囊虽然已经是很强的分隔符，但空白这一层不能反过来 ——
 * 「换小节」看着比「换段」还紧，层次就倒了。
 * 24dp ≈ 一个半行高（14sp × 1.25 = 17.5dp），够分出小节，又不把两节拉成两页。
 */
private val SectionGap = 24.dp

/** 附件两行之间的距离。 */
private val AttachmentGap = 6.dp

/** 固定区与滚动区的分界：主题色浅发丝线，标示「这块会滚」，不抢胶囊（课程身份）与按钮（动作）的语义。 */
@Composable
private fun ScrollBoundaryDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
        thickness = 0.5.dp
    )
}
