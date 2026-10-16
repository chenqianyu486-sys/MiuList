package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 校历四张表（学期单例 / 作息表 / 停课日 / 补课日）的数据访问。
 *
 * 放同一个 DAO 是因为它们总是被一起读取、一起替换——拆成四个 DAO 只会让调用处
 * 多传三个参数，换不来任何隔离价值。
 */
@Dao
interface CalendarDao {

    // ---- 学期（单例） ----

    @Query("SELECT * FROM semester WHERE id = 1 LIMIT 1")
    fun semester(): Flow<SemesterEntity?>

    @Query("SELECT * FROM semester WHERE id = 1 LIMIT 1")
    suspend fun semesterOnce(): SemesterEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSemester(semester: SemesterEntity)

    @Query("DELETE FROM semester")
    suspend fun clearSemester()

    // ---- 作息表 ----

    @Query("SELECT * FROM timetable_periods ORDER BY period ASC")
    fun timetablePeriods(): Flow<List<TimetablePeriodEntity>>

    @Query("SELECT * FROM timetable_periods ORDER BY period ASC")
    suspend fun timetablePeriodsOnce(): List<TimetablePeriodEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPeriods(periods: List<TimetablePeriodEntity>)

    @Query("DELETE FROM timetable_periods")
    suspend fun clearPeriods()

    // ---- 停课日 ----

    @Query("SELECT * FROM no_class_days ORDER BY epochDay ASC")
    fun noClassDays(): Flow<List<NoClassDayEntity>>

    @Query("SELECT * FROM no_class_days ORDER BY epochDay ASC")
    suspend fun noClassDaysOnce(): List<NoClassDayEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNoClassDays(days: List<NoClassDayEntity>)

    @Query("DELETE FROM no_class_days")
    suspend fun clearNoClassDays()

    // ---- 补课日 ----

    @Query("SELECT * FROM makeup_days ORDER BY epochDay ASC")
    fun makeupDays(): Flow<List<MakeupDayEntity>>

    @Query("SELECT * FROM makeup_days ORDER BY epochDay ASC")
    suspend fun makeupDaysOnce(): List<MakeupDayEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMakeupDays(days: List<MakeupDayEntity>)

    @Query("DELETE FROM makeup_days")
    suspend fun clearMakeupDays()

    @Query("DELETE FROM makeup_days WHERE epochDay IN (:epochDays)")
    suspend fun deleteMakeupDays(epochDays: List<Long>)
}