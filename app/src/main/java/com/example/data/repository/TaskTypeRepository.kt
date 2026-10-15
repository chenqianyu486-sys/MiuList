package com.example.data.repository

import androidx.room.withTransaction
import com.example.data.local.AppDatabase
import com.example.data.local.CustomTaskTypeDao
import com.example.data.local.CustomTaskTypeEntity
import com.example.data.model.CustomTaskType
import com.example.data.model.TaskTypePalettes
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * 用户新建的待办类型。
 *
 * 只管存取，配色**不**在这里选——见 [create] 的说明：色对必须是逐个验算过的，
 * 让调用方从 [TaskTypePalettes] 里挑一个传进来，比在这里临时推导更可控。
 */
class TaskTypeRepository(
    private val db: AppDatabase,
    private val dao: CustomTaskTypeDao
) {

    fun observeAll(): Flow<List<CustomTaskType>> =
        dao.observeAll().map { rows -> rows.map { it.toModel() } }

    suspend fun getAllOnce(): List<CustomTaskType> = dao.getAllOnce().map { it.toModel() }

    /**
     * 新建一个类型。
     *
     * [paletteIndex] 必须在 [TaskTypePalettes] 的范围内——调用方（新建类型弹层）
     * 本来就是让用户在一排色块里点选的，不接受「算一个颜色」。
     *
     * 重名直接拒绝：类型是筛选和分组的一级维度，两个同名类型会让
     * 「按类型」视图里出现两行一模一样只靠颜色区分的组头。
     */
    suspend fun create(label: String, paletteIndex: Int): Result<String> {
        val trimmed = label.trim()
        if (trimmed.isEmpty()) return Result.failure(IllegalArgumentException("类型名不能为空"))
        if (dao.count() >= MAX_CUSTOM_TYPES) {
            return Result.failure(IllegalStateException("自定义类型最多 $MAX_CUSTOM_TYPES 个"))
        }
        if (dao.getAllOnce().any { it.label.equals(trimmed, ignoreCase = true) }) {
            return Result.failure(IllegalStateException("已经有叫「$trimmed」的类型了"))
        }
        val palette = TaskTypePalettes.getOrNull(paletteIndex)
            ?: return Result.failure(IllegalArgumentException("配色超出可选范围"))

        val id = UUID.randomUUID().toString()
        // sortOrder 取当前最大值 +1，保证新建的在筛选行和分组顺序里排在最后，
        // 而不是每次都插到中间、把用户已经习惯的顺序打乱。
        val nextOrder = (dao.getAllOnce().maxOfOrNull { it.sortOrder } ?: 0) + 1
        db.withTransaction {
            dao.insert(
                CustomTaskTypeEntity(
                    id = id,
                    label = trimmed,
                    colorHex = palette.colorHex,
                    inkHex = palette.inkHex,
                    sortOrder = nextOrder
                )
            )
        }
        return Result.success(id)
    }

    /**
     * 删掉一个自定义类型，并在同一个事务中同时删除该分类下的所有待办。
     *
     * 返回被删除的待办条数。
     */
    suspend fun delete(id: String): Int {
        val taskTypeKey = "CUSTOM:$id"
        return db.withTransaction {
            val deletedCount = db.taskDao().deleteTasksByType(taskTypeKey)
            dao.deleteById(id)
            deletedCount
        }
    }

    private fun CustomTaskTypeEntity.toModel() = CustomTaskType(
        id = id,
        label = label,
        colorHex = colorHex,
        inkHex = inkHex,
        sortOrder = sortOrder
    )

    private companion object {
        /**
         * 自定义类型数量上限。
         *
         * 类型是筛选行里的一颗 chip，也是「按类型」视图里的一组。超过这个数，
         * 两处都会变成要横向滚很久才找得到东西的长条——而这些 chip 的宽度不可控。
         */
        const val MAX_CUSTOM_TYPES = 12
    }
}