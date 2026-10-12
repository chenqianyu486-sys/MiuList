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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.TaskTypePalettes
import com.example.ui.theme.ChipSurfaceNeutral
import com.example.ui.theme.GlassEdgeBottom

/**
 * 新建一个待办类型。
 *
 * 只有两个决定：**叫什么**和**什么颜色**。刻意不放第三个字段——
 * 类型一旦可自定义，就一定会有人开始往里塞备注、排序、图标之类的东西，
 * 而那些字段对「这条待办属于哪一类」这个问题一个都不产生新信息。
 *
 * 颜色从 [TaskTypePalettes] 里点选而不是自己调：那 8 对（淡彩面 + 同色相墨色）
 * 都是逐对比过 WCAG 的，一个自由取色器等于把「当前景读不出来」这件事
 * 交给用户去踩。
 */
@Composable
fun NewTaskTypeDialog(
    onDismiss: () -> Unit,
    onCreate: (label: String, paletteIndex: Int) -> Unit
) {
    var label by remember { mutableStateOf("") }
    var paletteIndex by remember { mutableIntStateOf(0) }

    val trimmed = label.trim()

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.padding(horizontal = 16.dp),
        title = {
            Text(
                text = "新建待办类型",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 用 BasicTextField 而不是 OutlinedTextField：
                // 这里只有一行短输入，OutlinedTextField 按自己的 MinHeight
                // 顶起来的上下留白比文字本身还高（TaskEditDialog 里有同样的记录）。
                val shape = RoundedCornerShape(12.dp)
                BasicTextField(
                    value = label,
                    onValueChange = { label = it },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .background(ChipSurfaceNeutral)
                        .border(0.5.dp, GlassEdgeBottom, shape)
                        .padding(horizontal = 12.dp, vertical = 11.dp)
                        .testTag("new_task_type_label_input"),
                    decorationBox = { inner ->
                        Box {
                            if (label.isEmpty()) {
                                Text(
                                    text = "如：读书笔记、小组作业",
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            inner()
                        }
                    }
                )

                Column {
                    Text(
                        text = "颜色",
                        style = MaterialTheme.typography.labelMedium,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TaskTypePalettes.forEachIndexed { index, palette ->
                            val selected = index == paletteIndex
                            // 选中态是「双层环」，不是换填充色：淡彩本身在近白底上
                            // 几乎看不出差别（34% 透明度只有一两档色差），
                            // 真要靠填充色区分就等于没有区分。
                            Box(
                                modifier = Modifier
                                    .size(30.dp)
                                    .clip(RoundedCornerShape(percent = 50))
                                    .border(
                                        width = if (selected) 2.dp else 0.dp,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        shape = RoundedCornerShape(percent = 50)
                                    )
                                    .padding(if (selected) 3.dp else 0.dp)
                                    .clip(RoundedCornerShape(percent = 50))
                                    .background(parseColor(palette.colorHex))
                                    .clickable { paletteIndex = index }
                                    .testTag("new_task_type_palette_$index"),
                                contentAlignment = Alignment.Center
                            ) {
                                // 选中时在色块正中再画一个白点，暗色也能看清选了哪个
                                if (selected) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(RoundedCornerShape(percent = 50))
                                            .background(Color.White.copy(alpha = 0.9f))
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onCreate(trimmed, paletteIndex) },
                enabled = trimmed.isNotEmpty(),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                modifier = Modifier.testTag("create_task_type_button")
            ) {
                Text(
                    text = "创建",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, shape = RoundedCornerShape(10.dp)) {
                Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    )
}

/**
 * 删除自定义类型的二次确认。
 *
 * 删除是不可逆的，而它挂在一个「长按 chip」这种不显眼的入口上，
 * 所以必须先确认一次；顺带把后果说清楚——用了这个类型的待办会退回「未分类」，
 * 而不是跟着消失。
 */
@Composable
fun DeleteTaskTypeConfirmDialog(
    typeLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = {
            Text(
                text = "删除类型「$typeLabel」？",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Text(
                // 说清楚后果，别只问「确定吗」：用户担心的「待办会不会没了」
                // 答案是不没了，但答案得由界面主动给。
                text = "用了这个类型的待办会退回「未分类」，待办本身不会被删除。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, shape = RoundedCornerShape(10.dp)) {
                Text("删除", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, shape = RoundedCornerShape(10.dp)) {
                Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    )
}