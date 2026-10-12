package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.CustomTaskType
import com.example.data.model.TaskPriority
import com.example.data.model.TaskTypes

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

@Composable
fun TaskPriorityBadge(
    priorityStr: String,
    modifier: Modifier = Modifier
) {
    val priority = TaskPriority.fromString(priorityStr)
    Box(
        modifier = modifier
            .background(
                parseColor(priority.colorHex).copy(alpha = TaskChipFillAlpha),
                RoundedCornerShape(6.dp)
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            // label 本身是「高 / 中 / 低」，别再拼「优」——低优先级会拼出「低优」
            text = priority.label,
            color = parseColor(priority.inkHex),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            fontSize = 10.sp
        )
    }
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
