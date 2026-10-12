package com.example.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {

    /**
     * 未完成在前，同状态按截止时间升序，**没设截止的排在最后**。
     *
     * 那个 CASE 是必须的：`NO_DUE_TIMESTAMP` 是 `Long.MIN_VALUE`，
     * 直接 `ORDER BY dueDateTimestamp` 会把「不设截止」顶到列表最前面——
     * 而它们既不紧急也不该被第一个扫到。
     */
    @Query(
        """
        SELECT * FROM tasks
        ORDER BY isCompleted ASC,
                 CASE WHEN dueDateTimestamp = -9223372036854775808 THEN 1 ELSE 0 END ASC,
                 dueDateTimestamp ASC,
                 id DESC
        """
    )
    fun getAllTasks(): Flow<List<TaskEntity>>

    @Query(
        """
        SELECT * FROM tasks
        ORDER BY isCompleted ASC,
                 CASE WHEN dueDateTimestamp = -9223372036854775808 THEN 1 ELSE 0 END ASC,
                 dueDateTimestamp ASC,
                 id DESC
        """
    )
    suspend fun getAllTasksOnce(): List<TaskEntity>

    @Query("SELECT COUNT(*) FROM tasks")
    suspend fun countTasks(): Int

    @Query("SELECT * FROM tasks WHERE courseId = :courseId ORDER BY isCompleted ASC, dueDateTimestamp ASC")
    fun getTasksByCourse(courseId: Long): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE courseId = :courseId AND isCompleted = 0")
    fun getPendingTasksByCourse(courseId: Long): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE taskType = :taskType ORDER BY isCompleted ASC, dueDateTimestamp ASC")
    fun getTasksByType(taskType: String): Flow<List<TaskEntity>>

    /**
 * 自动生成待办的唯一性判据：**同课程 + 同类型 + 同周次 + 同周几**。
 *
 * 原来是「同课程 + 同类型 + generatedDate（同一天）」。改成周次之后，
 * 一次生成一整周也不会互相盖掉，而连点两次同一个周次仍然只出一份。
 */
@Query("SELECT * FROM tasks WHERE courseId = :courseId AND taskType = :taskType AND week = :week AND dayOfWeek = :dayOfWeek LIMIT 1")
    suspend fun findExistingWeekTask(courseId: Long, taskType: String, week: Int, dayOfWeek: Int): TaskEntity?

    @Query("SELECT COUNT(*) FROM tasks WHERE courseId = :courseId AND isCompleted = 0")
    fun countPendingTasksForCourse(courseId: Long): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: TaskEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTasks(tasks: List<TaskEntity>): List<Long>

    @Update
    suspend fun updateTask(task: TaskEntity)

    @Query("UPDATE tasks SET isCompleted = :isCompleted, completedAt = :completedAt WHERE id = :id")
    suspend fun updateTaskCompletion(id: Long, isCompleted: Boolean, completedAt: Long?)

    @Delete
    suspend fun deleteTask(task: TaskEntity)

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun deleteTaskById(id: Long)

    @Query("DELETE FROM tasks WHERE courseId = :courseId")
    suspend fun deleteTasksByCourseId(courseId: Long)

    @Query("DELETE FROM tasks")
    suspend fun clearTasks()
}
