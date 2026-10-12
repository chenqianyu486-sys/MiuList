package com.example.ui.components

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.calendar.EpochDays
import com.example.data.local.MakeupDayEntity
import com.example.data.local.NoClassDayEntity
import com.example.data.local.SemesterEntity
import com.example.data.local.TimetablePeriodEntity
import com.example.data.timetable.MAX_PERIODS_PER_DAY
import com.example.data.timetable.PeriodDraft
import com.example.data.timetable.PeriodTable
import com.example.data.timetable.resizePeriodDrafts
import com.example.data.timetable.toDrafts

/**
 * 本学期自定义校历。
 *
 * 顶部那行「第 1 周 = X 月 X 日 – X 月 X 日」的换算是刻意做的：
 * 学期起始日填错会让**单双周整体判错且不易察觉**，必须给一个能一眼验的反馈。
 */
@Composable
fun AcademicCalendarDialog(
    semester: SemesterEntity?,
    periodTable: PeriodTable,
    existingNoClass: List<NoClassDayEntity>,
    existingMakeup: List<MakeupDayEntity>,
    onSave: (
        SemesterEntity?,
        List<TimetablePeriodEntity>,
        List<NoClassDayEntity>,
        List<MakeupDayEntity>
    ) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(semester?.name ?: defaultSemesterName()) }
    var startText by remember { mutableStateOf(semester?.startEpochDay?.let { EpochDays.format(it) } ?: "") }
    var firstMondayText by remember { mutableStateOf(semester?.firstMondayEpochDay?.let { EpochDays.format(it) } ?: "") }
    var endText by remember { mutableStateOf(semester?.endEpochDay?.let { EpochDays.format(it) } ?: "") }
    var totalWeeksText by remember { mutableStateOf(semester?.totalWeeks?.toString() ?: "20") }
    /** 保存被拦住的原因。宁可挡下保存，也不静默丢掉用户敲进去的停课/补课。 */
    var inputError by remember { mutableStateOf<String?>(null) }

    // 作息：改造前这里只有一个只读文本，把节数和每节起止时间打印出来就算完 ——
    // 界面上没有任何输入控件，保存时原样回传 periodTable，等于这个配置根本没法改。
    // 现在节数与逐节时间都是草稿行，改节数只增删末尾行（见 resizePeriodDrafts）。
    val periodDrafts = remember {
        mutableStateListOf<PeriodDraft>().apply {
            addAll(
                if (periodTable.isEmpty) {
                    PeriodTable.ZJU_PERIODS.toDrafts()
                } else {
                    periodTable.toDrafts()
                }
            )
        }
    }
    var periodsPerDayText by remember { mutableStateOf(periodDrafts.size.toString()) }

    // 已存的条目回填成可编辑的草稿行，而不是只显示一个计数：
    // 只显示计数的话，用户改一次校历就会看不见自己存过的停课与补课。
    val noClassDrafts = remember {
        mutableStateListOf<String>().apply {
            addAll(existingNoClass.sortedBy { it.epochDay }.map { EpochDays.format(it.epochDay) })
        }
    }
    val makeupDrafts = remember {
        mutableStateListOf<Pair<String, String>>().apply {
            addAll(
                existingMakeup.sortedBy { it.epochDay }
                    .map { EpochDays.format(it.epochDay) to it.makeupForDayOfWeek.toString() }
            )
        }
    }

    // 换算预览：学期起始日 / 首个周一解析不出来时显示提示而不是静默算错
    val startDay = EpochDays.parse(startText)
    val endDay = EpochDays.parse(endText)
    val firstMonday = EpochDays.parse(firstMondayText) ?: startDay?.let { EpochDays.mondayOf(it) }
    val derivedWeeks = if (firstMonday != null && endDay != null) {
        ((endDay - firstMonday) / 7L + 1L).toInt().coerceAtLeast(1)
    } else null

    val week1Label = if (firstMonday != null) {
        "第 1 周 = ${EpochDays.formatCn(firstMonday)} – ${EpochDays.formatCn(firstMonday + 6)}"
    } else null

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = {
            Text(
                text = "本学期校历",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("学期名称（20xx-20xx学年 春季/秋季学期）") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = startText,
                    onValueChange = { startText = it },
                    label = { Text("学期开始日 yyyy-MM-dd") },
                    placeholder = { Text("2026-09-07") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = firstMondayText,
                    onValueChange = { firstMondayText = it },
                    label = { Text("首个周一（留空则按开始日所在周推算）") },
                    placeholder = { Text(firstMonday?.let { EpochDays.format(it) } ?: "2026-08-31") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = endText,
                    onValueChange = { endText = it },
                    label = { Text("学期结束日 yyyy-MM-dd") },
                    placeholder = { Text("2027-01-17") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = totalWeeksText,
                    onValueChange = { totalWeeksText = it },
                    label = { Text("学期共有几周（留空则按日期推算）") },
                    placeholder = { Text(derivedWeeks?.toString() ?: "20") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                week1Label?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                if (startDay != null && endDay != null && startDay > endDay) {
                    Text(
                        text = "开始日晚于结束日",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                // ---- 停课 ----
                SectionLabel("停课日期（单个日期，或 2026-10-01~2026-10-07 这样的区间）")
                noClassDrafts.forEachIndexed { index, text ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = text,
                            onValueChange = { noClassDrafts[index] = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall,
                            isError = text.isNotBlank() && parseNoClassRange(text) == null
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        TextButton(onClick = { noClassDrafts.removeAt(index) }) { Text("删", fontSize = 11.sp) }
                    }
                }
                OutlinedButton(
                    onClick = { noClassDrafts.add("") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) { Text("添加停课区间", fontSize = 12.sp) }

                // ---- 补课 ----
                SectionLabel("补课日期（哪天要补 · 补周几 1–7）")
                makeupDrafts.forEachIndexed { index, pair ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = pair.first,
                            onValueChange = { makeupDrafts[index] = pair.first to it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall,
                            isError = pair.first.isNotBlank() && EpochDays.parse(pair.first) == null
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        OutlinedTextField(
                            value = pair.second,
                            onValueChange = { makeupDrafts[index] = it to pair.second },
                            modifier = Modifier.width(64.dp),
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall,
                            isError = pair.second.toIntOrNull()?.let { it !in 1..7 } ?: true
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        TextButton(onClick = { makeupDrafts.removeAt(index) }) { Text("删", fontSize = 11.sp) }
                    }
                }
                OutlinedButton(
                    onClick = { makeupDrafts.add("" to "1") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) { Text("添加补课", fontSize = 12.sp) }

                // ---- 作息：每天几节 + 每节起止时刻 + 所属大节 ----
                SectionLabel("每天上课节数")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = periodsPerDayText,
                        onValueChange = { raw ->
                            periodsPerDayText = raw
                            val n = raw.toIntOrNull()
                            if (n != null) {
                                val resized = resizePeriodDrafts(periodDrafts.toList(), n)
                                periodDrafts.clear()
                                periodDrafts.addAll(resized)
                                // 越界值当场夹回来，输入框也显示夹后的值，
                                // 否则用户会看到「22 节」而实际存了 20 节
                                periodsPerDayText = resized.size.toString()
                            }
                        },
                        modifier = Modifier.width(96.dp),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall,
                        isError = periodsPerDayText.toIntOrNull()
                            ?.let { it !in 1..MAX_PERIODS_PER_DAY } ?: false
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "节 · 1–$MAX_PERIODS_PER_DAY",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                OutlinedButton(
                    onClick = {
                        val resized = resizePeriodDrafts(PeriodTable.ZJU_PERIODS.toDrafts(), 15)
                        periodDrafts.clear()
                        periodDrafts.addAll(resized)
                        periodsPerDayText = resized.size.toString()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) { Text("填入浙大作息（15 节）", fontSize = 12.sp) }

                SectionLabel("每节起止时刻")
                periodDrafts.forEachIndexed { index, draft ->
                    PeriodDraftRow(
                        draft = draft,
                        onStartChange = {
                            periodDrafts[index] = draft.copy(startText = it)
                        },
                        onEndChange = {
                            periodDrafts[index] = draft.copy(endText = it)
                        },
                        onSectionChange = {
                            periodDrafts[index] = draft.copy(section = it)
                        }
                    )
                }

                // 错误提示放在最后：改造前它夹在补课与作息之间，而作息编辑现在就在它下面，
                // 报错行会被刚编辑的那几行顶到视口外。贴着保存按钮报才看得见。
                inputError?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = save@{
                    val start = EpochDays.parse(startText)
                    val end = EpochDays.parse(endText)
                    if (start == null || end == null || start > end) {
                        inputError = "学期起始日与结束日必须都能解析，且起始日不晚于结束日"
                        return@save
                    }

                    // 逐行解析停课 / 补课。任何一行不合法就停下并指出来 ——
                    // 保存按钮不做「悄悄跳过这一行」这种事。
                    val noClass = mutableListOf<NoClassDayEntity>()
                    noClassDrafts.forEachIndexed { index, raw ->
                        if (raw.isBlank()) return@forEachIndexed
                        val days = parseNoClassRange(raw)
                        if (days == null) {
                            inputError = "第 ${index + 1} 行停课日期无法解析：$raw"
                            return@save
                        }
                        days.forEach { noClass += NoClassDayEntity(it, "") }
                    }

                    val makeup = mutableListOf<MakeupDayEntity>()
                    makeupDrafts.forEachIndexed { index, (dateText, dowText) ->
                        if (dateText.isBlank() && dowText.isBlank()) return@forEachIndexed
                        val day = EpochDays.parse(dateText)
                        if (day == null) {
                            inputError = "第 ${index + 1} 行补课日期无法解析：$dateText"
                            return@save
                        }
                        val dow = dowText.toIntOrNull()
                        if (dow == null || dow !in 1..7) {
                            inputError = "第 ${index + 1} 行「补周几」必须是 1–7，当前是「$dowText」"
                            return@save
                        }
                        makeup += MakeupDayEntity(day, dow, "")
                    }

                    inputError = null
                    val fm = EpochDays.parse(firstMondayText) ?: EpochDays.mondayOf(start)
                    val weeks = totalWeeksText.toIntOrNull()
                        ?: (((end - fm) / 7L + 1L).toInt().coerceAtLeast(1))

                    // 作息逐行校验，和停课/补课同一条规矩：有一行不合法就挡下保存并指出是哪一行，
                    // 绝不「悄悄跳过这一行」——那会存进一张和用户所见不一致的作息表。
                    if (periodDrafts.isEmpty()) {
                        inputError = "每天至少要有 1 节课"
                        return@save
                    }
                    val periodEntities = mutableListOf<TimetablePeriodEntity>()
                    periodDrafts.forEachIndexed { index, draft ->
                        val slot = draft.toPeriodSlot()
                        if (slot == null) {
                            val reason = when {
                                draft.startMinute == null || draft.endMinute == null ->
                                    "时刻格式应为 HH:mm"
                                else -> "开始时刻必须早于结束时刻"
                            }
                            inputError = "第 ${index + 1} 节（${draft.startText}-${draft.endText}）：$reason"
                            return@save
                        }
                        periodEntities += AcademicCalendarRepositoryDefaults.toEntity(slot)
                    }

                    val entity = SemesterEntity(
                        name = name,
                        yearSpan = Regex("(20\\d{2})\\s*[-–—~至]\\s*(20\\d{2})").find(name)
                            ?.let { "${it.groupValues[1]}-${it.groupValues[2]}" } ?: "",
                        term = if (name.contains("春")) "春季" else if (name.contains("秋")) "秋季" else "",
                        startEpochDay = start,
                        firstMondayEpochDay = fm,
                        endEpochDay = end,
                        totalWeeks = weeks,
                        // 节数跟着实际保存的作息走，而不是旧表的 periodsPerDay ——
                        // 两者在改造前恒等，改成可编辑后就会不一致。
                        periodsPerDay = periodEntities.size
                    )
                    onSave(
                        entity,
                        periodEntities,
                        // 同一天重复填写时按主键去重，否则 INSERT 会直接冲突
                        noClass.distinctBy { it.epochDay },
                        makeup.distinctBy { it.epochDay }
                    )
                },
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) { Text("保存", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/**
 * `2026-10-01` 或 `2026-10-01~2026-10-07` → 展开成逐天。
 *
 * 只用 `~` / `～` / `至` 当区间分隔符：日期本身带连字符，
 * 拿 `-` 当分隔符会把 `2026-10-01` 切成三段。
 *
 * @return null = 无法解析（调用方负责告诉用户，不要静默跳过）
 */
private fun parseNoClassRange(text: String): List<Long>? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null
    val parts = trimmed.split('~', '～', '至').map { it.trim() }.filter { it.isNotEmpty() }
    return when (parts.size) {
        1 -> EpochDays.parse(parts[0])?.let { listOf(it) }
        2 -> {
            val from = EpochDays.parse(parts[0])
            val to = EpochDays.parse(parts[1])
            if (from != null && to != null && from <= to) EpochDays.expandRange(from, to) else null
        }
        else -> null
    }
}

private object AcademicCalendarRepositoryDefaults {
    fun toEntity(p: com.example.data.timetable.PeriodSlot) =
        TimetablePeriodEntity(p.period, p.startMinute, p.endMinute, p.section)
}

private val SECTIONS = listOf("上午", "下午", "晚上")

/**
 * 一节课的编辑行：节号 + 起 + 止 + 所属大节。
 *
 * 大节用下拉而不是三个固定 chip：一行里塞不下三段文字，而它的取值就是那三个，
 * 展开选择比横向铺开省横向空间。
 */
@Composable
private fun PeriodDraftRow(
    draft: PeriodDraft,
    onStartChange: (String) -> Unit,
    onEndChange: (String) -> Unit,
    onSectionChange: (String) -> Unit
) {
    var sectionMenuOpen by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "第${draft.period}节",
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
            modifier = Modifier.width(44.dp),
            textAlign = TextAlign.Start
        )
        OutlinedTextField(
            value = draft.startText,
            onValueChange = onStartChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall,
            isError = !draft.isValid
        )
        Spacer(modifier = Modifier.width(4.dp))
        OutlinedTextField(
            value = draft.endText,
            onValueChange = onEndChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall,
            isError = !draft.isValid
        )
        Spacer(modifier = Modifier.width(6.dp))
        Box {
            Text(
                text = draft.section.ifBlank { "上午" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .widthIn(min = 46.dp)
                    .clickable { sectionMenuOpen = true }
                    .padding(vertical = 10.dp)
            )
            DropdownMenu(
                expanded = sectionMenuOpen,
                onDismissRequest = { sectionMenuOpen = false }
            ) {
                SECTIONS.forEach { s ->
                    DropdownMenuItem(
                        text = { Text(s, fontSize = 13.sp) },
                        onClick = {
                            onSectionChange(s)
                            sectionMenuOpen = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
        modifier = Modifier.padding(top = 6.dp)
    )
}

private fun defaultSemesterName(): String {
    val now = java.time.LocalDate.ofEpochDay(EpochDays.today())
    val startYear = if (now.monthValue >= 8) now.year else now.year - 1
    val term = if (now.monthValue in 1..7) "春季" else "秋季"
    return "$startYear-${startYear + 1}学年 $term 学期"
}