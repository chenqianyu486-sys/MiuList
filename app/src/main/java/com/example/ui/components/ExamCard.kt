package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.calendar.AppZone
import com.example.data.model.ExamEntry
import com.example.data.model.ExamKind
import com.example.ui.theme.CardBaseCoatAlpha
import com.example.ui.theme.CourseTintAlpha
import com.example.ui.theme.LocalEffectiveFontScale
import com.example.ui.theme.MiuSurface
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.accentBarColor
import com.example.ui.theme.accentOnCourse
import com.example.ui.theme.cardTint
import com.example.ui.theme.deepEnoughForWhite
import com.example.ui.theme.glassSurface
import java.time.Instant

// ============================================================================
// 文案（纯函数，可离线单测）
// ============================================================================

/**
 * 考试的时刻区间，如「09:00-11:00」。
 *
 * **没有时刻就返回空串**，不编一个占位时间出来 ——
 * 教务网确实会先公布「有这场考试」而把时间留空（见 `ExamEntity` 的注释），
 * 编一个「09:00」出来只会被当成真的。
 */
internal fun examClockRange(entry: ExamEntry): String {
    if (entry.startMinute <= 0) return ""
    val start = formatMinuteOfDay(entry.startMinute)
    if (entry.endMinute <= 0 || entry.endMinute == entry.startMinute) return start
    return "$start-${formatMinuteOfDay(entry.endMinute)}"
}

/** 一天里的第几分钟 → 「09:00」。 */
internal fun formatMinuteOfDay(minute: Int): String {
    val h = (minute / 60).coerceIn(0, 23)
    val m = (minute % 60).coerceIn(0, 59)
    return String.format("%02d:%02d", h, m)
}

/**
 * 「09:00」→ 540。解析不出来（格式不对、小时越界）返回 null。
 *
 * 本地编辑考试时刻用：宁可**不让保存**，也不要悄悄存一个错的时间 ——
 * 考试时间错了，��户会按错的时间出门。
 */
internal fun parseClockText(text: String): Int? {
    val t = text.trim()
    if (t.isEmpty()) return null
    val m = CLOCK_REGEX.find(t) ?: return null
    val h = m.groupValues[1].toIntOrNull() ?: return null
    val min = m.groupValues[2].toIntOrNull() ?: return null
    if (h !in 0..23 || min !in 0..59) return null
    return h * 60 + min
}

private val CLOCK_REGEX = Regex("""^(\d{1,2})\s*[:：]\s*(\d{1,2})$""")

/**
 * 考试的日期文案。
 *
 * 近三天用「今天 / 明天 / 后天」，更远的用「M月d日」。
 * 之所以不用「周几」：考试是**一次性的事件**，
 * 而课表是循环的 —— 对课表来说「周几」是必要信息，对考试来说不是，
 * 那一两个字留给「还有几天」更有用。
 *
 * **日期没公布时**返回学校给的那句标签（「考试周第 3 天」），原样呈现。
 */
internal fun examDateLabel(entry: ExamEntry, now: Long): String {
    if (!entry.hasDate) return entry.dateLabel.ifBlank { "日期待定" }
    return when (val days = examDaysUntil(entry, now)) {
        0L -> "今天"
        1L -> "明天"
        2L -> "后天"
        else -> AppZone.format("M月d日", entry.startAt)
    }
}

/**
 * 倒计时 chip 的文案。
 *
 * - 日期未知 → null（没有可倒计时的东西）；
 * - 今天 / 明天 / 后天 → null（日期行已经说清楚了，再挂一个「今天」是重复）；
 * - 3~30 天 → 「N 天后」；
 * - 更远 → 「N 周后」（考试通常在期末，隔了几周，写「43 天后」反而不好读）。
 *
 * 已经过去的那场返回 null：过期考试由列表层直接过滤掉，
 * 但万一它因为别的原因还留在界面上，宁可**不显示倒计时**，
 * 也不要显示一个负数天。
 */
internal fun examCountdownLabel(entry: ExamEntry, now: Long): String? {
    if (!entry.hasDate) return null
    return when (val days = examDaysUntil(entry, now)) {
        in 3L..30L -> "$days 天后"
        in 31L..Int.MAX_VALUE.toLong() -> "${days / 7} 周后"
        else -> null
    }
}

/** 考试落在哪一天（北京时间的 epochDay）。日期未知时返回 null。 */
internal fun examEpochDay(entry: ExamEntry): Long? {
    if (!entry.hasDate) return null
    return Instant.ofEpochMilli(entry.startAt)
        .atZone(AppZone.ZONE)
        .toLocalDate()
        .toEpochDay()
}

/** 距今还有几天。负数 = 已经过去。 */
internal fun examDaysUntil(entry: ExamEntry, now: Long): Long {
    val examDay = examEpochDay(entry) ?: return 0L
    val today = Instant.ofEpochMilli(now)
        .atZone(AppZone.ZONE)
        .toLocalDate()
        .toEpochDay()
    return examDay - today
}

/**
 * 这场考试是不是「今天就要考」。
 *
 * 总览页要把考试和课摆进**同一个列表**，那就得先知道哪些考试属于今天。
 *
 * 判据是**日期**而不是时刻：按时刻算的话，今天 08:00 的考试在 09:00 之后
 * 就不再算「今天的」，而它今天还要考。
 */
internal fun isExamToday(entry: ExamEntry, now: Long): Boolean =
    entry.hasDate && examDaysUntil(entry, now) == 0L

// ============================================================================
// 卡片
// ============================================================================

/**
 * 一场考试的卡片。
 *
 * ## 为什么考试卡要长成自己的样子
 *
 * 它和课程卡共用同一套卡面语言（同样的玻璃面、同样的课程色竖条、同样的晕开），
 * 但**不能长得一样** —— 一张写着「期末」和写着教室名的卡片混在一起，
 * 用户要在两行小字里分辨「这是要上课的地方」还是「这是要考试的地方」。
 *
 * 三个区分点：
 * 1. **实心的「期末 / 期中」徽标**。课程卡上的小签都是半透明的，
 *    这一枚是实心的，一眼就能从一堆半透明签里跳出来。
 * 2. **倒计时 chip**（「3 天后」）。课程卡上没有这个东西。
 * 3. **三行结构**（名称 / 什么时候 / 在哪），课程卡是两行。
 *
 * ## 「学校还没安排」怎么表现
 *
 * 考场与座位用一枚**空心胶囊**「考场与座位待安排」表示。
 * 空心 = 一个还没填上的格子，而实心文字（哪怕写「未知」）读起来像一条信息。
 *
 * 这件事不能省：考试先公布时间、座位表往往要晚几天甚至几周才出，
 * 界面上如果写「未知」，用户会以为自己漏了什么，或者要去教务网问为什么查不到。
 *
 * @param now 当前的 epoch 毫秒。倒计时每次重组现算，不缓存。
 * @param onClick 点这张卡时回调；为 null 时这张卡不可点。
 */
@Composable
fun ExamCard(
    entry: ExamEntry,
    now: Long,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val examColor = parseColor(entry.courseColorHex)
    val countdown = examCountdownLabel(entry, now)
    val timeLine = listOfNotNull(
        examDateLabel(entry, now).takeIf { it.isNotBlank() },
        examClockRange(entry).takeIf { it.isNotBlank() }
    ).joinToString(" ")

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("exam_card_${entry.id}")
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        shape = MiuSurface.CardShape,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .glassSurface(
                    shape = MiuSurface.CardShape,
                    tint = cardTint(examColor, CourseTintAlpha),
                    tintAlpha = CourseTintAlpha,
                    baseCoatAlpha = CardBaseCoatAlpha
                )
                .courseCardWash(courseColor = examColor, shape = MiuSurface.CardShape)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top
        ) {
            // 课程色竖条。和课程卡一致 —— 它是「这门课」的识别线索，
            // 考试属于哪门课，用户扫一眼竖条就知道。
            Box(
                modifier = Modifier
                    .size(4.dp, 56.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                accentBarColor(examColor),
                                accentBarColor(examColor).copy(alpha = 0.55f)
                            )
                        )
                    )
            )
            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = entry.courseName,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        // weight(1f, fill=false)：必须给名字弹性，
                        // 否则右边的「期末」徽标会被挤成 0 宽（Row 先量非弹性子项）。
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    ExamKindBadge(kind = entry.kind, examColor = examColor)
                }

                Spacer(modifier = Modifier.height(4.dp * LocalEffectiveFontScale))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (countdown != null) {
                        ExamCountdownChip(text = countdown, examColor = examColor)
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(
                        text = timeLine,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.height(3.dp * LocalEffectiveFontScale))

                ExamLocationRow(entry = entry)
            }
        }
    }
}

/**
 * 「期末 / 期中」徽标。
 *
 * **实心**是刻意的：课程卡上的小签（教室、时段）都是半透明的，
 * 考试这一枚要是也半透明，它就淹没在那堆半透明签里了。
 *
 * 底色用 [deepEnoughForWhite] 把课程色压暗到「白字读得清」的深度 ——
 * 于是徽标既认得出是哪门课，又站得住。
 *
 * 公开是因为总览页那张「下一场考试」的加高卡也要用同一枚 ——
 * 同一门课的徽标在两张卡上长得不一样，比颜色不一致更刺眼。
 */
@Composable
internal fun ExamKindBadge(kind: ExamKind, examColor: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(deepEnoughForWhite(examColor, target = 4.5f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = kind.label,
            color = Color.White,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

/** 倒计时 chip。与课程卡的教室胶囊同一套语言（半透明底 + 细描边）。 */
@Composable
private fun ExamCountdownChip(text: String, examColor: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(examColor.copy(alpha = 0.16f))
            .border(0.5.dp, examColor.copy(alpha = 0.32f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            color = accentOnCourse(examColor),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

/**
 * 考场与座位那一行。
 *
 * 缺哪一块就给哪一块挂一枚**空心胶囊**：
 * - 两块都没有 → 「考场与座位待安排」（学校连考场都还没定）；
 * - 有考场没座位 → 考场照常写，旁边挂「座位待安排」（考场定了，座位表还没出）；
 * - 有座位没考场 → 挂「考场待安排」再写座位。
 */
@Composable
private fun ExamLocationRow(entry: ExamEntry) {
    val room = entry.classroom.trim()
    val seat = entry.seat.trim()

    Row(verticalAlignment = Alignment.CenterVertically) {
        if (room.isNotEmpty()) {
            Text(
                text = room,
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
        } else {
            PendingPill(text = "考场待安排")
            Spacer(modifier = Modifier.width(6.dp))
        }

        if (seat.isNotEmpty()) {
            if (room.isNotEmpty()) {
                Text(
                    text = " · 座位 ",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }
            Text(
                text = seat,
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        } else {
            Spacer(modifier = Modifier.width(6.dp))
            PendingPill(text = "座位待安排")
        }
    }
}

/**
 * 「还没安排」的占位胶囊。
 *
 * **空心**是语义的一部分：实心胶囊是一个「有内容的标签」，
 * 空心胶囊是一个「还没填上的格子」。后者才读得出「还没定」这层意思。
 *
 * 公开是因为总览页那张「下一场考试」的加高卡也要用同一枚。
 */
@Composable
internal fun PendingPill(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .border(0.75.dp, TextMuted.copy(alpha = 0.45f), RoundedCornerShape(5.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp)
    ) {
        Text(
            text = text,
            color = TextSecondary,
            fontSize = 10.sp,
            maxLines = 1
        )
    }
}
