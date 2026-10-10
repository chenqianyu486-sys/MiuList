package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.TaskEntity
import com.example.data.model.TaskType
import com.example.ui.components.TaskItemCard
import com.example.ui.components.parseColor
import com.example.ui.viewmodel.MainUiState
import com.example.ui.viewmodel.TaskGroupingMode
import com.example.ui.viewmodel.TaskStatusFilter
import java.util.Calendar

@Composable
fun ChecklistScreen(
    state: MainUiState,
    onSearchChange: (String) -> Unit,
    onSelectTaskType: (TaskType?) -> Unit,
    onSelectGroupingMode: (TaskGroupingMode) -> Unit,
    onSelectStatusFilter: (TaskStatusFilter) -> Unit,
    onToggleTaskComplete: (TaskEntity) -> Unit,
    onEditTask: (TaskEntity) -> Unit,
    onDeleteTask: (TaskEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    // Filter tasks based on search, type, and status
    val filteredTasks = remember(state.tasks, state.searchQuery, state.selectedTaskType, state.statusFilter) {
        state.tasks.filter { task ->
            val matchSearch = state.searchQuery.isBlank() ||
                    task.title.contains(state.searchQuery, ignoreCase = true) ||
                    task.courseName.contains(state.searchQuery, ignoreCase = true) ||
                    task.description.contains(state.searchQuery, ignoreCase = true)

            val matchType = state.selectedTaskType == null || task.taskType == state.selectedTaskType.name

            val matchStatus = when (state.statusFilter) {
                TaskStatusFilter.ALL -> true
                TaskStatusFilter.PENDING -> !task.isCompleted
                TaskStatusFilter.COMPLETED -> task.isCompleted
            }

            matchSearch && matchType && matchStatus
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        // Search Input
        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = onSearchChange,
            placeholder = { Text("搜索课程名称、预习或作业内容...", fontSize = 13.5.sp) },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
            trailingIcon = {
                if (state.searchQuery.isNotBlank()) {
                    IconButton(onClick = { onSearchChange("") }) {
                        Icon(Icons.Outlined.Clear, contentDescription = "清除搜索", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
            shape = RoundedCornerShape(14.dp),
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("checklist_search_input"),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Grouping Mode Chips (Segmented Pill Style)
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(TaskGroupingMode.entries) { mode ->
                val isSelected = state.groupingMode == mode
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
                        .border(
                            1.dp,
                            if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outline,
                            RoundedCornerShape(8.dp)
                        )
                        .clickable { onSelectGroupingMode(mode) }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .testTag("grouping_mode_${mode.name}")
                ) {
                    Text(
                        text = mode.label,
                        fontSize = 11.5.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Status & Type Filters
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Status filters
            items(TaskStatusFilter.entries) { status ->
                val isSelected = state.statusFilter == status
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
                        .border(
                            1.dp,
                            if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outline,
                            RoundedCornerShape(8.dp)
                        )
                        .clickable { onSelectStatusFilter(status) }
                        .padding(horizontal = 9.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = status.label,
                        fontSize = 11.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Task Type filters
            item {
                val isSelected = state.selectedTaskType == null
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
                        .border(
                            1.dp,
                            if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outline,
                            RoundedCornerShape(8.dp)
                        )
                        .clickable { onSelectTaskType(null) }
                        .padding(horizontal = 9.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = "全部类型",
                        fontSize = 11.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            items(TaskType.entries) { type ->
                val isSelected = state.selectedTaskType == type
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
                        .border(
                            1.dp,
                            if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outline,
                            RoundedCornerShape(8.dp)
                        )
                        .clickable { onSelectTaskType(type) }
                        .padding(horizontal = 9.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = type.label,
                        fontSize = 11.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Task List with Grouping
        if (filteredTasks.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "📋 暂无符合条件的作业待办",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "可尝试切换过滤条件，或点击右下角按钮添加新作业",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                when (state.groupingMode) {
                    TaskGroupingMode.BY_COURSE_TIME -> {
                        // Sort strictly by course start time / assignment start time (也就是作业开始时间)
                        val sortedByCourseTime = filteredTasks.sortedWith(
                            compareBy(
                                { it.isCompleted }, // incomplete tasks first
                                { if (it.startTimeTimestamp > 0) it.startTimeTimestamp else it.dueDateTimestamp }
                            )
                        )
                        val groupedByCourseTime = sortedByCourseTime.groupBy {
                            it.startTimeString.ifBlank { "按课程时间安排" }
                        }

                        groupedByCourseTime.forEach { (timeLabel, tasks) ->
                            item(key = "header_course_time_$timeLabel") {
                                Card(
                                    shape = RoundedCornerShape(12.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surface
                                    ),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                                    elevation = CardDefaults.cardElevation(0.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 14.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Box(
                                                modifier = Modifier
                                                    .size(26.dp)
                                                    .clip(CircleShape)
                                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Outlined.Schedule,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(15.dp)
                                                )
                                            }
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = "课程时间: $timeLabel",
                                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        }

                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(MaterialTheme.colorScheme.primaryContainer)
                                                .padding(horizontal = 8.dp, vertical = 3.dp)
                                        ) {
                                            Text(
                                                text = "${tasks.size} 项待办",
                                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                                color = MaterialTheme.colorScheme.primary,
                                                fontSize = 11.sp
                                            )
                                        }
                                    }
                                }
                            }

                            items(tasks, key = { it.id }) { task ->
                                TaskItemCard(
                                    task = task,
                                    onToggleComplete = { onToggleTaskComplete(task) },
                                    onEdit = { onEditTask(task) },
                                    onDelete = { onDeleteTask(task) }
                                )
                            }
                        }
                    }

                    TaskGroupingMode.BY_COURSE -> {
                        // Group tasks by course
                        val groupedByCourse = filteredTasks.groupBy { it.courseName }
                        groupedByCourse.forEach { (courseName, tasks) ->
                            item(key = "header_course_$courseName") {
                                val firstTask = tasks.firstOrNull()
                                val courseColor = firstTask?.let { parseColor(it.courseColorHex) } ?: MaterialTheme.colorScheme.primary
                                val completed = tasks.count { it.isCompleted }
                                val ratio = if (tasks.isNotEmpty()) completed.toFloat() / tasks.size.toFloat() else 0f

                                Card(
                                    shape = RoundedCornerShape(12.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                                    elevation = CardDefaults.cardElevation(0.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(10.dp)
                                                        .clip(CircleShape)
                                                        .background(courseColor)
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    text = courseName,
                                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                            }

                                            Text(
                                                text = "$completed / ${tasks.size} 完成",
                                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }

                                        Spacer(modifier = Modifier.height(8.dp))

                                        LinearProgressIndicator(
                                            progress = { ratio },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(5.dp)
                                                .clip(RoundedCornerShape(3.dp)),
                                            color = courseColor,
                                            trackColor = MaterialTheme.colorScheme.outline
                                        )
                                    }
                                }
                            }

                            items(tasks, key = { it.id }) { task ->
                                TaskItemCard(
                                    task = task,
                                    onToggleComplete = { onToggleTaskComplete(task) },
                                    onEdit = { onEditTask(task) },
                                    onDelete = { onDeleteTask(task) }
                                )
                            }
                        }
                    }

                    TaskGroupingMode.BY_URGENCY -> {
                        // Group by deadline urgency: 今天截止, 明天截止, 本周内, 较早/已逾期
                        val now = System.currentTimeMillis()
                        val groupedByUrgency = filteredTasks.groupBy { task ->
                            val diff = task.dueDateTimestamp - now
                            val calNow = Calendar.getInstance()
                            val calDue = Calendar.getInstance().apply { timeInMillis = task.dueDateTimestamp }
                            val isSameDay = calNow.get(Calendar.YEAR) == calDue.get(Calendar.YEAR) &&
                                    calNow.get(Calendar.DAY_OF_YEAR) == calDue.get(Calendar.DAY_OF_YEAR)

                            when {
                                task.isCompleted -> "已完成任务"
                                diff < 0 -> "已逾期需补交"
                                isSameDay -> "今天截止 (急)"
                                diff < 2 * 86400000L -> "明天截止"
                                diff < 7 * 86400000L -> "本周内截止"
                                else -> "较远安排"
                            }
                        }

                        groupedByUrgency.forEach { (groupTitle, tasks) ->
                            item(key = "header_urgency_$groupTitle") {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "$groupTitle (${tasks.size})",
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                        color = if (groupTitle.contains("急") || groupTitle.contains("逾期")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                                    )
                                }
                            }

                            items(tasks, key = { it.id }) { task ->
                                TaskItemCard(
                                    task = task,
                                    onToggleComplete = { onToggleTaskComplete(task) },
                                    onEdit = { onEditTask(task) },
                                    onDelete = { onDeleteTask(task) }
                                )
                            }
                        }
                    }

                    TaskGroupingMode.BY_TYPE -> {
                        // Group by Task Type (预习 / 课后作业 / 实验报告 / 复习总结)
                        val groupedByType = filteredTasks.groupBy { it.taskType }
                        groupedByType.forEach { (typeStr, tasks) ->
                            val type = TaskType.fromString(typeStr)
                            val color = parseColor(type.colorHex)

                            item(key = "header_type_$typeStr") {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(RoundedCornerShape(2.dp))
                                            .background(color)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "${type.label} (${tasks.size})",
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                        color = color
                                    )
                                }
                            }

                            items(tasks, key = { it.id }) { task ->
                                TaskItemCard(
                                    task = task,
                                    onToggleComplete = { onToggleTaskComplete(task) },
                                    onEdit = { onEditTask(task) },
                                    onDelete = { onDeleteTask(task) }
                                )
                            }
                        }
                    }

                    TaskGroupingMode.BY_CATEGORY -> {
                        // Group by Course Category
                        val groupedByCategory = filteredTasks.groupBy { it.courseCategory }
                        groupedByCategory.forEach { (categoryName, tasks) ->
                            item(key = "header_category_$categoryName") {
                                Text(
                                    text = "$categoryName (${tasks.size})",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }

                            items(tasks, key = { it.id }) { task ->
                                TaskItemCard(
                                    task = task,
                                    onToggleComplete = { onToggleTaskComplete(task) },
                                    onEdit = { onEditTask(task) },
                                    onDelete = { onDeleteTask(task) }
                                )
                            }
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(20.dp))
                }
            }
        }
    }
}
