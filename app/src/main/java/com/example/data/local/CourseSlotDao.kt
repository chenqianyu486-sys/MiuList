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

    @Query("SELECT * FROM course_slots ORDER BY dayOfWeek ASC, startPeriod ASC")
    suspend fun getAllSlotsOnce(): List<CourseSlotEntity>

    @Query("SELECT * FROM course_slots WHERE courseId = :courseId ORDER BY dayOfWeek ASC, startPeriod ASC")
    fun getSlotsForCourse(courseId: Long): Flow<List<CourseSlotEntity>>

    @Query("SELECT * FROM course_slots WHERE courseId = :courseId ORDER BY dayOfWeek ASC, startPeriod ASC")
    suspend fun getSlotsForCourseSync(courseId: Long): List<CourseSlotEntity>

    @Query("SELECT * FROM course_slots WHERE dayOfWeek = :dayOfWeek ORDER BY startPeriod ASC")
    fun getSlotsByDay(dayOfWeek: Int): Flow<List<CourseSlotEntity>>

    @Query("SELECT * FROM course_slots WHERE dayOfWeek = :dayOfWeek ORDER BY startPeriod ASC")
    suspend fun getSlotsByDaySync(dayOfWeek: Int): List<CourseSlotEntity>

    /**
     * 周次筛选下推到 SQL：一条 JOIN 取代「拉全部时段再在 Kotlin 里过滤」。
     *
     * 注意周次来自 `course_slot_weeks`，所以**未指定开课周次的时段查不到**——
     * 调用方在没配置学期时要走 [getSlotsByDaySync] 这条不过滤周次的路径。
     */
    @Query(
        """
        SELECT s.* FROM course_slots s
        INNER JOIN course_slot_weeks w ON w.courseSlotId = s.id
        WHERE s.dayOfWeek = :dayOfWeek AND w.week = :week
        ORDER BY s.startPeriod ASC
        """
    )
    suspend fun getSlotsForDayAndWeek(dayOfWeek: Int, week: Int): List<CourseSlotEntity>

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
