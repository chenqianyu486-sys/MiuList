package com.example.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v3 → v4。
 *
 * 这次迁移**只做纯新增**：给 `courses` 补一列，其余全是新表。
 * `course_slots` 一列不动 —— 「未指定开课周次」天然等于「每周」，
 * 正是改造前的行为，对现有用户零变化。
 *
 * 破坏性回退（`fallbackToDestructiveMigration`）已在 [AppDatabase] 移除：
 * 有了真实迁移后继续留着，等于「未来任何 schema 疏漏都静默清空用户数据」。
 * 代价是漏写迁移会让升级用户崩溃而不是清空 —— 这是刻意的，两者都不可接受时
 * 崩溃更安全，因为它会被迁移测试和第一次升级立刻发现。
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {

        // 1. 课程自然键（重复导入的幂等基础）
        //    SQLite 要求给已有行的表加 NOT NULL 列时必须带默认值。
        //    实体侧没有 @ColumnInfo(defaultValue=...)，Room 校验时空默认值视为通过。
        db.execSQL("ALTER TABLE courses ADD COLUMN dedupeKey TEXT NOT NULL DEFAULT ''")
        db.execSQL(
            """
            UPDATE courses SET dedupeKey =
                TRIM(LOWER(IFNULL(code, ''))) || '|' ||
                TRIM(LOWER(IFNULL(name, ''))) || '|' ||
                TRIM(LOWER(IFNULL(teacher, '')))
            """.trimIndent()
        )
        // 非唯一：历史数据可能已含重复课程，唯一索引会让本迁移直接失败
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_courses_dedupeKey` ON `courses` (`dedupeKey`)")

        // 2. 开课周次。ON DELETE CASCADE 让「时段删了周次残留」不可能发生
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `course_slot_weeks` (
                `courseSlotId` INTEGER NOT NULL,
                `week` INTEGER NOT NULL,
                PRIMARY KEY(`courseSlotId`, `week`),
                FOREIGN KEY(`courseSlotId`) REFERENCES `course_slots`(`id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_course_slot_weeks_week` " +
                "ON `course_slot_weeks` (`week`)"
        )

        // 3. 类别规则落库（改造前只活在内存 StateFlow 里，进程一杀就丢）
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `category_rules` (
                `category` TEXT NOT NULL,
                `preStudy` INTEGER NOT NULL,
                `postHomework` INTEGER NOT NULL,
                `labReport` INTEGER NOT NULL,
                `defaultPostDays` INTEGER NOT NULL,
                `defaultMinutes` INTEGER NOT NULL,
                PRIMARY KEY(`category`)
            )
            """.trimIndent()
        )

        // 4. 校历单例。**不插默认行**：未配置时走 AcademicCalendar 的降级路径
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `semester` (
                `id` INTEGER NOT NULL,
                `name` TEXT NOT NULL,
                `yearSpan` TEXT NOT NULL,
                `term` TEXT NOT NULL,
                `startEpochDay` INTEGER NOT NULL,
                `firstMondayEpochDay` INTEGER NOT NULL,
                `endEpochDay` INTEGER NOT NULL,
                `totalWeeks` INTEGER NOT NULL,
                `periodsPerDay` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )

        // 5. 作息表种子。
        //    这段 INSERT 是本次迁移的关键：它让「作息数据化」对现有用户完全无感。
        //    不这么做，用户升级瞬间课表栅格的行数、大节分隔线位置、每节课时间
        //    会全部变形。数值与 PeriodTable.DEFAULT_PERIODS 逐条一致，
        //    由迁移测试逐字段比对守住。
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `timetable_periods` (
                `period` INTEGER NOT NULL,
                `startMinute` INTEGER NOT NULL,
                `endMinute` INTEGER NOT NULL,
                `section` TEXT NOT NULL,
                PRIMARY KEY(`period`)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO `timetable_periods` (`period`, `startMinute`, `endMinute`, `section`) VALUES
                (1, 480, 525, '上午'),
                (2, 535, 580, '上午'),
                (3, 600, 645, '上午'),
                (4, 655, 700, '上午'),
                (5, 840, 885, '上午'),
                (6, 895, 940, '下午'),
                (7, 960, 1005, '下午'),
                (8, 1015, 1060, '下午'),
                (9, 1110, 1155, '下午'),
                (10, 1165, 1210, '下午'),
                (11, 1220, 1265, '晚上'),
                (12, 1275, 1320, '晚上'),
                (13, 1325, 1370, '晚上')
            """.trimIndent()
        )

        // 6. 停课日（区间在导入时已按天展开）
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `no_class_days` (
                `epochDay` INTEGER NOT NULL,
                `note` TEXT NOT NULL,
                PRIMARY KEY(`epochDay`)
            )
            """.trimIndent()
        )

        // 7. 补课日。makeupForDayOfWeek = 这天要上星期几的课
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `makeup_days` (
                `epochDay` INTEGER NOT NULL,
                `makeupForDayOfWeek` INTEGER NOT NULL,
                `note` TEXT NOT NULL,
                PRIMARY KEY(`epochDay`)
            )
            """.trimIndent()
        )
    }
}

/**
 * v4 → v5。
 *
 * 「一键生成本周待办」把待办的唯一性判据从「课程 + 类型 + 某一天」换成
 * 「课程 + 类型 + 周次 + 周几」，于是要多存两列；生成规则也从课程类别下沉到
 * 课程 × 类型，多了一张表。
 *
 * 全部是**纯新增**：已有的待办拿不到周次就落成 0（= 无周次概念），
 * 它们本来就不参与新判据的重复生成，一次性生成的老待办不会被误删也不会被误重。
 * 这是刻意的——宁可老的自动待办留一个「看不出属于第几周」，也不要为了凑周次去猜：
 * 猜错会让下一周生成时把它们重复一遍。
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {

        // 1. 待办的周次与周几。
        //    SQLite 要求给已有行的表加 NOT NULL 列时必须带默认值；实体侧也要写
        //    @ColumnInfo(defaultValue = "0")，否则 Room 校验老库会直接判为 schema 不符。
        db.execSQL("ALTER TABLE tasks ADD COLUMN week INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE tasks ADD COLUMN dayOfWeek INTEGER NOT NULL DEFAULT 0")

        // 2. 去重热路径的复合索引
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_tasks_courseId_taskType_week_dayOfWeek` " +
                "ON `tasks` (`courseId`, `taskType`, `week`, `dayOfWeek`)"
        )

        // 3. 课程 × 类型的生成规则。
        //    外键指向 courses 且 ON DELETE CASCADE：删课程时规则不可能残留。
        //    (与 tasks 不同，这里不会出现 courseId = 0 的哨兵行，所以外键是安全的。)
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `task_generation_rules` (
                `courseId` INTEGER NOT NULL,
                `taskType` TEXT NOT NULL,
                `cadence` TEXT NOT NULL,
                `dueOffsetDays` INTEGER NOT NULL,
                `dueBeforeMinutes` INTEGER NOT NULL,
                PRIMARY KEY(`courseId`, `taskType`),
                FOREIGN KEY(`courseId`) REFERENCES `courses`(`id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_task_generation_rules_courseId` " +
                "ON `task_generation_rules` (`courseId`)"
        )

        // 4. 类别规则新增「预习提前分钟数」。
        //    原来类别规则只有 defaultPostDays 一个截止参数，预习没有自己的口径，
        //    补一列才能让类别层也能给预习定截止（课程级没配时兜底用）。
        db.execSQL(
            "ALTER TABLE category_rules ADD COLUMN preDueBeforeMinutes INTEGER NOT NULL DEFAULT 5"
        )
    }
}

/**
 * v5 → v6。
 *
 * 两件事，都不新增待办表上的列：
 *
 * 1. 用户自定义的待办类型落一张新表。
 * 2. 内置类型砍掉「复习总结」和「其他」——「其他」和新增的「留空」是同一件事，
 *    两份表达会让筛选里出现两个意思相同的选项。历史数据里这两种类型的待办
 *    统一改写成空串，也就是「未分类」，而不是留一个解析不出来的悬空值。
 *
 * 截止日期可留空**没有**动 schema：那一列继续是非空 Integer，
 * 「无截止」用 `TaskEntity.NO_DUE_TIMESTAMP` 哨兵（见 TaskEntity）。
 * 为一个「本来就可以没有」的语义去重建整张 tasks 表并逐行回填，
 * 风险远大于收益——而且新表一落地，类型就再也不是枚举了，
 * 那条老路（改列）会逼着所有读 `taskType` 的地方同时改类型。
 */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {

        // 1. 用户新建的待办类型
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `custom_task_types` (
                `id` TEXT NOT NULL,
                `label` TEXT NOT NULL,
                `colorHex` TEXT NOT NULL,
                `inkHex` TEXT NOT NULL,
                `sortOrder` INTEGER NOT NULL,
                `createdAt` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )

        // 2. 历史「复习总结 / 其他」降级为「未分类」。
        //    tasks 上有 (courseId, taskType, ...) 的去重索引，两种类型值都在里面，
        //    改写成空串不影响索引可用性（等值查询照走索引）。
        db.execSQL("UPDATE tasks SET taskType = '' WHERE taskType IN ('REVIEW', 'CUSTOM')")

        // 3. 生成规则表同理。这两种类型本来就永远不会被开启（isEnabled 恒为 false），
        //    真有残留也是导入进来的脏数据，删掉比留着解析不出更干净。
        db.execSQL("DELETE FROM task_generation_rules WHERE taskType IN ('REVIEW', 'CUSTOM')")
    }
}