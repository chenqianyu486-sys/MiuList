package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ImportExport
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Rule
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.vector.ImageVector
import com.example.data.calendar.EpochDays
import com.example.data.local.CourseWithSlots
import com.example.ui.components.IslandBottomInset
import com.example.ui.components.parseColor
import com.example.ui.theme.PageHInset
import com.example.ui.theme.CourseTintAlpha
import com.example.ui.theme.CourseTintVivid
import com.example.ui.theme.LimeGreenOnLight
import com.example.ui.theme.TeaColor
import com.example.ui.theme.TeaColorBorder
import com.example.ui.theme.TeaOnColor
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.isGreenishColor
import com.example.ui.theme.glassSurface
import com.example.ui.theme.readableOn
import com.example.ui.viewmodel.MainUiState

@Composable
fun SettingsScreen(
    state: MainUiState,
    onOpenCategoryRulesDialog: () -> Unit,
    onOpenTaskRulesDialog: () -> Unit,
    onOpenAddCourseDialog: () -> Unit,
    onEditCourse: (CourseWithSlots) -> Unit,
    onDeleteCourse: (Long, String) -> Unit,
    onOpenTransferDialog: () -> Unit,
    onOpenAcademicCalendarDialog: () -> Unit,
    onResetDemoData: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = PageHInset),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        // 底部给浮岛导航留出收尾空间，内容本身仍会滚到岛底下被毛玻璃糊住
        contentPadding = PaddingValues(bottom = IslandBottomInset)
    ) {
        item {
            Spacer(modifier = Modifier.height(6.dp))
        }

        item {
            val semester = state.semester
            val configured = semester != null && semester.firstMondayEpochDay > 0L
            SettingsActionCard(
                icon = Icons.Outlined.Tune,
                title = "课程联动规则",
                subtitle = "按课程类别设默认值，课程级可覆盖",
                actionLabel = "设置",
                actionTestTag = "open_category_rules_button",
                onAction = onOpenCategoryRulesDialog
            )
            Spacer(modifier = Modifier.height(16.dp))
            SettingsActionCard(
                icon = Icons.Outlined.Rule,
                title = "待办生成规则",
                subtitle = "按课程设定节奏与截止",
                actionLabel = "设置",
                actionTestTag = "open_task_rules_button",
                onAction = onOpenTaskRulesDialog
            )
            Spacer(modifier = Modifier.height(16.dp))
            SettingsActionCard(
                icon = Icons.Outlined.ImportExport,
                title = "课表导入 / 导出",
                subtitle = "JSON 文件 · 课程与作息一起走",
                actionLabel = "管理",
                actionTestTag = "open_transfer_button",
                onAction = onOpenTransferDialog
            )
            Spacer(modifier = Modifier.height(16.dp))
            SettingsActionCard(
                icon = Icons.Outlined.CalendarMonth,
                title = "本学期校历",
                // 未配置必须说清楚后果：这时开课周次（单双周）整体不生效，
                // 而不是「默认每周」这种听起来没问题的说法
                subtitle = if (configured) {
                    "${semester.name.ifBlank { "本学期" }} · 第 1 周 = ${EpochDays.formatCn(semester.firstMondayEpochDay)}"
                } else {
                    "未设置 · 开课周次暂不生效"
                },
                actionLabel = if (configured) "修改" else "设置",
                actionTestTag = "open_academic_calendar_button",
                onAction = onOpenAcademicCalendarDialog
            )
        }

        // Course Management Section Header
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(TeaColor)
                            .border(0.5.dp, TeaColorBorder, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.School,
                            contentDescription = null,
                            tint = TeaOnColor,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "本学期课程 · ${state.coursesWithSlots.size}",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Button(
                    onClick = onOpenAddCourseDialog,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier.testTag("add_new_course_button")
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onPrimary)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("添加课程", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
                }
            }
        }

        // Courses List with multi-slot schedule display
        items(state.coursesWithSlots, key = { it.course.id }) { cWithSlots ->
            val course = cWithSlots.course
            val courseColor = parseColor(course.colorHex)

            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .glassSurface(shape = RoundedCornerShape(18.dp), tint = courseColor, tintAlpha = CourseTintAlpha, vividness = CourseTintVivid)
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp, 48.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(courseColor)
                    )

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = course.name,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.5.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            val isGreen = isGreenishColor(courseColor)
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (isGreen) TeaColor else MaterialTheme.colorScheme.surfaceVariant)
                                    .border(0.5.dp, if (isGreen) TeaColorBorder else courseColor.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
                                    .padding(horizontal = 7.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = course.category,
                                    // 胶囊底是近白（surfaceVariant），粉彩原色当前景读不出来，
// 必须跟课表/课程弹窗用同一个 readableOn() 压暗档
                                    color = if (isGreen) TeaOnColor else readableOn(courseColor),
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    fontSize = 10.5.sp
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(3.dp))

                        Text(
                            text = "时段: ${cWithSlots.getScheduleSummary()}",
                            style = MaterialTheme.typography.labelSmall,
                            // 卡面已染成这门课的粉彩，抹茶绿压不住，改用同色系压暗档
                            color = readableOn(courseColor),
                            fontWeight = FontWeight.SemiBold
                        )

                        Text(
                            text = "${course.classroom} · ${course.teacher}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Text(
                            text = "预习 ${if (course.autoPreStudy) "开" else "关"} · 作业 ${if (course.autoPostHomework) "开" else "关"} · 实验 ${if (course.autoLabReport) "开" else "关"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 10.sp
                        )
                    }

                    IconButton(onClick = { onEditCourse(cWithSlots) }) {
                        Icon(Icons.Outlined.Edit, contentDescription = "编辑课程", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    IconButton(onClick = { onDeleteCourse(course.id, course.name) }) {
                        Icon(Icons.Outlined.DeleteOutline, contentDescription = "删除课程", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        // Demo Data Reset Action
        item {
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .glassSurface(shape = RoundedCornerShape(18.dp))
                        .padding(16.dp)
                ) {
                    Text(
                        text = "演示数据",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "恢复官方示例课表与待办，含多次排课、长实验课与晚课。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = onResetDemoData,
                        shape = RoundedCornerShape(10.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        modifier = Modifier.testTag("reset_demo_data_button")
                    ) {
                        // 主题绿只能当填充色，当图标前景压在近白卡面上只有 1.4:1。
                        // 图标跟着下面这行文字走。
                        Icon(Icons.Outlined.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurface)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("恢复示例数据", color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(30.dp))
        }
    }
}

/**
 * 设置页里「图标 + 标题 + 副标题 + 一个按钮」的一行。
 *
 * 抽出来是因为这类入口已有四个（联动规则 / 导入导出 / 校历 / 以后的更多），
 * 原先每处都摊开写一遍 50 行卡片结构，加一个入口就得复制一次——
 * 复制出来的那份迟早和别人长得不一样。
 */
@Composable
private fun SettingsActionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    actionLabel: String,
    actionTestTag: String,
    onAction: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        // 整张卡可点，落点和右边那个按钮一致，不存在两个不同的行为
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onAction)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .glassSurface(shape = RoundedCornerShape(18.dp))
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        // 用 TextSecondary 而不是全局的 onSurfaceVariant：后者是
                        // TextMuted #78827B，压在这张卡面上实测只有 3.74:1，
                        // 11sp 小字过不了 AA。TextSecondary #4A524D 实测约 7.6:1。
                        // 只改这一处，不动全局 token（那会牵动所有页面）。
                        color = TextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Button(
                onClick = onAction,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                modifier = Modifier.testTag(actionTestTag)
            ) {
                Text(actionLabel, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
            }
        }
    }
}
