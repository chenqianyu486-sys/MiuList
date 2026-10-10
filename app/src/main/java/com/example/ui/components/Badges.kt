package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import com.example.data.model.TaskPriority
import com.example.data.model.TaskType
import com.example.ui.theme.TeaColor
import com.example.ui.theme.isGreenishColor

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

@Composable
fun TaskTypeBadge(
    taskTypeStr: String,
    modifier: Modifier = Modifier
) {
    val type = TaskType.fromString(taskTypeStr)
    val color = parseColor(type.colorHex)
    val isGreen = isGreenishColor(color)
    val bg = if (isGreen) TeaColor else color.copy(alpha = 0.08f)
    val border = if (isGreen) TeaColor else color.copy(alpha = 0.35f)
    val fg = if (isGreen) MaterialTheme.colorScheme.primary else color
    Box(
        modifier = modifier
            .border(0.5.dp, border, RoundedCornerShape(6.dp))
            .background(bg, RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = type.label,
            color = fg,
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
    val color = parseColor(priority.colorHex)
    val isGreen = isGreenishColor(color)
    val bg = if (isGreen) TeaColor else color.copy(alpha = 0.08f)
    val border = if (isGreen) TeaColor else color.copy(alpha = 0.35f)
    val fg = if (isGreen) MaterialTheme.colorScheme.primary else color
    Box(
        modifier = modifier
            .border(0.5.dp, border, RoundedCornerShape(6.dp))
            .background(bg, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "${priority.label}优",
            color = fg,
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
