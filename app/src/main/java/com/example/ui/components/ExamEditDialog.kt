package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.ExamEntry
import com.example.data.model.ExamKind
import com.example.ui.theme.TextSecondary

/**
 * 编辑一场考试：考场、座位、考试时刻。
 *
 * ## 只让改这三样
 *
 * 课程名、期中/期末是**学校给的事实**，本地改了没有意义 ——
 * 下次同步就被覆盖。真正需要人补的是**学校还没公布、但学生已经从通知里知道**的东西：
 * 考场、座位号，以及偶尔调整过的考试时刻。
 *
 * ## 改完留到下一次同步为止
 *
 * 教务网同步是整表覆盖（见 `ExamRepository.replaceAll`），
 * 所以本地编辑是**临时补丁**，不是对学校数据的修改。
 * 这句话必须写进弹层里：否则用户会以为改了就稳了，
 * 下次同步发现被盖回去时只会觉得 App 有 bug。
 */
@Composable
fun ExamEditDialog(
    entry: ExamEntry,
    onDismiss: () -> Unit,
    onSave: (classroom: String, seat: String, startMinute: Int, endMinute: Int) -> Unit
) {
    var classroom by remember(entry.id) { mutableStateOf(entry.classroom) }
    var seat by remember(entry.id) { mutableStateOf(entry.seat) }
    var startText by remember(entry.id) {
        mutableStateOf(if (entry.startMinute > 0) formatMinuteOfDay(entry.startMinute) else "")
    }
    var endText by remember(entry.id) {
        mutableStateOf(if (entry.endMinute > 0) formatMinuteOfDay(entry.endMinute) else "")
    }

    val startMinute = parseClockText(startText)
    val endMinute = parseClockText(endText)
    val timeValid = startMinute != null && endMinute != null && endMinute >= startMinute

    GlassOverlayHost(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = entry.courseName,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f, fill = false)
                )
                ExamKindBadge(
                    kind = entry.kind,
                    examColor = parseColor(entry.courseColorHex)
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = classroom,
                    onValueChange = { classroom = it },
                    label = { Text("考场") },
                    placeholder = { Text("如：紫金港东2-201") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("exam_classroom_input"),
                    singleLine = true
                )
                OutlinedTextField(
                    value = seat,
                    onValueChange = { seat = it },
                    label = { Text("座位号") },
                    placeholder = { Text("如：12-3-15") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("exam_seat_input"),
                    singleLine = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = startText,
                        onValueChange = { startText = it },
                        label = { Text("开始") },
                        isError = startText.isNotBlank() && startMinute == null,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("exam_start_input"),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = endText,
                        onValueChange = { endText = it },
                        label = { Text("结束") },
                        isError = endText.isNotBlank() && endMinute == null,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("exam_end_input"),
                        singleLine = true
                    )
                }
                if (!timeValid) {
                    Text(
                        text = "时刻要写成 HH:mm（结束不能早于开始）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Text(
                    text = "教务网同步后，这些改动会被服务端的数据覆盖",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        },
        confirmButton = {
            GlassActionButton(
                text = "保存",
                enabled = timeValid,
                onClick = {
                    onSave(
                        classroom,
                        seat,
                        startMinute ?: entry.startMinute,
                        endMinute ?: entry.endMinute
                    )
                },
                testTag = "exam_save_button"
            )
        },
        dismissButton = {
            GlassActionButton(
                text = "取消",
                onClick = onDismiss,
                testTag = "exam_cancel_button"
            )
        }
    )
}

/** 弹层按钮：与项目里其它玻璃弹层一致。 */
@Composable
private fun GlassActionButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    testTag: String? = null
) {
    androidx.compose.material3.TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = if (testTag != null) Modifier.testTag(testTag) else Modifier
    ) {
        Text(text, fontSize = 13.sp)
    }
}
