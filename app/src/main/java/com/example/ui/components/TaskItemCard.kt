package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.TaskEntity
import com.example.data.model.CustomTaskType
import com.example.data.model.TaskStar
import com.example.data.model.TaskTypeOption
import com.example.data.model.TaskTypes
import com.example.ui.theme.CourseTintAlpha
import com.example.ui.theme.CourseTintVivid
import com.example.ui.theme.NestedFaceAlpha
import com.example.ui.theme.TaskCheckIdleFill
import com.example.ui.theme.accentOnCourse
import com.example.ui.theme.glassSurface
import com.example.ui.theme.readableOn
import java.util.Calendar

@Composable
fun TaskItemCard(
    task: TaskEntity,
    /** 用户新建的待办类型。类型徽标要靠它解析，必传——漏传就编译不过。 */
    customTypes: List<CustomTaskType>,
    onToggleComplete: () -> Unit,
    /** 点整张卡打开待办详情弹层，编辑/删除都在弹层里做。 */
    onOpenDetail: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * 是否被放在另一张「已染成同一门课粉彩」的卡片里面（目前只有课表今日页的课程卡）。
     *
     * 这种位置不能自己再染一遍同一个颜色：同一个色相叠两次只会更深，
     * 实测外层 #D6E9FE 会叠成 #C2DFFD，层次正好反了——内层比外层重。
     * 改成近白「挖入」，靠「浅在深上」撑出层次，和课程详情弹层里的处理一致。
     */
    nestedInCourseCard: Boolean = false
) {
    val courseColor = parseColor(task.courseColorHex)

    // 没设截止的待办不参与逾期判断：哨兵是 Long.MIN_VALUE，
    // 直接拿去和 now 比会得到 true，一条「不设截止」的待办会被染成逾期红。
    val hasDue = TaskEntity.hasDue(task)
    val isOverdue = !task.isCompleted && hasDue && task.dueDateTimestamp < System.currentTimeMillis()
    val dueRelativeText = remember(task.dueDateTimestamp) {
        if (hasDue) getRelativeDueDescription(task.dueDateTimestamp) else NO_DUE_TEXT
    }
    val typeOption = remember(task.taskType, customTypes) {
        TaskTypes.resolve(task.taskType, customTypes)
    }
    val isStarred = remember(task.priority) { TaskStar.isMarked(task.priority) }
    // 类别的墨色不用在这里再取一遍：它走 TaskTypeBadge（淡彩底 + 墨色字，
    // 验算见 EnumsAndModels）；星标是纯图标，色值固定在 StarInk。

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("task_card_${task.id}")
            .clickable { onOpenDetail() },
        shape = RoundedCornerShape(18.dp),
        // 卡片本体画在里面的 Column 上（云母面），Card 这层只做点击和裁剪
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .glassSurface(
                    shape = RoundedCornerShape(18.dp),
                    // 整张卡染这门课的粉彩：颜色本身就是「这是哪门课」的标识，
                    // 不必再靠一条色条去认。被嵌在课程卡里时反过来挖近白，理由见 nestedInCourseCard
                    tint = if (nestedInCourseCard) Color.White else courseColor,
                    tintAlpha = if (nestedInCourseCard) NestedFaceAlpha else CourseTintAlpha,
                    // 内层是近白挖入，本来就没颜色可提；「不关联课程」的中性灰同样不能提饱和——
                    // vivid() 会把 gray 里那点微弱的蓝偏（#C9D0D8 的 b > r）放大成饱和蓝，
                    // 实测未关联卡面被推成 #D8EAFF（hue 212°），和高数卡几乎一模一样
                    vividness = if (nestedInCourseCard || TaskEntity.isUnlinked(task)) {
                        0f
                    } else {
                        CourseTintVivid
                    },
                    // 已完成的待办压扁一点（面从 38% 降到 30%），别跟没做完的抢注意力
                    faceAlphaScale = if (task.isCompleted) 0.78f else 1f
                )
                .padding(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Interactive Checkbox with animated fill
                //
                // 未勾选 = 纯白圆，不描边。走过的弯路别再走回去：
                // ① Transparent + outline(#DDE6E2)：压在染色卡面上约 1.05:1，等于没有圈；
                // ② 白填充 + 中灰边(#98A29E)：轮廓够清楚了，但用户明确表示「灰色圆边框丑」，已删。
                // 现在圈只有填充，白色 alpha 也提到 1.0（半透白会透出卡面的染色色相就不再是白的）。
                // 详见 Color.kt 的 TaskCheckIdleFill。
                val checkboxBg by animateColorAsState(
                    targetValue = if (task.isCompleted) MaterialTheme.colorScheme.primary else TaskCheckIdleFill,
                    animationSpec = tween(durationMillis = 200),
                    label = "checkboxBg"
                )
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(checkboxBg)
                        .clickable { onToggleComplete() }
                        .testTag("task_checkbox_${task.id}"),
                    contentAlignment = Alignment.Center
                ) {
                    if (task.isCompleted) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "已完成",
                            tint = Color(0xFF1E2D0E),
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = task.title,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = if (task.isCompleted) FontWeight.Normal else FontWeight.Bold,
                            fontSize = 14.5.sp,
                            lineHeight = 19.sp,
                            textDecoration = if (task.isCompleted) TextDecoration.LineThrough else TextDecoration.None
                        ),
                        color = if (task.isCompleted) {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(5.dp))

                    // 名称以外的信息全部压在这一行：ddl · 关联课程 · 待办类别。
                    // 课程名吃掉弹性宽度先被截断，另外两段都是短的，宁可牺牲课程名也要保住它们。
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MetaText(
                            text = dueRelativeText,
                            // ddl 是这一行最该被扫到的字段，不能用 onSurfaceVariant：
                            // 实测 #78827B 压在橙课卡面 #FFF0E1 上只有 3.56:1、紫课面 3.20:1。
                            // 逾期红同理——#EF4444 直接当前景也只有 3.4:1，压暗后仍有 8:1 且认得出是警示色。
                            // 没设截止的走中性色：它不是一条需要被扫出来的紧急信息，
                            // 染成和「逾期」一样重的字反而会把它抬到不该在的位置。
                            color = if (isOverdue) {
                                readableOn(MaterialTheme.colorScheme.error)
                            } else if (hasDue) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            fontWeight = if (isOverdue) FontWeight.Bold else FontWeight.Medium
                        )
                        // 不关联课程时整段拿掉，别在 meta 行里留一个
                        // 只写着「未关联」的占位——那种信息对扫描没有任何帮助
                        if (!TaskEntity.isUnlinked(task)) {
                            MetaSeparator()
                            MetaText(
                                text = task.courseName,
                                color = accentOnCourse(courseColor),
                                modifier = Modifier.weight(1f, fill = false)
                            )
                        }
                        // 类别在这里是 chip，不是裸文字：和弹层里的徽标同一套
                        // 「淡彩底 + 墨色字」，扫列表时它自成一块，
                        // 不用逐字去读「· 课前预习」这一串。
                        //
                        // 分隔点是**跟着段走**的，不是写死一串：类型可以留空、
                        // 课程可以没关联，两种情况都会让「· 」孤零零地留在行尾或行首。
                        if (typeOption != null) {
                            MetaSeparator()
                            TaskTypeBadge(
                                taskTypeStr = task.taskType,
                                customTypes = customTypes
                            )
                        }
                    }
                }

                // 星标单独占卡片右侧、垂直居中，不挤在 meta 行里。
                //
                // 两个原因：① meta 行是文字和 chip 的地方，星星塞进去要和
                // 「· 」分隔点抢位置，而它比旁边任何一段都重要；
                // ② 位置固定在右下角，整屏扫下来眼睛走的是一条直线，
                // 「哪些是我挑出来的」不用逐行读就能看出来。
                //
                // 没标星就不画，连间距一起省掉——满屏的空星占位
                // 只会把真正标星的那几条淹没在噪点里。
                //
                // 尺寸跟左侧的勾选圈（24.dp）**对齐**：两个都是「这条待办的状态」，
                // 一样大小才像一对。并排两个不等大的图标（24 的圈 + 19 的星）
                // 看着就像星是个附属品，而它其实和完成状态一样重要。
                if (isStarred) {
                    Spacer(modifier = Modifier.width(10.dp))
                    TaskStarMark(size = 24.dp)
                }
            }
            // 备注整块搬走了，只留在详情弹层里（TaskDetailSheet）。
            // 备注是待办里最长最不受约束的一段文字，放在卡片上必然把它撑成两行三行，
            // 而列表要的是一眼扫过去的密度——所以卡片只管「是什么、什么时候、属于哪门课」，
            // 要读正文就点开。
        }
    }
}

/** 附加信息行里的一段文字。固定单行，靠 [MetaSeparator] 分隔。 */
@Composable
private fun MetaText(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight = FontWeight.Normal
) {
    Text(
        text = text,
        color = color,
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = fontWeight),
        fontSize = 11.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
    )
}

@Composable
private fun MetaSeparator() {
    Text(
        text = " · ",
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
        style = MaterialTheme.typography.labelSmall,
        fontSize = 11.sp,
        maxLines = 1
    )
}

/** 没设截止的待办，ddl 段显示的字样。清单页按截止分组时也用它当组内标签。 */
const val NO_DUE_TEXT = "不设截止"

fun getRelativeDueDescription(dueTimestamp: Long): String {
    // 哨兵也挡一道：这个函数被弹层、生成预览等多处直接调用，
    // 漏判一次就是「不设截止」显示成逾期了 56 年。
    if (dueTimestamp == TaskEntity.NO_DUE_TIMESTAMP) return NO_DUE_TEXT

    val now = System.currentTimeMillis()
    val diff = dueTimestamp - now

    val nowCal = Calendar.getInstance()
    val dueCal = Calendar.getInstance().apply { timeInMillis = dueTimestamp }

    val isSameDay = nowCal.get(Calendar.YEAR) == dueCal.get(Calendar.YEAR) &&
            nowCal.get(Calendar.DAY_OF_YEAR) == dueCal.get(Calendar.DAY_OF_YEAR)

    nowCal.add(Calendar.DAY_OF_YEAR, 1)
    val isTomorrow = nowCal.get(Calendar.YEAR) == dueCal.get(Calendar.YEAR) &&
            nowCal.get(Calendar.DAY_OF_YEAR) == dueCal.get(Calendar.DAY_OF_YEAR)

    val hourStr = String.format("%02d:%02d", dueCal.get(Calendar.HOUR_OF_DAY), dueCal.get(Calendar.MINUTE))

    return when {
        diff < 0 -> "已超时"
        isSameDay -> "今天 $hourStr"
        isTomorrow -> "明天 $hourStr"
        diff < 7 * 86400000L -> "${(diff / 86400000L) + 1}天后"
        else -> "${dueCal.get(Calendar.MONTH) + 1}月${dueCal.get(Calendar.DAY_OF_MONTH)}日"
    }
}
