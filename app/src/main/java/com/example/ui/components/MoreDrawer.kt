package com.example.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Rule
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.calendar.EpochDays
import com.example.data.local.CourseWithSlots
import com.example.ui.theme.CardBlockGap
import com.example.ui.theme.PageHInset
import com.example.ui.theme.TextTertiary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.glassSurface
import com.example.ui.viewmodel.MainUiState

/**
 * 顶栏展开出来的「更多」页。
 *
 * **覆盖全屏，不留缝**：半幅面板会让人以为底下那一半还能点，于是想点被挡住的
 * 内容时只会得到「没反应」。这一层本来就是一次明确的「换个地方操作」，
 * 铺满之后它就是一个独立的页面。
 *
 * 于是**遮罩也一并删掉**：面板已经盖满整屏，遮罩完全被压在下面，
 * 只剩一层看不见却仍参与命中测试的开销。关闭只剩右上角 × 和系统返回键两条路。
 *
 * **它现在同时是设置页的一半**：账号、学期、生成规则、课程管理都收在这里，
 * 设置页只留下导入导出和演示数据。所以这一页要滚动，也因此用 `LazyColumn`——
 * 课程多起来时（一个学期 9~10 门）一次性量全部高度会白算一屏。
 *
 * **面是真·毛玻璃**（`glassSurface` + 同一个 `HazeState`），和浮岛同一套语言：
 * 它压在页面内容之上，底下滚过去的卡片要能被糊掉，否则就是一块盖住整屏的实心板。
 * 因此它必须渲染在 `hazeSource` 之后——和浮岛一样的位置要求。
 *
 * **三级文字用 `TextTertiary` 而不是 `TextMuted`**：这一页是玻璃面，背后有卡片颜色在动，
 * 实测 `TextMuted`（#78827B）压在上面只有 3.48:1，而脚注是 10.5.sp、分区标题 11.sp，
 * 都属普通小字、过不了 AA。`TextTertiary`（#3B433F）实测 8.5:1 ——
 * 层级仍然比标题弱一档，但不靠牺牲可读性换层级。
 *
 * **「本学期课程」这一区可折叠**：它是最长的一块，九门课能把「设置」入口顶到屏外，
 * 而它在这一页里只是「一次性检查一遍」的内容，不是要反复对照着用的东西。
 * 状态放在 `AnimatedVisibility` **之外**：抽屉面板本身是常驻组合的，只有可见时才组合内容，
 * 折叠状态写在内容里会在每次关闭抽屉时一起被丢掉，用户收起一次、点开又得重收一遍。
 * 默认展开：折叠是用户自己按出来的，不该让「第一次打开」先变成一屏标题。
 */
@Composable
fun MoreDrawer(
    visible: Boolean,
    state: MainUiState,
    onDismiss: () -> Unit,
    onOpenCloudAccount: () -> Unit,
    onOpenAcademicCalendar: () -> Unit,
    onOpenCategoryRules: () -> Unit,
    onOpenTaskRules: () -> Unit,
    onOpenAddCourse: () -> Unit,
    onEditCourse: (CourseWithSlots) -> Unit,
    onDeleteCourse: (CourseWithSlots) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val semester = state.semester
    val termConfigured = semester != null && semester.firstMondayEpochDay > 0L
    val termName = semester?.name?.ifBlank { null }
        ?: if (termConfigured) "本学期" else "未设置学期"

    // 见文件头：写在 AnimatedVisibility 外面，跨「关抽屉→再开」保留。
    var coursesExpanded by rememberSaveable { mutableStateOf(true) }
    // 没有课程时不给折叠开关：这时候折起来什么也看不见，只会让「课程不见了」和
    // 「我收起来了」两种状态长得一模一样，而空态提示（唯一有用的信息）不该跟着折没。
    val coursesCollapsible = state.coursesWithSlots.isNotEmpty()

    // 系统返回键 = 收起这一页，而不是退出 App。
    // 少了这一行，用户在抽屉里按返回会直接回到桌面——这是全屏覆盖层最常见的
    // 漏项，而且只有真按一次键才会发现，单测和编译都测不出来。
    BackHandler(enabled = visible) { onDismiss() }

    AnimatedVisibility(
        visible = visible,
        // **纯淡入淡出，没有位移**。曾经试过从顶栏往下展开（slideInVertically），
        // 但那一页是全屏的：面板高度和屏幕一样高，滑进滑出时露出的那一截
        // 永远只是「被顶边或底边切掉的一行字」，读起来像卡片被拖走了一段，
        // 而不是这一页在打开。位移要成立得有一个可辨认的边界，全屏层没有。
        //
        // 所以只留透明度这一条线索，时长比带位移时略短一点：淡入到一半时
        // 底下的页面已经完全透得出来，位移再慢也只会让人等。
        enter = fadeIn(tween(200, easing = LinearOutSlowInEasing)),
        exit = fadeOut(tween(160, easing = FastOutLinearInEasing)),
        modifier = modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .glassSurface(
                    shape = RoundedCornerShape(0.dp),
                    blurRadius = 96.dp,
                    faceAlphaScale = 1f
                )
                // 面板自己吃掉点击：这一层盖住了整屏，命中测试向下找时
                // 会落到背后的页面卡片上，点一下「更多」标题就等于点了底下的课。
                // indication 置 null，让它只消费事件、不留涟漪。
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) {}
                .testTag("more_drawer")
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = PageHInset),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                item {
                    Column {
                        Spacer(modifier = Modifier.statusBarsPadding().height(4.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "更多",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            // 关闭是这一页自己的落点：点遮罩已经不存在了，
                            // 系统返回键是另一条路。
                            Box(
                                modifier = Modifier
                                    .size(30.dp)
                                    .clip(RoundedCornerShape(9.dp))
                                    .clickable { onDismiss() }
                                    .testTag("more_drawer_close_button"),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Close,
                                    contentDescription = "关闭",
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(17.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(CardBlockGap))

                        DrawerRow(
                            icon = Icons.Outlined.CloudSync,
                            title = "浙大学号",
                            detail = if (state.boundStudentId == null) {
                                "未绑定 · 点此登录浙大教务"
                            } else {
                                state.boundStudentId
                            },
                            detailMuted = state.boundStudentId == null,
                            footnote = if (state.isDemoMode) "演示数据模式" else null,
                            testTag = "drawer_account_row",
                            onClick = onOpenCloudAccount
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        DrawerRow(
                            icon = Icons.Outlined.CalendarMonth,
                            title = "当前学期",
                            detail = termName,
                            detailMuted = !termConfigured,
                            footnote = if (termConfigured) {
                                "第 1 周 = ${EpochDays.formatCn(semester!!.firstMondayEpochDay)}"
                            } else {
                                "未设置 · 开课周次暂不生效"
                            },
                            testTag = "drawer_semester_row",
                            onClick = onOpenAcademicCalendar
                        )

                        Spacer(modifier = Modifier.height(CardBlockGap))
                        DrawerSectionLabel("生成规则")

                        // **两个入口而不是合成一个**：它们是两个各自独立的弹层
                        // （按类别设默认值 / 按课程设节奏与截止），合成一行就得
                        // 再套一层二级选择才能选到其中之一——为了少一行而多一次点击，
                        // 不划算。原来它们在设置页里也是并排的两张卡。
                        DrawerRow(
                            icon = Icons.Outlined.Tune,
                            title = "课程联动规则",
                            // 文案跟着语义走：这一行不再是「设个默认值、课程级可以覆盖」，
                            // 而是**批量覆盖**该类别下所有课程的开关。写「默认值」会让人
                            // 以为不动它就不生效，进弹层看到全量覆盖就变成了意外。
                            detail = "按类别批量设置开关",
                            footnote = "会覆盖该类别下所有课程",
                            testTag = "drawer_category_rules_row",
                            onClick = onOpenCategoryRules
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        DrawerRow(
                            icon = Icons.Outlined.Rule,
                            title = "待办生成规则",
                            detail = "按课程设定节奏与截止",
                            testTag = "drawer_task_rules_row",
                            onClick = onOpenTaskRules
                        )

                        Spacer(modifier = Modifier.height(CardBlockGap))
                        DrawerSectionLabel(
                            text = "本学期课程 · ${state.coursesWithSlots.size}",
                            actionText = "添加",
                            onAction = onOpenAddCourse,
                            expanded = coursesExpanded,
                            onToggleExpand = if (coursesCollapsible) {
                                { coursesExpanded = !coursesExpanded }
                            } else {
                                null
                            }
                        )
                    }
                }

                if (state.coursesWithSlots.isEmpty()) {
                    item {
                        Text(
                            text = "还没有课程 · 先从课表导入，或点右上角添加",
                            fontSize = 11.sp,
                            color = TextTertiary,
                            modifier = Modifier.padding(start = 12.dp, top = 2.dp, bottom = 8.dp)
                        )
                    }
                }

                // **收起时是「不 emit」而不是把行折成 0 高**：这些行在 `LazyColumn` 里，
                // 而 `LazyColumn` 只测量可见区，`AnimatedVisibility` 那种「父容器自己长高/变矮」
                // 的动画在这里无从测量——它会拿不到子项高度，要么不动要么直接塌。
                // 展开侧用逐行错峰淡入补上过程感（见 [CourseRowReveal]），
                // 收起侧就是一次性的收起：那本来就是用户主动按下去的动作，干净利落更合适。
                if (!coursesCollapsible || coursesExpanded) {
                    itemsIndexed(state.coursesWithSlots, key = { _, cWithSlots -> cWithSlots.course.id }) { index, cWithSlots ->
                        CourseRowReveal(index = index) {
                            CourseDrawerRow(
                                courseWithSlots = cWithSlots,
                                onEdit = { onEditCourse(cWithSlots) },
                                // 这里不直接删：列表密集，删除图标挨着相邻课程，
                                // 手滑一下就是不可逆的误删。交给二次确认弹层处理。
                                onDelete = { onDeleteCourse(cWithSlots) }
                            )
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(CardBlockGap))
                    DrawerSectionLabel("其他")

                    DrawerRow(
                        icon = Icons.Outlined.School,
                        title = "设置",
                        detail = "整机快照 · 演示数据",
                        testTag = "drawer_settings_row",
                        onClick = onOpenSettings
                    )

                    Spacer(modifier = Modifier.navigationBarsPadding().height(8.dp))
                }
            }
        }
    }
}

/**
 * 抽屉里的分区小标题；带一个可选的右侧动作（课程区的「添加」用它）。
 *
 * 还可以再带一个**折叠箭头**（`onToggleExpand != null` 时出现）：给了它，整行就是一个
 * 开关的落点，而不是只把箭头那一小块做成可点——箭头 18dp 不到手指好按的尺寸，
 * 只让箭头可点等于要瞄准。
 *
 * 折叠行额外有 32dp 的最小高度和圆角裁剪：没有圆角，涟漪会铺到整个行宽上像一条色块；
 * 没有最小高度，这一行（11.sp 文字 + 8.dp 下边距 ≈ 23dp）比页面里其它可点的行都矮一截。
 */
@Composable
private fun DrawerSectionLabel(
    text: String,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
    expanded: Boolean = true,
    onToggleExpand: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onToggleExpand != null) {
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onToggleExpand() }
                        .defaultMinSize(minHeight = 32.dp)
                        .testTag("drawer_section_toggle")
                } else {
                    Modifier
                }
            )
            .padding(start = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextTertiary,
            modifier = Modifier.weight(1f)
        )
        if (actionText != null && onAction != null) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onAction() }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .testTag("drawer_section_action"),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(3.dp))
                Text(
                    text = actionText,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
        // 箭头放在「添加」右边、整行最末：和这一页其它行的行尾箭头同一位置，
        // 收起时指向右（还能往下走），展开时朝下（就停在这儿）。
        if (onToggleExpand != null) {
            Spacer(modifier = Modifier.width(2.dp))
            Icon(
                imageVector = if (expanded) {
                    Icons.Outlined.KeyboardArrowDown
                } else {
                    Icons.AutoMirrored.Outlined.KeyboardArrowRight
                },
                contentDescription = if (expanded) "收起课程列表" else "展开课程列表",
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/**
 * 课程行逐条错峰淡入。
 *
 * **只动不透明度，不动位置**：行与行之间是共享边界的（行高按内容算、间距只有 8dp），
 * 一旦让它们各自往下滑，九门课就会在展开时依次「掉下来」，读起来像页面在加载。
 *
 * 错峰步长 30ms、上限 210ms：九门课最后一行的延迟也仍在 200ms 出头，
 * 一次展开看完也就 0.4s；再多就不像「展开」而像「在等」。
 */
@Composable
private fun CourseRowReveal(
    index: Int,
    content: @Composable () -> Unit
) {
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        alpha.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = 170,
                delayMillis = (index * 30).coerceAtMost(210)
            )
        )
    }
    Box(
        modifier = Modifier.graphicsLayer {
            this.alpha = alpha.value
        }
    ) {
        content()
    }
}

/**
 * 抽屉里的一行：左侧图标 + 标题 + 值（值在标题下面）+ 行尾箭头。
 *
 * 整行可点，行尾箭头只是方向提示——一个落点，不多不少。
 * 行的面**不画描边**：这一页自己已经是毛玻璃面，行再描一道边会变成格子纸。
 *
 * 图标带一块 40dp 的近白圆角底：全屏页面上没有别的框把它和背景分开，
 * 一颗有底的图标就顺手承担了「这是一行入口」的分组作用（和设置页的
 * `SettingsActionCard` 同一套语言）。
 */
@Composable
private fun DrawerRow(
    icon: ImageVector,
    title: String,
    detail: String,
    testTag: String,
    onClick: () -> Unit,
    detailMuted: Boolean = false,
    footnote: String? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 10.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(21.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextTertiary
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = detail,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                // 未绑定的学号 / 未设置的学期要用次级墨色：它们是「还没有的值」，
                // 和已填好的值用同一个字重会让人以为真填过。
                color = if (detailMuted) TextSecondary else MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (footnote != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = footnote,
                    fontSize = 10.5.sp,
                    color = TextTertiary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp)
        )
    }
}

/**
 * 抽屉里的课程行。
 *
 * **比设置页那张课程卡瘦得多**：这一页已经有七八行了，课程每行再占三行信息
 * （类别胶囊 / 地点老师 / 生成开关），九门课一进来就要滚很久才能看到下面的
 * 「设置」入口。这里只留**课名 + 时段**——这两项就是「我排了什么课」的全部；
 * 其余细节点进编辑弹层看。
 *
 * 整行可点 = 编辑（和设置页原卡片一致），右侧另外给删除。
 * 这两个是**不同的动作**，不是同一行的两个落点，所以各挂一个 32dp 的点击区
 * （不用 `IconButton`：它的 `minimumInteractiveComponentSize` 不看父级高度）。
 */
@Composable
private fun CourseDrawerRow(
    courseWithSlots: CourseWithSlots,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val course = courseWithSlots.course
    val courseColor = parseColor(course.colorHex)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable { onEdit() }
            .padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp)
            .testTag("drawer_course_${course.id}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 课程色竖条：卡面是全屏玻璃、没有染色，留一根实色才能一眼认出是哪门课。
        Box(
            modifier = Modifier
                .size(5.dp, 34.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(courseColor)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = course.name,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = courseWithSlots.getScheduleSummary(),
                fontSize = 11.sp,
                color = TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.width(6.dp))
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(9.dp))
                .clickable { onEdit() }
                .testTag("drawer_course_edit_${course.id}"),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.Edit,
                contentDescription = "编辑课程",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(17.dp)
            )
        }
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(9.dp))
                .clickable { onDelete() }
                .testTag("drawer_course_delete_${course.id}"),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.DeleteOutline,
                contentDescription = "删除课程",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(17.dp)
            )
        }
    }
}
