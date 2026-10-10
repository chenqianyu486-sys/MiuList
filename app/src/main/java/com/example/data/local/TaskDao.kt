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
    @Query("SELECT * FROM tasks ORDER BY isCompleted ASC, dueDateTimestamp ASC, id DESC")
    fun getAllTasks(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE courseId = :courseId ORDER BY isCompleted ASC, dueDateTimestamp ASC")
    fun getTasksByCourse(courseId: Long): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE courseId = :courseId AND isCompleted = 0")
    fun getPendingTasksByCourse(courseId: Long): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE taskType = :taskType ORDER BY isCompleted ASC, dueDateTimestamp ASC")
    fun getTasksByType(taskType: String): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE courseId = :courseId AND taskType = :taskType AND generatedDate = :generatedDate LIMIT 1")
    suspend fun findExistingAutoTask(courseId: Long, taskType: String, generatedDate: String): TaskEntity?

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
