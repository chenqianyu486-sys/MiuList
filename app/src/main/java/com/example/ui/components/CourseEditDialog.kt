package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import com.example.data.model.COURSE_CATEGORIES
import com.example.data.model.COURSE_PALETTE
import com.example.data.model.CourseCategory
import com.example.data.model.DEFAULT_CATEGORY
import com.example.data.model.SlotWithWeeks
import com.example.data.timetable.PeriodTable
import com.example.data.timetable.WeekSet
import com.example.ui.theme.LimeGreenOnLight
import com.example.ui.theme.LocalEffectiveFontScale

val DAYS_OF_WEEK = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

/**
 * 开课周次的预设。全部归结成同一个集合形状 —— 没有「单双周枚举列」这种东西，
 * 「每周 / 单周 / 双周 / 第 a–b 周」只是集合的四种常见取值。
 */
enum class WeekPreset(val label: String) {
    EVERY("每周"),
    ODD("单周"),
    EVEN("双周");

    fun resolve(totalWeeks: Int): Set<Int> = when (this) {
        EVERY -> WeekSet.all(totalWeeks)
        ODD -> WeekSet.odd(totalWeeks)
        EVEN -> WeekSet.even(totalWeeks)
    }
}

data class SlotDraft(
    var dayOfWeek: Int = 1,
    var startPeriod: Int = 1,
    var endPeriod: Int = 2,
    var classroom: String = "",
    /** 开课周次。空集合 = 每周（与库里的「未指定」同义）。 */
    var weeks: Set<Int> = emptySet()
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CourseEditDialog(
    initialCourseWithSlots: CourseWithSlots?,
    periodTable: PeriodTable = PeriodTable.DEFAULT,
    /** 已有时段 id → 开课周次，用于回填编辑态 */
    initialSlotWeeks: Map<Long, Set<Int>> = emptyMap(),
    totalWeeks: Int = 25,
    onDismiss: () -> Unit,
    onSave: (CourseEntity, List<SlotWithWeeks>) -> Unit
) {
    val initialCourse = initialCourseWithSlots?.course
    val initialSlots = initialCourseWithSlots?.slots ?: emptyList()

    var name by remember { mutableStateOf(initialCourse?.name ?: "") }
    var category by remember { mutableStateOf(initialCourse?.category ?: DEFAULT_CATEGORY) }
    var teacher by remember { mutableStateOf(initialCourse?.teacher ?: "") }
    var classroom by remember { mutableStateOf(initialCourse?.classroom ?: "") }
    var colorHex by remember { mutableStateOf(initialCourse?.colorHex ?: "#93C5FD") }

    // Multi-slot drafts
    val slotDrafts = remember {
        mutableStateListOf<SlotDraft>().apply {
            if (initialSlots.isNotEmpty()) {
                initialSlots.forEach { s ->
                    add(
                        SlotDraft(
                            dayOfWeek = s.dayOfWeek,
                            startPeriod = s.startPeriod,
                            endPeriod = s.endPeriod,
                            classroom = s.classroom,
                            weeks = initialSlotWeeks[s.id] ?: emptySet()
                        )
                    )
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

    GlassOverlayHost(
        onDismissRequest = onDismiss,
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
                    placeholder = { Text("如：高等数学") },
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
                // 5 个类别一行放不下。必须用 FlowRow：普通 Row 既不换行也不滚动，
                // 「通识公共 / 专业选修」会被挤出弹窗边界外，渲染成空白残片且点不到
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    COURSE_CATEGORIES.forEach { cat ->
                        FilterChip(
                            selected = category == cat,
                            onClick = {
                                category = cat
                                if (cat == CourseCategory.LAB) autoLabReport = true
                            },
                            label = { Text(cat, fontSize = 11.sp) },
                            shape = RoundedCornerShape(10.dp),
                            colors = micaChipColors(),
                            border = micaChipBorder(category == cat)
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
                    text = "主题色",
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
                            tint = LimeGreenOnLight,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "上课时段 · ${slotDrafts.size} 个",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = LimeGreenOnLight
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
                        Text("添加", fontSize = 11.sp)
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
                                        text = "${index + 1}. ${getDayName(draft.dayOfWeek)} ${formatPeriodRange(draft.startPeriod, draft.endPeriod)}",
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
                                // 七颗等宽 chip 在 2.0 倍字号下每颗只有 ~48dp，
                                // 「周一」两个字 + chip 内边距放不下，会折成上下两行。
                                // 大字号时只留「一」～「日」：上面那行摘要已经写明「周几」，
                                // 这一排只是选择器，不必每个都重复「周」。
                                val singleCharWeekday = LocalEffectiveFontScale > 1.6f
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
                                            label = {
                                                Text(
                                                    if (singleCharWeekday) dName.removePrefix("周") else dName,
                                                    fontSize = 10.sp
                                                )
                                            },
                                            shape = RoundedCornerShape(10.dp),
                                            colors = micaChipColors(),
                                            border = micaChipBorder(draft.dayOfWeek == dayNum),
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
                                            label = { Text("起始节", fontSize = 10.sp) },
                                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = startExpanded) },
                                            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable)
                                        )
                                        ExposedDropdownMenu(
                                            expanded = startExpanded,
                                            onDismissRequest = { startExpanded = false }
                                        ) {
                                            (1..periodTable.periodsPerDay).forEach { p ->
                                                DropdownMenuItem(
                                                    text = { Text("第${p}节 (${periodTable.slotOf(p)?.startTime ?: "--"})") },
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
                                            label = { Text("结束节", fontSize = 10.sp) },
                                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = endExpanded) },
                                            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable)
                                        )
                                        ExposedDropdownMenu(
                                            expanded = endExpanded,
                                            onDismissRequest = { endExpanded = false }
                                        ) {
                                            (draft.startPeriod..periodTable.periodsPerDay).forEach { p ->
                                                DropdownMenuItem(
                                                    text = { Text("第${p}节 (${periodTable.slotOf(p)?.endTime ?: "--"})") },
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

                                val timeText = periodTable.timeRangeText(draft.startPeriod, draft.endPeriod)
                                Text(
                                    text = "$timeText · 共 ${draft.endPeriod - draft.startPeriod + 1} 节",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                Spacer(modifier = Modifier.height(6.dp))

                                // 开课周次：每周 / 单周 / 双周 / 第 a–b 周 / 自定义
                                Text(
                                    text = "开课周次",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                )
                                FlowRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    WeekPreset.entries.forEach { preset ->
                                        FilterChip(
                                            selected = draft.weeks == preset.resolve(totalWeeks),
                                            onClick = {
                                                slotDrafts[index] = draft.copy(weeks = preset.resolve(totalWeeks))
                                            },
                                            label = { Text(preset.label, fontSize = 10.sp) },
                                            shape = RoundedCornerShape(10.dp),
                                            colors = micaChipColors(),
                                            border = micaChipBorder(draft.weeks == preset.resolve(totalWeeks))
                                        )
                                    }
                                }
                                Text(
                                    text = if (draft.weeks.isEmpty()) "未指定（按每周处理）"
                                    else WeekSet.describe(draft.weeks, totalWeeks),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                Spacer(modifier = Modifier.height(6.dp))

                                var customWeeks by remember(draft.weeks, totalWeeks) {
                                    mutableStateOf(
                                        if (draft.weeks.isEmpty()) "" else draft.weeks.sorted().joinToString(",")
                                    )
                                }
                                OutlinedTextField(
                                    value = customWeeks,
                                    onValueChange = {
                                        customWeeks = it
                                        val parsed = WeekSet.parse(it.ifBlank { null }, totalWeeks)
                                        if (parsed.issue == null) {
                                            slotDrafts[index] = draft.copy(weeks = parsed.weeks)
                                        }
                                    },
                                    label = { Text("自定义周次（留空=每周）") },
                                    placeholder = { Text("如 1,3,5 或 1-8") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )

                                OutlinedTextField(
                                    value = draft.classroom,
                                    onValueChange = { slotDrafts[index] = draft.copy(classroom = it) },
                                    label = { Text("本时段教室（留空用默认）") },
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
                    text = "待办联动",
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
                            Text("课前预习", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
                            Text("上课前自动生成", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                            Text("课后作业", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
                            Text("课后自动生成", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                            Text("实验报告", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
                            Text("实验课自动生成", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                        val resolved = if (draft.weeks.isEmpty()) WeekSet.all(totalWeeks) else draft.weeks
                        SlotWithWeeks(
                            slot = CourseSlotEntity(
                                courseId = courseToSave.id,
                                dayOfWeek = draft.dayOfWeek,
                                startPeriod = draft.startPeriod,
                                endPeriod = draft.endPeriod,
                                classroom = draft.classroom.trim().ifBlank { courseToSave.classroom }
                            ),
                            weeks = resolved.filter { it in 1..totalWeeks }.toSet()
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

/**
 * 删除课程的二次确认（形状照 [DeleteTaskTypeConfirmDialog] 那一套）。
 *
 * 删课是不可逆的，而入口有两条：课表格子底部的「删除课程」，以及更多抽屉里
 * 课程行上的删除图标 —— 尤其后者，手指在密集列表上滑过去就点了，没有回旋余地。
 *
 * 弹层**只确认、不预演后果**：确认之后才是真正落库，[MainViewModel.deleteCourse]
 * 才会把待办移入「未关联课程」。这里要替用户回答的是「待办会不会跟着没了」，
 * 答案是不没了 —— 但答案必须由界面主动给，光问「确定吗」等于把判断推回给用户。
 *
 * 挂在课程详情弹层/抽屉**之上**：取消后退回原来那一层，接着还能点下一门课。
 */
@Composable
fun DeleteCourseConfirmDialog(
    courseName: String,
    slotCount: Int,
    linkedTaskCount: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    GlassOverlayHost(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "删除课程「$courseName」？",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Column {
                Text(
                    text = buildString {
                        append("课程和 ")
                        append(slotCount)
                        append(" 个上课时段会被删除，这个操作不能撤销。")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (linkedTaskCount > 0) {
                        "$linkedTaskCount 条关联待办不会被删除，会移入「未关联课程」。"
                    } else {
                        "这门课没有关联待办。"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.testTag("delete_course_confirm_button")
            ) {
                Text("删除", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.testTag("delete_course_cancel_button")
            ) {
                Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    )
}
