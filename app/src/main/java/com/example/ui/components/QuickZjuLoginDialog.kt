package com.example.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.TextMuted

/**
 * 绑定浙大统一身份认证学号密码的轻量弹窗。
 * 用于在总览页卡片背面未绑定时，一键输入并硬件加密保存。
 */
@Composable
fun QuickZjuLoginDialog(
    initialStudentId: String = "",
    onConfirm: (studentId: String, password: String) -> Unit,
    onDismiss: () -> Unit
) {
    var studentId by remember { mutableStateOf(initialStudentId) }
    var password by remember { mutableStateOf("") }

    GlassOverlayHost(
        onDismissRequest = onDismiss,
        // 弹层里有密码框：挂着期间整个窗口禁止截屏/录屏，
        // 免得密码明文进到截图、相册或最近任务缩略图里。
        secure = true,
        shape = RoundedCornerShape(22.dp),
        title = {
            Text(
                text = "绑定浙大账号",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "绑定统一身份认证账号后，将自动同步「学在浙大」的作业与截止时间。\n凭据由 Android KeyStore 硬件加密，绝不明文落盘。",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
                Spacer(modifier = Modifier.height(14.dp))

                OutlinedTextField(
                    value = studentId,
                    onValueChange = { studentId = it },
                    label = { Text("学号") },
                    leadingIcon = {
                        Icon(imageVector = Icons.Outlined.Person, contentDescription = null)
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        // 学号是纯数字：关掉自动更正与首字母大写，
                        // 否则输入法可能把「3l90l234」悄悄改成「31901234」而用户看不出来。
                        autoCorrectEnabled = false,
                        capitalization = KeyboardCapitalization.None
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("统一身份认证密码") },
                    leadingIcon = {
                        Icon(imageVector = Icons.Outlined.Lock, contentDescription = null)
                    },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        // 密码不参与自动更正：不纠错就不会把「A」改成别的，
                        // 也不会把密码存进输入法的学习词库。
                        autoCorrectEnabled = false,
                        capitalization = KeyboardCapitalization.None
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (studentId.isNotBlank() && password.isNotBlank()) {
                        onConfirm(studentId.trim(), password.trim())
                    }
                },
                enabled = studentId.isNotBlank() && password.isNotBlank(),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("保存并同步")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("取消")
            }
        }
    )
}
