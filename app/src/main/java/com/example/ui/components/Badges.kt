package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.CustomTaskType
import com.example.data.model.TaskTypes
import com.example.ui.theme.StarInk

fun parseColor(hex: String, fallback: Color = Color(0xFFBCE472)): Color {
    return try {
        val clean = hex.removePrefix("#")
        val normalized = if (clean.equals("67E8F9", ignoreCase = true) || clean.equals("38BDD4", ignoreCase = true)) "208E9E" else clean
        val colorInt = normalized.toLong(16)
        if (normalized.length == 6) {
            Color(0xFF000000 or colorInt)
        } else {
            Color(colorInt)
        }
    } catch (_: Exception) {
        fallback
    }
}

/**
 * 待办类别 / 优先级徽标的配色配方：淡彩面 + 同色相墨色字。
 *
 * 淡彩在 34% 透明度下压在白卡上是 #E1F1F6 那一档，够柔、够清新，
 * 但也正因为淡，字必须用配好的墨色（`TaskTypeOption.inkHex`），不能用淡彩本身。
 *
 * 不描边：淡彩 35% 的边和淡彩 34% 的面几乎是同一个颜色，描了等于没描；
 * 层次本来就该由「淡面 + 深字」这对反差撑，和勾选圈的处理是同一个道理。
 */
private const val TaskChipFillAlpha = 0.34f

/**
 * 待办类型徽标。解析不出来时**整个徽标不画**，而不是补一个灰的「其他」。
 *
 * 类型现在既有内置枚举又有用户新建的，解析必须拿到自定义类型表，
 * 所以这张表是显式入参而不是在组件里自己去查——查一次不难，难的是保证
 * 每个调用点都传对了；漏传就编译不过，比悄悄画出一个假标签强。
 *
 * 「解析不出来」有两种：留空（`TASK_TYPE_NONE`）和指向已删除类型的悬空 key。
 * 两者都不显示徽标——留空是绝大多数手写待办的常态，给它们挂一个「未分类」灰块
 * 只会让 meta 行永远占着一个位置却说不出任何信息。
 */
@Composable
fun TaskTypeBadge(
    taskTypeStr: String,
    customTypes: List<CustomTaskType>,
    modifier: Modifier = Modifier
) {
    val option = TaskTypes.resolve(taskTypeStr, customTypes) ?: return
    Box(
        modifier = modifier
            .background(
                parseColor(option.colorHex).copy(alpha = TaskChipFillAlpha),
                RoundedCornerShape(6.dp)
            )
            .padding(horizontal = 7.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = option.label,
            color = parseColor(option.inkHex),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            fontSize = 10.5.sp
        )
    }
}

/**
 * 星标。**没标星时整个组件不画**，调用点自己决定要不要留位。
 *
 * 它是这三个组件里唯一一个不需要配色的：星标的形状本身就是全部信息，
 * 颜色只用来让它在淡彩卡面上被扫到——暖琥珀的 [StarInk]（实测在八支课程卡面
 * 和中性卡面上都有 4.6:1 以上，远高于图形元素的 3:1 门槛）。
 * 不用「灰色空星」表示未标星：空星和图标占位在列表里分辨不出来，
 * 等于给每一行都加了一个没有含义的噪点。
 *
 * 只读的——要能点的用 [TaskStarToggle]，别把 clickable 塞进这里：
 * 课程详情弹层和详情弹层里的星是「告诉你这条标了」，不是「按这里改」。
 */
@Composable
fun TaskStarMark(
    modifier: Modifier = Modifier,
    size: Dp = 14.dp
) {
    Icon(
        imageVector = Icons.Rounded.Star,
        contentDescription = "已标星",
        tint = StarInk,
        modifier = modifier.size(size)
    )
}

/**
 * 待办卡片右侧那颗**可点**的星标。
 *
 * 两态由用户定：未标星 = **白色描边空星**，标星 = **黄色实心星**。
 * 卡面本身不动（仍是这门课的粉彩）——颜色这一格只归星标，两处都染等于
 * 「标了星」被重复表达一次。
 *
 * 为什么两态都要画：它是个开关，只画「已标星」那一态就只剩取消、
 * 没有标记，交互反而不成立。这推翻了「没标星就不画」那条旧结论——
 * 那条成立的前提是星标纯展示，现在是控件了。
 *
 * 空星用纯白（不是灰）：它压在淡彩卡面上只有 1.2~1.5:1，和左侧那个纯白勾选圈
 * 是同一个取舍，用户明确要过这个。识别靠的是「描边空星 vs 实心星」的形状差，
 * 不是亮度差。实心态那一格用 [StarInk]——它是黄底上唯一还认得出的那支黄。
 */
@Composable
fun TaskStarToggle(
    isStarred: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    unstarredColor: Color = Color.White
) {
    Icon(
        imageVector = if (isStarred) Icons.Rounded.Star else Icons.Outlined.Star,
        contentDescription = if (isStarred) "取消星标" else "标星",
        tint = if (isStarred) StarInk else unstarredColor,
        modifier = modifier
            .size(size)
            .clickable { onToggle() }
    )
}

@Composable
fun CourseColorDot(
    hex: String,
    modifier: Modifier = Modifier
) {
    val color = parseColor(hex)
    Box(
        modifier = modifier
            .background(color, RoundedCornerShape(50))
    )
}
