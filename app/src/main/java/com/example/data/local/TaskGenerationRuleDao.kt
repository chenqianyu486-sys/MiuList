package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskGenerationRuleDao {

    @Query("SELECT * FROM task_generation_rules")
    fun getAll(): Flow<List<TaskGenerationRuleEntity>>

    @Query("SELECT * FROM task_generation_rules")
    suspend fun getAllOnce(): List<TaskGenerationRuleEntity>

    @Query("SELECT * FROM task_generation_rules WHERE courseId = :courseId")
    suspend fun getByCourse(courseId: Long): List<TaskGenerationRuleEntity>

    @Query("SELECT COUNT(*) FROM task_generation_rules")
    suspend fun count(): Int

    /** 规则粒度是「课程 + 类型」，所以主键冲突就意味着同一格被改过 —— 覆盖它。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rule: TaskGenerationRuleEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rules: List<TaskGenerationRuleEntity>)

    @Query("DELETE FROM task_generation_rules WHERE courseId = :courseId AND taskType = :taskType")
    suspend fun delete(courseId: Long, taskType: String)

    @Query("DELETE FROM task_generation_rules")
    suspend fun clear()
}