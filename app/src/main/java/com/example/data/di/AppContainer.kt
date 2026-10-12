package com.example.data.di

import android.content.Context
import com.example.data.codec.JsonScheduleCodec
import com.example.data.codec.ScheduleCodec
import com.example.data.local.AppDatabase
import com.example.data.model.CategoryRule
import com.example.data.repository.AcademicCalendarRepository
import com.example.data.repository.CourseRepository
import com.example.data.repository.ScheduleSettingsRepository
import com.example.data.repository.TaskRepository
import com.example.data.repository.TaskTypeRepository
import com.example.data.source.zju.ZjuImportService
import com.example.data.transfer.ScheduleExportService
import com.example.data.transfer.ScheduleImportService
import com.example.data.validate.ScheduleBundleValidator

/**
 * 手写的依赖容器。
 *
 * 改造前 `MainActivity` 里直接 `AppDatabase.getDatabase → 3 个 DAO → Repository`
 * 现场拼，装配逻辑跟着 UI 走、测试里要重复抄一遍。现在集中在这里：
 * 加一个后端能力只改这一个文件，界面与测试都从同一处取依赖。
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val database: AppDatabase by lazy { AppDatabase.getDatabase(appContext) }

    val courseRepository: CourseRepository by lazy {
        CourseRepository(
            db = database,
            courseDao = database.courseDao(),
            slotDao = database.courseSlotDao(),
            weekDao = database.courseSlotWeekDao(),
            taskDao = database.taskDao()
        )
    }

    val taskRepository: TaskRepository by lazy { TaskRepository(database.taskDao()) }

    val taskTypeRepository: TaskTypeRepository by lazy {
        TaskTypeRepository(database, database.customTaskTypeDao())
    }

    val settingsRepository: ScheduleSettingsRepository by lazy {
        ScheduleSettingsRepository(
            database,
            database.categoryRuleDao(),
            database.taskGenerationRuleDao()
        )
    }

    val calendarRepository: AcademicCalendarRepository by lazy {
        AcademicCalendarRepository(database, database.calendarDao())
    }

    val codec: ScheduleCodec by lazy { JsonScheduleCodec() }

    val validator: ScheduleBundleValidator by lazy { ScheduleBundleValidator() }

    val importService: ScheduleImportService by lazy {
        ScheduleImportService(
            db = database,
            codec = codec,
            validator = validator,
            courseRepo = courseRepository,
            calendarRepo = calendarRepository,
            settingsRepo = settingsRepository
        )
    }

    val exportService: ScheduleExportService by lazy {
        ScheduleExportService(codec, courseRepository, calendarRepository, settingsRepository)
    }

    /**
     * 「ZJU」导入源。纯网络客户端，不依赖 Room，所以它自己不持有数据库 ——
     * 产出的 JSON 走 `importService` 那条与文件导入相同的管线落库。
     */
    val zjuImportService: ZjuImportService by lazy { ZjuImportService() }

    companion object {
        /**
         * 内置类别规则。这 5 条现在退居为「默认值层」：课程 × 类型的规则优先，
         * 这里只在课程级没配时兜底（见 `GenerationRuleResolver`）。
         */
        val DEFAULT_CATEGORY_RULES: List<CategoryRule> = listOf(
            CategoryRule("公共基础", true, true, false, 6, 45),
            CategoryRule("专业必修", true, true, false, 6, 60),
            CategoryRule("实验实践", true, false, true, 6, 90),
            CategoryRule("通识公共", true, true, false, 6, 30),
            CategoryRule("专业选修", true, true, false, 6, 45)
        )
    }
}