package com.example.ui.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.local.TaskEntity

/**
 * 勾掉一条待办前的二次确认。
 *
 * ## 为什么要问这一句
 *
 * 勾掉一条待办是**不可见地丢掉了它**：它从清单里消失，而「刚才那条是不是
 * 真的做完了」只有用户自己能回答。问一句换来手滑勾掉不再发生。
 *
 * ## 这个弹层要替用户回答什么
 *
 * 不是「确定吗」——那等于把判断原样推回给用户。要回答的是**哪一条**：
 * 屏幕上往往有好几条待办，一句「完成这条待办？」没有指名道姓，
 * 用户得先自己在心里对上号，对不上就点了「完成」，于是又完成错一条。
 *
 * 所以这里把**待办名摆出来**。
 *
 * **不再解释「按完会怎样」**：这一层曾经有一句「标成已完成后，它会从待办清单里消失」，
 * 那是把「完成」当成一个要科普的动作在说话 —— 用户点的是自己刚勾掉的那颗圆圈，
 * 后果他比谁都清楚，多这一句只让弹层变啰嗦。
 * 确认的意义是**对号**（这是哪一条），不是**讲解**。
 *
 * ## 什么时候弹
 *
 * 只在**标成已完成**时弹。取消完成不问：那是一次撤销，把待办放回清单里
 * 没有风险，而问一句只会让人多按一次。开关在设置页里
 * （「待办完成确认」），关掉之后这一层根本不出现。
 */
@Composable
fun TaskCompleteConfirmDialog(
    task: TaskEntity,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    GlassOverlayHost(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "完成这条待办？",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            // 待办名：卡片上显示的是裁掉课程名的那一份（见 [taskDisplayTitle]），
            // 弹层里要用**同一个**名字 —— 用户在卡上看到什么，这里就得是什么，
            // 否则「对不上号」的问题又回来了。
            Text(
                text = taskDisplayTitle(task),
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                modifier = Modifier.testTag("task_complete_confirm_button")
            ) {
                Text("完成", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.testTag("task_complete_cancel_button")
            ) {
                Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    )
}
