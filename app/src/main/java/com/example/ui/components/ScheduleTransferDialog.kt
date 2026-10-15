package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.example.data.transfer.ImportPreview
import com.example.data.transfer.ImportReport
import com.example.data.transfer.ImportStrategy
import com.example.data.validate.ImportIssue
import com.example.ui.theme.ChipSurfaceNeutral
import com.example.ui.theme.TextSecondary
import com.example.ui.viewmodel.ImportSource
import com.example.ui.viewmodel.ZjuTermOption

/**
 * 课表导入 / 导出。
 *
 * 交互沿用 App 既有的「先预览、再确认」惯例（和 QuickGenerateDialog 同构）：
 * 选策略 → 选文件 → **看清楚会发生什么** → 才允许落库。
 * 绝不做「点了直接导入、出了问题再回滚」。
 */
@Composable
fun ScheduleTransferDialog(
    strategy: ImportStrategy,
    importSource: ImportSource,
    zjuTermOptions: List<ZjuTermOption>,
    zjuError: String?,
    isZjuRunning: Boolean,
    preview: ImportPreview?,
    issues: List<ImportIssue>,
    errorMessage: String?,
    isBusy: Boolean,
    report: ImportReport?,
    onSourceChange: (ImportSource) -> Unit,
    onZjuImport: (studentId: String, password: String, option: ZjuTermOption, remember: Boolean) -> Unit,
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

    var studentId by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var rememberCredentials by remember { mutableStateOf(true) }
    var selectedTermKey by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "课表导入 / 导出",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            // 弹层正文默认继承 onSurfaceVariant（TextMuted #78827B），压在卡面上实测
            // 只有 3.74:1 —— 11–12sp 的小字过不了 AA，而这里恰好是用户**必须读懂**
            // 的内容（选了「整表替换」会删掉什么）。统一抬到 TextSecondary（约 7.6:1）。
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

                // ---- 来源：本地文件 / 浙大教务网 ----
                Text(
                    text = "数据来源",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ImportSource.entries.forEach { s ->
                        FilterChip(
                            selected = importSource == s,
                            onClick = { onSourceChange(s) },
                            // 有预览之后锁住：中途换来源会让「眼前这份预览」失去意义
                            enabled = !isBusy && !isZjuRunning && preview == null && report == null,
                            label = { Text(s.label, fontSize = 11.sp) },
                            shape = RoundedCornerShape(10.dp),
                            colors = micaChipColors(),
                            border = micaChipBorder(importSource == s)
                        )
                    }
                }

                if (importSource == ImportSource.ZJU) {
                    val selected = zjuTermOptions.firstOrNull { it.key == selectedTermKey }
                        ?: zjuTermOptions.firstOrNull()
                    OutlinedTextField(
                        value = studentId,
                        onValueChange = { studentId = it },
                        label = { Text("学号", fontSize = 12.sp) },
                        singleLine = true,
                        enabled = !isZjuRunning,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("密码", fontSize = 12.sp) },
                        singleLine = true,
                        enabled = !isZjuRunning,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )

                    // 学期下拉：选项来自 zju-ical 的公共校历配置，抓课时也要靠它算总周数
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(zjuTermOptions) { option ->
                            val isSelected = option == selected
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        if (isSelected) MaterialTheme.colorScheme.primary
                                        else ChipSurfaceNeutral
                                    )
                                    .clickable { selectedTermKey = option.key }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = option.label,
                                    fontSize = 11.5.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    zjuError?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { rememberCredentials = !rememberCredentials }
                    ) {
                        Checkbox(
                            checked = rememberCredentials,
                            onCheckedChange = { rememberCredentials = it },
                            colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "安全记住学号密码 (Android KeyStore 硬件加密)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Text(
                        text = "通过设备硬件 Keystore 加密存储，不提交版本库。断网直接读取本地缓存。",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )

                    Button(
                        onClick = {
                            selected?.let { onZjuImport(studentId, password, it, rememberCredentials) }
                        },
                        enabled = !isZjuRunning && selected != null && studentId.isNotBlank() && password.isNotBlank(),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth().testTag("zju_fetch_button")
                    ) {
                        Text(
                            text = if (isZjuRunning) "获取中…" else "获取课表",
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

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
                    // 这段是用户真正要读的内容（选了「整表替换」会删掉什么），
                    // 用 onSurfaceVariant（TextMuted）实测只有 3.74:1，不过 AA。
                    color = TextSecondary
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { createDoc.launch("miulist-schedule.json") },
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
        if (issues.isNotEmpty()) {
            Text("问题 ${issues.size} 条：", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
            issues.take(10).forEach {
                Text("· ${it.scope}：${it.detail}", style = MaterialTheme.typography.labelSmall)
            }
            if (issues.size > 10) Text("… 另有 ${issues.size - 10} 条", style = MaterialTheme.typography.labelSmall)
        }
    }
}
