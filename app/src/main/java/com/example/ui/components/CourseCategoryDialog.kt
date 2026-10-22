package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Assignment
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.local.CourseWithSlots
import com.example.data.model.COURSE_CATEGORIES
import com.example.ui.theme.TextSecondary

/**
 * 「还没点任何一门课」的哨兵值。
 *
 * 课程 id 是 Room 自增主键（正数），-1 不会是真实 id，拿它当占位最稳。
 * 为什么不用 null：rememberSaveable 的存档通道只认基本类型，
 * 塞一个可空值进去要么编译不过，要么在某些版本上走到
 * 「这个值存不进 Bundle」那条报错分支。
 */
private const val NO_COURSE_SELECTED = -1L

/**
 * 课程分类弹层：**顶部一行课程，点哪张看哪张，主体是类别选项**。
 *
 * ## 为什么不是一张长长的课程列表
 *
 * 上一版把所有课程竖着列出来，一屏装不下就变成「弹层高到占满整屏」——
 * 十二门课就滚半天，而且弹层的高度随课程数增长，课多的人根本用不下去。
 * 分类是个**一门一门过**的活：一次只看一门，看完再换下一门。
 *
 * 所以课程收成顶部**一行卡片**，下面整块留给五个类别选项。
 * 弹层高度于是**与课程数无关**：三门课和三十门课一样高。
 *
 * ## 交互：点一门课 → 选一个类别 → 存库
 *
 * - 顶部一行：每张卡片是一门课（色条 + 课程名 + 它当前的类别）。
 *   **点哪张就切到哪张**（选中的那张高亮）。
 * - 主体：当前课程的五个类别选项，选中的那个是选中的态。
 * - 点一个选项 → **立刻存库**（只改类别，不动那三个待办开关 ——
 *   那是用户自己的值），**然后留在这一门**。
 *   换下一门由顶部那行**点** —— 节奏由用户自己掌握。
 *   （这一版之前是「选完自动跳下一门」，用户反馈取消了：那一跳是 App 替用户
 *   做的决定，他还没看清刚改的那一门就被带走了，改了什么、还剩几门全得回头找。）
 * - 没有「保存」按钮：每门课的类别各自独立，没有「一起提交」这回事，
 *   多一层暂存就多一份「改了到底存没存」的心智负担。
 * - 点当前已选中的那个选项：不写库、不提示（单选列表的常规行为）。
 *
 * ## 为什么不再横着滑切换
 *
 * 那一行原来是个分页器：横着滑就换一门，滑到哪门，下面就给哪门选类别。
 * 用户反馈要的是**点**。三条理由：
 *
 * 1. **横滑是看不见的状态变化**：手指还压在卡片上，滑到一半根本不知道
 *    停在哪一门；下面那一大块选项跟着换，还得低头看下面才知道换没换。
 * 2. **会误改**：滑动的惯性还没停，拇指一抬就落在下面某个类别选项上 ——
 *    那一门课就被改了，而且改完那一行还停在别的门上，很难一眼看出改错了门。
 * 3. **点是有目标的**：点了哪张卡片，那张卡片就高亮着，「现在看的是哪门」
 *    一眼可见，不用从「下面这堆选项是给谁的」反推。
 *
 * 横着滑**仍然能滑**（课程多了要往回看别的课），但滑只负责**浏览**，
 * 不再决定「当前是哪门」。
 *
 * ## 当前是哪门：由**点中的那张**说了算
 *
 * 记的是**课程 id**，不是下标，也不是「第一张可见项」。
 * 列表会变（改完类别会刷新、课可能增删），按下标记的话，
 * 前面少一门，后面每门都会**错位一号** —— 看着在给 A 选类别，其实改的是 B。
 *
 * ## 一门课都没有时
 *
 * 空态给一句「还没有课程」和「去添加课程」。那个添加弹层是**叠在本弹层上面**的，
 * 本弹层仍然开着 —— 所以引导不会把这一步误判成做完了。
 */
@Composable
fun CourseCategoryDialog(
    courses: List<CourseWithSlots>,
    /** 选定了某门课的某个类别。调用方负责落库并给一句提示。 */
    onSelectCategory: (CourseWithSlots, String) -> Unit,
    /** 空态里的「去添加课程」。 */
    onAddCourse: () -> Unit,
    onDismiss: () -> Unit
) {
    // 当前看的是哪门课 = 顶部那一行里**被点中的那张**。
    //
    // 记 id 不记下标（理由见文件头的说明）：课程列表会变，
    // 按下标记的话，删掉前面那门，后面每门都会错位一号。
    var selectedCourseId by rememberSaveable { mutableStateOf(NO_COURSE_SELECTED) }
    val current = resolveSelectedCourse(
        courses,
        selectedCourseId.takeIf { it != NO_COURSE_SELECTED }
    )

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
                        imageVector = Icons.Outlined.Assignment,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "课程分类",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (courses.isEmpty()) {
                    CategoryEmptyState(onAddCourse = onAddCourse)
                } else {
                    // 顶部一行课程卡片：**点哪张就切到哪张**。
                    // 横着滑只是「看看后面还有哪些课」，**不再自动换当前课**。
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(horizontal = 2.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("course_category_strip")
                    ) {
                        items(items = courses, key = { it.course.id }) { course ->
                            CourseCategoryTab(
                                course = course,
                                selected = course.course.id == current?.course?.id,
                                onClick = { selectedCourseId = course.course.id }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // 一句提示：现在换课要**点**，不再是滑。
                    // 交互从滑改成点之后，这一行是唯一告诉用户「怎么换」的地方。
                    Text(
                        text = "点上面的课程卡片切换",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // 主体：当前课程的五个类别选项。
                    current?.let { course ->
                        Text(
                            text = "给「${course.course.name}」选类别",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        COURSE_CATEGORIES.forEach { category ->
                            CategoryOptionRow(
                                label = category,
                                selected = category == course.course.category,
                                onClick = {
                                    // 选完**留在这一门**：换下一门由顶部那行**点**。
                                    // 之前这里是「选完自动跳下一门」，用户反馈取消 ——
                                    // 那一跳是 App 替用户做的决定，他还没看清刚改的那一门
                                    // 就被带走了，于是改了什么、还剩几门，全得回头找。
                                    if (shouldPersistCategory(course.course.category, category)) {
                                        onSelectCategory(course, category)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                // 改动是逐条落库的，这个按钮不「保存」任何东西 —— 它只是「分完了，收摊」。
                onClick = onDismiss,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text("完成", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
            }
        }
    )
}

/**
 * 选了类别之后要不要写库。
 *
 * 选的就是当前类别时**不写**：一次「什么都没发生」不该配一句「已归到 X」，
 * 也不该动数据库（省一次写，省一次列表刷新）。
 */
internal fun shouldPersistCategory(currentCategory: String, pickedCategory: String): Boolean =
    currentCategory != pickedCategory

/**
 * 当前该显示哪门课。
 *
 * 认的是**课程 id**，不是下标，也不是「第一张可见项」：
 * 课程列表会变（改完类别会刷新、课可能增删），按下标记的话，
 * 前面少一门，后面每门都会**错位一号** —— 看着在给 A 选类别，其实改的是 B。
 *
 * 还没点过（null）→ 第一门；点过、但那门已经不在列表里 → 退回第一门。
 */
internal fun resolveSelectedCourse(
    courses: List<CourseWithSlots>,
    selectedCourseId: Long?
): CourseWithSlots? =
    courses.firstOrNull { it.course.id == selectedCourseId } ?: courses.firstOrNull()

/**
 * 顶部那一行里的一门课。
 *
 * 两行：色条 + 课程名，下面是它当前的类别。类别那一行不是装饰 ——
 * 用户要靠它一眼看出哪几门还没分（导入进来的课都落在「公共基础」，
 * 分过的和没分过的在这行里长得一样就没法一眼看出来了）。
 *
 * 定宽 140dp：一次看见两三门课，比整行满宽更像个「可以挑」的列表；
 * 宽度固定才看得出这是一排同类的东西。
 */
@Composable
private fun CourseCategoryTab(
    course: CourseWithSlots,
    selected: Boolean,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(140.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surface
            )
            .border(
                1.dp,
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                RoundedCornerShape(12.dp)
            )
            // selectable 而不是 clickable：它带「选中」语义，
            // 读屏软件能念出「已选中」——这一行现在就是一组单选按钮。
            .selectable(selected = selected, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .testTag("course_category_tab_${course.course.id}")
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(4.dp, 14.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(parseColor(course.course.colorHex))
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = course.course.name,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = course.course.category,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
            else TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 一个类别选项：左边一个选中标记，右边是名字。整行是落点。
 *
 * 用「单选」的形态而不是一排小 chip：主体是选项，就得让每个选项都够大、
 * 一眼看出选中了哪一个 —— 一排 11sp 的 chip 既难点又看不出选中态。
 */
@Composable
private fun CategoryOptionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surface
            )
            .border(
                1.dp,
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                RoundedCornerShape(12.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("course_category_option_$label"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .background(
                    if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surface
                )
                .border(
                    1.5.dp,
                    if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline,
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                Icon(
                    imageVector = Icons.Outlined.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(12.dp)
                )
            }
        }
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurface
        )
    }
}

/** 一门课都没有时的样子：说清现在缺什么，并给出去补的入口。 */
@Composable
private fun CategoryEmptyState(onAddCourse: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "还没有课程",
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "先加一门课，再回来给它定类别。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(14.dp))
        OutlinedButton(
            onClick = onAddCourse,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.testTag("course_category_empty_add")
        ) {
            Text("去添加课程", color = MaterialTheme.colorScheme.onSurface)
        }
    }
}
