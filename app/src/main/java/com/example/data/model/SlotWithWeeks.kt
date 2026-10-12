package com.example.data.model

import com.example.data.local.CourseSlotEntity

/**
 * 时段 + 该时段的「开课周次」。
 *
 * 落库时 [CourseSlotEntity.id] 要等插入后才拿得到，所以写入路径必须先把
 * 时段插进去、拿到 id，再把周次行插进 `course_slot_weeks`。
 */
data class SlotWithWeeks(
    val slot: CourseSlotEntity,
    val weeks: Set<Int>
) {
    val dayOfWeek: Int get() = slot.dayOfWeek
    val startPeriod: Int get() = slot.startPeriod
    val endPeriod: Int get() = slot.endPeriod
    val classroom: String get() = slot.classroom
}