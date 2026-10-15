package com.example.ui.viewmodel

import com.example.data.local.TaskEntity
import org.junit.Assert.*
import org.junit.Test

class TaskStatusFilterTest {

    @Test
    fun testTaskStatusFilterEntries() {
        val entries = TaskStatusFilter.entries
        assertEquals(2, entries.size)
        assertEquals(listOf(TaskStatusFilter.PENDING, TaskStatusFilter.COMPLETED), entries)
        assertEquals("待完成", TaskStatusFilter.PENDING.label)
        assertEquals("已完成", TaskStatusFilter.COMPLETED.label)
    }

    @Test
    fun testDefaultStateHasPendingSelected() {
        val state = MainUiState()
        assertEquals(setOf(TaskStatusFilter.PENDING), state.statusFilters)
    }

    @Test
    fun testToggleFilterLogic() {
        var filters = setOf(TaskStatusFilter.PENDING)

        fun toggle(filter: TaskStatusFilter) {
            filters = if (filter in filters) {
                if (filters.size > 1) filters - filter else filters
            } else {
                filters + filter
            }
        }

        // Initially only PENDING is selected
        assertEquals(setOf(TaskStatusFilter.PENDING), filters)

        // Cannot deselect the only active filter
        toggle(TaskStatusFilter.PENDING)
        assertEquals(setOf(TaskStatusFilter.PENDING), filters)

        // Select COMPLETED -> both are selected (semantics: 全部)
        toggle(TaskStatusFilter.COMPLETED)
        assertEquals(setOf(TaskStatusFilter.PENDING, TaskStatusFilter.COMPLETED), filters)

        // Deselect PENDING -> only COMPLETED remains
        toggle(TaskStatusFilter.PENDING)
        assertEquals(setOf(TaskStatusFilter.COMPLETED), filters)

        // Cannot deselect COMPLETED when it's the only one
        toggle(TaskStatusFilter.COMPLETED)
        assertEquals(setOf(TaskStatusFilter.COMPLETED), filters)

        // Select PENDING -> both are selected again
        toggle(TaskStatusFilter.PENDING)
        assertEquals(setOf(TaskStatusFilter.PENDING, TaskStatusFilter.COMPLETED), filters)

        // Deselect COMPLETED -> only PENDING remains
        toggle(TaskStatusFilter.COMPLETED)
        assertEquals(setOf(TaskStatusFilter.PENDING), filters)
    }

    @Test
    fun testTaskMatchingSemantics() {
        fun matches(isCompleted: Boolean, statusFilters: Set<TaskStatusFilter>): Boolean {
            return if (isCompleted) {
                TaskStatusFilter.COMPLETED in statusFilters
            } else {
                TaskStatusFilter.PENDING in statusFilters
            }
        }

        val pendingOnly = setOf(TaskStatusFilter.PENDING)
        val completedOnly = setOf(TaskStatusFilter.COMPLETED)
        val bothAll = setOf(TaskStatusFilter.PENDING, TaskStatusFilter.COMPLETED)

        // Both selected == 全部: both completed and pending match
        assertTrue(matches(isCompleted = false, statusFilters = bothAll))
        assertTrue(matches(isCompleted = true, statusFilters = bothAll))

        // Pending only
        assertTrue(matches(isCompleted = false, statusFilters = pendingOnly))
        assertFalse(matches(isCompleted = true, statusFilters = pendingOnly))

        // Completed only
        assertFalse(matches(isCompleted = false, statusFilters = completedOnly))
        assertTrue(matches(isCompleted = true, statusFilters = completedOnly))
    }
}
