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

/**
 * v6 → v7。
 *
 * 浙大作息实际是 13 节，此前内置的 `PeriodTable.ZJU_PERIODS` 却有 15 节 ——
 * 多出的第 14、15 节（21:20–22:05 / 22:10–22:55）是当初照抄第三方库 zju-ical
 * 的 Go 常量带进来的，浙大并没有这两节。
 *
 * 只改常量是不够的：全新安装靠 `ensureDefaultPeriods` 播种，能拿到新值；
 * 但**已经装过、且库里的作息表还是那份 15 行种子的用户，升级后依然会画到 15 行**，
 * 底部两行永远是空的。所以这次迁移把已经落库的那两行删掉。
 *
 * **只删与当初种子逐值相同的行**：作息表是可以自定义的（设置页能填到
 * `MAX_PERIODS_PER_DAY` = 20 节），一刀 `period > 13` 会连带删掉用户自己配的
 * 14/15 节 —— 那是替用户做决定，而且删掉的是他真的在上的课。判据取
 * (period, startMinute, endMinute, section) 四项全等：真有人手动把第 14 节
 * 设成 21:20–22:05 的概率可以忽略，而这正是我们自己写进去的那一行。
 * 用户自己配的 14/15 节留着不动，浙大真要上到那么晚，本来就是他的选择。
 *
 * `course_slots` 没有指向 `timetable_periods` 的外键（它只 FK 到 `courses`），
 * 所以删行不会级联删课程时段。万一有残留的 14/15 节引用，栅格会用
 * `coerceIn(1, periodsPerDay)` 夹回范围内显示（见 ScheduleScreen）；
 * 浙大教务网本身也不会返回这两节，导入路径造不出这种数据。
 */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            DELETE FROM `timetable_periods`
            WHERE (`period` = 14 AND `startMinute` = 1280 AND `endMinute` = 1325 AND `section` = '晚上')
               OR (`period` = 15 AND `startMinute` = 1330 AND `endMinute` = 1375 AND `section` = '晚上')
            """.trimIndent()
        )
    }
}

/**
 * v7 → v8。
 *
 * 内置类别规则的三个开关改了取值（用户 2026-10-14 定，见 `AppContainer.DEFAULT_CATEGORY_RULES`）：
 * 公共基础 / 专业必修 / 专业选修 从「预习 + 作业」变成「只作业」，通识公共三个全关，
 * 实验实践本来就是「预习 + 实验报告」，不变。
 *
 * 只改常量不够，和 v6 → v7 同一个道理：`ensureDefaultRules` 只在 `category_rules`
 * 空表时才播种，已经装过的用户库里有当初那份种子行，升级后设置页显示的仍是旧值。
 *
 * **必须连课程一起写穿**：三个开关的真相源在 `courses.auto*`（`GenerationRuleResolver.isEnabled`
 * 只读它）。只改类别规则行，类别说「关」而课还是「开」，联动方向直接反了 —— 这就是
 * 差量写法特有的漂移，而规则表本来就是批量设置、对下面所有课程有权威。
 *
 * **判据是「值仍逐位等于旧种子」**：用户在设置页把某一门课的开关单独改过，
 * 那门课不该被这次升级顺手按回去（他调过的值与旧种子不同，天然被排除）；
 * 类别规则那一侧同理，只改写仍等于旧种子三件套的行。两个方向各留一个对照组用例。
 *
 * 已有联动的用户不受影响：他的规则行早就被改成自己的值，不等于旧种子，
 * 迁移对它就是空操作。
 */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        for (rule in LEGACY_CATEGORY_SWITCHES) {
            db.execSQL(
                """
                UPDATE `category_rules`
                SET `preStudy` = ?, `postHomework` = ?, `labReport` = ?
                WHERE `category` = ?
                  AND `preStudy` = ? AND `postHomework` = ? AND `labReport` = ?
                """.trimIndent(),
                arrayOf(
                    rule.newPre, rule.newPost, rule.newLab,
                    rule.category, rule.oldPre, rule.oldPost, rule.oldLab
                )
            )
            db.execSQL(
                """
                UPDATE `courses`
                SET `autoPreStudy` = ?, `autoPostHomework` = ?, `autoLabReport` = ?
                WHERE `category` = ?
                  AND `autoPreStudy` = ? AND `autoPostHomework` = ? AND `autoLabReport` = ?
                """.trimIndent(),
                arrayOf(
                    rule.newPre, rule.newPost, rule.newLab,
                    rule.category, rule.oldPre, rule.oldPost, rule.oldLab
                )
            )
        }
    }
}

/**
 * v7 里那 5 条类别规则里**取值发生变化**的那些，以及它们各自的新旧三件套。
 *
 * 「实验实践」不在表里：它的 (预习开, 作业关, 实验开) 新旧一致，写不写都一样的 SQL，
 * 列进来只是让读代码的人多找一行。整数而不是 Boolean：SQLite 没有布尔类型，
 * Room 的 Boolean 列就是 0/1，绑 Boolean 会依赖驱动是否替你转换，不值得赌。
 */
private val LEGACY_CATEGORY_SWITCHES = listOf(
    LegacySwitches("公共基础", oldPre = 1, oldPost = 1, oldLab = 0, newPre = 0, newPost = 1, newLab = 0),
    LegacySwitches("专业必修", oldPre = 1, oldPost = 1, oldLab = 0, newPre = 0, newPost = 1, newLab = 0),
    LegacySwitches("专业选修", oldPre = 1, oldPost = 1, oldLab = 0, newPre = 0, newPost = 1, newLab = 0),
    LegacySwitches("通识公共", oldPre = 1, oldPost = 1, oldLab = 0, newPre = 0, newPost = 0, newLab = 0)
)

private data class LegacySwitches(
    val category: String,
    val oldPre: Int,
    val oldPost: Int,
    val oldLab: Int,
    val newPre: Int,
    val newPost: Int,
    val newLab: Int
)

/**
 * v8 → v9。
 *
 * 给 `makeup_days` 补一列 `sourceEpochDay`：补课的**来源日**（那份课原本属于哪一天）。
 *
 * 之前只存了 `makeupForDayOfWeek`（补星期几的课）。那是**有损**的：调休的两端可以
 * 隔两周以上，2026 国庆就是把 10-02（周五，第 3 周）的课补到 10-17（周六，第 5 周），
 * 只记周几就丢掉了「这是哪一周的课」，排课时会拿第 5 周去筛，结果要么凭空多出一次课、
 * 要么一次都不出，而界面上看不出哪里不对。
 *
 * **已有行统一补 0**，表示「来源日未知」——这是刻意的：0 的含义是退回按补课当天算周次，
 * 与升级前逐字节一致。反推来源日（拿 `makeupForDayOfWeek` 去同周里找一个同周几的日子）
 * 看似能填上，但补上去的是**一个猜测值**，会把「本来就不知道」伪装成「知道」，
 * 于是周次可能悄悄判错。宁可明确地退回旧行为。
 */
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE makeup_days ADD COLUMN sourceEpochDay INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * v9 → v10。
 *
 * 给 `tasks` 补两列，做**跨设备同步**用：`syncUid` 与 `updatedAt`。
 *
 * 补了之后同一条待办在两台设备上才认得出是同一条（判据见 `TaskEntity.syncUid`
 * 与 `updatedAt` 的注释：本地自增 id 和本地 courseId 跨设备都不成立）。
 *
 * **已有行统一填空串 / 0，uid 留到应用启动时惰性补齐**，理由两条：
 * - SQL 里生成不出 UUID，只能拼一个「看起来像」的字符串，那不是稳定身份；
 * - 更不能按内容哈希：标题与正文用户随时会改，一改哈希就变，
 *   两台设备对同一条待办算出的 uid 就不一样了，于是每次同步都在制造重复。
 *
 * `updatedAt` 同理不能在这里填：`0` 会让它在任何一次后写覆盖的比较里都判负，
 * 等于「历史待办永远被别人导出的文件覆盖」。补齐时取该行已有的 `createdAt`。
 *
 * 代价是升级后有一段「本机待办没有 uid」的窗口期。导入侧与导出侧都对空 uid
 * 做了防御（跳过 + 记 issue，绝不现场生成），所以这段窗口期不会静默出错。
 */
val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE tasks ADD COLUMN syncUid TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE tasks ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * v10 → v11。
 *
 * 给 `courses` 补一列 `note`：课程备注（用户自己写的自由文本）。
 *
 * **纯新增，已有行统一补空串**：升级前根本没有「备注」这个概念，
 * 不该凭空给老课程编一条出来。空串就是「没写」。
 *
 * SQLite 要求给已有行的表加 NOT NULL 列时必须带默认值，实体侧也要写
 * `@ColumnInfo(defaultValue = "")`，否则 Room 校验老库时会判为 schema 不符
 * （与 `MIGRATION_9_10` 的 `syncUid` 同一条理由）。
 */
val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE courses ADD COLUMN note TEXT NOT NULL DEFAULT ''")
    }
}

/**
 * v11 → v12。
 *
 * 新增一张 `exams` 表：**考试数据独立于课程数据**。
 *
 * ## 为什么是**新表**而不是给 `courses` 加列
 *
 * 考试来自另一个接口（教务网「个人考试查询」），粒度也不同：
 * 一门课一个学期可能只有一场期末考，期中考干脆没有；
 * 而考试里的教室与座位是**考试当天**才定的，和这门课平常上课的教室没有必然关系。
 *
 * 塞进 `courses` / `course_slots` 会带来两个坏处：
 * 删课会连带删掉考试（考试是学校已经公布的事实，不该随用户的课程增删而消失），
 * 而导入课表时又必然要把考试整个重建一遍（课表是 REPLACE 语义）。
 *
 * ## 关联靠**冗余字段**而不是外键
 *
 * `courseCode` / `courseName` 是冗余存的一份，用来在读时把考试挂回课程。
 * 没有外键，于是删课程不会碰到这张表 —— 而这正是要的行为。
 *
 * ## `credit` 是 REAL
 *
 * 学分带小数（1.5 / 2.0），整数列存不下。
 */
val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `exams` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `xkkh` TEXT NOT NULL,
                `courseCode` TEXT NOT NULL,
                `courseName` TEXT NOT NULL,
                `kind` TEXT NOT NULL,
                `startAt` INTEGER NOT NULL,
                `endAt` INTEGER NOT NULL,
                `startMinute` INTEGER NOT NULL,
                `endMinute` INTEGER NOT NULL,
                `dateLabel` TEXT NOT NULL,
                `classroom` TEXT NOT NULL,
                `seat` TEXT NOT NULL,
                `credit` REAL NOT NULL,
                `termLabel` TEXT NOT NULL,
                `updatedAt` INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_exams_courseCode` ON `exams` (`courseCode`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_exams_startAt` ON `exams` (`startAt`)"
        )
    }
}
