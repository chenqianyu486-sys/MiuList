package com.example.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CourseDao {
    @Query("SELECT * FROM courses ORDER BY id ASC")
    fun getAllCourses(): Flow<List<CourseEntity>>

    @Transaction
    @Query("SELECT * FROM courses ORDER BY id ASC")
    fun getAllCoursesWithSlots(): Flow<List<CourseWithSlots>>

    @Transaction
    @Query("SELECT * FROM courses WHERE id = :id LIMIT 1")
    suspend fun getCourseWithSlotsById(id: Long): CourseWithSlots?

    @Query("SELECT * FROM courses WHERE id = :id LIMIT 1")
    suspend fun getCourseById(id: Long): CourseEntity?

    /**
     * 按自然键查重。**可能返回多条**：dedupeKey 用的是非唯一索引，
     * 历史数据里本就可能存在同名同师同代码的课程。调用方取首条并记 issue。
     */
    @Query("SELECT * FROM courses WHERE dedupeKey = :dedupeKey ORDER BY id ASC")
    suspend fun getByDedupeKey(dedupeKey: String): List<CourseEntity>

    @Query("SELECT COUNT(*) FROM courses")
    suspend fun getAllCoursesCount(): Int

    @Query("SELECT * FROM courses ORDER BY id ASC")
    suspend fun getAllCoursesOnce(): List<CourseEntity>

    /**
     * 按类别取课。「课程联动规则」写穿时按这个取：一次只动这一个类别的课程。
     */
    @Query("SELECT * FROM courses WHERE category = :category ORDER BY id ASC")
    suspend fun getByCategory(category: String): List<CourseEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCourse(course: CourseEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCourses(courses: List<CourseEntity>): List<Long>

    @Update
    suspend fun updateCourse(course: CourseEntity)

    /**
     * 只改类别，别的一概不动。
     *
     * 课程自己那三个开关（课前预习 / 课后作业 / 实验报告）是**用户自己的值**，
     * 课程编辑里也是这么定的（见 `saveCourseWithSlots` 的编辑分支）。
     * 分类只是把这门课挪到另一个组，不该替他重置开关。
     */
    @Query("UPDATE courses SET category = :category WHERE id = :id")
    suspend fun updateCategory(id: Long, category: String)

    /** 批量写回，只改开关时不用为每门课单开一条 UPDATE。 */
    @Update
    suspend fun updateCourses(courses: List<CourseEntity>)

    @Delete
    suspend fun deleteCourse(course: CourseEntity)

    @Query("DELETE FROM courses WHERE id = :id")
    suspend fun deleteCourseById(id: Long)

    @Query("DELETE FROM courses")
    suspend fun clearCourses()
}
