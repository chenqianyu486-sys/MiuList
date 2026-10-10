package com.example.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "course_slots",
    foreignKeys = [
        ForeignKey(
            entity = CourseEntity::class,
            parentColumns = ["id"],
            childColumns = ["courseId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["courseId"]), Index(value = ["dayOfWeek", "startPeriod"])]
)
data class CourseSlotEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val courseId: Long,
    val dayOfWeek: Int, // 1 = 周一 ... 7 = 周日
    val startPeriod: Int, // 1..13
    val endPeriod: Int, // 1..13 (>= startPeriod)
    val classroom: String = "" // if blank, uses course.classroom
)
