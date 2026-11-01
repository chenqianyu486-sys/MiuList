package com.example.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.unit.dp

/**
 * 「有新版了」的提示弹层。
 *
 * ## 它要回答什么
 *
 * 只回答两件事：**新的是哪一版**、**去哪儿下**。
 *
 * 不写「检测到新版本，已为你准备好…」这类话 —— 用户要的是「有新版，去下」，
 * 其余都是把同一件事说三遍。
 *
 * ## 为什么不给下载按钮
 *
 * 下载要走浏览器（APK 覆盖安装要走系统安装器，App 自己装不了自己）。
 * 所以这里给的是**发布页**的入口：那里有 Android 版与电脑版的下载，
 * 还有「GitHub 打不开？Gitee 备用下载」——在 App 里塞一条直链，
 * 等于替用户把「哪个源能用」这件事决定了，而那条路未必对所有人都通。
 *
 * ## 「稍后」不是「以后别提醒」
 *
 * 「稍后」只是把这一次提示收掉：下次再点「检查更新」还会提示。
 * 用户没说不提醒，只是这一次不想被打断。
 */
@Composable
fun AppUpdateDialog(
    /** 最新版版本号，如 `1.2.0`。 */
    latestVersion: String,
    /** 用户现在装的版本号，如 `1.1.0`。 */
    currentVersion: String,
    /** 「去发布页」按下时把用户带去哪儿。 */
    onOpenReleasePage: () -> Unit,
    onDismiss: () -> Unit
) {
    GlassOverlayHost(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "有新版本了",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Column {
                Text(
                    text = "已经发布 v$latestVersion，你现在用的是 v$currentVersion。",
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "去发布页就能拿到新版，装之前记得先备份。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onOpenReleasePage,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                modifier = Modifier.testTag("app_update_open_release_page_button")
            ) {
                Text("去发布页", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.testTag("app_update_later_button")
            ) {
                Text("稍后", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    )
}
