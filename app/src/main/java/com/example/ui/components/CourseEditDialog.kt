package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.CourseEntity
import com.example.data.local.CourseSlotEntity
import com.example.data.local.CourseWithSlots

val COURSE_PALETTE = listOf(
    "#FCA5A5", // 粉彩红 (Pastel Red)
    "#F9A8D4", // 粉彩粉 (Pastel Pink)
    "#86EFAC", // 粉彩绿 (Pastel Green)
    "#208E9E", // 粉彩青 (Pastel Cyan)
    "#93C5FD", // 粉彩蓝 (Pastel Blue)
    "#D8B4FE", // 粉彩紫 (Pastel Purple)
    "#FDBA74", // 粉彩橙 (Pastel Orange)
    "#FDE047"  // 粉彩黄 (Pastel Yellow)
)

val COURSE_CATEGORIES = listOf(
    "专业必修",
    "公共基础",
    "实验实践",
    "通识公共",
    "专业选修"
)

val DAYS_OF_WEEK = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

data class SlotDraft(
    var dayOfWeek: Int = 1,
    var startPeriod: Int = 1,
    var endPeriod: Int = 2,
    var classroom: String = ""
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseEditDialog(
    initialCourseWithSlots: CourseWithSlots?,
    onDismiss: () -> Unit,
    onSave: (CourseEntity, List<CourseSlotEntity>) -> Unit
) {
    val initialCourse = initialCourseWithSlots?.course
    val initialSlots = initialCourseWithSlots?.slots ?: emptyList()

    var name by remember { mutableStateOf(initialCourse?.name ?: "") }
    var category by remember { mutableStateOf(initialCourse?.category ?: "专业必修") }
    var teacher by remember { mutableStateOf(initialCourse?.teacher ?: "") }
    var classroom by remember { mutableStateOf(initialCourse?.classroom ?: "") }
    var colorHex by remember { mutableStateOf(initialCourse?.colorHex ?: "#93C5FD") }

    // Multi-slot drafts
    val slotDrafts = remember {
        mutableStateListOf<SlotDraft>().apply {
            if (initialSlots.isNotEmpty()) {
                initialSlots.forEach { s ->
                    add(SlotDraft(s.dayOfWeek, s.startPeriod, s.endPeriod, s.classroom))
                }
            } else {
                // Default slot: 周一 第1-2节
                add(SlotDraft(dayOfWeek = 1, startPeriod = 1, endPeriod = 2))
            }
        }
    }

    // Automation rules
    var autoPreStudy by remember { mutableStateOf(initialCourse?.autoPreStudy ?: true) }
    var autoPostHomework by remember { mutableStateOf(initialCourse?.autoPostHomework ?: true) }
    var autoLabReport by remember { mutableStateOf(initialCourse?.autoLabReport ?: false) }
    var defaultPostDays by remember { mutableIntStateOf(initialCourse?.defaultPostDueDays ?: 3) }

    val scrollState = rememberScrollState()

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(
                text = if (initialCourse == null) "添加课程" else "编辑课程",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Course Name
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("课程名称") },
                    placeholder = { Text("例如：高等数学、数据结构与算法") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("course_name_input"),
                    singleLine = true
                )

                // Category chips
                Text(
                    text = "课程类别",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    COURSE_CATEGORIES.forEach { cat ->
                        FilterChip(
                            selected = category == cat,
                            onClick = {
                                category = cat
                                if (cat == "实验实践") autoLabReport = true
                            },
                            label = { Text(cat, fontSize = 11.sp) }
                        )
                    }
                }

                // Teacher & Default Classroom
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = teacher,
                        onValueChange = { teacher = it },
                        label = { Text("任课教师") },
                        placeholder = { Text("王教授") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = classroom,
                        onValueChange = { classroom = it },
                        label = { Text("默认教室") },
                        placeholder = { Text("教二-301") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }

                // Color Theme Picker (8 Pastel Colors)
                Text(
                    text = "课程粉彩主题色 (8色)",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    COURSE_PALETTE.forEach { hex ->
                        val color = parseColor(hex)
                        val isSelected = colorHex.equals(hex, ignoreCase = true)
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(color)
                                .border(
                                    width = if (isSelected) 2.5.dp else 0.8.dp,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else Color(0xFFCBD5E1),
                                    shape = CircleShape
                                )
                                .clickable { colorHex = hex },
                            contentAlignment = Alignment.Center
                        ) {
                            if (isSelected) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(Color.White)
                                )
                            }
                        }
                    }
                }

                // Multi-slot Schedule Management (一周多次上课，自定义1-13节，如3-5节、11-13节)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.Schedule,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "上课时段 (${slotDrafts.size}个，支持一周多次/1-13节)",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        )
                    }

                    OutlinedButton(
                        onClick = {
                            slotDrafts.add(SlotDraft(dayOfWeek = 4, startPeriod = 3, endPeriod = 5))
                        },
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                        modifier = Modifier.testTag("add_slot_button")
                    ) {
                        Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(2.dp))
                        Text("添时段", fontSize = 11.sp)
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    slotDrafts.forEachIndexed { index, draft ->
                        Card(
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "时段 ${index + 1}: ${getDayName(draft.dayOfWeek)} ${formatPeriodRange(draft.startPeriod, draft.endPeriod)}",
                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.primary
                                    )

                                    if (slotDrafts.size > 1) {
                                        IconButton(
                                            onClick = { slotDrafts.removeAt(index) },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.DeleteOutline,
                                                contentDescription = "删除此时间段",
                                                tint = MaterialTheme.colorScheme.error,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(6.dp))

                                // Select Day of Week
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    DAYS_OF_WEEK.forEachIndexed { dIdx, dName ->
                                        val dayNum = dIdx + 1
                                        FilterChip(
                                            selected = draft.dayOfWeek == dayNum,
                                            onClick = {
                                                slotDrafts[index] = draft.copy(dayOfWeek = dayNum)
                                            },
                                            label = { Text(dName, fontSize = 10.sp) },
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                // Period range selection: Start Period (1..13) and End Period (start..13)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    // Start period dropdown
                                    var startExpanded by remember { mutableStateOf(false) }
                                    ExposedDropdownMenuBox(
                                        expanded = startExpanded,
                                        onExpandedChange = { startExpanded = it },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        OutlinedTextField(
                                            value = "第${draft.startPeriod}节",
                                            onValueChange = {},
                                            readOnly = true,
                                            label = { Text("起始节(1-13)", fontSize = 10.sp) },
                                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = startExpanded) },
                                            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable)
                                        )
                                        ExposedDropdownMenu(
                                            expanded = startExpanded,
                                            onDismissRequest = { startExpanded = false }
                                        ) {
                                            (1..13).forEach { p ->
                                                DropdownMenuItem(
                                                    text = { Text("第${p}节 (${ALL_PERIODS[p - 1].startTime})") },
                                                    onClick = {
                                                        val newEnd = draft.endPeriod.coerceAtLeast(p)
                                                        slotDrafts[index] = draft.copy(startPeriod = p, endPeriod = newEnd)
                                                        startExpanded = false
                                                    }
                                                )
                                            }
                                        }
                                    }

                                    // End period dropdown
                                    var endExpanded by remember { mutableStateOf(false) }
                                    ExposedDropdownMenuBox(
                                        expanded = endExpanded,
                                        onExpandedChange = { endExpanded = it },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        OutlinedTextField(
                                            value = "第${draft.endPeriod}节",
                                            onValueChange = {},
                                            readOnly = true,
                                            label = { Text("结束节(1-13)", fontSize = 10.sp) },
                                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = endExpanded) },
                                            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable)
                                        )
                                        ExposedDropdownMenu(
                                            expanded = endExpanded,
                                            onDismissRequest = { endExpanded = false }
                                        ) {
                                            (draft.startPeriod..13).forEach { p ->
                                                DropdownMenuItem(
                                                    text = { Text("第${p}节 (${ALL_PERIODS[p - 1].endTime})") },
                                                    onClick = {
                                                        slotDrafts[index] = draft.copy(endPeriod = p)
                                                        endExpanded = false
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(4.dp))

                                val (timeS, timeE) = getPeriodTimeRange(draft.startPeriod, draft.endPeriod)
                                Text(
                                    text = "具体时段时间: $timeS ~ $timeE (共计 ${draft.endPeriod - draft.startPeriod + 1} 节课)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                Spacer(modifier = Modifier.height(6.dp))

                                OutlinedTextField(
                                    value = draft.classroom,
                                    onValueChange = { slotDrafts[index] = draft.copy(classroom = it) },
                                    label = { Text("本时段特设教室 (留空则使用默认教室)") },
                                    placeholder = { Text(classroom.ifBlank { "如：信息楼304" }) },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )
                            }
                        }
                    }
                }

                // Automation Rules Section
                Text(
                    text = "联动待办规则",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { autoPreStudy = !autoPreStudy }
                    ) {
                        Checkbox(checked = autoPreStudy, onCheckedChange = { autoPreStudy = it })
                        Spacer(modifier = Modifier.width(6.dp))
                        Column {
                            Text("自动生成课前预习待办", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
                            Text("上课前自动生成核心知识点预备与课件查阅任务", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { autoPostHomework = !autoPostHomework }
                    ) {
                        Checkbox(checked = autoPostHomework, onCheckedChange = { autoPostHomework = it })
                        Spacer(modifier = Modifier.width(6.dp))
                        Column {
                            Text("自动生成课后作业待办", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
                            Text("课后自动排期生成作业习题与复习任务", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { autoLabReport = !autoLabReport }
                    ) {
                        Checkbox(checked = autoLabReport, onCheckedChange = { autoLabReport = it })
                        Spacer(modifier = Modifier.width(6.dp))
                        Column {
                            Text("自动生成实验/实践报告待办", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
                            Text("适用于实验课、工程实训等报告撰写", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isBlank()) return@Button
                    val courseToSave = (initialCourse ?: CourseEntity(
                        name = name.trim(),
                        category = category,
                        teacher = teacher.trim().ifBlank { "任课教师" },
                        classroom = classroom.trim().ifBlank { "教学楼" },
                        colorHex = colorHex,
                        autoPreStudy = autoPreStudy,
                        autoPostHomework = autoPostHomework,
                        autoLabReport = autoLabReport,
                        defaultPostDueDays = defaultPostDays
                    )).copy(
                        name = name.trim(),
                        category = category,
                        teacher = teacher.trim().ifBlank { "任课教师" },
                        classroom = classroom.trim().ifBlank { "教学楼" },
                        colorHex = colorHex,
                        autoPreStudy = autoPreStudy,
                        autoPostHomework = autoPostHomework,
                        autoLabReport = autoLabReport,
                        defaultPostDueDays = defaultPostDays
                    )

                    val slotsToSave = slotDrafts.map { draft ->
                        CourseSlotEntity(
                            courseId = courseToSave.id,
                            dayOfWeek = draft.dayOfWeek,
                            startPeriod = draft.startPeriod,
                            endPeriod = draft.endPeriod,
                            classroom = draft.classroom.trim().ifBlank { courseToSave.classroom }
                        )
                    }

                    onSave(courseToSave, slotsToSave)
                },
                modifier = Modifier.testTag("save_course_button"),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text("保存课程", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    )
}
