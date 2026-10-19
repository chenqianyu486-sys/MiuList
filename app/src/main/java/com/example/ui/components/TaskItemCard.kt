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
import java.util.Calendar

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
                .padding(horizontal = 13.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 勾选圈回到原来的纯白圆：浅色壁纸上它是「卡面挖出来的一个洞」，
            // 和课程粉彩是同一套语言。只有深色壁纸把卡面推到近白时，
            // 才补一圈淡墨描边，否则白圆会消失。
            val checkboxBg by animateColorAsState(
                targetValue = if (task.isCompleted) MaterialTheme.colorScheme.primary else Color.White,
                animationSpec = tween(durationMillis = 200),
                label = "checkboxBg"
            )
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .clickable { onToggleComplete() }
                    .testTag("task_checkbox_${task.id}"),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(checkboxBg),
                    contentAlignment = Alignment.Center
                ) {
                    if (task.isCompleted) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "已完成",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(14.dp)
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
                    textDecoration = if (task.isCompleted) TextDecoration.LineThrough else TextDecoration.None,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(4.dp))

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

            // 星标回到原来的白描边：浅色壁纸的粉彩卡上它和勾选圈是同一对控件。
            // 深色壁纸把卡面推到近白时，才改用墨色空星，避免白色消失。
            TaskStarToggle(
                isStarred = isStarred,
                onToggle = onToggleStar,
                size = 24.dp,
                unstarredColor = Color.White,
                modifier = Modifier.testTag("task_star_${task.id}")
            )
        }
    }
}

/** meta 行里的小圆点分隔符：比一个「·」字符更轻，也不跟正文抢字宽。 */
@Composable
private fun MetaDot() {
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
