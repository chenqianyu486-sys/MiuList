package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Assignment
import androidx.compose.material.icons.automirrored.outlined.StickyNote2
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.CourseEntity
import com.example.data.local.CourseSlotEntity
import com.example.data.local.CourseWithSlots
import com.example.data.local.TaskEntity
import com.example.data.model.CustomTaskType
import com.example.data.model.ExamEntry
import com.example.data.model.ExamKind
import com.example.data.model.TaskStar
import com.example.data.model.TaskTypes
import com.example.data.timetable.PeriodTable
import com.example.data.timetable.WeekSet
import com.example.ui.theme.AlertRed
import com.example.ui.theme.CourseCellOnIdle
import com.example.ui.theme.CourseCellOnPending
import com.example.ui.theme.CourseKhakiMetaOnIdle
import com.example.ui.theme.CourseTintAlpha
import com.example.ui.theme.CardBaseCoatAlpha
import com.example.ui.theme.CourseCellBlurRadius
import com.example.ui.theme.CourseCellDeepFaceAlpha
import com.example.ui.theme.cardTint
import com.example.ui.theme.NestedFaceAlpha
import com.example.ui.theme.TeaColor
import com.example.ui.theme.TeaColorBorder
import com.example.ui.theme.TeaOnColor
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.TextTertiary
import com.example.ui.theme.accentOnCourse
import com.example.ui.theme.accentBarColor
import com.example.ui.theme.deepEnoughForWhite
import com.example.ui.theme.isGreenishColor
import com.example.ui.theme.isLightColor
import com.example.ui.theme.glassSurface
import com.example.ui.theme.pushAwayFrom
import com.example.ui.theme.readableOn

/**
 * 课程色卡面的那层「晕开 + 高光」。
 *
 * ## 这层为什么必须留着
 *
 * 纯色块看上去像一张贴纸：颜色从边缘一刀切下去。而真实的东西（比如一张卡片）
 * 靠近边缘时会透出底下的颜色 —— 这层横向渐变做的就是这件事：
 * 左边压一点课程色，往右淡出，于是卡片像是**泡在**这张底色里，而不是**贴在**上面。
 * 上面那层竖向的白色高光则给它一个受光面。
 *
 * 早先这两道渐变被当成「装饰」删过一次，删完所有卡片都变得像色块矩阵，
 * 层次感全靠描边撑着 —— 而描边在浅色和花纹壁纸上都不成立。
 *
 * ## 为什么从 `DashboardScreen` 挪到这里
 *
 * 考试卡也要用同一套卡面语言（同一个 App 里的卡片长得不一样，比颜色不一致更刺眼）。
 * 原来它是 Dashboard 里的 private 函数，课表页那边拿不到。
 *
 * @param washAlpha 横向晕开的强度
 * @param sheenAlpha 顶部高光的强度
 */
fun Modifier.courseCardWash(
    courseColor: Color,
    shape: RoundedCornerShape,
    washAlpha: Float = 0.10f,
    sheenAlpha: Float = 0.11f
): Modifier = this
    .clip(shape)
    .drawBehind {
        drawRect(
            brush = Brush.horizontalGradient(
                0f to accentBarColor(courseColor).copy(alpha = washAlpha),
                0.62f to Color.Transparent
            )
        )
        drawRect(
            brush = Brush.verticalGradient(
                0f to Color.White.copy(alpha = sheenAlpha),
                0.5f to Color.Transparent
            )
        )
    }

/**
 * Adjust color saturation using Android HSV conversion.
 * saturationFactor > 1.0 increases saturation (more vivid).
 * saturationFactor < 1.0 decreases saturation (more muted/pastel/greyed).
 */
fun adjustColorSaturation(color: Color, saturationFactor: Float): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.RGBToHSV(
        (color.red * 255).toInt(),
        (color.green * 255).toInt(),
        (color.blue * 255).toInt(),
        hsv
    )
    hsv[1] = (hsv[1] * saturationFactor).coerceIn(0f, 1f)
    val argb = android.graphics.Color.HSVToColor((color.alpha * 255).toInt(), hsv)
    return Color(argb)
}

/**
 * Visual badge dots indicating how many homework items are pending vs completed.
 * Lit (亮): Uncompleted homework task (solid bright accent dot).
 * Dim (灭): Completed homework task (subtle dimmed hollow ring).
 */
@Composable
fun TaskDotsBadge(
    linkedTasks: List<TaskEntity>,
    activeColor: Color,
    modifier: Modifier = Modifier
) {
    if (linkedTasks.isEmpty()) return

    val maxDisplayDots = 4
    val displayTasks = linkedTasks.take(maxDisplayDots)
    val remaining = linkedTasks.size - maxDisplayDots
    val isGreen = isGreenishColor(activeColor)
    val bg = if (isGreen) TeaColor else Color.White.copy(alpha = 0.88f)
    val dotColor = if (isGreen) TeaOnColor else activeColor

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .border(0.5.dp, if (isGreen) TeaColorBorder else activeColor.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
            .padding(horizontal = 3.5.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.5.dp)
    ) {
        displayTasks.forEach { task ->
            if (!task.isCompleted) {
                // 亮: 待办未完成 - 高亮实心圆点
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                )
            } else {
                // 灭: 待办已完成 - 熄灭空心圆点
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(Color.Transparent)
                        .border(1.dp, if (isGreen) Color(0xFF5E4F41) else activeColor.copy(alpha = 0.35f), CircleShape)
                )
            }
        }
        if (remaining > 0) {
            Text(
                text = "+$remaining",
                fontSize = 7.5.sp,
                fontWeight = FontWeight.Bold,
                color = dotColor
            )
        }
    }
}

/**
 * 周课表栅格里的**考试**格。
 *
 * ## 为什么考试也占格子
 *
 * 考试有具体时刻（09:00–11:00），而周课表是**按节次排的时间轴** ——
 * 把考试放进对应的那几行，它才和课落在同一条时间线上：
 * 「周三下午有考试」这件事，在这一格里一眼能看见。
 *
 * 列表页只看接下来三天（见 `buildDayGroups`），更远的考试得靠这里看 ——
 * 翻到考试那一周就摆在对应节次上。
 *
 * ## 它和课程格怎么区分
 *
 * - **底色是深档**：课程格里深档表示「有未完成待办」，
 *   而考试本身就是一件必须到场的事 —— 深档在这里是恰当的强调，不是挪用。
 * - **底部徽标写「期末 / 期中」**：课程格那一行写的是「N 待办」，
 *   位置一样、语义不同，一眼能分。
 * - **跨行**：考试一般横跨两三节，块更高，本身就是视觉信号。
 */
@Composable
fun ExamTimetableCell(
    entry: ExamEntry,
    periodStart: Int,
    periodEnd: Int,
    onClick: () -> Unit,
    conflicted: Boolean = false,
    modifier: Modifier = Modifier
) {
    val baseColor = parseColor(entry.courseColorHex)
    val deepColor = deepEnoughForWhite(baseColor)
    val cellShape = RoundedCornerShape(if (conflicted) 6.dp else 9.dp)
    val span = (periodEnd - periodStart + 1).coerceAtLeast(1)

    Card(
        modifier = modifier
            .fillMaxSize()
            .clip(cellShape)
            .clickable { onClick() }
            .testTag("exam_cell_${entry.id}"),
        shape = cellShape,
        colors = CardDefaults.cardColors(containerColor = deepColor),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = if (conflicted) 2.dp else 4.dp,
                    end = if (conflicted) 2.dp else 4.dp,
                    top = if (conflicted) 8.dp else 3.dp,
                    bottom = 3.dp
                ),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = entry.courseName,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = if (conflicted) 10.5.sp else 12.5.sp
                    ),
                    color = CourseCellOnPending,
                    maxLines = when {
                        conflicted -> 4
                        span >= 2 -> 3
                        else -> 1
                    },
                    overflow = TextOverflow.Ellipsis,
                    fontSize = if (conflicted) 9.sp else 10.5.sp
                )
                // 跨节的考试把时刻写出来：它横跨的节次比课程多，
                // 只看行高说不出「到底是 09:00 还是 14:00 开考」。
                //
                // **只写开始时刻**：一格净宽约 55dp，「09:00-11:00」在 8.5sp 下放不下
                // （实测被截成「09:00-…」，半截时间比没有更让人困惑）。
                // 结束时刻点开编辑弹层就能看到。
                if (span >= 2 && entry.startMinute > 0) {
                    Text(
                        text = formatMinuteOfDay(entry.startMinute),
                        color = CourseCellOnPending.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = if (conflicted) 7.5.sp else 8.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // 底部徽标：期末 / 期中。
            // 样式沿用课程格那枚（磨砂白玻璃底 + 墨黑字）——
            // 同一张表里的徽标必须长得一样，否则读者要重新学一遍。
            Box(
                modifier = Modifier
                    .glassSurface(
                        shape = RoundedCornerShape(5.dp),
                        tint = Color.White,
                        tintAlpha = 0.82f,
                        blurRadius = 0.dp
                    )
                    .padding(horizontal = 3.5.dp, vertical = 1.dp)
            ) {
                Text(
                    text = entry.kind.label,
                    color = TextPrimary,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    fontSize = 7.5.sp,
                    maxLines = 1
                )
            }
        }
    }
}

/**
 * 扁平化课程色块 (Flat course cell).
 *
 * 设计特点：
 * 1. **只有两档，且两档同源**：先从课程主题色压出一档「深」（有未完成待办），
 *    再由它减淡出「浅」（无未完成待办）——色相严格一致，只有深浅之分。
 *    判据是「有没有**未完成**的待办」：从没挂过待办的、和挂了但全部完成的，
 *    走的是同一个浅色档，只在徽标上用 ✓ 区分，不分第三档配色；
 * 2. **两档的课程名都压在 AA 以下**，而且都是用户明示的取舍（要的是减轻视觉注意力）：
 *    深色档白字 [CourseCellOnPending]（蓝 3.81:1 / 青 4.39:1），
 *    浅色档浅灰 [CourseCellOnIdle]（1.98:1），都取在各自的「刚好看得见」附近；
 * 3. **格子里只有课程名一行**：教室位置按要求撤掉了，所以不存在次要文字，
 *    「主/次两级配色」在这里已经整体退场，层级只靠字号和底色深浅；
 * 4. 平涂无描边、无阴影、无左侧指示条，层次全靠圆角与底色深浅撑住；
 * 5. 底部状态徽标：**始终是磨砂白玻璃底 + 墨黑字**，不跟课程色走——
 *    底色深浅已经在表达状态，徽标只负责报数目（有待办报「N 待办」，
 *    全部完成只给一枚 ✓ 圆标，不写「已结清」三个字）；
 * 6. [conflicted] = 该时段和别的课撞了。撞课时格子被压窄，圆角跟着收一点，
 *    排版整体收紧，并在右上角挂一个极小的红点——
 *    挤在半列宽里时，光看颜色分不出哪门课。
 *
 * **格子里没有「有课程备注」的角标**，这是量过的：332dp 的真机上，
 * 一格净宽约 42dp，底部那枚「N 待办」胶囊已经占到离右边缘 6.5dp 以内，
 * 再放一枚图标必然压到胶囊上（实机截图里它读起来像「待办带了个便签」），
 * 放在右上角则要跟课程名抢第一行的宽度。
 * 备注角标改放在**行式**的课程列表上（抽屉的课程行、课表页的「列表」视图），
 * 那里才有位置；点格子进课程详情弹层，备注就在最上面。
 */
@Composable
fun CourseTimetableCell(
    course: CourseEntity,
    slot: CourseSlotEntity,
    linkedTasks: List<TaskEntity>,
    onClick: () -> Unit,
    conflicted: Boolean = false,
    modifier: Modifier = Modifier
) {
    val courseBaseColor = parseColor(course.colorHex)
    val hasLinkedTasks = linkedTasks.isNotEmpty()
    val pendingTasksCount = linkedTasks.count { !it.isCompleted }
    val isAllCompleted = hasLinkedTasks && pendingTasksCount == 0
    val span = (slot.endPeriod - slot.startPeriod + 1).coerceAtLeast(1)

    // 两档状态，**共用同一个基色**：先从课程设置里的主题色压出一档「深」，
    // 再由这一档减淡出「浅」——所以两档色相严格一致，只有深浅之分。
    //
    // 深（有待办）：适度压深 + 白字。白字压原始粉彩是隐形的（#93C5FD 只有 1.6:1），
    // 压到多深由 deepEnoughForWhite 按对比度目标二分，目标 3.8 是用户选定的「适度压深」。
    // 浅（无未完成待办）：把深色那一档朝白推到一个**固定亮度**上，
    // 字退到「刚好看得见」的下限（课程名 ≈1.98:1、教室号 ≈1.36:1）。
    // 「白灰」在这里物理上做不到——#D2D5D4 的亮度 0.660 比底色 0.616 还高，
    // 落在粉底 #F1C1DB 上只有 1.06:1，是彻底抹掉而不是退后，
    // 所以浅色档用的是中灰而不是白灰，这是用户选定的折中。
    //
    // 「有未完成待办」才进深色档：没挂待办、和挂了但全部完成，走的是同一个浅色档，
    // 差别只在徽标（✓ 已结清 / 无徽标），配色上不再分第三档。
    val hasPending = pendingTasksCount > 0
    val deepColor = deepEnoughForWhite(courseBaseColor)
    // 底色亮度被 [pushAwayFrom] 钉死在相对亮度 0.616 附近——注意这里传进去的
    // [CourseKhakiMetaOnIdle] 已经不是「要画出来的字色」，它现在的身份是
    // **背景调亮旋钮**：当初是按「卡其灰教室号要 4.5:1」定的钉点，字改成
    // [CourseCellOnIdle] 之后钉点没跟着动，底色因此与改动前逐像素一致。
    // 想重新调底色深浅时改这里，但要记得它同时决定了所有字色的对比度基数。
    val cellBg: Color =
        if (hasPending) deepColor else pushAwayFrom(deepColor, CourseKhakiMetaOnIdle)

    // 两档的字色只服务课程名这一行。教室号移除之后不存在次要文字，
    // 所以「主/次两级配色」这件事在课表格子上已经整体退场，
    // 层级全靠字号（10.5sp 半粗）和底色深浅。
    val titleColor = if (hasPending) CourseCellOnPending else CourseCellOnIdle

    

    // 撞课的格子被压到半列宽，圆角跟着收一点，否则小格上的圆角会显得笨重
    val cellShape = RoundedCornerShape(if (conflicted) 6.dp else 9.dp)

    // 深色档改成玻璃面，浅色档仍是实色平涂。
    //
    // ⚠️ **别把这件事的收益说成「换壁纸颜色不漂」**——实测不成立：
    // 深色档原本是 100% 不透明的实色块，**壁纸对它零影响**。实测两张完全不同的壁纸
    // （近白粉彩默认壁纸 vs 饱和珊瑚粉）下，绿格的基线主色逐字节相同，都是 (42,146,80)。
    // 也就是说「这一格在不同壁纸上显出的深浅不一样」在改动前就不存在。
    //
    // 真正买到的是另外两件：
    //   1. **摊平壁纸上大尺度的局部色差**。默认/预设壁纸都是极平滑的渐变，糊它等于
    //      没糊，所以这个收益在预设上看不出来；但换自定义照片或有花纹的壁纸时，
    //      格子里那片壁纸先被摊平，卡面不会跟着壁纸的花纹一块亮一块暗——这才是
    //      用户要的「颜色显示平滑均衡」。
    //   2. **材质归位**。这一档此前是全 App 唯一没参与玻璃语言的大色块，卡面上没有
    //      磨砂颗粒、边缘也不做抗锯齿；挂上 hazeEffect 后两者都跟其余卡片一致。
    //
    // 代价有两条，都已量过：卡面各通道**变亮 3~12 级**（6% 的背光透上来），
    // 白字对比度从 3.8:1 降到约 3.4:1。理由与底线见 [CourseCellDeepFaceAlpha]。
    val deepFace = if (hasPending) {
        Modifier.glassSurface(
            shape = cellShape,
            tint = cellBg,
            tintAlpha = CourseCellDeepFaceAlpha,
            blurRadius = CourseCellBlurRadius,
        )
    } else {
        Modifier
    }

    Card(
        modifier = modifier
            .fillMaxSize()
            .clip(cellShape)
            .clickable { onClick() }
            .testTag("course_slot_${course.id}_${slot.id}"),
        shape = cellShape,
        colors = CardDefaults.cardColors(
            containerColor = if (hasPending) Color.Transparent else cellBg
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(deepFace)
        ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // 撞课的格子只有半个日列宽（54dp 的列分两半 ≈ 26dp），
                // 4.dp 的横向内边距一扣就只剩 18.dp，一个汉字都放不下，
                // 所有文字都会折成竖条。内边距和字号都得跟着「紧凑」走。
                .padding(
                    start = if (conflicted) 2.dp else 4.dp,
                    end = if (conflicted) 2.dp else 4.dp,
                    // 撞课的格子里右上角挂着冲突角点，标题不往下让一让的话
                    // 角点会正好压在首行文字上，看着像正文里的一个标点
                    top = if (conflicted) 8.dp else 3.dp,
                    bottom = 3.dp
                ),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top: Course Name & Room
            Column {
                Text(
                    text = course.name,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = if (conflicted) 10.5.sp else 12.5.sp
                    ),
                    color = titleColor,
                    // 行高 48dp 时的可用空间：单节卡内容区约 38dp（48 − 4 外 padding −
                    // 6 内 padding），一行 12.5sp 标题（≈12.5dp）+ 徽标（14dp）+ 内边距
                    // ≈ 32.5dp，一行标题放得下、两行放不下，所以单节仍锁一行；
                    // 连堂两节的内容区约 86dp，三行标题（≈37.5dp）加徽标（14dp）
                    // 才 51.5dp，余量充足。
                    //
                    // 之前这里按 43dp 的老行高写成「连堂两行 → 2 行标题」，加上左侧刻度栏
                    // 让每个日列窄了约 2.9dp，「数据结构与算法」这类 6 字课程名就被
                    // 截成「数据结构与算…」。行高放宽后正好补回这一行。
                    // 撞课的格子只有半个日列宽（≈26dp），行高也调小了，反而能多塞两行。
                    maxLines = when {
                        conflicted -> 4
                        span >= 2 -> 3
                        else -> 1
                    },
                    overflow = TextOverflow.Ellipsis,
                    fontSize = if (conflicted) 9.sp else 10.5.sp
                )

                // 教室位置按要求不再显示：格子里只剩课程名一行，腾出的竖向空间
                // 全给标题（在窄格里本来就要靠多行才放得下）。课程详情弹层里仍有教室号。
            }

            // Bottom: 极简状态徽标
            //
            // 徽标**不跟着课程色走**：始终是磨砂白玻璃底 + 墨黑字。
            // 之前它跟着底色变（绿课换淡茶底、字用 readableOn 压暗的课程色），
            // 结果同一枚徽标在八门课上长出八种样子，而这恰恰是整张表里最该统一的元件——
            // 底色深浅已经在表达「有没有待办」，徽标只负责说数目，不该再叠一层信息。
            //
            // blurRadius 传 0：课程格在 MainActivity 的 hazeSource 里面，
            // 再嵌 hazeEffect 就是自反馈（MainActivity 里那条注释写过同样的道理）。
            // 没有真模糊，但白面 + 玻璃的白色边缘聚光线 + 半透，质感照样立得住。
            if (hasPending) {
                Box(
                    modifier = Modifier
                        .glassSurface(
                            shape = RoundedCornerShape(5.dp),
                            tint = Color.White,
                            tintAlpha = 0.82f,
                            blurRadius = 0.dp
                        )
                        .padding(horizontal = 3.5.dp, vertical = 1.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.5.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(3.5.dp)
                                .clip(CircleShape)
                                .background(TextPrimary)
                        )
                        Text(
                            // 窄格里「N 待办」三个字会折成竖条，只留数字，含义不变
                            text = if (conflicted) "$pendingTasksCount" else "$pendingTasksCount 待办",
                            color = TextPrimary,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            fontSize = 7.5.sp,
                            maxLines = 1
                        )
                    }
                }
            } else if (isAllCompleted) {
                // 只留一个 ✓，不再写「已结清」三个字：徽标能占的竖向空间是硬的，
                // 半节课的格子放不下两行，钩子本身已经足够表意。
                //
                // 顺带从胶囊改成正圆：里面只剩一个字符时，胶囊会在字符两侧空出一段
                // 档，看着像多出来的一颗点而不像一枚徽标；圆形和「N 待办」那枚的
                // 圆角端点也能对上，整套徽标视觉重量一致。
                Box(
                    modifier = Modifier
                        .glassSurface(
                            shape = CircleShape,
                            tint = Color.White,
                            tintAlpha = 0.82f,
                            blurRadius = 0.dp
                        )
                        .size(14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "✓",
                        color = TextPrimary,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        fontSize = 8.sp,
                        maxLines = 1
                    )
                }
            }
        }

        // 撞课角标：挤在半列宽里时，光看颜色分不出哪门课，得有个显式标记。
        // 这里原来写的是「冲突」两个字，但在 26dp 的格子里必然折成竖排，
        // 还会盖住标题首行——改成不占文字宽度的角点：白圈 + 红心，
        // 浅色深色格子上都跳得出来，语义是「注意，这门课和别的撞了」。
        if (conflicted) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(2.5.dp)
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.8f)),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(4.dp)
                        .clip(CircleShape)
                        .background(AlertRed)
                )
            }
        }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseLinkageSheet(
    courseWithSlots: CourseWithSlots,
    linkedTasks: List<TaskEntity>,
    /** 用户新建的待办类型，解析待办列表里的类别徽标用。 */
    customTypes: List<CustomTaskType>,
    periodTable: PeriodTable = PeriodTable.DEFAULT,
    slotWeeks: Map<Long, Set<Int>> = emptyMap(),
    totalWeeks: Int = 25,
    onDismiss: () -> Unit,
    onToggleTaskComplete: (TaskEntity) -> Unit,
    onToggleTaskStar: (TaskEntity) -> Unit = {},
    onInspectTask: (TaskEntity) -> Unit = {},
    onAddNewTaskForCourse: () -> Unit,
    onEditCourse: () -> Unit,
    onDeleteCourse: () -> Unit
) {
    val course = courseWithSlots.course
    val slots = courseWithSlots.slots
    val courseColor = parseColor(course.colorHex)

    GlassBottomSheetHost(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // **必须可滚**：底部弹层是底部对齐的，内容比屏幕高时多出来的那截
                // 会直接掉到屏幕外 —— 面板本身不封顶（`GlassBottomSheetHost` 没有
                // `heightIn(max=…)`），所以这里不给上限，内容就是被裁掉而不是能滚到。
                //
                // 后果很具体：系统字号 2.0 时，这张弹层最下面的「编辑课程 / 删除课程」
                // 两个按钮会掉出屏幕，**永远点不到**（实测 uiautomator dump 里根本没有
                // 这两个节点）。加了这个滚动就能滚到。
                //
                // 顺带说一句：这不是备注引入的 bug —— 加备注之前那 2 个按钮就已经在
                // 屏幕外了（只差 ~18px），备注只是把差距拉大到 ~165px。
                // 别的底部弹层（待办详情）一直用 [SheetContent]，它带滚动。
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 4.dp, bottom = 12.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(courseColor)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = course.name,
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                    )
                }
                val isGreen = isGreenishColor(courseColor)
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isGreen) TeaColor else courseColor.copy(alpha = 0.15f))
                        .border(0.5.dp, if (isGreen) TeaColorBorder else courseColor.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = course.category,
                        color = if (isGreen) TeaOnColor else readableOn(courseColor),
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Course Info & Multi-Slot schedule Card
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        // 和页面上那张课程卡同一套参数：同一门课在弹层里和页面上
                        // 必须是同一个样子，不能因为背后的底不同就换一档配色。
                        .glassSurface(shape = RoundedCornerShape(18.dp), tint = cardTint(courseColor, CourseTintAlpha), tintAlpha = CourseTintAlpha, baseCoatAlpha = CardBaseCoatAlpha)
                        .padding(14.dp)
                ) {
                    val distinctRooms = slots
                        .map { it.classroom.ifBlank { course.classroom } }
                        .filter { it.isNotBlank() }
                        .distinct()
                    val hasMultipleRooms = distinctRooms.size > 1
                    val commonRoom = when {
                        distinctRooms.size == 1 -> distinctRooms.first()
                        hasMultipleRooms -> "多地点（共 ${distinctRooms.size} 处）"
                        course.classroom.isNotBlank() -> course.classroom
                        else -> "未指定地点"
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(end = 12.dp)
                        ) {
                            Icon(
                                Icons.Outlined.Person,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp),
                                tint = accentOnCourse(courseColor)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                // 全角冒号：中文正文里半角「:」跟英文标点排在一起
                                // 是一处刺眼的例外，App 别处（「贡献者：」等）用的都是全角。
                                text = "教师：${course.teacher.ifBlank { "未指定" }}",
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.End,
                            modifier = Modifier.weight(1f, fill = false)
                        ) {
                            Icon(
                                Icons.Outlined.LocationOn,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp),
                                tint = accentOnCourse(courseColor)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "地点：$commonRoom",
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.End
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = "每周时段 · ${slots.size} 次",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = accentOnCourse(courseColor)
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    slots.sortedWith(compareBy({ it.dayOfWeek }, { it.startPeriod })).forEach { s ->
                        val (sTime, eTime) = periodTable.timeRange(s.startPeriod, s.endPeriod) ?: ("--" to "--")
                        val weekLabel = slotWeeks[s.id]?.takeIf { it.isNotEmpty() }
                            ?.let { WeekSet.describe(it, totalWeeks) }
                        val specificRoom = s.classroom.ifBlank { course.classroom }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = RoundedCornerShape(5.dp),
                                color = accentOnCourse(courseColor).copy(alpha = 0.12f),
                                border = BorderStroke(0.5.dp, accentOnCourse(courseColor).copy(alpha = 0.25f))
                            ) {
                                Text(
                                    text = getDayName(s.dayOfWeek),
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = accentOnCourse(courseColor),
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = formatPeriodRange(s.startPeriod, s.endPeriod),
                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "($sTime-$eTime)",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (hasMultipleRooms && specificRoom.isNotBlank()) {
                                    Text(
                                        // 原来写「📍 教室名」：emoji 当标签用，
                                        // 而这个 App 的标签一向是图标或文字，
                                        // 「@」那枚早被设计笔记判成程序员语法退场了，
                                        // emoji 也只是同一类问题的另一种写法。
                                        // 这一行说的是**这一节**在哪，跟上面那行
                                        // 课程级的「地点」不是同一个口径，得说清楚。
                                        text = "本节地点：$specificRoom",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = accentOnCourse(courseColor),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            if (weekLabel != null) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Surface(
                                    shape = RoundedCornerShape(5.dp),
                                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                                    border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                                ) {
                                    Text(
                                        text = weekLabel,
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }
                        }
                    }

                    // 课程备注：只有用户写过的才显示。
                    //
                    // 没写就不占地方 —— 这个弹层是「这门课的全部信息」，
                    // 塞一条「备注：无」进去只会让人以为漏填了。
                    //
                    // 放在时段列表**之后**：备注是补充，时段是主信息，
                    // 顺序跟着「先要紧的后补充」走。
                    if (course.note.isNotBlank()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.Top) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.StickyNote2,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp),
                                tint = accentOnCourse(courseColor)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = course.note,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Linked Tasks Title + Add Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.Assignment,
                        contentDescription = null,
                        tint = readableOn(courseColor),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "关联待办 · ${linkedTasks.size}",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    if (linkedTasks.isNotEmpty()) {
                        Spacer(modifier = Modifier.width(6.dp))
                        TaskDotsBadge(linkedTasks = linkedTasks, activeColor = courseColor)
                    }
                }

                val btnContentColor = if (isLightColor(courseColor)) Color(0xFF1E2D0E) else Color.White
                Button(
                    onClick = onAddNewTaskForCourse,
                    contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = courseColor,
                        contentColor = btnContentColor
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.testTag("add_task_for_course_button")
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp), tint = btnContentColor)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("新增待办", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = btnContentColor)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Tasks List
            if (linkedTasks.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "暂无待办，点上方「新增待办」",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(panelScrollMaxHeight(200.dp)),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(linkedTasks, key = { it.id }) { task ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                            shape = RoundedCornerShape(14.dp),
                            elevation = CardDefaults.cardElevation(0.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .glassSurface(
                                        shape = RoundedCornerShape(14.dp),
                                        tint = Color.White,
                                        // 外层课程卡已经染成这门课的粉彩了，内层再染同一个色
                                        // 就成了色块叠色块。这里反过来用近白做挖入，
                                        // 层次靠「浅在深上」而不是再加一层同色。
                                        tintAlpha = NestedFaceAlpha,
                                        // 已完成的压扁，和 TaskItemCard 的处理保持一致
                                        faceAlphaScale = if (task.isCompleted) 0.78f else 1f
                                    )
                                    .clickable { onInspectTask(task) }
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val isTaskGreen = task.isCompleted || isGreenishColor(courseColor)
                                Box(
                                    modifier = Modifier
                                        .size(26.dp)
                                        .clip(CircleShape)
                                        .background(if (isTaskGreen) TeaColor else courseColor.copy(alpha = 0.15f))
                                        .clickable { onToggleTaskComplete(task) }
                                        .testTag("course_sheet_task_check_${task.id}"),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = if (task.isCompleted) Icons.Outlined.CheckCircle else Icons.Outlined.Schedule,
                                        contentDescription = if (task.isCompleted) "标记为未完成" else "标记为已完成",
                                        // 图标 tint 同理：粉彩黄/紫在近白底上等于隐形
                                        tint = if (isTaskGreen) TeaOnColor else readableOn(courseColor),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        // 留了空白的类型不画徽标，后面的 6dp 也跟着收掉——
                                        // 否则标题左边会恒定多出一道没有来由的缩进。
                                        TaskTypes.resolve(task.taskType, customTypes)?.let {
                                            TaskTypeBadge(taskTypeStr = task.taskType, customTypes = customTypes)
                                            Spacer(modifier = Modifier.width(6.dp))
                                        }
                                        Text(
                                            text = task.title,
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                fontWeight = if (task.isCompleted) FontWeight.Normal else FontWeight.SemiBold,
                                                textDecoration = if (task.isCompleted) TextDecoration.LineThrough else TextDecoration.None
                                            ),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        // 没设截止时 dueDateString 是空串，直接拼会留下一个
                                        // 光秃秃的「截止 」——那是在问用户一个答案不存在的问题。
                                        text = if (TaskEntity.hasDue(task)) {
                                            "截止 ${task.dueDateString}"
                                        } else {
                                            NO_DUE_TEXT
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                TaskStarToggle(
                                    isStarred = TaskStar.isMarked(task.priority),
                                    onToggle = { onToggleTaskStar(task) },
                                    unstarredColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                                    modifier = Modifier.testTag("course_sheet_task_star_${task.id}")
                                )
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(vertical = 12.dp))

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onEditCourse,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    // 不写 tint 会吃 OutlinedButton 的默认 contentColor（主题绿 #BCE472），
// 压在近白弹层底上只有 1.4:1，等于看不见。图标跟着下面这行文字走。
                    Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurface)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("编辑课程", color = MaterialTheme.colorScheme.onSurface)
                }
                OutlinedButton(
                    onClick = onDeleteCourse,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("删除课程")
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}
