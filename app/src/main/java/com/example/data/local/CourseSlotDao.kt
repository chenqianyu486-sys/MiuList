package com.example.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CourseSlotDao {
    @Query("SELECT * FROM course_slots ORDER BY dayOfWeek ASC, startPeriod ASC")
    fun getAllSlots(): Flow<List<CourseSlotEntity>>

    @Query("SELECT * FROM course_slots WHERE courseId = :courseId ORDER BY dayOfWeek ASC, startPeriod ASC")
    fun getSlotsForCourse(courseId: Long): Flow<List<CourseSlotEntity>>

    @Query("SELECT * FROM course_slots WHERE courseId = :courseId ORDER BY dayOfWeek ASC, startPeriod ASC")
    suspend fun getSlotsForCourseSync(courseId: Long): List<CourseSlotEntity>

    @Query("SELECT * FROM course_slots WHERE dayOfWeek = :dayOfWeek ORDER BY startPeriod ASC")
    fun getSlotsByDay(dayOfWeek: Int): Flow<List<CourseSlotEntity>>

    @Query("SELECT * FROM course_slots WHERE dayOfWeek = :dayOfWeek ORDER BY startPeriod ASC")
    suspend fun getSlotsByDaySync(dayOfWeek: Int): List<CourseSlotEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSlot(slot: CourseSlotEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSlots(slots: List<CourseSlotEntity>): List<Long>

    @Update
    suspend fun updateSlot(slot: CourseSlotEntity)

    @Delete
    suspend fun deleteSlot(slot: CourseSlotEntity)

    @Query("DELETE FROM course_slots WHERE courseId = :courseId")
    suspend fun deleteSlotsByCourseId(courseId: Long)

    @Query("DELETE FROM course_slots")
    suspend fun clearSlots()
}
