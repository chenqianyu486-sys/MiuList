package com.example.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.ImportExport
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Wallpaper
import com.example.data.model.WallpaperPresets
import com.example.data.model.WallpaperType
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.components.IslandBottomInset
import com.example.ui.theme.CardBlockGap
import com.example.ui.theme.PageHInset
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.glassSurface
import com.example.ui.viewmodel.MainUiState

/**
 * 设置页。
 *
 * **只剩两件事：课表导入/导出、演示数据。**
 *
 * 原来这里是六个入口（账号同步 / 课程联动规则 / 待办生成规则 / 导入导出 /
 * 本学期校历 / 课程管理列表），其中**五个都已经搬到顶栏展开的「更多」页**：
 * 账号与学期是「我现在是什么身份」的读数，规则和课程管理是日常真正常改的东西，
 * 它们都属于「打开一次、看完就走」的低频入口，放在一个要滚很久的设置页里
 * 只是让人多滚几屏。留在这里的只剩两件不常动、动了要慎重的数据类操作。
 *
 * 同一个入口在两个地方都能点，用户会犹豫该点哪个——所以搬走的同时，
 * 这一页里对应的入口**全部删除**，不做「这里也留一个」的重复。
 */
@Composable
fun SettingsScreen(
    state: MainUiState,
    onOpenTransferDialog: () -> Unit,
    onOpenCategoryManageDialog: () -> Unit,
    onOpenWallpaperDialog: () -> Unit = {},
    onResetDemoData: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = PageHInset),
        verticalArrangement = Arrangement.spacedBy(CardBlockGap),
        // 底部给浮岛导航留出收尾空间，内容本身仍会滚到岛底下被毛玻璃糊住
        contentPadding = PaddingValues(bottom = IslandBottomInset)
    ) {
        item {
            Spacer(modifier = Modifier.height(4.dp))
        }

        item {
            SettingsActionCard(
                icon = Icons.Outlined.ImportExport,
                title = "课表导入 / 导出",
                subtitle = "JSON 文件 · 课程与作息一起走",
                actionLabel = "管理",
                actionTestTag = "open_transfer_button",
                onAction = onOpenTransferDialog
            )
        }

        item {
            val customCount = state.customTaskTypes.size
            val subtitle = if (customCount > 0) {
                "4 个自带分类 · $customCount 个自定义分类"
            } else {
                "4 个自带分类 · 暂无自定义分类"
            }
            SettingsActionCard(
                icon = Icons.Outlined.Category,
                title = "待办分类管理",
                subtitle = subtitle,
                actionLabel = "管理",
                actionTestTag = "open_task_category_dialog_button",
                onAction = onOpenCategoryManageDialog
            )
        }

        item {
            val wallpaperSubtitle = when (state.wallpaperConfig.type) {
                WallpaperType.DEFAULT -> "默认壁纸 · ${WallpaperPresets.DEFAULT.name}"
                WallpaperType.PRESET -> {
                    val preset = WallpaperPresets.find(state.wallpaperConfig.presetId)
                    "预设壁纸 · ${preset?.name ?: "精选"}"
                }
                WallpaperType.CUSTOM -> "自定义图片 · 已应用"
            }
            SettingsActionCard(
                icon = Icons.Outlined.Wallpaper,
                title = "自定义壁纸",
                subtitle = wallpaperSubtitle,
                actionLabel = "更换",
                actionTestTag = "open_wallpaper_button",
                onAction = onOpenWallpaperDialog
            )
        }

        item {
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
                    Text(
                        text = "演示数据",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "恢复官方示例课表与待办，含多次排课、长实验课与晚课。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = onResetDemoData,
                        shape = RoundedCornerShape(10.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        modifier = Modifier.testTag("reset_demo_data_button")
                    ) {
                        // 主题绿只能当填充色，当图标前景压在近白卡面上只有 1.4:1。
                        // 图标跟着下面这行文字走。
                        Icon(Icons.Outlined.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurface)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("恢复示例数据", color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
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
                            text = "GPL-3.0",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "基于课程表的自动化轻量作业待办软件，提供分类清单与日程表双重视图。遵循 GNU GPLv3 协议开源，许可证继承自 Celechron。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
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
        note = "提供实时天气与空气质量数据服务支持（GitHub Actions 定时同步静态托管）",
        url = "https://www.seniverse.com"
    ),
    AcknowledgementInfo(
        project = "破晓壁纸 (PXLab)",
        contributors = "PXLab 破晓团队",
        note = "提供精选高质量壁纸资源、设计美学与透光质感参考",
        url = "https://pxlab.cn/index.html"
    )
)

/**
 * 设置页里「图标 + 标题 + 副标题 + 一个按钮」的一行。
 *
 * 抽出来是因为这类入口出现过好几个（导入导出 / 校历 / 以后的更多），
 * 原先每处都摊开写一遍 50 行卡片结构，加一个入口就得复制一次——
 * 复制出来的那份迟早和别人长得不一样。
 */
@Composable
private fun SettingsActionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    actionLabel: String,
    actionTestTag: String,
    onAction: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        // 整张卡可点，落点和右边那个按钮一致，不存在两个不同的行为
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onAction)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .glassSurface(shape = RoundedCornerShape(18.dp))
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        // 用 TextSecondary 而不是全局的 onSurfaceVariant：后者是
                        // TextMuted #78827B，压在这张卡面上实测只有 3.74:1，
                        // 11sp 小字过不了 AA。TextSecondary #4A524D 实测约 7.6:1。
                        // 只改这一处，不动全局 token（那会牵动所有页面）。
                        color = TextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Button(
                onClick = onAction,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                modifier = Modifier.testTag(actionTestTag)
            ) {
                Text(actionLabel, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
            }
        }
    }
}
