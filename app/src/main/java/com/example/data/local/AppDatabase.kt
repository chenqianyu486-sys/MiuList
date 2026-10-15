package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        CourseEntity::class,
        CourseSlotEntity::class,
        TaskEntity::class,
        CourseSlotWeekEntity::class,
        CategoryRuleEntity::class,
        TaskGenerationRuleEntity::class,
        SemesterEntity::class,
        TimetablePeriodEntity::class,
        NoClassDayEntity::class,
        MakeupDayEntity::class,
        CustomTaskTypeEntity::class
    ],
    version = 9,
    // 导出 schema 是 MigrationTestHelper 的前提：没有它就只能靠手点验证迁移
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun courseDao(): CourseDao
    abstract fun courseSlotDao(): CourseSlotDao
    abstract fun courseSlotWeekDao(): CourseSlotWeekDao
    abstract fun taskDao(): TaskDao
    abstract fun categoryRuleDao(): CategoryRuleDao
    abstract fun taskGenerationRuleDao(): TaskGenerationRuleDao
    abstract fun calendarDao(): CalendarDao
    abstract fun customTaskTypeDao(): CustomTaskTypeDao

    companion object {
        const val NAME = "course_todo_database"

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    NAME
                )
                    // 有真实迁移后不再允许破坏性回退。
                    // 漏写 MIGRATION_4_5 会让升级崩溃 —— 这比静默清空用户
                    // 的全部课表与待办安全得多，崩溃至少会被迁移测试和首次
                    // 升级立刻发现，清空则悄无声息。
                    .addMigrations(
                        MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8,
        MIGRATION_8_9
                    )
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}