package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.Rule
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.EventBusy
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.source.zju.ZjuFailureKind
import com.example.data.sync.SyncFailure
import com.example.ui.theme.AlertRed
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.TextTertiary

/**
 * 同步失败的完整提示卡。
 *
 * ## 三层结构：是什么 / 为什么 / 怎么办
 *
 * 只放一句「同步失败：xxx」是不够的 —— 用户看完仍然不知道该做什么，
 * 于是最常见的反应是反复点重试，而对**账号锁定**这类失败，重试恰恰会加重问题。
 * 所以卡片固定三层：
 *
 * 1. 标题（分类，唯一的名字来自 [ZjuFailureKind.title]）
 * 2. 原因（上游给的那一句）
 * 3. 建议（[SyncFailureAdvice]，这是本次改动真正加的东西）
 *
 * ## 为什么不描边
 *
 * 淡红底 + 一个红色图标已经足够说明「出事了」。加一圈灰边只会让它更像一个需要
 * 处理的**表单校验**框，而它其实是「这次没成功、下次再试也行」的状态提示。
 */
@Composable
fun SyncFailureCard(
    failure: SyncFailure,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
    onEditCredentials: (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            // 淡红底不用满不透明度：底下是弹层的 surface，留一点透明才不会糊成一块死色。
            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.75f))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = SyncFailureAdvice.iconOf(failure.kind),
                contentDescription = null,
                tint = AlertRed,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                // 14sp 粗体：正好够上 WCAG「大字」的 3:1，浅红底上的正红不用再加深。
                text = failure.kind.title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = AlertRed
            )
        }

        Text(
            text = failure.reason,
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )

        SyncFailureAdvice.localStatusOf(failure)?.let { status ->
            Text(
                text = status,
                style = MaterialTheme.typography.labelSmall,
                // 不用 TextMuted：实测它在淡红卡面上只有 3.65:1，而这一行 11sp 要 4.5。
                // TextTertiary 是本项目专门为「浅底小字必须过 AA」留的那一档。
                color = TextTertiary
            )
        }

        Text(
            text = SyncFailureAdvice.adviceOf(failure.kind),
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary
        )

        if (onRetry != null || onEditCredentials != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 锁定态不给「重试」：给了就是在鼓励用户去撞锁定期。
                if (onRetry != null && failure.kind != ZjuFailureKind.LOCKED) {
                    TextButton(onClick = onRetry) {
                        Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("重试一次", fontSize = 12.sp)
                    }
                }
                if (onEditCredentials != null && failure.kind.needsCredentials) {
                    TextButton(onClick = onEditCredentials) {
                        Icon(Icons.Outlined.Key, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("修改学号密码", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

/**
 * 分类 → 建议文案与图标。
 *
 * 抽成不带 Composable 的对象，是为了**能直接跑单测**：
 * 这段文案是用户唯一能看到的排障指引，它被改成什么样都不该只有截图能发现。
 */
internal object SyncFailureAdvice {

    fun iconOf(kind: ZjuFailureKind): ImageVector = when (kind) {
        ZjuFailureKind.CREDENTIAL -> Icons.Outlined.Key
        ZjuFailureKind.LOCKED -> Icons.Outlined.Block
        ZjuFailureKind.SESSION -> Icons.Outlined.Lock
        ZjuFailureKind.NETWORK -> Icons.Outlined.WifiOff
        ZjuFailureKind.SERVER -> Icons.Outlined.CloudOff
        ZjuFailureKind.TERM -> Icons.Outlined.EventBusy
        ZjuFailureKind.EMPTY -> Icons.Outlined.Dns
        ZjuFailureKind.DATA_INVALID -> Icons.AutoMirrored.Outlined.Rule
        ZjuFailureKind.UNKNOWN -> Icons.AutoMirrored.Outlined.HelpOutline
    }

    /**
     * 「怎么办」。措辞上有一条硬要求：**不要说「请重试」**给重试也没用的类别。
     * 用户照着做能少走弯路，比把原因描述得更精确更有价值。
     */
    fun adviceOf(kind: ZjuFailureKind): String = when (kind) {
        ZjuFailureKind.CREDENTIAL ->
            "核对学号与密码后再同步一次；如果是刚改过密码，这里存的还是旧的。"
        ZjuFailureKind.LOCKED ->
            "先不要再试了，继续输错会延长锁定期。等浙大解锁后再同步，这期间本地课表照常可用。"
        ZjuFailureKind.SESSION ->
            "重新走一次登录即可，和密码对错无关；同步时会自动重新登录。"
        ZjuFailureKind.NETWORK ->
            "检查网络、代理或 VPN 后重试。连不上不影响已缓存的课表，本地数据可以照常用。"
        ZjuFailureKind.SERVER ->
            "这是浙大那边的问题，改密码没有用。等一会儿再试，或先用文件导入。"
        ZjuFailureKind.TERM ->
            "换一个学期再试，或改用文件导入这份课表。"
        ZjuFailureKind.EMPTY ->
            "教务网返回的是空课表。先去浙大钉的个人课表确认，或改用文件导入。"
        ZjuFailureKind.DATA_INVALID ->
            "拉回来的数据缺字段或前后对不上，重试结果一样。改用文件导入更稳。"
        ZjuFailureKind.UNKNOWN ->
            "暂时判断不出原因，可以再试一次；反复失败就改用文件导入。"
    }

    /**
     * 本地数据那一行。
     *
     * 只在**用户可能因此改变行为**时才出现：网络/服务侧失败时他们会担心课表被清空，
     * 而「已经更新过」时他们会想把改动退回去 —— 两种都需要明说。
     */
    fun localStatusOf(failure: SyncFailure): String? = when {
        !failure.localDataIntact -> "注意：本地课表已被这次同步更新过"
        failure.localCourseCount == null -> null
        failure.localCourseCount > 0 -> "本地 ${failure.localCourseCount} 门课程仍然照常可用"
        else -> "本地暂时还没有课表数据"
    }
}
