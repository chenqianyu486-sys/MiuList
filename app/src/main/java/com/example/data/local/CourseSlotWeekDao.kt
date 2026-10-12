package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CourseSlotWeekDao {

    /** 全表读出。课表规模下最多几百行，一次全取比按需查询更好推理。 */
    @Query("SELECT * FROM course_slot_weeks")
    fun getAll(): Flow<List<CourseSlotWeekEntity>>

    @Query("SELECT * FROM course_slot_weeks")
    suspend fun getAllOnce(): List<CourseSlotWeekEntity>

    @Query("SELECT * FROM course_slot_weeks WHERE courseSlotId = :slotId ORDER BY week ASC")
    suspend fun getForSlot(slotId: Long): List<CourseSlotWeekEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<CourseSlotWeekEntity>)

    @Query("DELETE FROM course_slot_weeks WHERE courseSlotId = :slotId")
    suspend fun deleteForSlot(slotId: Long)

    @Query("DELETE FROM course_slot_weeks WHERE week > :totalWeeks")
    suspend fun deleteWeeksAbove(totalWeeks: Int)

    @Query("SELECT DISTINCT courseSlotId FROM course_slot_weeks WHERE courseSlotId NOT IN (:ids)")
    suspend fun findSlotsMissingFrom(ids: List<Long>): List<Long>

    @Query("DELETE FROM course_slot_weeks")
    suspend fun clear()
}