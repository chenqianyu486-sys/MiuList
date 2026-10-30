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

    /**
     * 这些时段在所有周次行被删光之后，需要按「每周」重新铺回来。
     *
     * 学期周数变小时，越界的周次行会被删掉；某个时段若因此一行不剩，
     * 它就成了「这学期一次都不上」，而空集合在 resolver 里表示每周 ——
     * 所以要把它按每周重新铺成 1..totalWeeks（见 `CourseRepository.clampSlotWeeksTo`）。
     *
     * 判据是「**在**时段表里、却**不在**周次表里」：
     * 反过来查（周次表里有、时段表里没有）拿到的是孤儿周次行，
     * 正常情况下永远是空集 —— 于是「周次被裁空后退回每周」这条修复从未真正执行过。
     */
    @Query("SELECT id FROM course_slots WHERE id IN (:ids) " +
           "AND id NOT IN (SELECT DISTINCT courseSlotId FROM course_slot_weeks)")
    suspend fun findSlotsMissingFrom(ids: List<Long>): List<Long>

    @Query("DELETE FROM course_slot_weeks")
    suspend fun clear()
}