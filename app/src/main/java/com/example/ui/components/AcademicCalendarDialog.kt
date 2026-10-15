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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
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
     * 一行补课草稿：补课日 / 补周几 / 来源日（那份课原本属于哪一天）。
     *
     * 必须留着来源日：补课日与来源日可以差两周以上（2026 国庆把 10-02 的周五课
     * 补到 10-17），只存周几就丢了「这是第几周的课」，排课时单双周会集体判错。
     * 手填的行留空即可（空 = 未知，退回按补课当天算）。
     */
    val makeupDrafts = remember {
        mutableStateListOf<MakeupDraft>().apply {
            addAll(existingMakeup.sortedBy { it.epochDay }.map { it.toDraft() })
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
                        TextButton(onClick = { noClassDrafts.removeAt(index) }) { Text("删", fontSize = 11.sp) }
                    }
                }
                OutlinedButton(
                    onClick = { noClassDrafts.add("") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) { Text("添加停课区间", fontSize = 12.sp) }

                // ---- 补课 ----
                SectionLabel("补课日期（哪天要补 · 补周几 1–7 · 来源日）")
                // 说明只写一遍，放在段标题下：原来挂在每一行下面，8 条补课就是 8 段
                // 一样的字，把真正要看的日期挤到视口外。
                Text(
                    text = "来源日填了才算这次补课属于哪一周的课；留空则按补课当天算。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                makeupDrafts.forEachIndexed { index, draft ->
                    Column(modifier = Modifier.padding(bottom = 2.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = draft.dateText,
                                onValueChange = { makeupDrafts[index] = draft.copy(dateText = it) },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodySmall,
                                isError = draft.dateText.isNotBlank() && EpochDays.parse(draft.dateText) == null
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            OutlinedTextField(
                                value = draft.dowText,
                                onValueChange = { makeupDrafts[index] = draft.copy(dowText = it) },
                                modifier = Modifier.width(48.dp),
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodySmall,
                                isError = draft.dowText.toIntOrNull()?.let { it !in 1..7 } ?: true
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            TextButton(onClick = { makeupDrafts.removeAt(index) }) { Text("删", fontSize = 11.sp) }
                        }
                        // 来源日另起一行：三个 `yyyy-MM-dd` 并排放不下 —— 实测日期框
                        // 只剩 90dp，而「2026-10-17」加输入框自带的那圈内边距就要 94dp，
                        // 截成「2026-09」比不给来源日更难认。
                        if (draft.showSource) {
                            OutlinedTextField(
                                value = draft.sourceText,
                                onValueChange = { makeupDrafts[index] = draft.copy(sourceText = it) },
                                modifier = Modifier.width(180.dp),
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodySmall,
                                isError = draft.sourceText.isNotBlank() &&
                                    EpochDays.parse(draft.sourceText) == null,
                                placeholder = { Text("来源日 2026-10-02", fontSize = 11.sp) }
                            )
                        }
                    }
                }
                OutlinedButton(
                    onClick = { makeupDrafts.add(MakeupDraft("", "1", "", showSource = true)) },
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
                        if (draft.dateText.isBlank() && draft.dowText.isBlank()) return@forEachIndexed
                        val day = EpochDays.parse(draft.dateText)
                        if (day == null) {
                            inputError = "第 ${index + 1} 行补课日期无法解析：${draft.dateText}"
                            return@save
                        }
                        val dow = draft.dowText.toIntOrNull()
                        if (dow == null || dow !in 1..7) {
                            inputError = "第 ${index + 1} 行「补周几」必须是 1–7，当前是「${draft.dowText}」"
                            return@save
                        }
                        // 来源日随草稿一起落库。它填错**不拦保存** —— 只影响「算第几周」，
                        // 把值清 0 退回按当天算即可；整行作废反而会连带丢掉用户敲好的
                        // 补课日与周几。输入框已经标红，错误当场看得见。
                        val source = if (draft.showSource) {
                            EpochDays.parse(draft.sourceText) ?: 0L
                        } else {
                            0L
                        }
                        makeup += MakeupDayEntity(day, dow, "", source)
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
 * 一行补课草稿：补课日 + 补周几 + 来源日。
 *
 * [sourceText] 存**原文**而不是 epochDay：输入框要允许敲到一半（「2026-1」）才判错，
 * 若把解析结果存成数字、显示时再格式化，用户每敲一个字符都会被重写成上一版能解析
 * 出来的日期，光标和输入都被抢走。保存那一刻才解析。
 *
 * [showSource] 是「这一行有没有来源日输入框」，由**建行时就定死**：库里有来源日就显示，
 * 「添加补课」新建的行也显示（默认给）。若改成「填了才显示」，手填的行就永远没有那个框，
 * 而来源日恰恰是这三样里最容易漏、又最影响单双周的一样。
 */
private data class MakeupDraft(
    val dateText: String,
    val dowText: String,
    val sourceText: String,
    val showSource: Boolean
)

/** 库里那条补课 → 草稿行。来源日为 0（未知）时留空，也不给它开输入框。 */
private fun MakeupDayEntity.toDraft() = MakeupDraft(
    dateText = EpochDays.format(epochDay),
    dowText = makeupForDayOfWeek.toString(),
    sourceText = if (sourceEpochDay > 0L) EpochDays.format(sourceEpochDay) else "",
    showSource = sourceEpochDay > 0L
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