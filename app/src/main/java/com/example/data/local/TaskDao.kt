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

    /**
     * 只改星标那一列（`priority`，存 `STAR` / 空串，见 `TaskStar`）。
     *
     * 和 [updateTask] 分开是刻意的：星标在列表里是个高频点按，
     * 走整行 `@Update` 会把同一行的其它字段一起写回去——一旦用户在这条上
     * 刚改过什么（或并发的另一条路径改过），整行回写就会把它悄悄盖掉。
     */
    @Query("UPDATE tasks SET priority = :priority WHERE id = :id")
    suspend fun updateTaskStar(id: Long, priority: String)

    @Delete
    suspend fun deleteTask(task: TaskEntity)

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun deleteTaskById(id: Long)

    /**
     * 把某门课下的待办整体移入「未关联课程」，返回被改写的条数。
     *
     * **删课程时走这个，不要写 DELETE。** 待办是用户自己的数据（手写的正文、
     * 勾选状态、截止日期、星标），删一门课不该顺手删掉它上面挂的东西 ——
     * 课程没了，待办还在，只是失去了归属。
     *
     * 只改归属相关的四列：标题、正文、截止、勾选状态、星标一律不碰。
     * 课程色 / 类别 / 课程名是从任务行上读的快照，不跟着改的话，
     * 这些待办会在清单里继续挂着已经不存在的课的配色。
     *
     * 「未关联」的三个哨兵由调用方从 [TaskEntity] 传进来，不写死在 SQL 里：
     * Room 只认注解里的字面量，写一份就等于给这组哨兵开出第二个真源。
     */
    @Query(
        """
        UPDATE tasks
        SET courseId = :unlinkedId,
            courseName = :unlinkedName,
            courseCategory = '',
            courseColorHex = :unlinkedColor
        WHERE courseId = :courseId
        """
    )
    suspend fun unlinkTasksByCourseId(
        courseId: Long,
        unlinkedId: Long,
        unlinkedName: String,
        unlinkedColor: String
    ): Int

    @Query("DELETE FROM tasks WHERE taskType = :taskType")
    suspend fun deleteTasksByType(taskType: String): Int

    @Query("SELECT COUNT(*) FROM tasks WHERE taskType = :taskType")
    suspend fun countTasksByType(taskType: String): Int

    @Query("DELETE FROM tasks")
    suspend fun clearTasks()
}
