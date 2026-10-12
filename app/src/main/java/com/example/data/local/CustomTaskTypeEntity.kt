package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 用户自己新建的待办类型。
 *
 * 为什么不把这几列塞进一张「所有类型」表、再给内置类型也插三行种子：
 * 内置类型的文案和配色是**代码级的事实**（生成引擎、课程级规则开关都直接读
 * [com.example.data.model.TaskType] 枚举），把它降级成数据就得在每一处读的地方
 * 补一次查库，还得处理「种子行被人删了」这种状态。分成两张表之后，
 * 内置的走枚举、自定义的走这张表，解析时合并（见 `TaskTypes.options`）。
 */
@Entity(tableName = "custom_task_types")
data class CustomTaskTypeEntity(
    /** 稳定 id。落库 key 是 `CUSTOM:<id>`，所以这里的值不能复用。 */
    @PrimaryKey
    val id: String,
    val label: String,
    val colorHex: String,
    val inkHex: String,
    /** 同一次新建里批量插入时的顺序；解析时按它排。 */
    val sortOrder: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)