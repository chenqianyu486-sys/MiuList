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
import com.example.ui.viewmodel.resolveVisibleGuideTarget
import com.example.ui.viewmodel.visibleGuideTargets

/**
 * 引导的每一步。
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
 * - [GuideTarget.QUICK_GENERATE_TOGGLE]：一键生成默认**关着**，用户根本不知道
 *   App 里有这个功能，更不知道「不配规则也能用」—— 这一步是唯一能告诉他这件事的地方。
 *
 * 对这两个，引导是**唯一**能说清它们是什么的地方 —— 功能自己那一页上也没有这句话，
 * 而用户找不到的入口，写在别处也没用。所以这两步带说明。
 *
 * 其余步骤一律 null：给它们加说明，等于把那个功能自己页面上的话抄一份，
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
 * 引导的**完整**清单：一步一个目标。
 *
 * ## 顺序不是随手的
 *
 * 「同步课表」排第一是因为它是**前置**：没有课表，后面几步说的东西都无从谈起。
 * 「一键生成待办」这个开关排在「课程分类」前面，是因为**决定在前**：
 * 不想用这个功能的人，先被推去配一遍类别与规则，那就正好是「折腾设置」，
 * 也就是这个开关本身要避免的那件事。
 * 「课程类别」排在「联动规则」前面，因为规则是**按类别**配的 ——
 * 先知道有类别这回事，才谈得上「按类别配」。
 * 「生成本周待办」排在联动规则**之后**，因为它就是**按规则生成**：规则没配，它没什么可生成。
 *
 * ## 这份清单**不是**用户要走的那一份
 *
 * 用户实际走几步取决于「一键生成待办」开没开：
 *
 * - 关着：三步（同步课表 → **开关** → 壁纸）—— 轻量路线；
 * - 开着：五步（同步课表 → 课程分类 → 联动规则 → 生成本周待办 → 壁纸）。
 *
 * 可见的那一份由 [visibleGuideSteps] 按开关状态算出来。
 *
 * ## 图标与入口用同一枚
 *
 * 每一步的图标都取自该功能在别处的入口图标（抽屉的 ZJU 行、课程联动规则行、
 * 设置页的壁纸卡、顶栏那颗 ✨）：用户看完引导再去找那个功能，
 * 看到的是同一枚图标，不用重新认一遍。
 *
 * ## 文案怎么写：专业、清晰、简洁
 *
 * 这一套文案是**写给用户看的一手说明**，不是产品经理的自我表达。三条规矩：
 *
 * - **标题只说这一件事**：十个字以内，不带「一下」「给我看看」这类口语尾巴；
 * - **按钮只写动作**，四个字以内，让用户一眼知道点下去会发生什么；
 * - **能一句说完就不写两句** —— 多出来的那一句永远是**会过期的副本**。
 *
 * 长度上限由 `OnboardingGuideStepsTest` 钉住：写长了先删字，别加句子。
 */
val GuideSteps: List<GuideStep> = listOf(
    GuideStep(
        target = GuideTarget.ZJU_SYNC,
        icon = Icons.Outlined.CloudSync,
        title = "同步浙大课表",
        // 删掉的说明里写过「学在浙大的作业也会并进待办清单」——**那本来就是不实的**：
        // 学在浙大的待办只以**只读清单**出现在总览页，从来不写进待办清单；
        // 而且登录课表那一步根本不拉它（它走的是 App 打开时按已存凭据刷新那条路）。
        // 这条事实留在这儿，免得以后又有人往引导里写它。
        actionLabel = "去同步"
    ),
    GuideStep(
        target = GuideTarget.QUICK_GENERATE_TOGGLE,
        // 与顶栏那颗按钮同一枚图标（AutoAwesome）：那颗按钮没有文字标签，
        // 全靠图标认，而这一枚正是它。
        icon = Icons.Outlined.AutoAwesome,
        title = "一键生成待办",
        // 这一步的动作是**打开这个功能**，不是去某个地方 ——
        // 所以按钮写「开启」而不是「去看看」。
        actionLabel = "开启",
        // 这一步**带说明**，因为一键生成默认**关着**：不告诉用户它存在，
        // 他就以为这个 App 只能手动加待办。说的两件事都对着真的行为：
        // 开启之后能生成哪三类、以及不开也能用（手动添加）。
        description = "把本周的作业、实验报告和预习排进清单；不想设置规则也可以手动添加待办。"
    ),
    GuideStep(
        target = GuideTarget.COURSE_CATEGORY,
        icon = Icons.Outlined.Assignment,
        title = "设置课程分类",
        // 删掉的说明列了五个类别名（公共基础、专业必修…）。那是**课程分类弹层里
        // 就有的内容**：引导复述一遍，只是多一处会过期的副本（那边改了这边就错），
        // 对用户也没有任何新信息。
        //
        // 留个事实备注：类别**不决定**一门已有课生成哪几类待办
        // （改类别不会重算那三个开关，那三个开关是用户自己的值）；
        // 真实的关系是「类别 → 它归联动规则里的哪一组」，改完类别要在第三步应用一次 ——
        // 那一步**不需要改开关**：按「应用」就是把当前规则重新盖到所有类别的课程上。
        actionLabel = "去设置"
    ),
    GuideStep(
        target = GuideTarget.LINKAGE_RULES,
        icon = Icons.Outlined.Tune,
        title = "配置课程联动规则",
        actionLabel = "去配置"
    ),
    GuideStep(
        target = GuideTarget.WEEK_TASK_GENERATION,
        // 与顶栏那颗按钮同一枚图标（AutoAwesome）：那颗按钮没有文字标签，
        // 全靠图标认，而这一枚正是它。
        icon = Icons.Outlined.AutoAwesome,
        title = "生成本周待办",
        actionLabel = "预览待办",
        // 这一步**带说明**，因为它入口是顶栏一颗没文字标签的图标：
        // 没有这句话，用户看见「✨」不知道那是干什么的。
        // 说的三件事都对着真的行为：按类别规则生成、覆盖作业/实验报告/预习三类、
        // 同一条不重复生成（去重键是「课程 + 类型 + 周次 + 周几」）。
        description = "点顶栏的 ✨，就能把本周的作业、实验报告和预习加入清单，同一条不会重复生成。"
    ),
    GuideStep(
        target = GuideTarget.WALLPAPER,
        icon = Icons.Outlined.Wallpaper,
        title = "设置壁纸",
        actionLabel = "去更换"
    )
)

/**
 * 当前状态下，用户**实际要走**的那些步骤（按顺序）。
 *
 * 「一键生成待办」关着时，课程分类 / 课程联动规则 / 生成本周待办这三步
 * **不出现**（它们只服务于那个功能），换成「要不要开启它」那一步；
 * 开着时这三步照旧，整份清单与加这个开关之前一模一样。
 *
 * 算的是**看得见**的清单，不是完整清单 —— 界面上的「第 N 步 / 共 M 步」、
 * 进度点、以及 ViewModel 算「下一步」都以它为准。
 */
internal fun visibleGuideSteps(quickGenerateEnabled: Boolean): List<GuideStep> {
    val visible = visibleGuideTargets(quickGenerateEnabled).toSet()
    return GuideSteps.filter { it.target in visible }
}

/**
 * 上手引导。
 *
 * ## 形态
 *
 * 玻璃面板 + 一步一屏。底子用 [GlassOverlayHost]：
 * 遮罩、玻璃面、返回键、点外面关闭、进场动效都是现成的，
 * 引导只管往里放内容 —— 弹层那套视觉与交互不用再写一遍。
 *
 * ## 走几步取决于「一键生成待办」开没开
 *
 * 关着时是轻量三步（同步课表 → 开关 → 壁纸），
 * 开着时是完整五步。走哪一套由
 * [visibleGuideSteps] 算，「第 N 步 / 共 M 步」与进度点都按**看得见的那一套**算。
 *
 * **当前这一步可能已经不在清单里**：用户在「开关」那一步按下「开启」，
 * 功能一开，那一步就随之消失 —— 引导落到它后面第一个看得见的步骤
 * （「课程分类」），由 [resolveVisibleGuideTarget] 决定。
 *
 * ## 三个按钮各管一件事
 *
 * - **动作按钮**（每步各一个：「去同步」「开启」「去设置」「去配置」「预览待办」「去更换」）：
 *   把引导**暂停**在这里，去那个功能。目标功能做完、
 *   弹层关掉之后引导会自己接回来（见 `MainViewModel.openOnboardingTarget`）——
 *   用户还欠着几页没看，收掉就等于让他自己记着看到第几步了。
 *   但**不叠在上面**：叠着的话两层玻璃面会糊在一起（弹层的遮罩叠着引导的遮罩，
 *   背后被压两遍），面板也会和面板重叠成一堆。
 *   （「开启」那一步是例外：它按下去是**打开这个功能**，
 *   引导不暂停也不等谁。）
 * - **「下一步」**：翻到下一步，最后一步是「完成」。
 * - **「跳过」**：直接收掉（这一趟到此为止）。
 *
 * @param resumed 刚从动作按钮那个功能回来时为真：面板上多一句
 *   「刚带你看过这一项」，否则用户看到的是和离开前一模一样的第 N 步，
 *   会以为引导没动过。
 */
@Composable
fun OnboardingGuide(
    step: GuideTarget,
    quickGenerateEnabled: Boolean,
    onDismiss: () -> Unit,
    onNext: () -> Unit,
    onOpenTarget: (GuideTarget) -> Unit,
    resumed: Boolean = false,
    modifier: Modifier = Modifier
) {
    val visibleSteps = visibleGuideSteps(quickGenerateEnabled)
    // 当前这一步**可能已经不在清单里**了（一键生成刚被开启，
    // 「开关」那一步随之消失）：落到它后面第一个看得见的步骤上。
    val current = resolveVisibleGuideTarget(step, quickGenerateEnabled)
    val safeIndex = visibleSteps.indexOfFirst { it.target == current }.coerceAtLeast(0)
    val isLast = safeIndex == visibleSteps.lastIndex

    GlassOverlayHost(
        onDismissRequest = onDismiss,
        modifier = modifier,
        panelModifier = Modifier.testTag("onboarding_guide_panel"),
        // 标题不占 host 的 title 槽：整套内容（含标题）一起做交叉淡入，
        // 分成两个槽会变成「标题先换、说明后换」的两次动作。
        title = null,
        text = {
            Crossfade(targetState = current, label = "onboarding_guide_step") { target ->
                // 内容从**完整清单**里取，不从「当前看得见的那一套」里取：
                // 淡出中的那一帧持有的是**旧**的目标，而那时候的可见清单
                // 已经不是现在这一套了 —— 在新清单里找旧目标会**找不到**，
                // 直接崩在 `first {}` 上（用户在引导里按下「开启」就会撞到：
                // 「开关」那一步随之从清单里消失）。
                val s = GuideSteps.first { it.target == target }
                // 这一帧用**哪一套清单**来算「第几步 / 共几步」：
                //
                // - 开关那一步只在「关」时出现 → 用关着时那套；
                // - 其余步骤用**当前**这套（开着时六步、关着时四步）。
                //
                // 于是按下「开启」时：淡出的那一帧显示的还是「第 2 步 / 共 4 步」，
                // 淡入的那一帧已经是「第 2 步 / 共 6 步」—— 两帧各自都是对的。
                val ownSteps = visibleGuideSteps(
                    quickGenerateEnabled = quickGenerateEnabled &&
                        target != GuideTarget.QUICK_GENERATE_TOGGLE
                )
                val stepIndex = ownSteps.indexOfFirst { it.target == target }.coerceAtLeast(0)
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
                                text = "第 ${stepIndex + 1} 步 / 共 ${ownSteps.size} 步",
                                style = MaterialTheme.typography.labelSmall,
                                // 用 TextSecondary 而不是全局的 onSurfaceVariant（= TextMuted
                                // #78827B）：这一行是 11sp 小字，压在玻璃面板上实测只有
                                // 3.7:1，过不了 AA。TextSecondary #4A524D 约 7.6:1。
                                // 同一个理由，设置页那张卡上也是这么改的。
                                color = TextSecondary
                            )
                        }
                        GuideProgressDots(total = ownSteps.size, current = stepIndex)
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    // 这一步要说明的话。
                    //
                    // 放在标题**下面**、动作按钮**上面**：它解释的是「这个东西是什么」，
                    // 紧挨着标题才接得上；而它也解释「为什么要按下面那个按钮」，
                    // 所以又不能离按钮太远。
                    //
                    // 字号与颜色跟「刚带你看过这一项」那句一致（bodySmall + TextSecondary）：
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
                            text = "刚带你看过这一项，确认后继续。",
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
