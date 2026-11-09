package com.example.ui.screens

import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.ImportExport
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.SystemUpdateAlt
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.source.weather.AqiStandard
import com.example.ui.components.CompactSwitch
import com.example.ui.components.islandBottomInset
import com.example.ui.theme.CardBlockGap
import com.example.ui.theme.LimeGreenTileFaint
import com.example.ui.theme.PageHInset
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.measureTextHeight
import com.example.ui.theme.measureTextSize
import com.example.ui.theme.glassSurface

/**
 * 设置页。
 *
 * **八件事：待办完成确认、空气质量标准、通知提醒、检查更新、
 * 课表导入/导出、待办分类管理、自定义壁纸、使用引导。**
 *
 * 原来这里是六个入口（账号同步 / 课程联动规则 / 待办生成规则 / 导入导出 /
 * 本学期校历 / 课程管理列表），其中**五个都已经搬到顶栏展开的「更多」页**：
 * 账号与学期是「我现在是什么身份」的读数，规则和课程管理是日常真正常改的东西，
 * 它们都属于「打开一次、看完就走」的低频入口，放在一个要滚很久的设置页里
 * 只是让人多滚几屏。留在这里的只剩不常动、动了要慎重的数据类操作。
 *
 * 原来还有第四件：「示例数据」（一键恢复官方示例课表与待办）。**2026-10-20 整项取消** ——
 * App 不再播种示例数据，这个入口也就没有存在理由了。
 *
 * 同一个入口在两个地方都能点，用户会犹豫该点哪个——所以搬走的同时，
 * 这一页里对应的入口**全部删除**，不做「这里也留一个」的重复。
 *
 * **每一项只留标题**（图标 + 标题 + 行尾一个「>」）：原来每张卡下面还挂着一句副标题
 * （导入导出说「课表、待办、分类与规则一起带走」、分类说「4 个自带分类 · N 个自定义
 * 分类」、壁纸说当前用的是哪一张、引导说「四步走一遍：…」）。那些句子讲的**都是
 * 这一项本身是什么**，而点进去的弹层里本来就写着；门口再说一遍是同一件事说两遍。
 * 标题 + 引导已经足够点，副标题一律不要。
 *
 * **行尾不再摆实心按钮**：原来右边那颗绿按钮（「管理」「更换」「打开」……）一屏七八个，
 * 视觉重量和标题一样重 —— 用户扫这一页时先看见的是按钮，不是这一行是什么。
 * 现在换成一个**线性画出来的「>」**：它只说明「这一行点得进去」，是个方向提示，
 * 不与标题抢注意力。整张卡仍然是唯一的落点（与抽屉里的行同一套约定）。
 *
 * **空气质量那一项是行尾的两颗按钮**：原来是一颗开关，标题被迫写成「空气质量用美国 AQI」
 * —— 开关只说「开 / 关」，用户得靠句子去猜「开」的是哪一套。现在两套标准的名字直接
 * 写成两颗按钮（左「中国」、右「美国」），当前是哪一颗绿着，一眼看见。
 *
 * **前两项是偏好**，排在最前面：待办完成确认是每天都会碰到的决定
 * （勾掉待办要不要多问一句）；空气质量标准决定天气块那一行显示国标还是
 * 美国 AQI —— 两套数都在 weather.json 里，拨一下只换显示，不联网。
 * 下面那几张都是「不常动、动了要慎重」的数据操作，常用的放最上面。
 */
@Composable
fun SettingsScreen(
    onOpenTransferDialog: () -> Unit,
    onOpenCategoryManageDialog: () -> Unit,
    onOpenWallpaperDialog: () -> Unit = {},
    onOpenOnboardingGuide: () -> Unit = {},
    /** 打开本地提醒设置（每日汇总 / 上课 / 待办截止）。 */
    onOpenNotificationDialog: () -> Unit = {},
    /** 「完成待办前要不要先确认」。关掉之后点圆圈不再弹确认框。 */
    taskCompleteConfirmEnabled: Boolean = true,
    onTaskCompleteConfirmChange: (Boolean) -> Unit = {},
    /** 天气块最后一行显示哪套 AQI。 */
    aqiStandard: AqiStandard = AqiStandard.CN,
    onAqiStandardChange: (AqiStandard) -> Unit = {},
    /** 当前版本号，显示在「关于」那一行。 */
    appVersion: String = "",
    /** 正在检查更新（按钮显示「检查中…」并禁用）。 */
    isCheckingAppUpdate: Boolean = false,
    /** 手动检查有没有新版本。 */
    onCheckAppUpdate: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            // 加了一项 AQI 标准之后这一页变长了；测试要能滚到屏幕外的卡片，
            // 不能靠“默认窗口里刚好露出来”这种脆弱的假设。
            .testTag("settings_list")
            .padding(horizontal = PageHInset),
        verticalArrangement = Arrangement.spacedBy(CardBlockGap),
        // 底部给浮岛导航留出收尾空间，内容本身仍会滚到岛底下被毛玻璃糊住
        contentPadding = PaddingValues(bottom = islandBottomInset())
    ) {
        item {
            Spacer(modifier = Modifier.height(4.dp))
        }

        item {
            SettingsToggleCard(
                icon = Icons.Outlined.CheckCircle,
                title = "待办完成确认",
                checked = taskCompleteConfirmEnabled,
                onCheckedChange = onTaskCompleteConfirmChange,
                testTag = "settings_task_complete_confirm_switch"
            )
        }

        item {
            // 两套 AQI 的**数值**不一样，不只是标签。天气块两套都用
            // “空气 + 等级 + 数值”的自然文案 —— 哪一套正在用，由行尾那颗绿按钮说出来。
            SettingsAqiCard(
                icon = Icons.Outlined.Cloud,
                title = "空气质量 AQI",
                standard = aqiStandard,
                onStandardChange = onAqiStandardChange
            )
        }

        item {
            // 提醒和「待办完成确认」一样属于每天都会碰到的偏好，所以排在数据类操作之前。
            // 它本身是一项独立功能，不塞进别的卡片里当一行开关。
            SettingsActionCard(
                icon = Icons.Outlined.Notifications,
                title = "通知提醒",
                actionTestTag = "open_notification_settings_row",
                onAction = onOpenNotificationDialog
            )
        }

        item {
            SettingsActionCard(
                icon = Icons.Outlined.ImportExport,
                title = "数据导入 / 导出",
                actionTestTag = "open_transfer_row",
                onAction = onOpenTransferDialog
            )
        }

        item {
            SettingsActionCard(
                icon = Icons.Outlined.Category,
                title = "待办分类管理",
                actionTestTag = "open_task_category_dialog_row",
                onAction = onOpenCategoryManageDialog
            )
        }

        item {
            SettingsActionCard(
                icon = Icons.Outlined.Wallpaper,
                title = "自定义壁纸",
                actionTestTag = "open_wallpaper_row",
                onAction = onOpenWallpaperDialog
            )
        }

        item {
            // 引导放在三张数据卡之后、关于之前：这一页的前三张都是
            // 「不常动、动了要慎重」的数据操作，而引导是**看不懂的时候想找的事**，
            // 排在它们后面才不打断那条主线。
            // 空态时引导会自动弹一次，但用户没准错过了 —— 想重看就该有个入口。
            SettingsActionCard(
                icon = Icons.Outlined.Explore,
                title = "使用引导",
                actionTestTag = "open_onboarding_guide_row",
                onAction = onOpenOnboardingGuide
            )
        }

        item {
            // 「检查更新」排在引导之后、关于之前：
            // 它是**偶尔才动一次**的动作，而「关于」那一块写着版本与许可，
            // 两件事说的是同一片地方，放在一起好找。
            //
            // **只有手动**：App 不会自己去问有没有新版 —— 每次启动都欠一次网络请求，
            // 而多数时候什么也不会发生。用户想知道的候点一下就好。
            //
            // 检查中的那一档，行尾那颗「>」让位给「检查中…」：这一行点不得，
            // 但用户得知道它**正在**查 —— 按钮没了，状态提示得留下。
            SettingsActionCard(
                icon = Icons.Outlined.SystemUpdateAlt,
                title = "检查更新",
                actionTestTag = "open_check_update_row",
                actionEnabled = !isCheckingAppUpdate,
                statusText = if (isCheckingAppUpdate) "检查中…" else null,
                onAction = onCheckAppUpdate
            )
        }

        item {
            val uriHandler = LocalUriHandler.current
            val clipboardManager = LocalClipboardManager.current
            val context = LocalContext.current

            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .glassSurface(shape = RoundedCornerShape(18.dp))
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Outlined.Code,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "关于与开源仓库",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Text(
                            // 版本与许可放在一起：这两样都是「关于这个 App」的信息，
                            // 用户报问题时也会照着它说版本号。
                            text = if (appVersion.isBlank()) "GPL-3.0" else "v$appVersion · GPL-3.0",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "基于课程表的自动化轻量作业待办软件，提供分类清单与日程表双重视图。以 GNU GPLv3 许可证开源，许可证继承自 Celechron。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    // GPLv3 第 5(d) 条要的「适当法律声明」是四件事：
                    // 版权声明、**不提供任何担保**、可以按本许可传递本作品、以及到哪儿看许可证副本。
                    // 以前这里只有「GPL-3.0」四个字和一个仓库链接 —— 担保与传递许可都没写，
                    // 缺的那两条在分发出去的 APK 里是实打实的合规缺口。
                    Text(
                        text = "版权 © 2026 MiuList 贡献者；继承自 Celechron 的部分版权归其原作者所有。" +
                            "您可以在同样条款下复制、修改与再分发本程序。" +
                            "本程序不提供任何担保。",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            .clickable {
                                try {
                                    uriHandler.openUri(MIULIST_REPO_URL)
                                } catch (_: Exception) {}
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = MIULIST_REPO_URL,
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = {
                                    clipboardManager.setText(AnnotatedString(MIULIST_REPO_URL))
                                    Toast.makeText(context, "已复制仓库地址", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.ContentCopy,
                                    contentDescription = "复制地址",
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            IconButton(
                                onClick = {
                                    try {
                                        uriHandler.openUri(MIULIST_REPO_URL)
                                    } catch (_: Exception) {}
                                },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                                    contentDescription = "在浏览器中打开",
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            val uriHandler = LocalUriHandler.current

            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .glassSurface(shape = RoundedCornerShape(18.dp))
                        .padding(16.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.Favorite,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "致谢与鸣谢列表",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "本项目在开发过程中参考并受益于以下优秀开源项目与服务，特向原作者及贡献者致敬：",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    AcknowledgementsList.forEachIndexed { index, ack ->
                        if (index > 0) {
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                                thickness = 0.5.dp,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    try {
                                        uriHandler.openUri(ack.url)
                                    } catch (_: Exception) {}
                                }
                                .padding(vertical = 4.dp, horizontal = 2.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = ack.project,
                                    style = MaterialTheme.typography.titleSmall.copy(
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Icon(
                                    imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                                    contentDescription = "打开项目地址",
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                                )
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "贡献者：" + ack.contributors,
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = ack.note,
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary
                            )
                        }
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(30.dp))
        }
    }
}

private const val MIULIST_REPO_URL = "https://github.com/chenqianyu486-sys/MiuList"

internal data class AcknowledgementInfo(
    val project: String,
    val contributors: String,
    val note: String,
    val url: String
)

internal val AcknowledgementsList = listOf(
    AcknowledgementInfo(
        project = "Celechron",
        contributors = "n0sig, Phil-Fan, Quinn-DJ, lelexia0131, bno3bno3 及社区贡献者",
        note = "本项目 GPLv3 许可证继承自 Celechron，参考其日程与课表功能设计；学在浙大待办同步的登录链路、认证失效判定与助教作业过滤口径亦参照其实现",
        url = "https://github.com/Celechron/Celechron"
    ),
    AcknowledgementInfo(
        project = "Elychron",
        contributors = "Elyyyyyyyyxer 及其对应贡献者",
        note = "校园课表与日程管理相关设计与灵感来源",
        url = "https://github.com/Elyyyyyyyyxer/Elychron"
    ),
    AcknowledgementInfo(
        project = "zju-ical",
        contributors = "cxz66666 及其对应贡献者（源自 determ1ne/grsical）",
        note = "浙大课表日历导出与转换协议参考",
        url = "https://github.com/cxz66666/zju-ical"
    ),
    AcknowledgementInfo(
        project = "zju-ical-py",
        contributors = "Xecades 及其对应贡献者",
        note = "浙大课表抓取解析与 iCal 转换 Python 实现参考",
        url = "https://github.com/Xecades/zju-ical-py"
    ),
    AcknowledgementInfo(
        project = "心知天气 (Seniverse)",
        contributors = "心知天气团队 (Seniverse)",
        // 只说天气：空气质量那项走的是 Open-Meteo（心知的 air/now.json 要单独
        // 开通产品权限，这把 key 没有，返回 AP010002）。
        // 署名写多了就是把没用的数据也算进来。
        note = "提供天气数据服务支持（由 GitHub Actions 定时同步为静态托管文件）",
        url = "https://www.seniverse.com"
    ),
    AcknowledgementInfo(
        project = "Open-Meteo",
        contributors = "Open-Meteo 团队；空气质量由哥白尼大气监测服务（CAMS）提供",
        // CC BY 4.0 的署名义务，必须带作者、许可协议、以及「数据可能有误」的免责。
        // 这一条不是客气话：删掉它就是把别人的数据拿来用而不署名。
        note = "提供空气质量原始污染物浓度与美国 EPA AQI，按 CC BY 4.0 授权" +
            "（creativecommons.org/licenses/by/4.0/）；中国国标 AQI 由本应用按 HJ 633-2012" +
            "从这些浓度换算。数据由数值模式生成，可能与地面实测存在偏差，不作为健康决策依据",
        url = "https://open-meteo.com"
    ),
    AcknowledgementInfo(
        project = "破晓壁纸 (PXLab)",
        contributors = "PXLab 破晓团队",
        // 只取了渐变的**角度与配色**并自己用 Compose 画，没有分发对方的位图，
        // 所以这里是「配色参考」而不是「提供了壁纸资源」。
        note = "预设壁纸的渐变角度与配色参考；壁纸由本项目自行绘制，未使用其位图",
        url = "https://pxlab.cn/index.html"
    )
)

/**
 * 设置页里「图标 + 标题 + 行尾引导」的一行。
 *
 * 抽出来是因为这类入口出现过好几个（导入导出 / 校历 / 以后的更多），
 * 原先每处都摊开写一遍 50 行卡片结构，加一个入口就得复制一次——
 * 复制出来的那份迟早和别人长得不一样。
 *
 * **没有副标题**：这一页的每一项都只留标题 + 引导。要说清楚的事在点进去的那一层说，
 * 门口这一行只负责「点进去」这件事。
 *
 * **行尾是「>」不是按钮**：整张卡是唯一的落点，那个尖括号只负责说
 * 「点进去会开一层」——它是提示，不是第二颗按钮。
 */
@Composable
private fun SettingsActionCard(
    icon: ImageVector,
    title: String,
    actionTestTag: String,
    /**
     * 这一行可不可点。禁用时**整张卡也不可点** —— 否则用户按标题照样触发了动作，
     * 而行尾看上去是淡的，两边对不上。
     */
    actionEnabled: Boolean = true,
    /**
     * 行尾的临时状态文字（如「检查中…」）。
     *
     * 给了就不画那个「>」：这一行此刻点不得，却得让用户知道它**正在**做什么 ——
     * 按钮没了，状态提示得留下。
     */
    statusText: String? = null,
    onAction: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        // 整张卡可点：行尾那个「>」只是方向提示，行为只有一个。
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = actionEnabled, onClick = onAction)
            .testTag(actionTestTag)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .glassSurface(shape = RoundedCornerShape(18.dp))
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SettingsIconTile(icon)
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            if (statusText != null) {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                SettingsChevron(enabled = actionEnabled)
            }
        }
    }
}

/**
 * 行首那颗 40dp 的圆角图标方块。
 *
 * 底色用 [LimeGreenTileFaint] 而不是主题的 primaryContainer：这一列方块一屏七八个，
 * 底色深一点就连成一片绿，把用户的注意力全吃掉了 —— 方块只是「这是一行入口」的
 * 分组标记，不是内容本身。
 */
@Composable
private fun SettingsIconTile(icon: ImageVector, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(LimeGreenTileFaint),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(22.dp)
        )
    }
}

/**
 * 行尾的「>」：**线性画出来**的点击引导。
 *
 * 为什么不用现成的图标：这个 App 里的记号都是自己画的（待办卡那道勾就是
 * Canvas + Path 画的），跟着同一套画法走，线宽与圆角才是同一口气。
 *
 * 为什么用次级墨色而不是主题绿：绿在这个 App 里是**状态**的颜色
 * （开关打开、当前选中）。拿它画一个纯装饰的箭头，等于把状态的颜色稀释了。
 */
@Composable
private fun SettingsChevron(enabled: Boolean, modifier: Modifier = Modifier) {
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(
        modifier = modifier
            .size(10.dp, 15.dp)
            .alpha(if (enabled) 1f else 0.35f)
    ) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.28f, h * 0.16f)
            lineTo(w * 0.66f, h * 0.5f)
            lineTo(w * 0.28f, h * 0.84f)
        }
        drawPath(
            path = path,
            color = ink,
            style = Stroke(
                width = 1.5.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )
    }
}

/**
 * 空气质量标准那一项：标题 + 行尾**两颗按钮**。
 *
 * **为什么不是开关**：一颗开关只说「开 / 关」，用户得靠标题去猜「开」的是哪一套
 * —— 原来那行标题就被迫写成「空气质量用美国 AQI」，一句要绕的句子。这里把两套标准
 * 的名字直接写成两颗按钮（左「中国」、右「美国」），当前是哪一颗绿着，一眼看见，
 * 不用读句子。
 *
 * **和别的行一样摆成一行**：图标 + 标题 + 右边一个控件。
 * 这一页的每一行都是这个骨架，单独给空气质量多摆一段，整页的节奏就断了。
 */
@Composable
private fun SettingsAqiCard(
    icon: ImageVector,
    title: String,
    standard: AqiStandard,
    onStandardChange: (AqiStandard) -> Unit
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .glassSurface(shape = RoundedCornerShape(18.dp))
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SettingsIconTile(icon)
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    // 极端字号下标题可以截断：右边那颗槽是固定宽的，
                    // 标题让位才是对的 —— 槽一挤，两格里的字就认不出了。
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            AqiStandardButtons(
                standard = standard,
                onStandardChange = onStandardChange
            )
        }
    }
}
/** 每档的最小宽度。 */
private val AqiSegmentMinWidth = 48.dp

/** 每档里文字左右的留白。 */
private val AqiSegmentPadH = 11.dp

/** 外框高度（基准字号下）。 */
private val AqiFrameHeight = 30.dp

/** 外框内边距，同时也是两档之间的缝：两档挨着，没有额外的间距。 */
private val AqiFrameInset = 3.dp

/** 外框的圆角。 */
private val AqiFrameRadius = 10.dp

/** 选中块（绿的那一档）的圆角。 */
private val AqiSelectedRadius = 8.dp

/**
 * 选中块滑过去的时长。
 *
 * 220ms 与课表页「周课表 ↔ 列表」那个分段控件**用的是同一档**：
 * 短于 ~150ms 会读成瞬间跳变，长于 ~300ms 就变成需要等的动画了。
 * 字色走同一条曲线，于是绿块走到哪、字就亮到哪，两者是同一件事。
 */
private const val AqiSlideMillis = 220

/**
 * 两条 AQI 标准的**一个连体分段控件**：左 [AqiStandard.CN]「中国」，
 * 右 [AqiStandard.US]「美国」，两档**左右联通**，共一个外框。
 *
 * **为什么是连体的**：这是**同一个东西的两档** —— 用哪一套 AQI 标准，
 * 不是两件不同的事，而是同一个开关的两头。课表页的「周课表 ↔ 列表」
 * 是同一套写法的另一个例子（见 `ScheduleScreen.FlatSegmentedRow`）：
 * 一个外框、两档并排、当前那档绿着。分成两颗独立的按钮反而会说成
 * 「两件事」，用户会犹豫该点哪个。
 *
 * **为什么不滑**：滑块那种「选中块飞过去」是给**换视图**用的（换页、换视图）；
 * 这里是**二选一**，点哪档就是哪档，选中块原地换位置就够了 ——
 * 多一段滑行只是表演，而且滑到一半时那个中间态本身就没有意义。
 *
 * **选中态是绿平涂 + 深墨字**（绿是状态色，字必须用深的那支，
 * 见 Color.kt 的全局约定），没选中那档是外框底色透出来 + 次级墨色。
 */
@Composable
private fun AqiStandardButtons(
    standard: AqiStandard,
    onStandardChange: (AqiStandard) -> Unit,
    modifier: Modifier = Modifier
) {
    val isUs = standard == AqiStandard.US
    val segmentWidth = aqiSegmentWidth()
    Box(
        modifier = modifier
            .testTag("settings_aqi_buttons")
            // 外框 = 两档 + 上下左右各一圈内边距。
            // **两档之间不额外留缝**（课表页那个分段控件中间有 3dp 缝，
            // 是因为它的选中块要飞过去，得有地方落笔）；
            // 这里选中块滑过去，两档直接挨着 —— 这才是「左右联通」。
            .width(segmentWidth * 2 + AqiFrameInset * 2)
            .height(aqiFrameHeight())
            .clip(RoundedCornerShape(AqiFrameRadius))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(AqiFrameInset)
    ) {
        // 选中块：绿底，滑到当前那一档下面。
        //
        // **和课表页那套是同一个动作**（见 ScheduleScreen.FlatSegmentedRow）：
        // 两档等宽，所以位移 = 段宽 × 序号，不用测宽、不用读布局反推位置。
        val indicatorOffset by animateDpAsState(
            targetValue = if (isUs) segmentWidth else 0.dp,
            animationSpec = tween(AqiSlideMillis, easing = FastOutSlowInEasing),
            label = "aqi_indicator"
        )
        Box(
            modifier = Modifier
                .offset(x = indicatorOffset)
                .width(segmentWidth)
                .fillMaxHeight()
                .clip(RoundedCornerShape(AqiSelectedRadius))
                .background(MaterialTheme.colorScheme.primary)
        )

        // 两档排在选中块之后画：同父级里先画的在下，正好转绿块垫在文字底下。
        Row(modifier = Modifier.fillMaxSize()) {
            AqiSegment(
                text = "中国",
                selected = !isUs,
                onClick = { onStandardChange(AqiStandard.CN) },
                segmentWidth = segmentWidth,
                testTag = "settings_aqi_cn_label"
            )
            AqiSegment(
                text = "美国",
                selected = isUs,
                onClick = { onStandardChange(AqiStandard.US) },
                segmentWidth = segmentWidth,
                testTag = "settings_aqi_us_label"
            )
        }
    }
}

/**
 * 每档的宽度：最宽那个标签 + 左右留白。
 *
 * **两档必须等宽**：连体控件要左右对称，而且等宽才能保证「换档时
 * 整个控件的宽度不动」。宽度不等的话，选中态一换，两档的宽度就跟着换，
 * 控件宽度会抖一下。
 *
 * **量文字而不是按系数估**：中文回退字体的字宽比拉丁字体大得多，
 * 拿字号乘系数估会在大字号那一档正好估小。这里直接量一次（见
 * [measureTextSize]），量多少是多少。
 */
@Composable
private fun aqiSegmentWidth(): Dp {
    val style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
    val labelWidth = maxOf(
        measureTextSize("中国", style).width,
        measureTextSize("美国", style).width
    )
    return maxOf(AqiSegmentMinWidth, labelWidth + AqiSegmentPadH * 2)
}

/**
 * 外框实际高度：**装得下就是 30dp，装不下就按文字长高**。
 *
 * 段里那行中文的行高约 1.5em（比拉丁字体大得多），系统字号 1.5 时就有 27dp，
 * 而外框里那格只有 30 − 3×2 = 24dp —— 文字下半截会被切掉。
 * 这里实测文字高度再定外框高（见 [measureTextHeight]），不拿字号乘系数估。
 */
@Composable
private fun aqiFrameHeight(): Dp {
    val style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
    val labelHeight = measureTextHeight(text = "中国", style = style)
    return maxOf(AqiFrameHeight, labelHeight + AqiFrameInset * 2)
}

/**
 * 连体控件里的一档。
 *
 * **不画自己的底**：底由外层那块滑动的绿块统一表示（跟课表页的
 * `FlatSegment` 同一套写法）。两个独立背景在切换瞬间是「一格消失、
 * 另一格出现」，读起来像刷新；一块滑过去则是同一个控件换了位置，
 * 用户能直接看出「这两个是同一个东西的两档」。
 *
 * **字色跟着走一段**：绿块要滑 220ms，字色若瞬间换，会出现
 * 「绿块还在路上、字已经黑了」的一瞬不协调。
 */
@Composable
private fun RowScope.AqiSegment(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    segmentWidth: Dp,
    testTag: String
) {
    val contentColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = tween(AqiSlideMillis, easing = FastOutSlowInEasing),
        label = "aqi_segment_color"
    )
    Box(
        modifier = Modifier
            .width(segmentWidth)
            .fillMaxHeight()
            .clip(RoundedCornerShape(AqiSelectedRadius))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .testTag(testTag),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
            ),
            color = contentColor,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * 设置页里「图标 + 标题 + 一颗开关」的一行。
 *
 * 形状照 [SettingsActionCard]：左边同一个图标方块 + 标题，右边换成一颗开关。
 *
 * **整行不可点**：改状态只有那颗开关一个落点。把整行也做成可点会多出第二个落点
 * （按标题算「改了」，按空白处又像没反应），与「一行只留一个落点」的约定冲突
 * （抽屉里那个开关行是同一条理由）。
 *
 * **同样没有副标题**：这一页的规矩是门口只说「这一项叫什么」，
 * 要说清楚的事在点进去的那一层说。
 */
@Composable
private fun SettingsToggleCard(
    icon: ImageVector,
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .glassSurface(shape = RoundedCornerShape(18.dp))
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SettingsIconTile(icon)
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            CompactSwitch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                modifier = Modifier.testTag(testTag)
            )
        }
    }
}
