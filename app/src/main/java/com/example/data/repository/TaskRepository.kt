package com.example.data.repository

import com.example.data.local.TaskDao
import com.example.data.local.TaskEntity
import com.example.data.model.TaskStar
import kotlinx.coroutines.flow.Flow

/** 待办的读写。 */
class TaskRepository(private val taskDao: TaskDao) {

    fun getAllTasks(): Flow<List<TaskEntity>> = taskDao.getAllTasks()

    fun getTasksByCourse(courseId: Long): Flow<List<TaskEntity>> = taskDao.getTasksByCourse(courseId)

    fun getPendingTasksByCourse(courseId: Long): Flow<List<TaskEntity>> =
        taskDao.getPendingTasksByCourse(courseId)

    fun countPendingTasksForCourse(courseId: Long): Flow<Int> = taskDao.countPendingTasksForCourse(courseId)

    suspend fun getAllTasksOnce(): List<TaskEntity> = taskDao.getAllTasksOnce()

    suspend fun insertTask(task: TaskEntity): Long = taskDao.insertTask(task)

    suspend fun insertTasks(tasks: List<TaskEntity>): List<Long> = taskDao.insertTasks(tasks)

    suspend fun updateTask(task: TaskEntity) = taskDao.updateTask(task)

    suspend fun deleteTask(task: TaskEntity) = taskDao.deleteTask(task)

    suspend fun countTasks(): Int = taskDao.countTasks()

    suspend fun toggleTaskComplete(id: Long, currentStatus: Boolean) {
        val newStatus = !currentStatus
        val completedAt = if (newStatus) System.currentTimeMillis() else null
        taskDao.updateTaskCompletion(id, newStatus, completedAt)
    }

    /**
     * 设星标。取值一律过 [TaskStar.encode]，别在别处拼 `STAR` / 空串。
     */
    suspend fun setTaskStar(id: Long, marked: Boolean) {
        taskDao.updateTaskStar(id, TaskStar.encode(marked))
    }

    /**
     * 「同课程 + 同类型 + 同周次 + 同周几」的自动待办是否已存在。
     *
     * 抽成方法而不是让引擎直接摸 DAO：预览路径与提交路径都要它，
     * 但前者不该依赖写库能力。
     */
    suspend fun hasWeekTask(courseId: Long, taskTypeName: String, week: Int, dayOfWeek: Int): Boolean =
        taskDao.findExistingWeekTask(courseId, taskTypeName, week, dayOfWeek) != null

    suspend fun deleteTasksByType(taskType: String): Int = taskDao.deleteTasksByType(taskType)

    suspend fun countTasksByType(taskType: String): Int = taskDao.countTasksByType(taskType)
}