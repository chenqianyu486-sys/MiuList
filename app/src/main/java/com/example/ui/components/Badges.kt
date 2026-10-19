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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
 * 两态由用户定：未标星 = **粉彩星体 + 白色描边**（[unstarredFill] 为 null 时退成
 * 白色描边空星），标星 = **黄色实心星**。
 * 卡面本身不动（仍是这门课的粉彩）——颜色这一格只归星标，两处都染等于
 * 「标了星」被重复表达一次。
 *
 * 为什么两态都要画：它是个开关，只画「已标星」那一态就只剩取消、
 * 没有标记，交互反而不成立。这推翻了「没标星就不画」那条旧结论——
 * 那条成立的前提是星标纯展示，现在是控件了。
 *
 * 未标星那一态可以再垫一层**半透明填色**（[unstarredFill]）：星体因此是
 * 卡片主题色（这门课的粉彩）而不是死白，白只剩描边那一圈。
 * 「描边空星 vs 实心星」的形状差照旧认得出，而两个状态之间那道对比
 * 也不再是「白 vs 黄」——是「粉彩 vs 琥珀」。
 */
@Composable
fun TaskStarToggle(
    isStarred: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    unstarredColor: Color = Color.White,
    /**
     * 未标星时垫在描边下面那层**半透明填色**（通常是卡片主题色）。
     * null = 星体不填色，只有描边（课程详情弹层里那排待办就是这个用法）。
     */
    unstarredFill: Color? = null
) {
    val description = if (isStarred) "取消星标" else "标星"
    Box(
        modifier = modifier
            .size(size)
            .clickable { onToggle() }
            // 无障碍描述挂在**带 tag 的这一层**：星体和描边是两层图标，
            // 描述若留在图标上，测试和读屏都找不到「标星 / 取消星标」这个动作。
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        if (isStarred) {
            Icon(
                imageVector = Icons.Rounded.Star,
                contentDescription = null,
                tint = StarInk,
                modifier = Modifier.size(size)
            )
        } else {
            if (unstarredFill != null) {
                // 白色描边 = 略大一点的纯白星垫在下面，粉彩星压在上面。
                //
                // 为什么不用 Material 的 Outlined.Star 当描边：它那圈描边在 24dp 上
                // 几乎把星体整个填满（实测画出来是一颗实心白星），压在粉彩上等于
                // 粉彩全被盖住——星标还是白的，压根没染上色。
                // 「大一号的白星 + 缩回去的粉彩星」才真正得到「白只留描线」的那颗星。
                Icon(
                    imageVector = Icons.Rounded.Star,
                    contentDescription = null,
                    tint = unstarredColor,
                    modifier = Modifier.size(size).scale(StarRimScale)
                )
                Icon(
                    imageVector = Icons.Rounded.Star,
                    contentDescription = null,
                    tint = unstarredFill,
                    modifier = Modifier.size(size)
                )
            } else {
                Icon(
                    imageVector = Icons.Outlined.Star,
                    contentDescription = null,
                    tint = unstarredColor,
                    modifier = Modifier.size(size)
                )
            }
        }
    }
}

/**
 * 星标那圈白色描边用「白星放大几成」来造。
 *
 * 1.10 档：外尖处白边约 1dp（五角星外半径约 10.4，尖上多出来 10%），
 * 内凹角处约 0.5dp——比描边粗细完全一致差得远，但 24dp 上看不出来。
 */
private const val StarRimScale = 1.10f

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
