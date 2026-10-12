package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryRuleDao {

    @Query("SELECT * FROM category_rules ORDER BY category ASC")
    fun getAll(): Flow<List<CategoryRuleEntity>>

    @Query("SELECT * FROM category_rules ORDER BY category ASC")
    suspend fun getAllOnce(): List<CategoryRuleEntity>

    @Query("SELECT * FROM category_rules WHERE category = :category LIMIT 1")
    suspend fun getByCategory(category: String): CategoryRuleEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rules: List<CategoryRuleEntity>)

    @Query("DELETE FROM category_rules")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM category_rules")
    suspend fun count(): Int
}