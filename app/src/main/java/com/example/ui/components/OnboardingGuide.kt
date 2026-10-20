package com.example.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.outlined.Assignment
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ui.theme.TextMuted
import com.example.ui.viewmodel.GuideTarget

/**
 * 引导的一步。
 *
 * [target] 是**行为契约**（见 [GuideTarget]），其余都是给人看的：
 * 图标、标题、说明、按钮上那句话。
 */
@Immutable
data class GuideStep(
    val target: GuideTarget,
    val icon: ImageVector,
    val title: String,
    val description: String,
    val actionLabel: String
)

/**
 * 引导的四步。
 *
 * ## 为什么是这四件事
 *
 * 它们是「一个课表 App 从空到能用」真正要走的那几步：**先把课表弄进来**（否则
 * 后面全是空的）、**知道课分门别类**（类别决定默认生成哪几类待办）、
 * **会配联动规则**（不然待办生成得不对）、**把界面调成自己的**。
 *
 * ## 顺序不是随手的
 *
 * 「同步课表」排第一是因为它是**前置**：没有课表，后三步说的东西都无从谈起。
 * 「课程类别」排在「联动规则」前面，因为规则是**按类别**配的 ——
 * 先知道有类别这回事，才谈得上「按类别配」。
 *
 * ## 图标与入口用同一枚
 *
 * 每一步的图标都取自该功能在别处的入口图标（抽屉的 ZJU 行、课程联动规则行、
 * 设置页的壁纸卡）：用户看完引导再去找那个功能，看到的是同一枚图标，
 * 不用重新认一遍。
 */
val GuideSteps: List<GuideStep> = listOf(
    GuideStep(
        target = GuideTarget.ZJU_SYNC,
        icon = Icons.Outlined.CloudSync,
        title = "把浙大课表搬进来",
        description = "用学号和密码登录浙大教务，课表、作息表和校历会一起落到本地，" +
            "断网也能看；学在浙大的作业也会并进待办清单。",
        actionLabel = "去登录浙大"
    ),
    GuideStep(
        target = GuideTarget.COURSE_CATEGORY,
        icon = Icons.Outlined.Assignment,
        title = "给每门课分门别类",
        description = "每门课归到一个类别：公共基础、专业必修、实验实践、通识公共、专业选修。" +
            "类别决定它默认生成哪几类待办 —— 实验课默认交实验报告，通识课三个开关都不勾。",
        actionLabel = "去添加课程"
    ),
    GuideStep(
        target = GuideTarget.LINKAGE_RULES,
        icon = Icons.Outlined.Tune,
        title = "按类别配联动规则",
        description = "每个类别三个开关：课前预习、课后作业、实验报告。" +
            "改一个开关会覆盖该类别下的所有课程，弹层里会先告诉你波及几门课。",
        actionLabel = "去配置规则"
    ),
    GuideStep(
        target = GuideTarget.WALLPAPER,
        icon = Icons.Outlined.Wallpaper,
        title = "换一张壁纸",
        description = "八张预设渐变，或者从相册选一张自己的照片。" +
            "照片默认带柔焦，卡片上的字始终压得住。",
        actionLabel = "去换壁纸"
    )
)

/**
 * 上手引导。
 *
 * ## 形态
 *
 * 玻璃面板 + 四步，每步一屏。底子用 [GlassOverlayHost]：
 * 遮罩、玻璃面、返回键、点外面关闭、进场动效都是现成的，
 * 引导只管往里放内容 —— 弹层那套视觉与交互不用再写一遍。
 *
 * ## 三个按钮各管一件事
 *
 * - **「去看看」**：收掉引导，去那个功能。**收掉而不是叠在上面** ——
 *   叠着的话两层玻璃面会糊在一起（弹层的遮罩叠着引导的遮罩，背后被压两遍），
 *   面板也会和面板重叠成一堆。用户从设置里随时能再打开，不靠「暂停」续读。
 * - **「下一步」**：翻到下一步，最后一步是「完成」。
 * - **「跳过」**：直接收掉。
 */
@Composable
fun OnboardingGuide(
    stepIndex: Int,
    onDismiss: () -> Unit,
    onNext: () -> Unit,
    onOpenTarget: (GuideTarget) -> Unit,
    modifier: Modifier = Modifier
) {
    val safeIndex = stepIndex.coerceIn(0, GuideSteps.lastIndex)
    val step = GuideSteps[safeIndex]
    val isLast = safeIndex == GuideSteps.lastIndex

    GlassOverlayHost(
        onDismissRequest = onDismiss,
        modifier = modifier,
        panelModifier = Modifier.testTag("onboarding_guide_panel"),
        // 标题不占 host 的 title 槽：整套内容（含标题）一起做交叉淡入，
        // 分成两个槽会变成「标题先换、说明后换」的两次动作。
        title = null,
        text = {
            Crossfade(targetState = safeIndex, label = "onboarding_guide_step") { index ->
                val s = GuideSteps[index]
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = s.icon,
                                contentDescription = null,
                                modifier = Modifier.size(21.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = s.title,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "第 ${index + 1} 步 / 共 ${GuideSteps.size} 步",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted
                            )
                        }
                        GuideProgressDots(total = GuideSteps.size, current = index)
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = s.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedButton(
                        onClick = { onOpenTarget(s.target) },
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("onboarding_guide_action")
                    ) {
                        Text(s.actionLabel, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("onboarding_guide_skip")
            ) {
                Text("跳过", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            Button(
                onClick = onNext,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                modifier = Modifier.testTag("onboarding_guide_next")
            ) {
                Text(if (isLast) "完成" else "下一步")
            }
        }
    )
}

/**
 * 进度点。**用点而不是「1/4」那种数字条**：数字已经写在标题下面了，
 * 两处都写就成了两份同一个事实，其中一份会先过期。
 */
@Composable
private fun GuideProgressDots(total: Int, current: Int) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(total) { index ->
            val active = index == current
            Box(
                modifier = Modifier
                    .size(if (active) 6.dp else 5.dp)
                    .clip(CircleShape)
                    .background(
                        if (active) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.28f)
                    )
            )
        }
    }
}
