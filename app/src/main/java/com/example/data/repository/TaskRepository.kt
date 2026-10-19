package com.example.data.repository

import com.example.data.local.TaskDao
import com.example.data.local.TaskEntity
import com.example.data.model.TaskStar
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/** 待办的读写。 */
class TaskRepository(private val taskDao: TaskDao) {

    fun getAllTasks(): Flow<List<TaskEntity>> = taskDao.getAllTasks()

    fun getTasksByCourse(courseId: Long): Flow<List<TaskEntity>> = taskDao.getTasksByCourse(courseId)

    fun getPendingTasksByCourse(courseId: Long): Flow<List<TaskEntity>> =
        taskDao.getPendingTasksByCourse(courseId)

    fun countPendingTasksForCourse(courseId: Long): Flow<Int> = taskDao.countPendingTasksForCourse(courseId)

    suspend fun getAllTasksOnce(): List<TaskEntity> = taskDao.getAllTasksOnce()

    /**
     * 新建一条待办：分配 `syncUid` 并盖上 `updatedAt`。
     *
     * 这两件事放在仓储这一层而不是让每个调用方记得做 —— 漏一处就产出一条
     * 「在库里存在、但在同步文件里永远缺席」的待办，而那种缺失没有任何报错。
     */
    suspend fun insertTask(task: TaskEntity): Long {
        val now = System.currentTimeMillis()
        return taskDao.insertTask(task.stamped(now))
    }

    /** 批量插入，逐条盖时间戳（同一批共用同一个时刻）。 */
    suspend fun insertTasks(tasks: List<TaskEntity>): List<Long> {
        if (tasks.isEmpty()) return emptyList()
        val now = System.currentTimeMillis()
        return taskDao.insertTasks(tasks.map { it.stamped(now) })
    }

    /**
     * 编辑保存。`updatedAt` 由这里盖，不取实体里带的那个值 ——
     * 弹层持有的是一条**旧快照**，它身上的 `updatedAt` 是打开弹层那一刻的。
     */
    suspend fun updateTask(task: TaskEntity) =
        taskDao.updateTask(task.stamped(System.currentTimeMillis()))

    suspend fun deleteTask(task: TaskEntity) = taskDao.deleteTask(task)

    suspend fun countTasks(): Int = taskDao.countTasks()

    suspend fun toggleTaskComplete(id: Long, currentStatus: Boolean) {
        val newStatus = !currentStatus
        val completedAt = if (newStatus) System.currentTimeMillis() else null
        taskDao.updateTaskCompletion(id, newStatus, completedAt, System.currentTimeMillis())
    }

    /**
     * 设星标。取值一律过 [TaskStar.encode]，别在别处拼 `STAR` / 空串。
     */
    suspend fun setTaskStar(id: Long, marked: Boolean) {
        taskDao.updateTaskStar(id, TaskStar.encode(marked), System.currentTimeMillis())
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

    /**
     * 给还没有 `syncUid` 的历史行补上稳定身份。应用启动时调一次。返回补了几条。
     *
     * **`syncUid` 必须是随机 UUID，绝不能由内容派生**：标题和正文用户随时会改，
     * 内容哈希一改就变，于是同一条待办在两台设备上算出两个 uid，
     * 每次跨设备同步都会把它当成两条不同的待办各插一份。
     *
     * `updatedAt` 初值取该行**已有的** `createdAt`：填 0 会让它在任何一次
     * 后写覆盖里都判负，等于历史待办永远被别人导出的文件覆盖。
     *
     * 逐条写而不是一条 UPDATE 全表盖：这里要的是每行一个不同的 UUID。
     */
    suspend fun ensureSyncUids(): Int {
        val pending = taskDao.getMissingSyncUids()
        if (pending.isEmpty()) return 0
        for (task in pending) {
            taskDao.updateTask(
                task.copy(
                    syncUid = UUID.randomUUID().toString(),
                    updatedAt = task.createdAt
                )
            )
        }
        return pending.size
    }

    /** 补齐同步身份；已有 uid 的原样返回（导入侧拿文件里的 uid 时也走这条）。 */
    private fun TaskEntity.stamped(now: Long): TaskEntity = copy(
        syncUid = syncUid.ifBlank { UUID.randomUUID().toString() },
        updatedAt = now
    )
}