package com.example.data.local

import androidx.room.Embedded
import androidx.room.Relation
import com.example.ui.components.formatPeriodRange
import com.example.ui.components.getDayName

data class CourseWithSlots(
    @Embedded val course: CourseEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "courseId"
    )
    val slots: List<CourseSlotEntity>
) {
    fun getScheduleSummary(): String {
        if (slots.isEmpty()) return "暂未安排上课时间"
        return slots.sortedWith(compareBy({ it.dayOfWeek }, { it.startPeriod }))
            .joinToString("；") { slot ->
                "${getDayName(slot.dayOfWeek)} ${formatPeriodRange(slot.startPeriod, slot.endPeriod)}"
            }
    }
}
