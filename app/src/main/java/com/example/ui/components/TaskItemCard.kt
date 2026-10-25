package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import com.example.data.local.TaskEntity
import com.example.data.model.CustomTaskType
import com.example.data.model.TaskStar
import com.example.data.model.TaskTypes
import com.example.ui.theme.LocalWallpaperIsCustom
import com.example.ui.theme.MiuSurface
import com.example.ui.theme.MiuType
import com.example.ui.theme.NestedFaceAlpha
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.accentOnCourse
import com.example.ui.theme.cardTint
import com.example.data.calendar.AppZone
import com.example.ui.theme.glassSurface
import com.example.ui.theme.readableOn
import com.example.ui.theme.scaledWithText
import java.util.Calendar

/**
 * 圆圈（勾选圈）的填色不透明度。
 *
 * 填色是**这门课的粉彩本身**，不额外往卡面上压任何颜色：
 * 纯白实心会把卡面整个盖住（比正文还跳的一坨白），
 * 「换一支更浅的实色」或「压一层白纱」也都是新糊上去的东西——
 * 玻璃是透的，往浅里走只能靠**少遮一点底下的东西**。
 *
 * **这一档必须低于卡片自己的染色强度**（`CourseTintAlpha` = 0.30）：
 * 圆圈的填色要比卡面淡，才读得出「卡面上的一块淡斑」而不是「卡面本身」。
 * 0.08：卡面 #BBCFE4（蓝课）→ 圆圈 #BBCEE6，只差一两个色阶，
 * 肉眼几乎只剩那一圈白描边——「只保留白色描线」照到了字面。
 */
private const val CircleFillAlpha = 0.08f

/**
 * 星标的填色不透明度。
 *
 * 星是**笔画**不是面积：24dp 的一个星形轮廓，填色淡到 0.08 就整颗不见了，
 * 只剩白描边——那还是原来那颗「纯白空星」，而它当初被记成问题
 * （见 design-notes「现状诊断」第 6 条：星标像装饰而不是控件）。
 * 所以这一档比圆圈浓：0.30 的粉彩星体 + 白色描边，
 * 星形才认得出是「这门课的星」，同时白仍然只落在描边上。
 */
private const val StarFillAlpha = 0.30f

/**
 * 白色描线的不透明度。
 *
 * 实白是这两个控件上**最响**的东西：圆圈成了一圈实白线，
 * 星标更糟——它那颗垫底白星是实心的，整颗星直接读成一坨白形状，
 * 也就是「现状诊断」第 6 条记的「纯白空心，像装饰而不是控件」。
 * 0.70 让白退成一道柔和的边：还是白、还是描边，但不再压过卡面。
 *
 * 星标这一处尤其要降：星体是**压在**那颗白星上的，
 * 白从实白退到 0.70，星体才真的透出这门课的粉彩
 * （实白垫底时星体压在纯白上，再怎么降它也只是一颗更白的星）。
 */
private const val ControlRimAlpha = 0.70f

/** 白色描线的粗细。24dp 的控件上，1dp 才勾得出轮廓。 */
private val ControlRimWidth = 1.dp

@Composable
fun TaskItemCard(
    task: TaskEntity,
    /** 用户新建的待办类型。类型徽标要靠它解析，必传——漏传就编译不过。 */
    customTypes: List<CustomTaskType>,
    onToggleComplete: () -> Unit,
    /**
     * 点右侧那颗星切换星标。
     *
     * 必传而不是给默认值：它和 [onToggleComplete] 是同一级的行内控件，
     * 漏传一个的卡就是一颗点不动的星——那比编译不过更难发现。
     */
    onToggleStar: () -> Unit,
    /** 点整张卡打开待办详情弹层，编辑/删除都在弹层里做。 */
    onOpenDetail: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * 是否被放在另一张「已染成同一门课粉彩」的卡片里面（目前只有课表今日页的课程卡）。
     *
     * 这种位置不能自己再染一遍颜色；改成近白面，靠「浅在深上」撑出层次。
     */
    nestedInCourseCard: Boolean = false
) {
    val courseColor = parseColor(task.courseColorHex)

    // 圆圈和星标的填色：**这门课的粉彩本身**，半透明地压在卡面上。
    // 不额外压白、也不换别的颜色——卡面本身就是这支粉彩染出来的。
    // 两档不一样：圆圈是面积，要比卡面淡；星是笔画，要看得见。
    val circleFill = courseColor.copy(alpha = CircleFillAlpha)
    val starFill = courseColor.copy(alpha = StarFillAlpha)

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

    // 卡片标题里的课程名前缀和 meta 行的课程名重复。只做显示层裁剪：
    // 数据不动，搜索/导出仍按原题名；裁掉之后卡片上只剩「第5周预习」这类真正的任务名。
    val displayTitle = remember(task.title, task.courseName) { taskDisplayTitle(task) }

    // 不做深色模式：预设壁纸用原版 0.55 玻璃；用户照片用更厚的玻璃，
    // 让照片的细节退成颜色，不和待办标题抢注意力。
    val customWallpaper = LocalWallpaperIsCustom.current
    val contentBase = if (customWallpaper) 0.72f else 0.55f
    val tintAlpha = if (customWallpaper) 0.26f else 0.30f

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("task_card_${task.id}")
            .clickable { onOpenDetail() },
        shape = MiuSurface.CardShape,
        // 卡片本体画在里面的 Row 上（云母面），Card 这层只做点击和裁剪
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .glassSurface(
                    shape = MiuSurface.CardShape,
                    // 课程色回到卡面，但只做「哪门课」的暗示；正文对比度
                    // 由底色染色的等重计算和自适应白衬底一起兜住。
                    tint = if (nestedInCourseCard) Color.White else cardTint(courseColor, tintAlpha),
                    tintAlpha = if (nestedInCourseCard) NestedFaceAlpha else tintAlpha,
                    baseCoatAlpha = if (nestedInCourseCard) 0f else contentBase,
                    blurRadius = 0.dp,
                    // 已完成的待办压扁一点，别跟没做完的抢注意力
                    faceAlphaScale = if (task.isCompleted) 0.78f else 1f
                )
                .padding(
                    horizontal = 13.dp,
                    // 垂直内边距随字号放大：标题放大后，卡里的呼吸也要跟着大。
                    vertical = 11.dp.scaledWithText()
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 勾选圈的填色是这门课的粉彩，**比卡面淡**（不透明度 0.15 < 卡片的 0.30），
            // 白只留一圈描边——肉眼几乎只剩那一圈白线，就是「只保留白色描线」。
            // 完成态不变：主题绿实心 + 对勾，那是「做完了」的状态色，不是主题色。
            val checkboxBg by animateColorAsState(
                targetValue = if (task.isCompleted) MaterialTheme.colorScheme.primary else circleFill,
                animationSpec = tween(durationMillis = 200),
                label = "checkboxBg"
            )
            Box(
                modifier = Modifier
                    // 勾选圈是**固定盒子**：字放大 1.5 倍、圈不变，圈就小得像个句号。
                    // 所以圈跟字一起放大（见 [scaledWithText]）。
                    .size(32.dp.scaledWithText())
                    .clip(CircleShape)
                    .clickable { onToggleComplete() }
                    .testTag("task_checkbox_${task.id}"),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(24.dp.scaledWithText())
                        .clip(CircleShape)
                        .background(checkboxBg)
                        .border(
                            ControlRimWidth,
                            Color.White.copy(alpha = ControlRimAlpha),
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (task.isCompleted) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "已完成",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(14.dp.scaledWithText())
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(7.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = displayTitle,
                    style = MiuType.TaskTitle,
                    color = if (task.isCompleted) {
                        TextSecondary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    textDecoration = if (task.isCompleted) TextDecoration.LineThrough else TextDecoration.None
                    // 名称**不设行数上限**：长名字折行显示，而不是被省略号截掉。
                    //
                    // 这一行是这条待办唯一能一眼读到名字的地方 —— 项目原则是
                    // 「读不全就不算标题」。原来卡在两行：「【作业】数据结构 第6周作业」
                    // 在窄屏上折两行还差一点，末尾就被省略号吃掉了，读出来的是
                    // 一个不完整的名字（还容易被当成另一个待办的名字）。
                    //
                    // 名字是用户自己写的，长度不可控，所以上限交给内容本身：
                    // 折几行就长几行，卡跟着长。
                )

                Spacer(modifier = Modifier.height(4.dp.scaledWithText()))

                // 名称以外的信息全部压在这一行：ddl · 课程名 · 待办类别。
                // 课程名沿用它自己的深色墨，不再额外加一枚色点：
                // 卡面已经染了这门课的颜色，再点一次是同一句话说三遍。
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = dueRelativeText,
                        // 逾期红要用压暗的那一档：#EF4444 直接当前景只有 3.4:1。
                        color = if (isOverdue) {
                            readableOn(MaterialTheme.colorScheme.error)
                        } else if (hasDue) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            TextSecondary
                        },
                        style = MiuType.TaskMeta,
                        fontWeight = if (isOverdue) FontWeight.SemiBold else FontWeight.Normal
                    )
                    if (!TaskEntity.isUnlinked(task)) {
                        MetaDot()
                        Text(
                            text = task.courseName,
                            color = accentOnCourse(courseColor),
                            style = MiuType.TaskMeta,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                    if (typeOption != null) {
                        MetaDot()
                        TaskTypeBadge(
                            taskTypeStr = task.taskType,
                            customTypes = customTypes
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(6.dp))

            // 星标的星体填半透明的课程粉彩（0.38），白只留在描边上：
            // 星是笔画，淡到 0.15 就整颗看不见了，那还是原来那颗「纯白空星」。
            // 「描边空星 vs 实心星」的形状差仍然认得出，
            // 而「标了星」那一态照旧是实心琥珀色——那是状态色，不是主题色。
            TaskStarToggle(
                isStarred = isStarred,
                onToggle = onToggleStar,
                size = 24.dp.scaledWithText(),
                unstarredColor = Color.White.copy(alpha = ControlRimAlpha),
                unstarredFill = starFill,
                modifier = Modifier.testTag("task_star_${task.id}")
            )
        }
    }
}

/**
 * meta 行里的小圆点分隔符：比一个「·」字符更轻，也不跟正文抢字宽。
 *
 * 不是 private —— 课程详情弹层那条待办行的 meta 行（截止 + 类别徽标）
 * 也要用同一颗点。两处都是「一行里并排放两样信息」，分隔符得是同一个，
 * 否则同一条待办在清单页和弹层里读起来是两种排版。
 */
@Composable
internal fun MetaDot() {
    Spacer(modifier = Modifier.width(5.dp))
    Box(
        modifier = Modifier
            .size(2.5.dp)
            .clip(CircleShape)
            .background(TextSecondary.copy(alpha = 0.38f))
    )
    Spacer(modifier = Modifier.width(5.dp))
}

/**
 * 只改显示：课程联动生成的标题通常是「课程名 + 空格 + 任务名」，
 * 而课程名已经在 meta 行里。裁掉前缀之后，卡片上只剩真正的任务名，
 * 标题不必为了重复的课程名折成两行。
 *
 * 数据层不动：搜索、导出、详情页仍使用完整标题。
 */
private fun taskDisplayTitle(task: TaskEntity): String {
    val courseName = task.courseName.trim()
    if (courseName.isEmpty() || TaskEntity.isUnlinked(task)) return task.title
    val prefix = "$courseName "
    if (task.title.startsWith(prefix)) {
        val rest = task.title.removePrefix(prefix).trim()
        if (rest.isNotEmpty()) return rest
    }
    return task.title
}

/** 没设截止的待办，ddl 段显示的字样。清单页按截止分组时也用它当组内标签。 */
const val NO_DUE_TEXT = "不设截止"

fun getRelativeDueDescription(dueTimestamp: Long): String {
    // 哨兵也挡一道：这个函数被弹层、生成预览等多处直接调用，
    // 漏判一次就是「不设截止」显示成逾期了 56 年。
    if (dueTimestamp == TaskEntity.NO_DUE_TIMESTAMP) return NO_DUE_TEXT

    val now = System.currentTimeMillis()
    val diff = dueTimestamp - now

    // 「今天 / 明天」是**北京的**今天与明天。跟着设备时区走，
    // GMT 设备在 北京 00:30 会把昨天当成今天，写出「今天 23:59」——而那条其实已经过去一天了。
    val nowCal = AppZone.calendar()
    val dueCal = AppZone.calendar(dueTimestamp)

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
