package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.di.AppContainer
import com.example.ui.theme.LocalGlassHazeState
import com.example.ui.theme.LocalCardRevealProgress
import com.example.ui.components.AcademicCalendarDialog
import com.example.ui.components.CategoryRulesDialog
import com.example.ui.components.CloudAccountDialog
import com.example.ui.components.CourseEditDialog
import com.example.ui.components.CourseLinkageSheet
import com.example.ui.components.DeleteCourseConfirmDialog
import com.example.ui.components.FloatingIslandNav
import com.example.ui.components.IslandBottomInset
import com.example.ui.components.MoreDrawer
import com.example.ui.components.DeleteTaskTypeConfirmDialog
import com.example.ui.components.NewTaskTypeDialog
import com.example.ui.components.QuickGenerateDialog
import com.example.ui.components.ScheduleTransferDialog
import com.example.ui.components.TaskCategoryManageDialog
import com.example.ui.components.TaskDetailSheet
import com.example.ui.components.TaskEditDialog
import com.example.ui.components.TaskRulesDialog
import com.example.ui.components.WeekPickerDialog
import com.example.ui.screens.ChecklistScreen
import com.example.ui.screens.DashboardScreen
import com.example.ui.screens.ScheduleScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.AppBackgroundLayer
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.OverlayHazeState
import com.example.ui.theme.PageHInset
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.TopBarActionSize
import com.example.ui.theme.TopBarHeight
import com.example.ui.theme.rememberCardRevealProgress
import com.example.ui.theme.pageChangeSpec
import com.example.ui.viewmodel.ImportSource
import com.example.ui.viewmodel.MainViewModel
import com.example.ui.viewmodel.MainViewModelFactory
import com.example.ui.viewmodel.NavigationTab
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels {
        // 依赖统一从 Application 持有的容器取，界面不再自己拼装数据库与 Repository
        val container = (application as MiuListApplication).container
        MainViewModelFactory(container)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                MainApp(viewModel = viewModel)
            }
        }
    }
}

/**
 * 顶栏本体。**不是 M3 的 `TopAppBar`**，是自己拼的一行。
 *
 * 换掉的理由有三个，都是减法：
 *
 *  1. 原来的标题「MiuList」是一个纯装饰：它不承载任何操作、不随页面变，
 *     却让每一屏都为此让出标题区。删掉之后这一行只剩真正会点的控件
 *     （学期名 / 生成本周待办 / 搜索）。
 *  2. `TopAppBar` 自带 16.dp 水平内边距和一整套标题排版。现在这一行里最高的
 *     东西只有 22.dp 的周次 chip，用不着为它留 64.dp。
 *  3. 少一层 `TopAppBarDefaults` 的颜色管道。顶栏本来就不铺面（容器透明），
 *     这层抽象换来的只是一个可忽略的默认高度。
 *
 * 状态栏内边距自己加：以前是 `TopAppBar` 内部的 `windowInsetsTopHeight` 处理的，
 * *  换掉它就得自己写，否则内容会顶到刘海/状态栏底下。
 *
 * 高度 40.dp 而不是更矮：一行里还要放 32.dp 的按钮和可点区域，
 * 40.dp 是「不碰 Material 的 48.dp 建议值」与「别把内容压得太紧」之间取的折中。
 *
 * 不用 `IconButton` 是有原因的：它内部挂着 `minimumInteractiveComponentSize()`，
 * 那东西用 `maxOf(placeable.size, 48.dp)` 算高度，**不看父级的 maxHeight**，
 * 放进 40.dp 的行里会直接溢出。所以这里用 `Box` + `clickable` 自己管尺寸——
 * 触摸区域跟着图标走，视觉尺寸 32.dp。
 *
 * **搜索就地把顶栏整行换掉**，而不是在顶栏下面展开一块面板：
 * 原来的搜索框是清单页正文里一个 44.dp 的满宽方块，任何形态的展开都还要往下占高度。
 * 让它占用顶栏本来就有的那 40.dp——用户要的空间总量一点没增加，
 * 而且焦点、返回键、系统输入法都落在这条已有的窗口边界内，不需要额外的窗口/弹层协调。
 *
 * **收起态是「学期名 + 两个常驻动作」**。搜索和「生成本周待办」都常驻每一页：
 * 它们是跨页面的全局操作（搜全库、给任意一周生成），藏在某一页里等于要求用户
 * 先想「这件事该去哪一页做」。周次选择则仍然不上顶栏：它会随日期自己变，
 * 是一个**读数**而不是一个入口，该待在总览页那张学期卡上。
 */
@Composable
private fun TopBarStrip(
    termName: String,
    termConfigured: Boolean,
    onOpenMoreMenu: () -> Unit,
    onGenerateWeekTasks: () -> Unit,
    searchActive: Boolean = false,
    searchQuery: String = "",
    onSearchQueryChange: (String) -> Unit = {},
    onOpenSearch: () -> Unit = {},
    onCloseSearch: () -> Unit = {}
) {
    if (searchActive) {
        SearchFieldStrip(
            query = searchQuery,
            onQueryChange = onSearchQueryChange,
            onClose = onCloseSearch
        )
        return
    }
    // 整行是一个落点：左边学期名、右边下拉箭头都只是这一行的视觉组成。
    // 单独再给箭头挂一个 clickable 就会在一行里出现两个含义相同的落点，
    // 用户会先点箭头、发现没反应、再点整行——和卡片「只留一个落点」的约定一致。
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(TopBarHeight)
            .padding(horizontal = PageHInset)
            .clip(RoundedCornerShape(10.dp))
            .clickable { onOpenMoreMenu() }
            .testTag("topbar_more_menu_button"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 学期名。**纯文本，不带 chip 底色也不描边**：它是一个读数，不是一个按钮。
        // 右边那两个动作是圆角色块（有底色 = 可点），一旦学期名也套一层底色，
        // 这一行里就出现了三个长得一样的东西，读起来分不清哪个能点。
        // 去掉底色之后字号要跟着提上来——11.5.sp 配 chip 才够读，
        // 纯文本同一个字号会缩成一行小字。
        Text(
            text = termName,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            // 未配置校历时学期名是空的：这时用次级墨色，让它读起来像
            // 「还没填的信息」而不是一个真的学期名。
            color = if (termConfigured) {
                MaterialTheme.colorScheme.onSurface
            } else {
                TextSecondary
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.width(3.dp))

        // 下拉箭头紧跟在文字后面而不是顶到行尾：它是这个标题的「可展开」记号，
        // 离远了就成了另一个独立的控件。它自己不可点（整行才是落点），
        // contentDescription 走 null —— 读屏念的是整行的语义，多念一遍是噪音。
        Icon(
            imageVector = Icons.Outlined.KeyboardArrowDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(19.dp)
        )

        Spacer(modifier = Modifier.weight(1f))

        // 两个常驻动作。顺序是「生成 → 搜索」：搜索是最高频的读操作，放最右（拇指侧）；
        // 生成是低频的写操作，紧挨着它。两个都不给文字标签——顶栏只有 40.dp，
        // 标签会把学期名挤掉，而这些动作的名字（搜索 / 一键生成）在图标上足够直白。
        //
        // 两个色块刻意一深一浅：生成是写操作，用主题绿平涂（绿只做填充、上面配深墨字，
        // 见 Color.kt 的全局约定），搜索是纯读操作，走中性 chip。一眼能分出主次。
        TopBarAction(
            icon = Icons.Outlined.AutoAwesome,
            contentDescription = "生成本周待办",
            onClick = onGenerateWeekTasks,
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            iconSize = 18.dp,
            testTag = "topbar_generate_tasks_button"
        )

        Spacer(modifier = Modifier.width(4.dp))

        // 搜索入口：常驻，点它会先跳到清单页再展开搜索（见 MainApp 的 onOpenSearch）。
        // 学期名那组不占 weight（按内容宽度排，箭头才能紧跟文字），
        // 全部剩余空间推给这一个 Spacer，两个图标就永远在最右边。
        TopBarAction(
            icon = Icons.Outlined.Search,
            contentDescription = "搜索待办",
            onClick = onOpenSearch,
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurface,
            iconSize = 19.dp,
            testTag = "topbar_search_button"
        )
    }
}

/**
 * 搜索态的顶栏：一行里是「返回 + 输入框 + 清除」。
 *
 * 高度、状态栏内边距、左右边距全部沿用 [TopBarStrip] 的那一套——搜索态必须和收起态
 * 占同样的一行，否则切换的瞬间整页内容会跳一下。
 *
 * 输入框仍然用 `BasicTextField` 而不是 M3 的 `OutlinedTextField`：后者把上下留白按
 * 自己的 56.dp MinHeight 算死，从外面硬卡一个 32.dp 只会让文字溢出被裁；
 * 而 1.3.0 的 `OutlinedTextField` 还没有 `contentPadding` 参数可以救。
 *
 * 焦点和输入法在进入这一行时就主动要过来：搜索是用户明确点出来的意图，
 * 等他自己再点一次输入框等于让他做两次同样的事。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchFieldStrip(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(TopBarHeight)
            .padding(horizontal = PageHInset)
            .testTag("topbar_search_strip"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 收起用的返回箭头走 IconButton 的替代写法（同 TopBarAction 的理由：
        // minimumInteractiveComponentSize 不看父级高度，40.dp 的行里会溢出）。
        Box(
            modifier = Modifier
                .size(TopBarActionSize)
                .clip(RoundedCornerShape(10.dp))
                .clickable {
                    keyboard?.hide()
                    onClose()
                }
                .testTag("topbar_search_close_button"),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = "收起搜索",
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(19.dp)
            )
        }

        Spacer(modifier = Modifier.width(6.dp))

        val shape = RoundedCornerShape(10.dp)
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = TextStyle(
                fontSize = 13.5.sp,
                color = MaterialTheme.colorScheme.onSurface
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
            modifier = Modifier
                .weight(1f)
                .height(32.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .focusRequester(focusRequester)
                .testTag("checklist_search_input"),
            decorationBox = { inner ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = 10.dp, end = 4.dp)
                ) {
                    Icon(
                        Icons.Outlined.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(17.dp)
                    )
                    Spacer(Modifier.width(7.dp))
                    Box(Modifier.weight(1f)) {
                        if (query.isEmpty()) {
                            // 占位文字用 TextSecondary 而不是 onSurfaceVariant：
                            // 后者（#78827B）压在 surfaceVariant 的 #F1F5F9 上只有 3.63:1，
                            // 13.5.sp 属普通小字、过不了 AA。它虽然是提示文字，
                            // 但它同时是「这里可以输入」的唯一说明，糊了就等于输入框没交代用法。
                            Text(
                                "搜索课程或待办",
                                fontSize = 13.5.sp,
                                color = TextSecondary
                            )
                        }
                        inner()
                    }
                    // 清除按钮放在输入框内部而不是外面：它是「把这行文字删掉」，
                    // 属于输入框的一部分，挪到行尾会变成一个和搜索无关的动作。
                    if (query.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .size(26.dp)
                                .clip(RoundedCornerShape(percent = 50))
                                .clickable { onQueryChange("") }
                                .testTag("checklist_search_clear_button"),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Outlined.Close,
                                contentDescription = "清除搜索",
                                tint = TextSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        )
    }
}

/** 顶栏右侧的动作按钮：圆角色块 + 图标，尺寸见 [TopBarActionSize]。 */
@Composable
private fun TopBarAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    containerColor: Color,
    contentColor: Color,
    iconSize: androidx.compose.ui.unit.Dp,
    testTag: String
) {
    Box(
        modifier = Modifier
            .size(TopBarActionSize)
            .clip(RoundedCornerShape(10.dp))
            .background(containerColor)
            .clickable { onClick() }
            .testTag(testTag),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = contentColor,
            modifier = Modifier.size(iconSize)
        )
    }
}

// 切页转场的时序（三段式）与卡片入场时间轴都在 Mica.kt：
// `pageChangeSpec()` 与 `rememberCardRevealProgress()`。课表页内
// 「周课表 ↔ 列表」的切换用的是同一套，两个地方不各抄一份。

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainApp(viewModel: MainViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.userMessage) {
        state.userMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearUserMessage()
        }
    }

    // 「每次进入软件自动刷新天气」。
    //
    // 只挂 ViewModel 的 init 是不够的：Android 切到别的 App 再切回来时进程还活着，
    // init 早就跑完了，那一次「进入」不会有任何反应。ON_RESUME 才对应用户
    // 真正看到界面的那一刻。
    //
    // 真正的节流在 ViewModel 里 —— 距上次拉取不足 20 分钟就读缓存不发请求，
    // 所以这里每次 resume 都触发也不会变成高频请求。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.syncWeather()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 搜索态属于顶栏，但查询词属于 ViewModel（清单页的过滤要用它）。
    // 留在 MainApp 而不是 ViewModel，是因为它是一个**界面开关**而不是业务状态，
    // 而这一行的生命周期正好和 Scaffold 的 topBar 一致。
    var searchVisible by remember { mutableStateOf(false) }

    // 顶栏抽屉的开关。和搜索态一样是纯界面开关，不进 ViewModel。
    var moreDrawerVisible by remember { mutableStateOf(false) }

    // 抽屉盖住大半屏时浮岛还浮在上面会很怪：它是「去哪一页」的入口，
    // 而抽屉里的设置才是此刻要去的地方。让它跟着抽屉一起收掉。
    val semesterEntity = state.semester
    val termConfigured = semesterEntity != null && semesterEntity.firstMondayEpochDay > 0L
    val termName = semesterEntity?.name?.ifBlank { null }
        ?: if (termConfigured) "本学期" else "未设置学期"

    // 切走清单页就收起搜索并清掉查询词：留着查询词会得到一个「看不见搜索框、
    // 却只有 3 条待办」的列表，用户无从判断是被筛了还是本来就只有 3 条。
    LaunchedEffect(state.currentTab) {
        if (state.currentTab != NavigationTab.CHECKLIST) {
            searchVisible = false
            viewModel.setSearchQuery("")
        }
        // 抽屉里的「设置」是切到设置页之后顺手收起的；换页之后还留着半屏面板
        // 会挡住新页面的第一眼内容。用户手动切 tab 时同理。
        moreDrawerVisible = false
    }

    // haze：整页内容当模糊源。浮岛导航是压在内容之上的，
    // 得把从它下面滚过去的卡片真糊掉，而不只是糊页面底那张渐变。
    //
    // 用的是进程级的 [OverlayHazeState] 而不是 remember 一个新的：弹层在独立窗口里，
    // 拿不到这里挂的 CompositionLocal，源和效果必须指着同一个实例才接得上。
    val hazeState = OverlayHazeState

    AppBackgroundLayer {
        CompositionLocalProvider(LocalGlassHazeState provides hazeState) {
        Scaffold(
        modifier = Modifier.fillMaxSize(),
        // 不铺不透明底，让 Mesh Gradient 露出来
        containerColor = Color.Transparent,
        // 刻意**不**在这里挂 snackbarHost：这一层之下还有满屏的同窗口 overlay
        // （抽屉、各类弹层），Scaffold 里的 SnackbarHost 会被它们整个盖住。
        // 表现是「点保存 / 点检查更新，一点反应都没有」——提示确实弹了，只是没人看见。
        // 真正的主机挪到了 MainApp 最末尾，和所有弹层同一层的 Box 里，见文件末尾。
        topBar = {
            TopBarStrip(
                termName = termName,
                termConfigured = termConfigured,
                onOpenMoreMenu = { moreDrawerVisible = true },
                onGenerateWeekTasks = { viewModel.generateTasksDirectlyForWeek() },
                searchActive = searchVisible,
                searchQuery = state.searchQuery,
                onSearchQueryChange = { viewModel.setSearchQuery(it) },
                // 搜索是全局入口，但查询词只作用在清单页（只有那一页在筛）。
                // 从别的页点它时先切到清单页再展开：否则顶栏会展开成一条搜得到、
                // 底下却纹丝不动的输入框，用户只会以为是自己输错了。
                onOpenSearch = {
                    if (state.currentTab != NavigationTab.CHECKLIST) {
                        viewModel.setNavigationTab(NavigationTab.CHECKLIST)
                    }
                    searchVisible = true
                },
                onCloseSearch = { searchVisible = false }
            )
        }
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            // hazeSource 和 hazeEffect 必须是兄弟节点，不能互相嵌套：
            // 源层要先被合成进父节点，模糊节点才拿得到那份已完成的纹理。
            // 把 hazeEffect 塞进 hazeSource 内部会变成自绘反馈，
            // 结果是「一点都糊不掉」，只剩一层白纱盖在身后的卡片上。
            //
            // 这里刻意不给底部留 92dp：内容要能滚到浮岛导航底下去，
            // 浮岛才真的是毛玻璃，而不是浮在一片空背景上。
            // 收尾靠各页滚动容器自己的 contentPadding（IslandBottomInset）。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    // 页面底由外层 MeshBackgroundLayer 提供：它同时把那张网格渐变
                    // 下发给所有玻璃面板做屏幕对齐重绘
                    .hazeSource(hazeState)
            ) {
            if (state.isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else {
                // 「首帧豁免」的标志。它必须活在这里（AnimatedContent 之外）——
                // 放进内容块的话每次切页都会被当成首帧，于是永远没有入场动画。
                val hasEverRendered = remember { mutableStateOf(false) }

                AnimatedContent(
                    targetState = state.currentTab,
                    transitionSpec = { pageChangeSpec() },
                    modifier = Modifier
                        .fillMaxSize()
                        .clipToBounds(),
                    label = "tab_screen_transition"
                ) { targetTab ->
                    // 第三段：骨架放大到位之后，卡片才由虚及实地依次亮起来。
                    // 整条时间轴（含为什么必须线性）写在 Mica.kt 的
                    // `rememberCardRevealProgress` 上，课表页内切视图用的是同一个。
                    val cardRevealState = rememberCardRevealProgress(
                        // 冷启动豁免：那一次没有「旧界面」，卡片直接满显示。
                        // 标志活在这里（AnimatedContent 之外）：放进内容块的话
                        // 每次切页都会被当成首帧，于是永远没有入场动画。
                        firstMountSeed = if (hasEverRendered.value) 0f else 1f
                    )
                    LaunchedEffect(Unit) { hasEverRendered.value = true }

                    CompositionLocalProvider(
                        LocalCardRevealProgress provides cardRevealState
                    ) {
                        when (targetTab) {
                            NavigationTab.DASHBOARD -> {
                                DashboardScreen(
                                    state = state,
                                    onGenerateTodayTasks = { viewModel.generateTasksDirectlyForWeek() },
                                    onPreviewGenerateTasks = { viewModel.openQuickGenerateWeekDialog() },
                                    onToggleTaskComplete = { viewModel.toggleTaskComplete(it) },
                                    onToggleTaskStar = { viewModel.toggleTaskStar(it) },
                                    onInspectTask = { viewModel.inspectTask(it) },
                                    onInspectCourse = { viewModel.inspectCourse(it) },
                                    onOpenWeekPicker = { viewModel.openWeekPicker() }
                                )
                            }

                            NavigationTab.SCHEDULE -> {
                                ScheduleScreen(
                                    state = state,
                                    onSelectViewMode = { viewModel.setScheduleViewMode(it) },
                                    onSelectWeek = { viewModel.selectWeek(it) },
                                    onOpenWeekPicker = { viewModel.openWeekPicker() },
                                    onInspectCourse = { viewModel.inspectCourse(it) }
                                )
                            }

                            NavigationTab.CHECKLIST -> {
                                ChecklistScreen(
                                    state = state,
                                    onSelectTaskTypeKey = { viewModel.setSelectedTaskType(it) },
                                    onOpenNewTypeDialog = { viewModel.openNewTaskTypeDialog() },
                                    onConfirmDeleteType = { viewModel.confirmDeleteTaskType(it) },
                                    onSelectGroupingMode = { viewModel.setGroupingMode(it) },
                                    onToggleStatusFilter = { viewModel.toggleStatusFilter(it) },
                                    onToggleTaskComplete = { viewModel.toggleTaskComplete(it) },
                                    onToggleTaskStar = { viewModel.toggleTaskStar(it) },
                                    onInspectTask = { viewModel.inspectTask(it) }
                                )
                            }

                            NavigationTab.SETTINGS -> {
                                SettingsScreen(
                                    state = state,
                                    onOpenTransferDialog = { viewModel.openTransferDialog() },
                                    onOpenCategoryManageDialog = { viewModel.openTaskCategoryManageDialog() },
                                    onResetDemoData = { viewModel.resetDemoData() }
                                )
                            }
                        }
                    }
                }
            }
            }

            // 顶栏抽屉在 Scaffold **之外**（见下面的兄弟节点）：topBar 是在 body
            // 之上绘制的，抽屉放进 body 会被顶栏压住——面板里的标题和关闭按钮
            // 正好落在顶栏那一行的位置，结果只剩顶栏的 chip 浮在面板上。

            // 底部浮岛导航：悬浮在内容之上，不占布局高度。
            // 抽屉开着时不画：它在抽屉之下会成为「透过毛玻璃看见的一颗岛」，
            // 既点不到，又在面板底部多出一块不该有的重量。
            if (!moreDrawerVisible) {
                FloatingIslandNav(
                    current = state.currentTab,
                    onSelect = viewModel::setNavigationTab,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }

            // FAB 抬到浮岛之上，别和岛叠在一起
            val fabVisible = !moreDrawerVisible && (state.currentTab == NavigationTab.CHECKLIST || state.currentTab == NavigationTab.SCHEDULE)
            AnimatedVisibility(
                visible = fabVisible,
                enter = fadeIn(tween(150, easing = LinearOutSlowInEasing)) + scaleIn(initialScale = 0.82f, animationSpec = tween(150, easing = LinearOutSlowInEasing)),
                exit = fadeOut(tween(100, easing = FastOutLinearInEasing)) + scaleOut(targetScale = 0.82f, animationSpec = tween(100, easing = FastOutLinearInEasing)),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    // 底部跟着 IslandBottomInset 走，别写死数字：
                    // 岛一改高度，这里写死的值就会悄悄和岛错开。
                    .padding(end = 24.dp, bottom = IslandBottomInset)
            ) {
                FloatingActionButton(
                    onClick = { viewModel.openAddTaskDialog() },
                    shape = RoundedCornerShape(20.dp),
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    elevation = androidx.compose.material3.FloatingActionButtonDefaults.elevation(
                        defaultElevation = 6.dp,
                        pressedElevation = 10.dp
                    ),
                    modifier = Modifier.testTag("fab_add_task")
                ) {
                    Icon(imageVector = Icons.Outlined.Add, contentDescription = "添加待办")
                }
            }
        }
    }

        // 顶栏抽屉：Scaffold 的兄弟节点，因此画在顶栏**之上**。
        // 它仍然是 hazeEffect 节点——排在 body 之后就能读到已完成的纹理，
        // 把底下的卡片真糊掉（和浮岛同一套做法与同一套参数）。
        //
        // 抽屉里的弹层**不再先收抽屉**，弹层是叠在抽屉之上的第二层：
        // 关掉它应该退回第一层（抽屉），而不是掉回底下那张内容页。
        // 早先的做法是「先 moreDrawerVisible = false 再开弹层」，理由是
        // 免得弹层背后铺着面板——但那个理由只对半幅抽屉成立（半幅时背后
        // 还看得见页面，弹层像是浮在页面中间），全屏之后背后本来就是一整块
        // 毛玻璃，没什么可挡的，而代价很实：校历改完只剩两张卡的设置页、
        // 或者总览页摆在那儿，用户得重新点一次顶栏才能接着改下一门课。
        // 唯一仍然先收抽屉的是「设置」那一行——它要切页，切页就该把抽屉收掉。
        MoreDrawer(
            visible = moreDrawerVisible,
            state = state,
            onDismiss = { moreDrawerVisible = false },
            // 下面这些都只是「在抽屉之上再开一层弹层」，所以不收抽屉：
            // 关掉弹层回到抽屉，接着还能点下一行。
            onOpenCloudAccount = { viewModel.openCloudAccountDialog() },
            onOpenAcademicCalendar = { viewModel.openAcademicCalendarDialog() },
            onOpenCategoryRules = { viewModel.openCategoryRulesDialog() },
            onOpenTaskRules = { viewModel.openTaskRulesDialog() },
            onOpenAddCourse = { viewModel.openAddCourseDialog() },
            onEditCourse = { viewModel.openEditCourseDialog(it) },
            onDeleteCourse = { viewModel.confirmDeleteCourse(it) },
            // 「设置」是切页不是叠层：切页由 LaunchedEffect(currentTab) 统一收抽屉。
            onOpenSettings = {
                viewModel.setNavigationTab(NavigationTab.SETTINGS)
            }
        )
        }
    }

    // 所有同窗口 overlay 与 SnackbarHost 放在同一个 Box 里，SnackbarHost 排在最后：
    // 同窗口 overlay 是靠**绘制顺序**决定谁压谁，而不是靠 zIndex。
    // SnackbarHost 留在 Scaffold 里的话会被上面每一层满屏 overlay 整个盖住，
    // 于是所有「在弹层里点的按钮」的结果提示都看不见 —— 保存、检查更新、删除课程全是。
    Box(modifier = Modifier.fillMaxSize()) {
        // Inspect Course Linkage Sheet (课表课程详情与关联待办弹层：待办详情与编辑弹层置于其上层)
        state.inspectingCourse?.let { courseWithSlots ->
            val linkedTasks = state.tasks.filter { it.courseId == courseWithSlots.course.id }
            CourseLinkageSheet(
                courseWithSlots = courseWithSlots,
                linkedTasks = linkedTasks,
                customTypes = state.customTaskTypes,
                periodTable = state.periodTable,
                slotWeeks = state.slotWeeks,
                totalWeeks = state.totalWeeks,
                onDismiss = { viewModel.inspectCourse(null) },
                onToggleTaskComplete = { viewModel.toggleTaskComplete(it) },
                onToggleTaskStar = { viewModel.toggleTaskStar(it) },
                onInspectTask = { viewModel.inspectTask(it) },
                onAddNewTaskForCourse = {
                    val cId = courseWithSlots.course.id
                    viewModel.inspectCourse(null)
                    viewModel.openAddTaskDialog(cId)
                },
                onEditCourse = {
                    viewModel.inspectCourse(null)
                    viewModel.openEditCourseDialog(courseWithSlots)
                },
                onDeleteCourse = {
                    // 只开确认弹层，不在这一层就删：课程详情弹层留在这儿当背景，
                    // 用户点「取消」还能回到这门课的关联待办接着看。
                    viewModel.confirmDeleteCourse(courseWithSlots)
                }
            )
        }

        // 删除课程的二次确认。位置在课程详情弹层**之后**：兄弟节点里排得靠后的画在上面，
        // 所以它同时压在课程详情弹层和（更早绘制的）更多抽屉之上，两个入口共用这一份。
        state.deletingCourse?.let { course ->
            DeleteCourseConfirmDialog(
                courseName = course.course.name,
                slotCount = course.slots.size,
                // 待办数在这里数而不是存进状态：它是这一刻界面上已有的数据，
                // 存一份进 state 就多一个会和真实待办漂移的副本。
                linkedTaskCount = state.tasks.count { it.courseId == course.course.id },
                onDismiss = { viewModel.cancelDeleteCourse() },
                onConfirm = { viewModel.deleteCourse(course.course.id, course.course.name) }
            )
        }

        // 单条待办详情弹层：卡片上点一下进来，编辑/删除都在这里
        state.inspectingTask?.let { inspectingTask ->
            val currentTask = state.tasks.find { it.id == inspectingTask.id } ?: inspectingTask
            TaskDetailSheet(
                task = currentTask,
                customTypes = state.customTaskTypes,
                onDismiss = { viewModel.closeTaskSheet() },
                onEdit = { viewModel.editTaskFromSheet(currentTask) },
                // 删完就把弹层收掉，否则它会停在一个已经不存在的待办上
                onDelete = {
                    viewModel.closeTaskSheet()
                    viewModel.deleteTask(currentTask)
                }
            )
        }

        // Task Edit/Add Dialog
        if (state.isAddTaskDialogOpen) {
            TaskEditDialog(
                initialTask = state.editingTask,
                courses = state.courses,
                customTypes = state.customTaskTypes,
                preselectedCourseId = state.preselectedCourseIdForNewTask,
                onDismiss = { viewModel.closeTaskDialog() },
                onCreateTaskType = { viewModel.openNewTaskTypeDialog() },
                onSave = { viewModel.saveTask(it) }
            )
        }

        // 新建待办类型。放在编辑弹层**之上**：类型是在「给一条待办选类型」的
        // 过程中被意识到缺的，所以建完必须能直接回到那一步，不该把编辑弹层一起关掉。
        if (state.isNewTaskTypeDialogOpen) {
            NewTaskTypeDialog(
                onDismiss = { viewModel.closeNewTaskTypeDialog() },
                onCreate = { label, paletteIndex -> viewModel.createTaskType(label, paletteIndex) }
            )
        }

        if (state.isTaskCategoryManageDialogOpen) {
            TaskCategoryManageDialog(
                customTypes = state.customTaskTypes,
                tasks = state.tasks,
                onDismiss = { viewModel.closeTaskCategoryManageDialog() },
                onDeleteCustomType = { viewModel.confirmDeleteTaskType(it) },
                onOpenNewTypeDialog = { viewModel.openNewTaskTypeDialog() }
            )
        }

        state.deletingTaskType?.let { type ->
            val taskCount = remember(state.tasks, type.key) {
                state.tasks.count { it.taskType == type.key }
            }
            DeleteTaskTypeConfirmDialog(
                typeLabel = type.label,
                taskCount = taskCount,
                onDismiss = { viewModel.cancelDeleteTaskType() },
                onConfirm = { viewModel.deleteTaskType(type) }
            )
        }

        // Course Edit/Add Dialog (supports multi-slot, data-driven periods and week sets)
        if (state.isAddCourseDialogOpen) {
            CourseEditDialog(
                initialCourseWithSlots = state.editingCourseWithSlots,
                periodTable = state.periodTable,
                initialSlotWeeks = state.editingCourseWithSlots?.slots
                    ?.associate { it.id to (state.slotWeeks[it.id] ?: emptySet()) }
                    ?: emptyMap(),
                totalWeeks = state.totalWeeks,
                onDismiss = { viewModel.closeCourseDialog() },
                onSave = { course, slots -> viewModel.saveCourseWithSlots(course, slots) }
            )
        }

        // 课表导入 / 导出
        if (state.isTransferDialogOpen) {
            val contentResolver = LocalContext.current.contentResolver
            ScheduleTransferDialog(
                strategy = state.importStrategy,
                importSource = state.importSource,
                zjuTermOptions = state.zjuTermOptions,
                zjuError = state.zjuError,
                isZjuRunning = state.isZjuImportRunning,
                onSourceChange = { source ->
                    viewModel.setImportSource(source)
                    if (source == ImportSource.ZJU) viewModel.loadZjuTerms()
                },
                onZjuImport = { id, pwd, option, remember -> viewModel.importFromZju(id, pwd, option, remember) },
                preview = state.importPreview,
                issues = state.importIssues,
                errorMessage = state.importError,
                isBusy = state.isImporting,
                report = state.importReport,
                onStrategyChange = { viewModel.setImportStrategy(it) },
                onExport = { uri -> viewModel.exportSchedule(uri, contentResolver) },
                onImportUri = { uri -> viewModel.importFromUri(uri, contentResolver) },
                onCommit = { viewModel.commitImport() },
                onDismissReport = { viewModel.dismissImportReport() },
                onDismiss = { viewModel.closeTransferDialog() }
            )
        }

        // 教务网账户与云端同步弹层
        if (state.isCloudAccountDialogOpen) {
            CloudAccountDialog(
                boundStudentId = state.boundStudentId,
                isRememberCredentials = state.isRememberCredentials,
                lastSyncTimestamp = state.lastSyncTimestamp,
                lastSyncAttemptTime = state.lastSyncAttemptTime,
                lastSyncFailureKind = state.lastSyncFailureKind,
                isDemoMode = state.isDemoMode,
                isSyncing = state.isSyncing,
                zjuTermOptions = state.zjuTermOptions,
                syncFailure = state.syncFailure,
                errorMessage = state.zjuError,
                onLoadTerms = { viewModel.loadZjuTerms() },
                onSyncWithCredentials = { id, pass, option, remember ->
                    viewModel.syncFromCloud(id, pass, option, remember)
                },
                onQuickSync = { viewModel.syncWithSavedCredentials() },
                onClearAccount = { viewModel.clearSavedAccount() },
                onDismiss = { viewModel.closeCloudAccountDialog() }
            )
        }

        // 本学期校历
        if (state.isAcademicCalendarDialogOpen) {
            AcademicCalendarDialog(
                semester = state.semester,
                periodTable = state.periodTable,
                existingNoClass = state.noClassDays,
                existingMakeup = state.makeupDays,
                // 官方那份与用户自己那份分两块摆，所以这两项与 semester 无关、各自独立。
                preset = state.zjuPreset,
                isPresetLoading = state.isZjuPresetLoading,
                isCheckingUpdate = state.isCheckingCalendarUpdate,
                pendingPreset = state.pendingPreset,
                availableTerms = state.zjuAvailableTerms,
                onPreviewTerm = { term -> viewModel.previewZjuTerm(term) },
                calendarDiff = state.calendarDiff,
                isDiffOpen = state.isCalendarDiffOpen,
                onCheckUpdate = { viewModel.checkCalendarUpdate() },
                onPreviewDiff = { viewModel.previewPendingPreset() },
                onDismissDiff = { viewModel.dismissCalendarDiff() },
                onConfirmApply = { viewModel.confirmApplyPreset() },
                onSave = { sem, periods, noClass, makeup ->
                    viewModel.saveAcademicCalendar(sem, periods, noClass, makeup)
                },
                onDismiss = { viewModel.closeAcademicCalendarDialog() }
            )
        }

        // Quick Generate Dialog
        if (state.isQuickGenerateDialogOpen) {
            QuickGenerateDialog(
                candidateTasks = state.candidateTasksForPreview,
                week = state.quickGenerateWeek ?: state.currentWeek,
                customTypes = state.customTaskTypes,
                onDismiss = { viewModel.closeQuickGenerateDialog() },
                onConfirm = { viewModel.confirmGenerateTasks(it) }
            )
        }

        // Category Rules Configuration Dialog
        if (state.isCategoryRulesDialogOpen) {
            CategoryRulesDialog(
                rules = state.categoryRules,
                // 批量设置的波及范围要显式告诉用户：改的不是这一行，而是这一类下的每一门课。
                courseCountByCategory = state.courses.groupingBy { it.category }.eachCount(),
                onDismiss = { viewModel.closeCategoryRulesDialog() },
                onApply = { viewModel.applyCategoryRules(it) }
            )
        }

        // 课程 × 类型的生成规则
        if (state.isTaskRulesDialogOpen) {
            TaskRulesDialog(
                courses = state.courses,
                taskRules = state.taskRules,
                onDismiss = { viewModel.closeTaskRulesDialog() },
                onSaveRule = { courseId, type, spec ->
                    viewModel.saveTaskRule(courseId, type, spec)
                },
                onToggleEnabled = { courseId, type, enabled ->
                    viewModel.setCourseTaskEnabled(courseId, type, enabled)
                }
            )
        }

        // Week Picker Dialog
        if (state.isWeekPickerDialogOpen) {
            WeekPickerDialog(
                currentWeek = state.currentWeek,
                selectedWeek = state.selectedWeek,
                totalWeeks = state.totalWeeks,
                onDismiss = { viewModel.closeWeekPicker() },
                onSelectWeek = { viewModel.selectWeek(it) }
            )
        }

        // 贴底 + 让开导航栏，和它在 Scaffold 里时占的位置一致。
        SnackbarHost(
            snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
        )
    }
}
