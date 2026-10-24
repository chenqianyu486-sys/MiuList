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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Assignment
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.School
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
import com.example.ui.theme.TextSecondary
import com.example.ui.viewmodel.GuideTarget

/**
 * 引导的一步。
 *
 * [target] 是**行为契约**（见 [GuideTarget]），其余都是给人看的：
 * 图标、标题、按钮上那句话，以及可选的 [description]。
 *
 * ## [description]：多数步骤没有，两步有
 *
 * 原来每一步下面还有一段解释（「用学号和密码登录浙大教务…」），全删了 ——
 * 标题已经把这一步要做什么说完了，那段解释讲的是**同一个事实**的另一种说法，
 * 标题 + 一段解释 = 同一件事说两遍。而且它是**会过期的副本**：
 * 里面写着「八张预设」「五个类别」这类别的页面上也写着的东西，
 * 那边一改，这边就成了一句错的说明。
 * 真要说清楚什么，位置在**那个功能自己那一页**，不在引导里。
 *
 * 但有**两个例外**，它们是整个 App 里两个**看不见**的功能：
 *
 * - [GuideTarget.WEEK_TASK_GENERATION]：入口是顶栏一颗**没有文字标签**的图标；
 * - [GuideTarget.HERO_FLIP_CARD]：学在浙大那一面**藏在主卡背面**，不翻就看不见。
 *
 * 对这两个，引导是**唯一**能说清它们是什么的地方 —— 功能自己那一页上也没有这句话，
 * 而用户找不到的入口，写在别处也没用。所以这两步带说明。
 *
 * 其余四步一律 null：给它们加说明，等于把那个功能自己页面上的话抄一份，
 * 而抄本会先过期。这个约束由 `OnboardingGuideStepsTest` 钉住 ——
 * 哪天真要给别的步骤加说明，先想清楚「这句话在功能自己那一页上是不是已经有了」。
 */
@Immutable
data class GuideStep(
    val target: GuideTarget,
    val icon: ImageVector,
    val title: String,
    val actionLabel: String,
    /** 这一步额外要说明的话；null = 只有标题，不多讲。 */
    val description: String? = null
)

/**
 * 引导的六步。
 *
 * ## 为什么是这六件事
 *
 * 前三步是「一个课表 App 从空到能用」真正要走的那几步：**先把课表弄进来**（否则
 * 后面全是空的）、**知道课分门别类**（类别决定它归联动规则里的哪一组）、
 * **会配联动规则**（不然待办生成得不对）。
 *
 * 后三步是另外三件事：**一键生成本周待办**（前面三步攒下来的规则到这里才用得上）、
 * **换一张壁纸**（把界面调成自己的）、**学在浙大那一面**（藏在总览页主卡的背面）。
 *
 * 最后两个是整个 App 里**看不见**的功能 —— 一个按钮没名字，一张卡有一面看不见 ——
 * 用户自己不会发现，所以引导要替 App 说一次。
 *
 * ## 顺序不是随手的
 *
 * 「同步课表」排第一是因为它是**前置**：没有课表，后面几步说的东西都无从谈起。
 * 「课程类别」排在「联动规则」前面，因为规则是**按类别**配的 ——
 * 先知道有类别这回事，才谈得上「按类别配」。
 * 「一键生成」排在联动规则**之后**，因为它就是**按规则生成**：规则没配，它没什么可生成。
 * 「学在浙大翻面」排在最后，因为它是这一趟的**终点** ——
 * 「去看看」把人送到总览页并替他把卡翻过去，这一趟引导就在那儿收尾。
 *
 * ## 图标与入口用同一枚
 *
 * 每一步的图标都取自该功能在别处的入口图标（抽屉的 ZJU 行、课程联动规则行、
 * 设置页的壁纸卡、顶栏那颗 ✨、主卡背面的学在浙大徽标）：用户看完引导再去找那个功能，
 * 看到的是同一枚图标，不用重新认一遍。
 */
val GuideSteps: List<GuideStep> = listOf(
    GuideStep(
        target = GuideTarget.ZJU_SYNC,
        icon = Icons.Outlined.CloudSync,
        title = "把浙大课表搬进来",
        // 删掉的说明里写过「学在浙大的作业也会并进待办清单」——**那本来就是不实的**：
        // 学在浙大的待办只以**只读清单**出现在总览页，从来不写进待办清单；
        // 而且登录课表那一步根本不拉它（它走的是 App 打开时按已存凭据刷新那条路）。
        // 这条事实留在这儿，免得以后又有人往引导里写它。
        actionLabel = "去登录浙大"
    ),
    GuideStep(
        target = GuideTarget.COURSE_CATEGORY,
        icon = Icons.Outlined.Assignment,
        title = "给每门课分门别类",
        // 删掉的说明列了五个类别名（公共基础、专业必修…）。那是**课程分类弹层里
        // 就有的内容**：引导复述一遍，只是多一处会过期的副本（那边改了这边就错），
        // 对用户也没有任何新信息。
        //
        // 留个事实备注：类别**不决定**一门已有课生成哪几类待办
        // （改类别不会重算那三个开关，那三个开关是用户自己的值）；
        // 真实的关系是「类别 → 它归联动规则里的哪一组」，改完类别要在第三步应用一次。
        actionLabel = "去选类别"
    ),
    GuideStep(
        target = GuideTarget.LINKAGE_RULES,
        icon = Icons.Outlined.Tune,
        title = "按类别配联动规则",
        actionLabel = "去配置规则"
    ),
    GuideStep(
        target = GuideTarget.WEEK_TASK_GENERATION,
        // 与顶栏那颗按钮同一枚图标（AutoAwesome）：那颗按钮没有文字标签，
        // 全靠图标认，而这一枚正是它。
        icon = Icons.Outlined.AutoAwesome,
        title = "一键生成本周待办",
        actionLabel = "看看它会排什么",
        // 这一步**带说明**，因为它入口是顶栏一颗没文字标签的图标：
        // 没有这句话，用户看见「✨」不知道那是干什么的。
        // 说的三件事都对着真的行为：按类别规则生成、覆盖作业/实验报告/预习三类、
        // 同一条不重复生成（去重键是「课程 + 类型 + 周次 + 周几」）。
        description = "顶栏那颗 ✨：按你配好的类别规则，把本周的课后作业、实验报告和预习一次排进清单。同一条不会重复生成。"
    ),
    GuideStep(
        target = GuideTarget.WALLPAPER,
        icon = Icons.Outlined.Wallpaper,
        title = "换一张壁纸",
        actionLabel = "去换壁纸"
    ),
    GuideStep(
        target = GuideTarget.HERO_FLIP_CARD,
        // 与主卡背面「学在浙大」那枚徽标同一枚图标（School）。
        icon = Icons.Outlined.School,
        title = "总览页那张卡能翻面",
        actionLabel = "翻给我看",
        // 这一步**带说明**，因为学在浙大那一面**藏着**：不把卡翻过去，
        // 用户根本不知道 App 里有这么一面。这一步的「去看看」会替他把卡翻过去。
        description = "总览页最上面那张卡是双面的：正面是日期和天气，点一下就翻过去 —— 背面是学在浙大待交的作业。"
    )
)

/**
 * 上手引导。
 *
 * ## 形态
 *
 * 玻璃面板 + 六步，每步一屏。底子用 [GlassOverlayHost]：
 * 遮罩、玻璃面、返回键、点外面关闭、进场动效都是现成的，
 * 引导只管往里放内容 —— 弹层那套视觉与交互不用再写一遍。
 *
 * ## 三个按钮各管一件事
 *
 * - **「去看看」**：把引导**暂停**在这里，去那个功能。目标功能做完、
 *   弹层关掉之后引导会自己接回来（见 `MainViewModel.openOnboardingTarget`）——
 *   用户还欠着几页没看，收掉就等于让他自己记着看到第几步了。
 *   但**不叠在上面**：叠着的话两层玻璃面会糊在一起（弹层的遮罩叠着引导的遮罩，
 *   背后被压两遍），面板也会和面板重叠成一堆。
 *   （最后一步「学在浙大翻面」是唯一的例外：它没有弹层可等，
 *   「去看看」就是把人送到总览页把卡翻过去，这一趟就此走完。）
 * - **「下一步」**：翻到下一步，最后一步是「完成」。
 * - **「跳过」**：直接收掉（这一趟到此为止）。
 *
 * @param resumed 刚从「去看看」那个功能回来时为真：面板上多一句
 *   「刚带你去过这一项」，否则用户看到的是和离开前一模一样的第 N 步，
 *   会以为引导没动过。
 */
@Composable
fun OnboardingGuide(
    stepIndex: Int,
    onDismiss: () -> Unit,
    onNext: () -> Unit,
    onOpenTarget: (GuideTarget) -> Unit,
    resumed: Boolean = false,
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
                // 内容区**可滚动**（GlassOverlayHost 的约定：内容长时由内层滚动，
                // 按钮行始终留在面板底部可见）。
                //
                // 有了说明文字之后内容会长一截，窄屏 + 大字号时可能超出面板高度；
                // 不滚的话超出的部分会被**裁掉**（标题还在、按钮被切掉半截）。
                //
                // 滚动状态放在 Crossfade 的内容里 → 每一步各自一份，
                // 翻到下一步时滚动位置从头开始，不会停在上一句的中间。
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
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
                                // 用 TextSecondary 而不是全局的 onSurfaceVariant（= TextMuted
                                // #78827B）：这一行是 11sp 小字，压在玻璃面板上实测只有
                                // 3.7:1，过不了 AA。TextSecondary #4A524D 约 7.6:1。
                                // 同一个理由，设置页那张卡上也是这么改的。
                                color = TextSecondary
                            )
                        }
                        GuideProgressDots(total = GuideSteps.size, current = index)
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    // 这一步要说明的话。
                    //
                    // 放在标题**下面**、动作按钮**上面**：它解释的是「这个东西是什么」，
                    // 紧挨着标题才接得上；而它也解释「为什么要按下面那个按钮」，
                    // 所以又不能离按钮太远。
                    //
                    // 字号与颜色跟「刚带你去过这一项」那句一致（bodySmall + TextSecondary）：
                    // 同一层级的辅助信息用同一套样式，不多引入一档字号。
                    s.description?.let { desc ->
                        Text(
                            text = desc,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                    // 刚从目标那边回来的提示。放在动作按钮**上面**：
                    // 它解释的是「为什么还是这一步」，紧挨着那个让人困惑的地方。
                    if (resumed) {
                        Text(
                            text = "刚带你去过这一项，确认好了再继续。",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                    }
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
