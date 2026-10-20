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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.codec.AcademicTerm
import com.example.data.calendar.CalendarDiff
import com.example.data.calendar.EpochDays
import com.example.data.local.MakeupDayEntity
import com.example.data.local.NoClassDayEntity
import com.example.data.local.SemesterEntity
import com.example.data.local.TimetablePeriodEntity
import com.example.data.repository.AcademicCalendarRepository
import com.example.data.source.zju.ZjuPresetSemester
import com.example.data.timetable.MAX_PERIODS_PER_DAY
import com.example.data.timetable.PeriodDraft
import com.example.data.timetable.PeriodTable
import com.example.data.timetable.resizePeriodDrafts
import com.example.data.timetable.toDrafts

/**
 * 本学期校历：**官方校历**与**你自己的校历**分开摆。
 *
 * 两块之间用一条分隔线隔开，上面那块只读、下面那块可编辑。混在一起显示过一次，
 * 后果是用户在界面上看到一份学期日期却分不清它是官方的还是自己上次敲的，
 * 于是改错了也不知道该去哪里改。
 *
 * 顶部那行「第 1 周 = X 月 X 日 – X 月 X 日」的换算是刻意做的：
 * 学期起始日填错会让**单双周整体判错且不易察觉**，必须给一个能一眼验的反馈。
 *
 * ## 差异确认为什么不是第二个弹层
 *
 * [GlassOverlayHost] 是**同窗口 overlay**。在里面再开 M3 `AlertDialog` /
 * `DatePickerDialog`（独立窗口）会造成"子层看得见但点不动"—— 上层窗口的透明区域
 * 把下面的都吃掉了触摸事件，不报错、只在真机上出现。
 * 所以差异预览在**同一个弹层内**展开，并把下面的自定义区收起来。
 */
@Composable
fun AcademicCalendarDialog(
    semester: SemesterEntity?,
    periodTable: PeriodTable,
    existingNoClass: List<NoClassDayEntity>,
    existingMakeup: List<MakeupDayEntity>,
    /** 当前可用的官方校历；null = 三层都读不到（弹层会明说，不会假装没有）。 */
    preset: ZjuPresetSemester?,
    isPresetLoading: Boolean,
    /** 「检查更新」在跑。 */
    isCheckingUpdate: Boolean = false,
    /** 刚拉到、还没应用的新版。非 null 时顶部出现「发现新版」。 */
    pendingPreset: ZjuPresetSemester? = null,
    /** 这份官方配置里**所有**可选的学年学期，供弹层顶部切换。 */
    availableTerms: List<AcademicTerm> = emptyList(),
    /** 切看别的学期。**只改显示，不写库。** */
    onPreviewTerm: (AcademicTerm) -> Unit = {},
    /** 差异预览的内容；null = 没在预览。 */
    calendarDiff: CalendarDiff? = null,
    isDiffOpen: Boolean = false,
    onCheckUpdate: () -> Unit = {},
    /** 算差异并展开预览。**不写库**。 */
    onPreviewDiff: () -> Unit = {},
    onDismissDiff: () -> Unit = {},
    /** 用户确认后才写库。 */
    onConfirmApply: () -> Unit = {},
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
    /**
     * 一行补课草稿：补课日 + 来源日（那份课原本属于哪一天）。
     *
     * **只有来源日是输入项**：补周几和第几周都由它算出来，让用户再手填一遍
     * 只会给一次「写着周五、来源日却是周三」的机会 —— 而那种错误在界面上看不出来。
     *
     * 两个日期框并排一行放得下：内容宽 283.dp，扣掉间距与「删」按钮后每个约 115.dp，
     * 而 `yyyy-MM-dd` 加输入框内边距要约 94.dp。（早先一行塞三个框时每个只剩 90.dp，
     * 那才是放不下的原因。）
     */
    val makeupDrafts = remember {
        mutableStateListOf<MakeupDraft>().apply {
            addAll(
                existingMakeup
                    .filterNot { it.sourceEpochDay > 0L && EpochDays.isWeekend(it.sourceEpochDay) && !EpochDays.isWeekend(it.epochDay) }
                    .sortedBy { it.epochDay }
                    .map { it.toDraft() }
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

    GlassOverlayHost(
        onDismissRequest = onDismiss,
        shape = GlassDialogShape,
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
                // ================= 官方（只读） =================
                SectionLabel("官方校历")

                // 学期切换。**只在有得选时才出现** ——
                // 配置里就一学期时，摆一个只能点出同一个选项的下拉纯属噪音。
                if (availableTerms.size > 1) {
                    var termMenuOpen by remember { mutableStateOf(false) }
                    val selectedIndex = remember(availableTerms, preset) {
                        availableTerms.indexOfFirst { it.label == preset?.label }.coerceAtLeast(0)
                    }
                    Box {
                        OutlinedButton(
                            onClick = { termMenuOpen = true },
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(
                                text = availableTerms[selectedIndex].label,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("▾", fontSize = 11.sp)
                        }
                        DropdownMenu(
                            expanded = termMenuOpen,
                            onDismissRequest = { termMenuOpen = false }
                        ) {
                            availableTerms.forEachIndexed { index, term ->
                                DropdownMenuItem(
                                    text = { Text(term.label, fontSize = 12.sp) },
                                    onClick = {
                                        termMenuOpen = false
                                        // 只切显示。真要落库仍走「应用到本机校历」。
                                        onPreviewTerm(term)
                                    },
                                    trailingIcon = {
                                        if (index == selectedIndex) Text("当前", fontSize = 10.sp)
                                    }
                                )
                            }
                        }
                    }
                }

                when {
                    isPresetLoading -> Text(
                        text = "正在读取…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    preset == null -> Text(
                        // 说清楚是「读不到」而不是「不存在」：这台机器上没打出那份数据
                        // （缓存与内置都没有），与用户自己的校历无关，底下那块照样能用。
                        text = "读不到官方校历，下面的自定义内容不受影响",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    else -> {
                        val p = preset
                        Text(
                            text = p.label,
                            style = MaterialTheme.typography.bodyMedium
                                .copy(fontWeight = FontWeight.SemiBold)
                        )
                        Text(
                            text = buildString {
                                append("${EpochDays.formatCn(p.startEpochDay)} – ${EpochDays.formatCn(p.endEpochDay)}")
                                append(" · ${p.totalWeeks} 周")
                                // 起始日就是第一周周一时再说一遍「第 1 周起于 …」是同一句话说两遍；
                                // 只有学期从周中开始（浙大不常见，但别的学校会）才需要补这句。
                                if (p.firstMondayEpochDay != p.startEpochDay) {
                                    append(" · 第 1 周起于 ${EpochDays.formatCn(p.firstMondayEpochDay)}")
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = buildString {
                                append("停课 ${p.noClassDays.size} 天")
                                append(" · 补课 ${p.makeupDays.size} 天")
                                // **来源必须显示**：用户凭什么相信这一块，唯一的依据
                                // 就是它能说清自己读的是远程、缓存还是内置。
                                append(" · 来源 ${p.originLabel}")
                                p.versionLabel?.let { append(" · 版本 $it") }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(
                                onClick = onCheckUpdate,
                                enabled = !isCheckingUpdate,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text(
                                    if (isCheckingUpdate) "检查中…" else "检查更新",
                                    fontSize = 12.sp
                                )
                            }
                            // 「应用到本机校历」不直接写库：它走差异预览。
                            // 直接一键全量替换过一次，用户自己敲的停课会被无声抹掉。
                            Spacer(modifier = Modifier.width(8.dp))
                            OutlinedButton(
                                onClick = onPreviewDiff,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp)
                            ) { Text("应用到本机校历", fontSize = 12.sp) }
                        }

                        // 拉到新版才出现。**只提示不自动应用** —— 校历一变就悄悄
                        // 改动用户正在用的学期，这种事不能替用户决定。
                        pendingPreset?.let { np ->
                            Text(
                                text = "发现新版 ${np.versionLabel ?: "（无版本号）"}",
                                style = MaterialTheme.typography.bodySmall
                                    .copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.primary
                            )
                            OutlinedButton(
                                onClick = onPreviewDiff,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp)
                            ) { Text("查看差异", fontSize = 12.sp) }
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(top = 4.dp, bottom = 2.dp))

                // 差异预览**替换**下面那块自定义区，而不是叠在它上面：
                // 两块表单同屏会让人分不清改的是哪一份，而这里要做的是一次确认。
                if (isDiffOpen && calendarDiff != null) {
                    CalendarDiffSection(
                        diff = calendarDiff,
                        onConfirm = onConfirmApply,
                        onCancel = onDismissDiff
                    )
                } else {
                // ================= 自定义 · 可编辑 =================
                SectionLabel("自定义（本机当前使用的）")
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("学期名称（20xx-20xx学年 秋冬/春夏 学期）") },
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
                        RemoveDraftButton(
                            contentDescription = "删除停课日期",
                            testTag = "remove_no_class_row_$index",
                            onClick = { noClassDrafts.removeAt(index) }
                        )
                    }
                }
                OutlinedButton(
                    onClick = { noClassDrafts.add("") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) { Text("添加停课区间", fontSize = 12.sp) }

                // ---- 补课 ----
                SectionLabel("补课日期（哪天要补 · 补的是哪一天的课）")
                // 说明只写一遍，放在段标题下：原来挂在每一行下面，8 条补课就是 8 段
                // 一样的字，把真正要看的日期挤到视口外。
                Text(
                    text = "来源日就是那份课原本所在的那一天：补周几和第几周都由它算出来，不用另填。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                makeupDrafts.forEachIndexed { index, draft ->
                    Row(
                        modifier = Modifier.padding(bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = draft.dateText,
                            onValueChange = { makeupDrafts[index] = draft.copy(dateText = it) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall,
                            placeholder = { Text("补课日 2026-10-17", fontSize = 11.sp) },
                            isError = draft.dateText.isNotBlank() && EpochDays.parse(draft.dateText) == null
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        OutlinedTextField(
                            value = draft.sourceText,
                            onValueChange = { makeupDrafts[index] = draft.copy(sourceText = it) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall,
                            placeholder = { Text("来源日 2026-10-02", fontSize = 11.sp) },
                            isError = draft.sourceText.isNotBlank() &&
                                EpochDays.parse(draft.sourceText) == null
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        RemoveDraftButton(
                            contentDescription = "删除补课日期",
                            testTag = "remove_makeup_row_$index",
                            onClick = { makeupDrafts.removeAt(index) }
                        )
                    }
                }
                OutlinedButton(
                    onClick = { makeupDrafts.add(MakeupDraft("", "")) },
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
                        // 节数从常量取，不写死：这张表改过一次（15 → 13），
                        // 写死的数字只会让按钮又变回上一版的错值。
                        val resized = resizePeriodDrafts(
                            PeriodTable.ZJU_PERIODS.toDrafts(),
                            PeriodTable.ZJU_PERIODS.size
                        )
                        periodDrafts.clear()
                        periodDrafts.addAll(resized)
                        periodsPerDayText = resized.size.toString()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) { Text("填入浙大作息（${PeriodTable.ZJU_PERIODS.size} 节）", fontSize = 12.sp) }

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
                }
            }
        },
        // 报错钉在**按钮正上方**而不是滚动区末尾：校历表单很长，用户点保存时人正盯着
        // 按钮，而报错落在滚动区底部就等于「点了没反应」—— 实机就是这样：提示确实
        // 生成了，要连滚三次才看得见。这一段不参与滚动。
        belowContent = {
            inputError?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        confirmButton = {
            // 差异预览开着时不摆「保存」：这一屏只有差异，确认动作在差异区里。
            // 两个都能点的话，用户不知道自己点的是"存草稿"还是"覆盖本机校历"。
            if (!isDiffOpen) {
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
                    makeupDrafts.forEachIndexed { index, draft ->
                        if (draft.dateText.isBlank() && draft.sourceText.isBlank()) return@forEachIndexed
                        val day = EpochDays.parse(draft.dateText)
                        if (day == null) {
                            inputError = "第 ${index + 1} 行补课日期无法解析：${draft.dateText}"
                            return@save
                        }
                        // 来源日必填，和「补周几」当年一样是硬校验而不是标红放行：
                        // 没有它就不知道补的是哪一天的课，那行等于一次无法解释的搬运。
                        val source = EpochDays.parse(draft.sourceText)
                        if (source == null) {
                            inputError =
                                "第 ${index + 1} 行补课需要填来源日（补的是哪一天的课）：${draft.sourceText.ifBlank { "空" }}"
                            return@save
                        }
                        makeup += MakeupDayEntity(day, EpochDays.dayOfWeek1To7(source), "", source)
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
                        term = seasonOf(name),
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
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/**
 * 「应用官方校历」的差异预览。
 *
 * ## 为什么必须有这一屏
 *
 * 应用是**全量替换**停课与补课。用户自己敲的东西（"考试周那几天不排课"）
 * 会在点完按钮后无声消失，而这个动作以前就只是一个按钮。
 *
 * 逐条列出「新增 / 移除 / 修改」，让用户在写库**之前**知道会少什么。
 *
 * ## 强调「移除」而不是只强调「新增」
 *
 * 新增的东西用户会开心，移除的东西用户会后悔。两者同屏时，
 * 只用 primary 色标"有 N 项更新"是不诚实的 —— 真正有破坏性的那半边
 * 必须和新增一样显眼。
 */
@Composable
private fun CalendarDiffSection(
    diff: CalendarDiff,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel("应用后会发生的变化")

        if (diff.isEmpty) {
            Text(
                text = "与当前校历完全一致，不需要改动",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (diff.semesterChanges.isNotEmpty()) {
            Text(
                text = "学期",
                style = MaterialTheme.typography.labelMedium
                    .copy(fontWeight = FontWeight.SemiBold)
            )
            diff.semesterChanges.forEach { DiffLine(it.label, it.before, it.after, DiffKind.CHANGED) }
        }

        if (diff.noClassAdded.isNotEmpty() || diff.noClassRemoved.isNotEmpty()) {
            Text(
                text = "停课",
                style = MaterialTheme.typography.labelMedium
                    .copy(fontWeight = FontWeight.SemiBold)
            )
            diff.noClassAdded.forEach { DiffLine(EpochDays.formatCn(it), "无", "停课", DiffKind.ADDED) }
            diff.noClassRemoved.forEach { DiffLine(EpochDays.formatCn(it), "停课", "恢复上课", DiffKind.REMOVED) }
        }

        if (diff.makeupAdded.isNotEmpty() || diff.makeupRemoved.isNotEmpty() || diff.makeupChanged.isNotEmpty()) {
            Text(
                text = "补课",
                style = MaterialTheme.typography.labelMedium
                    .copy(fontWeight = FontWeight.SemiBold)
            )
            diff.makeupAdded.forEach {
                DiffLine(EpochDays.formatCn(it.epochDay), "无", "补周${it.makeupForDayOfWeek}", DiffKind.ADDED)
            }
            diff.makeupRemoved.forEach {
                DiffLine(EpochDays.formatCn(it.epochDay), "补周${it.makeupForDayOfWeek}", "恢复", DiffKind.REMOVED)
            }
            diff.makeupChanged.forEach { c ->
                DiffLine(
                    label = EpochDays.formatCn(c.after.epochDay),
                    before = "补周${c.before.makeupForDayOfWeek}",
                    after = "补周${c.after.makeupForDayOfWeek}",
                    kind = DiffKind.CHANGED
                )
            }
        }

        Text(
            text = "确认后会整体替换上面的停课与补课（作息表不动）。被移除的那些会真的消失。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Button(
            onClick = onConfirm,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
        ) { Text("确认应用", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold) }

        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("先不应用") }
    }
}

/** 差异的一行。用 kind 决定颜色 —— 移除要和新增一样显眼，不能都当成普通文案。 */
private enum class DiffKind { ADDED, REMOVED, CHANGED }

@Composable
private fun DiffLine(label: String, before: String, after: String, kind: DiffKind) {
    val color = when (kind) {
        DiffKind.ADDED -> MaterialTheme.colorScheme.primary
        // 移除用 error 色：这是唯一"用户会后悔"的一类，压成灰色等于把它藏起来。
        DiffKind.REMOVED -> MaterialTheme.colorScheme.error
        DiffKind.CHANGED -> MaterialTheme.colorScheme.onSurface
    }
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "$before → $after",
            style = MaterialTheme.typography.bodySmall,
            color = color
        )
    }
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

/**
 * 草稿行尾的删除按钮：一枚叉。
 *
 * 原来是一个写着「删」的 TextButton——一个字占掉一整个按钮的宽度，
 * 而它表达的事只有「去掉这一行」。叉是这件事的通用形状，
 * 腾出来的宽度还给日期输入框（补课那行本来就有两个输入框挤在一起）。
 *
 * **不用 IconButton**：它的 `minimumInteractiveComponentSize` 不看父级高度，
 * 会把这一行顶高（MoreDrawer 的课程行也是这么绕开的）。这里自己给 32dp 见方。
 *
 * 颜色用 error：这个 App 里「删除」一律走错误色（更多页的删除课程、
 * 课程详情里的确认删除），叉号在主题绿底下会被读成「确认 / 关闭」。
 */
@Composable
private fun RemoveDraftButton(
    contentDescription: String,
    testTag: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(9.dp))
            .clickable(onClick = onClick)
            .testTag(testTag),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Outlined.Close,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(16.dp)
        )
    }
}

/**
 * 一行补课草稿：补课日 + 来源日。
 *
 * [sourceText] 存**原文**而不是 epochDay：输入框要允许敲到一半（「2026-1」）才判错，
 * 若把解析结果存成数字、显示时再格式化，用户每敲一个字符都会被重写成上一版能解析
 * 出来的日期，光标和输入都被抢走。保存那一刻才解析。
 *
 * 「补周几」原来也是草稿里的一项，现在删了：它由来源日算出来，让用户多填一遍
 * 等于多给一次自相矛盾的机会。库里来源日缺失的老行会回填成空，保存时会被挡下，
 * 用户照提示补上即可 —— 比让它悄悄按错的口径上课要好。
 */
private data class MakeupDraft(
    val dateText: String,
    val sourceText: String
)

/** 库里那条补课 → 草稿行。来源日为 0（老数据）时留空，保存时会被拦下要求补齐。 */
private fun MakeupDayEntity.toDraft() = MakeupDraft(
    dateText = EpochDays.format(epochDay),
    sourceText = if (sourceEpochDay > 0L) EpochDays.format(sourceEpochDay) else ""
)

/**
 * 停课日 → 草稿行：**连续的日子合成一个区间**。
 *
 * 国庆七天一天一行就是七个输入框，把弹层顶得比作息表还长；合成
 * `2026-10-01~2026-10-07` 一行既短，又是这个输入框本来就认的写法。
 *
 * 区间里夹着上课日的仍会分成两段 —— 强行合并会把中间那些天也停掉。
 */
private fun List<NoClassDayEntity>.toDraftRanges(): List<String> {
    val days = map { it.epochDay }.distinct().sorted()
    if (days.isEmpty()) return emptyList()
    val out = mutableListOf<String>()
    var from = days.first()
    var to = from
    days.drop(1).forEach { d ->
        if (d == to + 1L) {
            to = d
        } else {
            out += noClassRangeText(from, to)
            from = d
            to = d
        }
    }
    out += noClassRangeText(from, to)
    return out
}

private fun noClassRangeText(from: Long, to: Long): String =
    if (from == to) EpochDays.format(from) else "${EpochDays.format(from)}~${EpochDays.format(to)}"

/**
 * 学期名 → [SemesterEntity.term]。**直接走仓储那份**，弹层不自己再写一套 ——
 * 写过一份，然后漂成了旧的春秋/秋季口径；而「应用官方校历」那边也要算同一个字段，
 * 两份实现必然分叉。
 */
private fun seasonOf(name: String): String = AcademicCalendarRepository.seasonOf(name)

/** 学期名兜底直接走仓储那份，弹层不自己再写一套 —— 写过一份，然后漂成了旧的春秋/秋季口径。 */
private fun defaultSemesterName(): String = AcademicCalendarRepository.defaultName()