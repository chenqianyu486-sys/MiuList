package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CustomTaskTypeDao {

    @Query("SELECT * FROM custom_task_types ORDER BY sortOrder ASC, createdAt ASC")
    fun observeAll(): Flow<List<CustomTaskTypeEntity>>

    @Query("SELECT * FROM custom_task_types ORDER BY sortOrder ASC, createdAt ASC")
    suspend fun getAllOnce(): List<CustomTaskTypeEntity>

    @Query("SELECT COUNT(*) FROM custom_task_types")
    suspend fun count(): Int

    /**
     * 插入一条自定义待办类型。
     *
     * **冲突时覆盖而不是报错**：导入的语义是「另一台设备上的我更权威」，
     * 那边最新的名字与配色就是用户改完的样子。同一份整机快照导第二次时 id 会撞上，
     * 而报错的话整个导入事务都回滚 —— 用户做的事只是把同一份文件又导了一遍，
     * 看到的结果却是「导不进来」。
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: CustomTaskTypeEntity)

    /**
     * 删类型**不**级联去改 `tasks.taskType`。
     *
     * 那些待办会留下 `CUSTOM:<id>` 的悬空 key，解析时返回 null（见 `TaskTypes.resolve`），
     * 于是它们退化成「未分类」——正好等于把这一种类型从待办上摘掉，
     * 不用为一次删除写一整段 UPDATE，删类型也因此永远是个 O(1) 的操作。
     */
    @Query("DELETE FROM custom_task_types WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM custom_task_types")
    suspend fun clear()
}