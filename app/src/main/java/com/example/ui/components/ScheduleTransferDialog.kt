package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.example.data.transfer.ImportPreview
import com.example.data.transfer.ImportReport
import com.example.data.transfer.ImportStrategy
import com.example.data.validate.ImportIssue
import com.example.ui.theme.TextSecondary

/**
 * 数据导入 / 导出。**整机快照**，用于换机与多端之间搬运数据。
 *
 * 交互沿用 App 既有的「先预览、再确认」惯例（和 QuickGenerateDialog 同构）：
 * 选策略 → 选文件 → **看清楚会发生什么** → 才允许落库。
 * 绝不做「点了直接导入、出了问题再回滚」。
 *
 * ## 这里不再有浙大登录
 *
 * 原来这个弹层里嵌了一整块「学号 + 密码 + 获取课表」，那是浙大同步，
 * 而它已经有自己的两处入口：「更多」抽屉的浙大学号与总览页翻面卡的快捷登录。
 * 三处入口做同一件事，用户要猜该走哪条，职责也说不清。
 *
 * 两条链路现在分工明确，**不重叠**：
 * - **浙大同步**：以服务端为准刷新**课表**。服务端不知道你的待办勾没勾、打没打星标。
 * - **本弹层**：把本机的**全部状态**（课表 + 待办 + 自定义分类 + 生成规则）
 *   搬到另一台设备，或者把另一台设备上的改动取回来。
 */
@Composable
fun ScheduleTransferDialog(
    strategy: ImportStrategy,
    preview: ImportPreview?,
    issues: List<ImportIssue>,
    errorMessage: String?,
    isBusy: Boolean,
    report: ImportReport?,
    onStrategyChange: (ImportStrategy) -> Unit,
    onExport: (android.net.Uri) -> Unit,
    onImportUri: (android.net.Uri) -> Unit,
    onCommit: () -> Unit,
    onDismissReport: () -> Unit,
    onDismiss: () -> Unit
) {
    val createDoc = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let(onExport) }

    val openDoc = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(onImportUri) }

    GlassOverlayHost(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "数据导入 / 导出",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            // 弹层正文默认继承 onSurfaceVariant（TextMuted #78827B），压在卡面上实测
            // 只有 3.74:1 —— 11–12sp 的小字过不了 AA，而这里恰好是用户**必须读懂**
            // 的内容（选了「整机同步」会覆盖什么）。统一抬到 TextSecondary（约 7.6:1）。
            // 显式指定颜色的文字（错误红、标题、chips）不受影响。
            CompositionLocalProvider(LocalContentColor provides TextSecondary) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (isBusy) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.width(18.dp).height(18.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("处理中…", style = MaterialTheme.typography.bodySmall)
                    }
                }

                errorMessage?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { createDoc.launch("miulist-backup.json") },
                        enabled = !isBusy,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.width(16.dp).height(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("导出", fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = {
                            openDoc.launch(
                                arrayOf("application/json", "text/plain", "application/octet-stream", "*/*")
                            )
                        },
                        enabled = !isBusy,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Outlined.Upload, contentDescription = null, modifier = Modifier.width(16.dp).height(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("导入", fontSize = 12.sp)
                    }
                }

                Text(
                    text = "导出的文件包含课程、校历、待办、自定义分类与生成规则，不含浙大学号与密码。" +
                        "课表随时可以用浙大同步刷新，待办只能靠这个文件带走。",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )

                Text(
                    text = "冲突处理方式（每次导入前选择）",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ImportStrategy.entries.forEach { s ->
                        FilterChip(
                            selected = strategy == s,
                            onClick = { onStrategyChange(s) },
                            enabled = !isBusy && preview == null,
                            label = { Text(s.label, fontSize = 11.sp) },
                            shape = RoundedCornerShape(10.dp),
                            colors = micaChipColors(),
                            border = micaChipBorder(strategy == s)
                        )
                    }
                }
                Text(
                    text = strategy.description,
                    style = MaterialTheme.typography.labelSmall,
                    // 这段是用户真正要读的内容（选了「整机同步」会覆盖什么），
                    // 用 onSurfaceVariant（TextMuted）实测只有 3.74:1，不过 AA。
                    color = TextSecondary
                )

                // 「两台设备同时离线各改一条」的代价必须说清楚。
                // 「同步」这个词听起来像永不冲突，不说就等于让用户替我们兜底。
                if (strategy == ImportStrategy.SNAPSHOT) {
                    Text(
                        text = "同一条待办两台设备都改过时，较晚的那次胜出，另一台的改动会被覆盖。",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }

                preview?.let { p -> PreviewBlock(p, issues) }

                report?.let { r ->
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            text = "导入完成",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        )
                        Text(
                            text = "新增 ${r.inserted} 门 · 更新 ${r.updated} 门" +
                                (if (r.removed > 0) " · 清空 ${r.removed}" else ""),
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (r.semesterUpdated) Text("学期校历已更新", style = MaterialTheme.typography.bodySmall)
                        if (r.timetableUpdated) Text("作息表已更新", style = MaterialTheme.typography.bodySmall)
                        if (r.noClassDayCount > 0) Text("停课 ${r.noClassDayCount} 天", style = MaterialTheme.typography.bodySmall)
                        if (r.makeupDayCount > 0) Text("补课 ${r.makeupDayCount} 天", style = MaterialTheme.typography.bodySmall)
                        if (r.taskMerge.added > 0 || r.taskMerge.overwritten > 0 || r.taskMerge.keptLocal > 0) {
                            Text(
                                "待办：新增 ${r.taskMerge.added} · 覆盖 ${r.taskMerge.overwritten} · " +
                                    "保留本机 ${r.taskMerge.keptLocal}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        if (r.taskTypesImported > 0) {
                            Text("自定义分类 ${r.taskTypesImported} 个已同步", style = MaterialTheme.typography.bodySmall)
                        }
                        if (r.taskRulesImported > 0) {
                            Text("生成规则 ${r.taskRulesImported} 条已同步", style = MaterialTheme.typography.bodySmall)
                        }
                        if (r.taskMerge.losingCourse > 0) {
                            Text(
                                "${r.taskMerge.losingCourse} 条待办的课程本机没有，已移入「未关联课程」",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        if (r.tasksRelinked > 0) {
                            Text("待办保持关联 ${r.tasksRelinked} 条", style = MaterialTheme.typography.bodySmall)
                        }
                        if (r.tasksUnlinked > 0) {
                            Text(
                                "${r.tasksUnlinked} 条待办已移入「未关联课程」",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
            }
        },
        confirmButton = {
            when {
                report != null -> {
                    Button(
                        onClick = onDismissReport,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) { Text("完成", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold) }
                }

                preview != null -> {
                    Button(
                        onClick = onCommit,
                        enabled = !isBusy,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) { Text("确认导入", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold) }
                }

                else -> {
                    // 空闲态只有这一个落点。原先这里和 dismissButton 一起渲染出
                    // 「取消 · 关闭」两个同样的文字按钮，按哪个都只是关掉弹层 ——
                    // 同一个动作画两遍，用户还得先猜两者有什么区别。
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
            }
        },
        dismissButton = {
            // 「取消」只在预览态有意义：退回空闲态、不落库。
            // 报告态没有可退的地方（已经导完了），只留「完成」。
            if (preview != null && report == null) {
                TextButton(onClick = onDismissReport) { Text("取消") }
            }
        }
    )
}

@Composable
private fun PreviewBlock(preview: ImportPreview, issues: List<ImportIssue>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 260.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = "导入预览",
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
        )
        Text(
            text = "文件 ${preview.fileCourseCount} 门 → 新增 ${preview.newCourses.size} · " +
                "更新 ${preview.updatedCourses.size} · 保持 ${preview.unchangedCount}",
            style = MaterialTheme.typography.bodySmall
        )
        if (preview.strategy == ImportStrategy.REPLACE) {
            Text(
                text = buildString {
                    append("注意：将清空现有 ${preview.existingCourseCount} 门课程并整份导入，课程不可撤销。")
                    append("现有 ${preview.existingTaskCount} 条待办不会被删除：")
                    append("${preview.tasksKeepingCourse} 条继续挂在原课程下")
                    if (preview.tasksLosingCourse > 0) {
                        append("，${preview.tasksLosingCourse} 条因课程不在新表里而移入「未关联课程」")
                    }
                    append("。")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        if (preview.semesterChanged) {
            Text("学期校历：${preview.semesterSummary ?: "将更新"}", style = MaterialTheme.typography.bodySmall)
        }
        if (preview.timetableChanged) {
            Text("作息表将被文件中的设置覆盖", style = MaterialTheme.typography.bodySmall)
        }
        if (preview.noClassDayCount > 0) {
            Text("停课 ${preview.noClassDayCount} 天", style = MaterialTheme.typography.bodySmall)
        }
        if (preview.makeupDayCount > 0) {
            Text("补课 ${preview.makeupDayCount} 天", style = MaterialTheme.typography.bodySmall)
        }
        if (preview.updatedCourses.isNotEmpty()) {
            Text("将被更新：", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
            preview.updatedCourses.take(8).forEach { (name, _) ->
                Text("· $name", style = MaterialTheme.typography.labelSmall)
            }
            if (preview.updatedCourses.size > 8) {
                Text("… 另有 ${preview.updatedCourses.size - 8} 门", style = MaterialTheme.typography.labelSmall)
            }
        }

        // 整机同步才碰用户数据，课表导入（浙大同步走的那条）不显示这一段。
        if (preview.touchesUserData) {
            Text(
                text = "待办（文件 ${preview.fileTaskCount} 条）",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.padding(top = 8.dp)
            )
            Text(
                text = "新增 ${preview.taskMerge.added} · 用文件覆盖 ${preview.taskMerge.overwritten} · " +
                    "保留本机较新 ${preview.taskMerge.keptLocal}",
                style = MaterialTheme.typography.bodySmall
            )
            if (preview.taskMerge.losingCourse > 0) {
                Text(
                    "${preview.taskMerge.losingCourse} 条待办的课程本机没有，会移入「未关联课程」（待办本身不丢）",
                    style = MaterialTheme.typography.labelSmall
                )
            }
            if (preview.fileTaskTypeCount > 0 || preview.fileTaskRuleCount > 0) {
                Text(
                    text = "自定义分类 ${preview.fileTaskTypeCount} 个" +
                        "（覆盖 ${preview.taskTypesOverwritten}）· " +
                        "生成规则 ${preview.fileTaskRuleCount} 条" +
                        "（覆盖 ${preview.taskRulesOverwritten}）",
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }

        if (issues.isNotEmpty()) {
            Text("问题 ${issues.size} 条：", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
            issues.take(10).forEach {
                Text("· ${it.scope}：${it.detail}", style = MaterialTheme.typography.labelSmall)
            }
            if (issues.size > 10) Text("… 另有 ${issues.size - 10} 条", style = MaterialTheme.typography.labelSmall)
        }
    }
}
