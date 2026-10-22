package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.TaskEntity
import com.example.data.model.CustomTaskType
import com.example.ui.theme.TeaColor
import com.example.ui.theme.TeaColorBase
import com.example.ui.theme.TeaColorBorder
import com.example.ui.theme.TeaOnColor
import com.example.ui.theme.isGreenishColor
import com.example.ui.theme.readableOn

@Composable
fun QuickGenerateDialog(
    candidateTasks: List<TaskEntity>,
    week: Int,
    /**
     * 预习覆盖到的最后一周；等于 [week] 表示预习没有跨周（今天是学期最后一周）。
     * 弹层要靠它说清「为什么列表里有下一周的课」，见下面的文案。
     */
    preStudyLastWeek: Int,
    /** 类型徽标解析用。生成出来的待办只会带内置类型，但仍传真实的自定义类型表，
     *  免得这个弹层哪天开始显示手工待办时静默少一个标签。 */
    customTypes: List<CustomTaskType>,
    onDismiss: () -> Unit,
    onConfirm: (List<TaskEntity>) -> Unit
) {
    // Map of candidate task to isChecked
    val checkedMap = remember(candidateTasks) {
        mutableStateMapOf<TaskEntity, Boolean>().apply {
            candidateTasks.forEach { task ->
                this[task] = true
            }
        }
    }

    GlassOverlayHost(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(MaterialTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "生成本周待办",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // 生成范围：**一行说完，不带尾巴**。
                //
                // 两类待办的视野不同（作业/实验报告看本周，预习看到下周结束），
                // 跨周时必须分开说，否则用户看到列表里有下一周的课会以为生成错了。
                //
                // 原来这行尾巴上还挂着「可勾选：」——列表里一排复选框就在那儿，
                // 按钮上还写着「添加 N」，再说一遍是第三遍。
                val rangeText = if (preStudyLastWeek > week) {
                    "作业与实验报告取第 $week 周，预习从今天到第 $preStudyLastWeek 周末。"
                } else {
                    "按第 $week 周课表与规则生成。"
                }
                Text(
                    text = rangeText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // 一个都没生成时，**另起一行**说清楚。
                // 原来它是接在范围那句后面的（「…生成。已全部生成。」），
                // 两句话挤成一句，后半句像前半句的补充，读起来像半句话没说完。
                if (candidateTasks.isEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "没有新的待办可生成。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (candidateTasks.isNotEmpty()) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(panelScrollMaxHeight(260.dp)),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(candidateTasks) { task ->
                            val isChecked = checkedMap[task] ?: true
                            val courseColor = parseColor(task.courseColorHex)
                            val isGreen = isGreenishColor(courseColor)

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (isChecked) TeaColorBase.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surface)
                                    .border(
                                        1.dp,
                                        if (isChecked) TeaOnColor.copy(alpha = 0.45f) else MaterialTheme.colorScheme.outline,
                                        RoundedCornerShape(12.dp)
                                    )
                                    .clickable { checkedMap[task] = !isChecked }
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = isChecked,
                                    onCheckedChange = { checkedMap[task] = it },
                                    colors = androidx.compose.material3.CheckboxDefaults.colors(
                                        checkedColor = TeaOnColor
                                    )
                                )

                                Spacer(modifier = Modifier.width(6.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .background(if (isGreen) TeaColor else MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(6.dp))
                                                .border(0.5.dp, if (isGreen) TeaColorBorder else courseColor.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
                                                .padding(horizontal = 7.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                text = task.courseName,
                                                // 同 TaskItemCard：课程名徽标字压暗，否则粉彩紫/黄在近白底上基本看不见
                                                color = if (isGreen) TeaOnColor else readableOn(courseColor),
                                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                                fontSize = 11.sp
                                            )
                                        }

                                        TaskTypeBadge(taskTypeStr = task.taskType, customTypes = customTypes)
                                    }

                                    Spacer(modifier = Modifier.height(4.dp))

                                    Text(
                                        text = task.title,
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                        fontSize = 13.5.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )

                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        // 周次 / 周几已经是一条待办的正式属性了（也是去重键），
                                        // 预览时顺手报出来，免得生成完再去别处确认它落在哪天。
                                        Text(
                                            text = "第 ${task.week} 周 · ${
                                                com.example.data.model.getDayName(task.dayOfWeek)
                                            }",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontSize = 10.5.sp
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "截止 ${task.dueDateString}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            // 勾选数要在**组合期**算：复选框每动一次，这个 Button 会重组合，
            // 按钮上的数字跟着走（原来数字也是这么活的，只是外层多绕了一层）。
            val selectedCount = candidateTasks.count { checkedMap[it] == true }
            Button(
                onClick = {
                    onConfirm(candidateTasks.filter { checkedMap[it] == true })
                },
                // 全取消时**禁用**：按钮上写着「添加 0 条」，点了什么也不会发生，
                // 那还不如不让点。
                enabled = selectedCount > 0,
                shape = RoundedCornerShape(10.dp),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                modifier = Modifier.testTag("confirm_auto_generate_button")
            ) {
                Text(
                    text = "添加 $selectedCount 条",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    )
}
