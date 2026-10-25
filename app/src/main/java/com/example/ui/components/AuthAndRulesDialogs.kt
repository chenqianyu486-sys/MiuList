package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.CategoryRule
import com.example.ui.theme.measureTextSize

@Composable
fun WeekPickerDialog(
    /** 今天所在的周。格子上附淡绿色底色，让用户一眼找到现在。 */
    currentWeek: Int,
    /** 正在浏览的周。高亮边框指向它——翻到第 3 周时高亮不该还留在第 5 周。 */
    selectedWeek: Int,
    /** 学期总周数。写死 20 会让 16 周的学期凭空多出 4 个空格子。 */
    totalWeeks: Int,
    onDismiss: () -> Unit,
    onSelectWeek: (Int) -> Unit
) {
    GlassOverlayHost(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.CalendarMonth,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "切换周次",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        },
        text = {
            // 周次格子每行几列，由**实际可用宽度**决定，不写死 4 列。
            //
            // 写死 4 列时，系统字号 2.0 下每格只有 ~78dp，而「第10周」要 ~96dp，
            // 于是「第10周」被折成两行（实测截图），整块网格高低不齐。
            // 现在按最宽的那个标签（第 N 周）量出每格最小宽度，再算这一行放得下几列；
            // 基准字号下仍然是 4 列，观感不变。
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val weekLabelStyle = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold)
                val widestWeekLabel = measureTextSize(
                    "第${totalWeeks.coerceAtLeast(1)}周",
                    weekLabelStyle
                ).width
                val gridGap = 6.dp
                // 左右各留 6dp 呼吸，避免文字贴着格子的圆角边。
                val minCellWidth = widestWeekLabel + 12.dp
                val columns = (
                    (maxWidth + gridGap) / (minCellWidth + gridGap)
                ).toInt().coerceIn(2, 4)

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "课表与待办会同步更新：",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    (1..totalWeeks.coerceAtLeast(1)).chunked(columns).forEach { rowWeeks ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(gridGap)
                        ) {
                            rowWeeks.forEach { w ->
                                val isSelected = selectedWeek == w
                                val isCurrent = w == currentWeek

                                // 本周附淡绿色（primaryContainer 为主题极淡抹茶绿）
                                val backgroundColor = when {
                                    isCurrent -> MaterialTheme.colorScheme.primaryContainer
                                    isSelected -> MaterialTheme.colorScheme.surfaceVariant
                                    else -> MaterialTheme.colorScheme.surface
                                }
                                val borderColor = when {
                                    isSelected -> MaterialTheme.colorScheme.onPrimaryContainer
                                    isCurrent -> MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                                    else -> MaterialTheme.colorScheme.outline
                                }
                                val textColor = when {
                                    isCurrent -> MaterialTheme.colorScheme.onPrimaryContainer
                                    isSelected -> MaterialTheme.colorScheme.onSurface
                                    else -> MaterialTheme.colorScheme.onSurface
                                }
                                val textWeight = if (isSelected || isCurrent) FontWeight.Bold else FontWeight.Medium

                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(backgroundColor)
                                        .border(
                                            if (isSelected) 1.5.dp else 1.dp,
                                            borderColor,
                                            RoundedCornerShape(8.dp)
                                        )
                                        .clickable {
                                            onSelectWeek(w)
                                            onDismiss()
                                        }
                                        .padding(horizontal = 4.dp, vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "第${w}周",
                                        fontSize = 12.sp,
                                        fontWeight = textWeight,
                                        color = textColor
                                    )
                                }
                            }
                            // 最后一行不足 columns 个时补等宽空位，
                            // 否则剩下的格子会被拉宽，和上面几行对不齐。
                            repeat(columns - rowWeeks.size) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                shape = RoundedCornerShape(10.dp),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text("关闭", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
            }
        }
    )
}

/**
 * 「课程联动规则」= **按类别批量设置**该类别下所有课程的待办开关。
 *
 * 改动先落在本地暂存上，点「应用」才落库，点「取消」全部丢弃。
 * 早期版本是点一下勾就存，但那时保存只推「相对上一版变了的那几格」；
 * 改成全量刷之后（每个类别对其下所有课程都有权威）即时落库就意味着
 * 「勾第一格就把 9 门课的三个开关全盖掉」，所以暂存与取消是这条语义的前置条件，
 * 不是额外的贴心设计。
 *
 * 每张类别卡上标出它影响几门课：批量设置改的是别人已经配好的课，
 * 不显示波及范围，用户是在不知情的情况下改动整批设置。
 *
 * ## 「应用」任何时候都能按，而且作用于**全部类别**
 *
 * 这一层的语义是**批量设置**：类别规则对它下面的所有课程有权威，
 * 「应用」= 把当前这套规则**重新盖一遍**到所有类别的课程上。
 * 于是「这次一个字都没改」≠「没什么可做」——
 *
 * - 改了某门课的**类别**之后（只改类别，不动那三个开关，见
 *   `MainViewModel.updateCourseCategory`），要让它听新类别的话，
 *   就得来点一次「应用」——而这时弹层里可能什么都没改；
 * - 用户在**单门课**上单独调过的开关，会被下一次「应用」推回类别值
 *   （这正是「批量设置」与「默认值」的差别，不是 bug）；
 * - 新导入 / 新加的课，要等下一次「应用」才拿到类别规则。
 *
 * 早期版本把按钮禁在「这次有没有改动」上，于是上面第一条直接做不成：
 * 用户只能先在弹层里造一处**无意义的改动**，才能把那门课推回新类别。
 */
@Composable
fun CategoryRulesDialog(
    rules: List<CategoryRule>,
    /** 类别 → 该类别下的课程门数，用来显示批量设置的波及范围。 */
    courseCountByCategory: Map<String, Int> = emptyMap(),
    onDismiss: () -> Unit,
    onApply: (List<CategoryRule>) -> Unit
) {
    val scrollState = rememberScrollState()
    // 弹层每次打开都是新的组合（调用方按 isCategoryRulesDialogOpen 条件渲染），
    // 所以这里记得的就是本次打开时的原始值，不需要额外的重置逻辑。
    var staged by remember { mutableStateOf(rules) }

    // 这次改了哪几张卡 —— **只用来给卡片描边**，不决定「应用」的作用范围。
    val changed = staged.filter { next ->
        rules.firstOrNull { it.category == next.category } != next
    }
    // 「应用」覆盖的是**全部类别**下的课程，所以波及范围按全部类别算，
    // 不是按这次改动过的类别算。
    val affectedCourses = staged.sumOf { courseCountByCategory[it.category] ?: 0 }

    fun toggle(category: String, apply: (CategoryRule) -> CategoryRule) {
        staged = staged.map { if (it.category == category) apply(it) else it }
    }

    GlassOverlayHost(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Tune,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "课程联动规则",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "按类别批量设置该类别下所有课程的待办开关：",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // 「应用」的作用范围要说出来：它套到**全部类别**上，没改动也能按。
                // 不说清楚的话，用户会以为「没改就没必要按」而永远不按 ——
                // 而改完某门课的类别之后，恰恰只能靠这一步把它纳入新类别。
                Text(
                    text = "「应用」会套用到全部类别的课程，没有改动时也可以按。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                staged.forEach { rule ->
                    val courseCount = courseCountByCategory[rule.category] ?: 0
                    val dirty = changed.any { it.category == rule.category }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .border(
                                1.dp,
                                if (dirty) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outline,
                                RoundedCornerShape(12.dp)
                            )
                            .padding(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = rule.category,
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            // 波及范围写在类别名旁边：这是「批量设置」，改的不是这一行，
                            // 而是这一类下的每一门课（含用户已单独调过的那几门）。
                            Text(
                                text = "$courseCount 门课程",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        SwitchRow(
                            label = "课前预习",
                            checked = rule.autoPreStudy,
                            onChange = { on -> toggle(rule.category) { it.copy(autoPreStudy = on) } },
                            tag = "category_rules_switch_${rule.category}_pre_study"
                        )
                        SwitchRow(
                            label = "课后作业",
                            checked = rule.autoPostHomework,
                            onChange = { on -> toggle(rule.category) { it.copy(autoPostHomework = on) } },
                            tag = "category_rules_switch_${rule.category}_post_homework"
                        )
                        SwitchRow(
                            label = "实验报告",
                            checked = rule.autoLabReport,
                            onChange = { on -> toggle(rule.category) { it.copy(autoLabReport = on) } },
                            tag = "category_rules_switch_${rule.category}_lab_report"
                        )
                    }
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.testTag("category_rules_cancel")
            ) {
                Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            Button(
                // 不按「这次有没有改动」设门：这一层是批量设置，「应用」=
                // 把当前规则重新盖到所有类别的课程上，**没改动时它同样有事可做**
                // （改过类别、单独调过开关、刚导入新课之后都要靠它推平）。
                enabled = staged.isNotEmpty(),
                onClick = {
                    // 提交**全部类别**，不是只提交改动过的那几张卡。
                    onApply(staged)
                    onDismiss()
                },
                modifier = Modifier.testTag("category_rules_apply"),
                shape = RoundedCornerShape(10.dp),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Text(
                    text = if (affectedCourses > 0) "应用到 $affectedCourses 门课程" else "应用",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    )
}

/** 一行「标签 + 勾选框」，整行是落点。三个开关行长得一样，抽出来免得写三遍。 */
@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    /**
     * 勾选框上的 testTag。
     *
     * 每一张类别卡里都有这三个开关行，文字完全一样，只能靠 tag 区分
     * 「哪一类的哪一个开关」——测试里要精确点到某一张卡上的某一格。
     */
    tag: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onChange,
            modifier = Modifier.testTag(tag),
            colors = androidx.compose.material3.CheckboxDefaults.colors(
                checkedColor = MaterialTheme.colorScheme.primary
            )
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
    }
}
