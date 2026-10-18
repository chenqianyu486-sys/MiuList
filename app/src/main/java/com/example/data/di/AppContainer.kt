package com.example.data.di

import android.content.Context
import com.example.data.calendar.CalendarCache
import com.example.data.codec.JsonScheduleCodec
import com.example.data.codec.ScheduleCodec
import com.example.data.local.AppDatabase
import com.example.data.model.CategoryRule
import com.example.data.model.CourseCategory
import com.example.data.repository.AcademicCalendarRepository
import com.example.data.repository.CourseRepository
import com.example.data.repository.ScheduleSettingsRepository
import com.example.data.repository.TaskRepository
import com.example.data.repository.TaskTypeRepository
import com.example.data.repository.WallpaperRepository
import com.example.data.source.zju.CalendarUpdateService
import com.example.data.source.zju.CoursesTodoCache
import com.example.data.source.zju.ZjuCalendarSource
import com.example.data.source.zju.ZjuCoursesClient
import com.example.data.source.zju.ZjuImportService
import com.example.data.source.weather.SeniverseWeatherSource
import com.example.data.source.weather.WeatherCache
import com.example.data.source.weather.WeatherService
import com.example.data.security.SecureAccountStorage
import com.example.data.sync.ScheduleSyncManager
import com.example.data.transfer.ScheduleExportService
import com.example.data.transfer.ScheduleImportService
import com.example.data.validate.ScheduleBundleValidator
import java.io.File

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
            taskDao = database.taskDao(),
            categoryRuleDao = database.categoryRuleDao()
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
            database.taskGenerationRuleDao(),
            database.courseDao()
        )
    }

    val calendarRepository: AcademicCalendarRepository by lazy {
        AcademicCalendarRepository(database, database.calendarDao())
    }

    val wallpaperRepository: WallpaperRepository by lazy {
        WallpaperRepository(appContext)
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
     *
     * Context 在这里有两个用途：让 [ZjuCalendarSource] 读内置的校历兜底 assets，
     * 以及给 [CalendarCache] 一个 `filesDir` 落缓存。
     */
    val zjuCalendarSource: ZjuCalendarSource by lazy {
        ZjuCalendarSource(assets = appContext.assets)
    }

    /**
     * 校历的三层来源（远程 → 缓存 → 内置）。
     *
     * 弹层的预置块与课表导入**共用这一个实例** —— 它们必须看到同一份数据。
     * 改造前是两条独立链路（弹层只读内置、导入才联网），于是界面上显示的
     * 官方校历与真正落库的那份可以不是同一份，而两边都「看起来对」。
     */
    val calendarUpdateService: CalendarUpdateService by lazy {
        CalendarUpdateService(
            source = zjuCalendarSource,
            cache = CalendarCache(File(appContext.filesDir, CalendarCache.FILE_NAME))
        )
    }

    val zjuImportService: ZjuImportService by lazy {
        ZjuImportService(
            calendarSource = zjuCalendarSource,
            calendarUpdates = calendarUpdateService
        )
    }

    val zjuCoursesClient: ZjuCoursesClient by lazy {
        ZjuCoursesClient()
    }

    val coursesTodoCache: CoursesTodoCache by lazy {
        CoursesTodoCache(appContext)
    }

    /**
     * 总览页当天的天气。
     *
     * **这里没有任何密钥**，是刻意的：这一块曾经接心知天气，需要把私钥编译进
     * dex 才能在客户端算签名 —— 于是它以明文常量躺在安装包里（实测能直接搜到）。
     * 换成 Open-Meteo 之后这条注入链路整个删掉了，不需要 `isConfigured` 闸门，
     * 也不再有「忘了配密钥就什么都不显示」的那一档状态。
     */
    val weatherService: WeatherService by lazy {
        WeatherService(
            source = SeniverseWeatherSource(),
            cache = WeatherCache(File(appContext.filesDir, WeatherCache.FILE_NAME))
        )
    }

    val secureAccountStorage: SecureAccountStorage by lazy {
        SecureAccountStorage(appContext)
    }


    val syncManager: ScheduleSyncManager by lazy {
        ScheduleSyncManager(
            zjuImportService = zjuImportService,
            importService = importService,
            courseRepo = courseRepository,
            calendarRepo = calendarRepository,
            secureStorage = secureAccountStorage
        )
    }

    companion object {
        /**
         * 内置类别规则。这 5 条现在退居为「默认值层」：课程 × 类型的规则优先，
         * 这里只在课程级没配时兜底（见 `GenerationRuleResolver`）。
         *
         * 三个开关按「这类课实际要交什么」给，而不是一律预习 + 作业（用户 2026-10-14 定）：
         * - 专业必修 / 专业选修 / 公共基础：只交课后作业 → 只开作业
         * - 实验实践：交实验报告，且动手课需要看讲义 → 开预习 + 实验报告，不开作业
         * - 通识公共：三个都不默认开（课外阅读型，成绩不按周作业算）
         *
         * 改动不只落在这份常量：已装机的库里 `category_rules` 与课程上的 `auto*`
         * 都已经按旧默认值写过，由 `MIGRATION_7_8` 按「值仍等于旧种子」精确改写。
         */
        val DEFAULT_CATEGORY_RULES: List<CategoryRule> = listOf(
            CategoryRule(
                "公共基础",
                autoPreStudy = false, autoPostHomework = true, autoLabReport = false,
                defaultPostDays = 6, defaultMinutes = 45
            ),
            CategoryRule(
                "专业必修",
                autoPreStudy = false, autoPostHomework = true, autoLabReport = false,
                defaultPostDays = 6, defaultMinutes = 60
            ),
            CategoryRule(
                CourseCategory.LAB,
                autoPreStudy = true, autoPostHomework = false, autoLabReport = true,
                defaultPostDays = 6, defaultMinutes = 90
            ),
            CategoryRule(
                "通识公共",
                autoPreStudy = false, autoPostHomework = false, autoLabReport = false,
                defaultPostDays = 6, defaultMinutes = 30
            ),
            CategoryRule(
                "专业选修",
                autoPreStudy = false, autoPostHomework = true, autoLabReport = false,
                defaultPostDays = 6, defaultMinutes = 45
            )
        )
    }
}