package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.TaskEntity
import com.example.data.model.CustomTaskType
import com.example.data.model.TaskStar
import com.example.data.model.TaskTypes
import com.example.ui.theme.accentOnCourse

/**
 * 单条待办的详情弹层。
 *
 * 卡片上只留「名称 + 一行附加信息」，编辑、删除和备注正文都挪到这里：
 * 一行要塞 ddl / 课程 / 类别 / 优先级四段文字，再挤图标和长备注就必然要截断，
 * 而被截掉的往往正是最该看见的那一段。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskDetailSheet(
    task: TaskEntity,
    /** 用户新建的待办类型，解析类别徽标用。 */
    customTypes: List<CustomTaskType>,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val courseColor = parseColor(task.courseColorHex)
    val hasDue = TaskEntity.hasDue(task)
    val isOverdue = !task.isCompleted && hasDue && task.dueDateTimestamp < System.currentTimeMillis()
    val dueRelativeText = remember(task.dueDateTimestamp) {
        getRelativeDueDescription(task.dueDateTimestamp)
    }
    val typeOption = remember(task.taskType, customTypes) {
        TaskTypes.resolve(task.taskType, customTypes)
    }
    // 删除是破坏性的，先切成二次确认，别让一次误点就没了
    var confirmDelete by remember(task.id) { mutableStateOf(false) }

    GlassBottomSheetHost(onDismissRequest = onDismiss) {
        SheetContent {
            Text(
                text = task.title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                fontSize = 17.sp,
                color = MaterialTheme.colorScheme.onSurface,
                textDecoration = if (task.isCompleted) TextDecoration.LineThrough else TextDecoration.None
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = if (task.isCompleted) "已完成" else "未完成",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(14.dp))
            HairLine()
            Spacer(modifier = Modifier.height(12.dp))

            // 截止：相对时间当主文案（最该一眼看到的），绝对时间当小字备查。
            // 「不设截止」是它自己的状态，不是逾期，也不该配一个绝对时间小字。
            DetailRow(label = "截止") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = dueRelativeText,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        color = when {
                            isOverdue -> MaterialTheme.colorScheme.error
                            hasDue -> MaterialTheme.colorScheme.onSurface
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    if (task.dueDateString.isNotBlank()) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = task.dueDateString,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // 关联课程：没关联就整行拿掉，别显示一个空的「课程」标签
            if (!TaskEntity.isUnlinked(task)) {
                DetailRow(label = "课程") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(courseColor)
                        )
                        Spacer(modifier = Modifier.width(7.dp))
                        Text(
                            text = task.courseName,
                            style = MaterialTheme.typography.bodyMedium,
                            // 课程色当前景必须压暗：卡面/弹层底都是近白，粉彩原色读不出来
                            color = accentOnCourse(courseColor)
                        )
                        if (task.courseCategory.isNotBlank()) {
                            Spacer(modifier = Modifier.width(7.dp))
                            Text(
                                text = task.courseCategory,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // 待办分类 / 星标沿用既有徽标，列表和弹层的识别方式一致。
            // 两者都是「有才显示」：分类留空、没标星时整行拿掉——
            // 一个只有「分类：」却没有值的行是在问用户一个问题，
            // 而答案（没归类 / 没标星）没有他需要知道的新信息。
            //
            // 这一行的标签叫「分类」而不是「类别」：上面「课程」那一行里
            // 已经挨着课程名放着**课程自己的类别**（公共基础之类），
            // 再把这一行标成「类别」，同一个词在一张弹层里指两样东西。
            if (typeOption != null) {
                DetailRow(label = "分类") {
                    TaskTypeBadge(taskTypeStr = task.taskType, customTypes = customTypes)
                }
            }
            if (TaskStar.isMarked(task.priority)) {
                DetailRow(label = "星标") { TaskStarMark(size = 15.dp) }
            }

            if (task.description.isNotBlank()) {
                Spacer(modifier = Modifier.height(16.dp))
                HairLine()
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = "备注",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                // 备注是整张弹层里唯一的长正文，也是点开弹层的主要理由之一，
                // 字号给到 16sp——比上面所有字段都大，读起来才像「正文」而不是「字段值」。
                Text(
                    text = task.description,
                    style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 25.sp),
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Spacer(modifier = Modifier.height(22.dp))

            if (confirmDelete) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(onClick = { confirmDelete = false }, modifier = Modifier.weight(1f)) {
                        Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    OutlinedButton(
                        onClick = onDelete,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.DeleteOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("确认删除", color = MaterialTheme.colorScheme.error)
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onEdit,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Edit,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("编辑", color = MaterialTheme.colorScheme.onSurface)
                    }
                    OutlinedButton(
                        onClick = { confirmDelete = true },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.DeleteOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

/** 一条「标签 : 内容」的详情行。标签固定宽度，内容自己占剩下的。 */
@Composable
private fun DetailRow(label: String, content: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(46.dp)
        )
        content()
    }
}

@Composable
private fun HairLine() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant)
    )
}