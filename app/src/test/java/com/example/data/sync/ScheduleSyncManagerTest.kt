package com.example.data.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.codec.CalendarBundle
import com.example.data.codec.CourseBundle
import com.example.data.codec.JsonScheduleCodec
import com.example.data.codec.ProvenanceBundle
import com.example.data.codec.ScheduleBundle
import com.example.data.codec.SlotBundle
import com.example.data.codec.TermConfigBundle
import com.example.data.codec.TermRefBundle
import com.example.data.codec.TimetableBlockBundle
import com.example.data.di.AppContainer
import com.example.data.local.CourseEntity
import com.example.data.local.CourseSlotEntity
import com.example.data.local.TaskEntity
import com.example.data.model.SlotWithWeeks
import com.example.data.security.SecureAccountStorage
import com.example.data.source.zju.ZjuException
import com.example.data.source.zju.ZjuFailureKind
import com.example.data.source.zju.ZjuExamMapper
import com.example.data.source.zju.ZjuImportService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScheduleSyncManagerTest {

    private lateinit var container: AppContainer
    private lateinit var storage: SecureAccountStorage

    @Before
    fun setup() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        container = AppContainer(context)
        storage = SecureAccountStorage(
            context,
            prefName = "test_sync_manager_prefs",
            masterKeyProvider = SecureAccountStorage.softwareKeyProvider()
        )
        storage.clearCredentials()
        container.courseRepository.clearAllCourses()
        // clearAllCourses 只清课表、不动待办（导入不再吞待办），待办得显式清。
        container.database.taskDao().clearTasks()
        container.settingsRepository.ensureDefaultRules(AppContainer.DEFAULT_CATEGORY_RULES)
        container.calendarRepository.ensureDefaultPeriods()
    }

    /** 建一门本地课程。身份与 [createSampleV2Json] 里那门对齐时，同步后就是「同一门课」。 */
    private suspend fun seedLocalCourse(name: String, code: String, teacher: String): Long =
        container.courseRepository.saveCourseWithSlots(
            CourseEntity(
                name = name, code = code, category = "专业必修", teacher = teacher,
                classroom = "紫金港西2-212", colorHex = "#93C5FD"
            ),
            listOf(
                SlotWithWeeks(
                    slot = CourseSlotEntity(courseId = 0, dayOfWeek = 1, startPeriod = 6, endPeriod = 7),
                    weeks = setOf(1, 2, 3, 4, 5, 6, 7, 8)
                )
            )
        )

    private suspend fun seedTask(courseId: Long, courseName: String, title: String) {
        val unlinked = courseId == TaskEntity.UNLINKED_COURSE_ID
        container.database.taskDao().insertTask(
            TaskEntity(
                courseId = courseId,
                courseName = if (unlinked) TaskEntity.UNLINKED_COURSE_NAME else courseName,
                courseCategory = if (unlinked) "" else "专业必修",
                courseColorHex = if (unlinked) TaskEntity.UNLINKED_COLOR_HEX else "#93C5FD",
                title = title, taskType = "POST_CLASS",
                dueDateTimestamp = 1L, dueDateString = "2026-10-12 23:59"
            )
        )
    }

    /**
     * 假的浙大导入源。
     *
     * **考试必须在这里被掐掉**：默认实现会真的去登录浙大取考试，
     * 而单测里既没有网也不该有网 —— 那会让「同步」这个测试去访问真实服务。
     * 考试与课表是两份独立的数据，所以这里只掐考试那一条，课表照旧走桩。
     */
    private open class FakeZjuService : ZjuImportService() {
        override suspend fun fetchExams(
            studentId: String,
            password: String
        ): List<ZjuExamMapper.ExamRow> = emptyList()
    }
    private fun zjuReturning(json: String) = object : FakeZjuService() {
        override suspend fun fetchScheduleJson(
            studentId: String,
            password: String,
            option: ZjuImportService.TermOption
        ): String = json
    }

    private suspend fun syncWith(json: String) = ScheduleSyncManager(
        zjuImportService = zjuReturning(json),
        importService = container.importService,
        courseRepo = container.courseRepository,
        calendarRepo = container.calendarRepository,
        secureStorage = storage,
        examRepo = container.examRepository
    ).sync("3190101234", "pwd", ZjuImportService.TermOption("2026-2027", 0), rememberCredentials = false)

    private fun createSampleV2Json(): String {
        val course = CourseBundle(
            name = "智能物联网(AIoT)系统设计",
            code = "(2026-2027-1)-ISEE2020M",
            category = "专业必修",
            teacher = "史治国",
            classroom = "紫金港西2-212",
            colorHex = "#93C5FD",
            slots = listOf(
                SlotBundle(
                    dayOfWeek = 1,
                    startPeriod = 6,
                    endPeriod = 7,
                    classroom = "紫金港西2-212",
                    weeks = listOf(1, 2, 3, 4, 5, 6, 7, 8)
                )
            )
        )
        val bundle = ScheduleBundle(
            provenance = ProvenanceBundle(
                source = "zju",
                generatedAt = "2026-10-13T18:00:00Z"
            ),
            timetable = TimetableBlockBundle(
                termRef = TermRefBundle(year = "2026-2027", term = 0),
                courses = listOf(course)
            ),
            calendar = CalendarBundle(
                termConfigs = listOf(
                    TermConfigBundle(year = "2026-2027", term = 0, begin = 20260907, end = 20261108, firstWeekNo = 1)
                ),
                periods = emptyList()
            )
        )
        return JsonScheduleCodec().encode(bundle)
    }

    @Test
    fun `sync replaces the local timetable and persists real courses to local cache`() = runBlocking {
        // 1. 本地先留一门课：同步走的是 REPLACE，它必须被整表顶掉
        seedLocalCourse("过期课程", "OLD100", "王教授")
        assertEquals(1, container.courseRepository.getAllCoursesOnce().size)

        // 2. 构造支持桩数据的 ZjuImportService
        val sampleJson = createSampleV2Json()
        val fakeZjuService = object : FakeZjuService() {
            override suspend fun fetchScheduleJson(
                studentId: String,
                password: String,
                option: TermOption
            ): String = sampleJson
        }

        val syncManager = ScheduleSyncManager(
            zjuImportService = fakeZjuService,
            importService = container.importService,
            courseRepo = container.courseRepository,
            calendarRepo = container.calendarRepository,
            secureStorage = storage,
            examRepo = container.examRepository
        )

        // 3. 执行云端同步
        val result = syncManager.sync(
            studentId = "3190101234",
            password = "test_password_123",
            option = ZjuImportService.TermOption("2026-2027", 0),
            rememberCredentials = true
        )

        assertTrue("同步应该成功：$result", result is SyncResult.Success)
        val success = result as SyncResult.Success
        assertEquals(1, success.courseCount)

        // 4. 验证本地 Room 缓存里只剩云端那份课表
        val cachedCourses = container.courseRepository.getAllCoursesOnce()
        assertEquals(1, cachedCourses.size)
        assertEquals("智能物联网(AIoT)系统设计", cachedCourses.first().name)
        assertEquals("(2026-2027-1)-ISEE2020M", cachedCourses.first().code)

        // 5. 验证安全凭据已加密保存
        assertTrue(storage.hasSavedCredentials())
        assertEquals("3190101234", storage.getStudentId())
        assertEquals("test_password_123", storage.getPassword())
        assertNotNull(storage.getLastSyncTimestamp())
    }

    /**
     * 同步只换课表，不替用户安排这一周。
     *
     * 前置条件是「本来会生成」：本地那门课的课程级开关默认交作业，
     * 同步下来的学期（2026-09-07 起 8 周）里它第 6 周确实有课，
     * 所以只要还有那条自动生成逻辑，这里就一定至少多出一条待办。
     */
    @Test
    fun `sync does not generate this week's tasks on its own`() = runBlocking {
        seedLocalCourse("智能物联网(AIoT)系统设计", "(2026-2027-1)-ISEE2020M", "史治国")

        assertTrue(syncWith(createSampleV2Json()) is SyncResult.Success)

        assertEquals(
            "同步只该换课表；本周待办要用户自己点「生成本周待办」",
            0,
            container.database.taskDao().countTasks()
        )
    }

    /** 同步后的待办归属：课还在的留下，课没了的进「未关联课程」，本来就未关联的原样不动。 */
    @Test
    fun `sync keeps local tasks and only re-homes the ones whose course disappeared`() = runBlocking {
        val kept = seedLocalCourse("智能物联网(AIoT)系统设计", "(2026-2027-1)-ISEE2020M", "史治国")
        val gone = seedLocalCourse("已退的课", "GONE1001", "张教授")
        seedTask(kept, "智能物联网(AIoT)系统设计", "课还在的待办")
        seedTask(gone, "已退的课", "课没了的待办")
        seedTask(TaskEntity.UNLINKED_COURSE_ID, "", "本来就未关联的待办")

        val result = syncWith(createSampleV2Json())
        assertTrue("$result", result is SyncResult.Success)

        val tasks = container.database.taskDao().getAllTasksOnce()
        assertEquals("同步不该吞掉任何一条待办", 3, tasks.size)

        val newCourseId = container.courseRepository.getAllCoursesOnce()
            .single { it.name == "智能物联网(AIoT)系统设计" }.id
        assertNotEquals("课程换了新 id", kept, newCourseId)

        val byTitle = tasks.associateBy { it.title }
        assertEquals(
            "同一门课应当改挂到新 id 上，而不是掉进未关联",
            newCourseId,
            byTitle.getValue("课还在的待办").courseId
        )
        assertTrue(TaskEntity.isUnlinked(byTitle.getValue("课没了的待办")))
        assertEquals(
            "移进未关联课程时课程名字段要跟着归位，否则卡片上还挂着旧课名",
            TaskEntity.UNLINKED_COURSE_NAME,
            byTitle.getValue("课没了的待办").courseName
        )
        val untouched = byTitle.getValue("本来就未关联的待办")
        assertTrue(TaskEntity.isUnlinked(untouched))
        assertEquals(TaskEntity.UNLINKED_COURSE_ID, untouched.courseId)
    }

    @Test
    fun `network error when offline keeps existing local cache completely intact`() = runBlocking {
        // 1. 本地已有 1 门缓存课程
        val sampleJson = createSampleV2Json()
        container.importService.commit(container.importService.preview(sampleJson, com.example.data.transfer.ImportStrategy.REPLACE) as com.example.data.transfer.PreviewResult.Ready)
        val coursesBefore = container.courseRepository.getAllCoursesOnce()
        assertEquals(1, coursesBefore.size)

        // 2. 模拟断网抛出 IOException
        val offlineZjuService = object : FakeZjuService() {
            override suspend fun fetchScheduleJson(
                studentId: String,
                password: String,
                option: TermOption
            ): String = throw IOException("Failed to connect to 10.0.2.2:8888")
        }

        val syncManager = ScheduleSyncManager(
            zjuImportService = offlineZjuService,
            importService = container.importService,
            courseRepo = container.courseRepository,
            calendarRepo = container.calendarRepository,
            secureStorage = storage,
            examRepo = container.examRepository
        )

        val result = syncManager.sync(
            studentId = "3190101234",
            password = "test_password_123",
            option = ZjuImportService.TermOption("2026-2027", 0),
            rememberCredentials = true
        )

        assertTrue("断网必须归到 NETWORK：$result", result is SyncResult.Failed)
        val failure = (result as SyncResult.Failed).failure
        assertEquals(ZjuFailureKind.NETWORK, failure.kind)
        assertTrue("本地课表应当原样保留", failure.localDataIntact)

        // 3. 断网时本地缓存完全不受影响！
        val coursesAfter = container.courseRepository.getAllCoursesOnce()
        assertEquals(1, coursesAfter.size)
        assertEquals(coursesBefore.first().code, coursesAfter.first().code)

        // 4. 失败要被记下来，且**不能**动「上次成功同步」——那是另一件事
        assertEquals(ZjuFailureKind.NETWORK, storage.getLastSyncFailureKind())
        assertNotNull("应当记录尝试时间", storage.getLastSyncAttemptTime())
        assertNull("从未成功同步过，成功时间应当仍为空", storage.getLastSyncTimestamp())
    }

    @Test
    fun `auth error keeps existing local cache completely intact`() = runBlocking {
        val sampleJson = createSampleV2Json()
        container.importService.commit(container.importService.preview(sampleJson, com.example.data.transfer.ImportStrategy.REPLACE) as com.example.data.transfer.PreviewResult.Ready)

        val authFailZjuService = object : FakeZjuService() {
            override suspend fun fetchScheduleJson(
                studentId: String,
                password: String,
                option: TermOption
            ): String = throw ZjuException("学号或密码错误", ZjuFailureKind.CREDENTIAL)
        }

        val syncManager = ScheduleSyncManager(
            zjuImportService = authFailZjuService,
            importService = container.importService,
            courseRepo = container.courseRepository,
            calendarRepo = container.calendarRepository,
            secureStorage = storage,
            examRepo = container.examRepository
        )

        val result = syncManager.sync(
            studentId = "3190101234",
            password = "wrong_password",
            option = ZjuImportService.TermOption("2026-2027", 0),
            rememberCredentials = true
        )

        assertTrue(result is SyncResult.Failed)
        val failure = (result as SyncResult.Failed).failure
        assertEquals(ZjuFailureKind.CREDENTIAL, failure.kind)
        assertTrue("凭据类必须引导去改密码", failure.kind.needsCredentials)
        assertTrue(failure.localDataIntact)
        assertEquals(1, container.courseRepository.getAllCoursesOnce().size)
    }

    /**
     * 分类**不能**由文案推断出来。
     *
     * 早先的实现是 `msg.contains("学号或密码错误")` 这样判的，于是
     * 「改一句提示文案」就等于「静默改掉失败分类」。这条用例把两件事钉死：
     * 抛出的类别被原样保留，且文案换成别的说法也不影响分类。
     */
    @Test
    fun `failure kind comes from the throw site not from the message text`() = runBlocking {
        val service = object : FakeZjuService() {
            override suspend fun fetchScheduleJson(
                studentId: String,
                password: String,
                option: TermOption
            ): String = throw ZjuException("文案里完全没有「密码」两个字", ZjuFailureKind.LOCKED)
        }

        val result = ScheduleSyncManager(
            zjuImportService = service,
            importService = container.importService,
            courseRepo = container.courseRepository,
            calendarRepo = container.calendarRepository,
            secureStorage = storage,
            examRepo = container.examRepository
        ).sync("3190101234", "pwd", ZjuImportService.TermOption("2026-2027", 0))

        val failure = (result as SyncResult.Failed).failure
        assertEquals(ZjuFailureKind.LOCKED, failure.kind)
        assertEquals("文案里完全没有「密码」两个字", failure.reason)
    }

    /**
     * commit 之后才炸的异常，**不能**再说「本地原样保留」。
     *
     * 那一步已经把新课表写进 Room 了，报「本地没事」会让用户以为完全没变，
     * 于是把已经生效的课表当成旧数据删掉重导。
     */
    @Test
    fun `failure after commit reports local data as changed`() = runBlocking {
        val sampleJson = createSampleV2Json()
        val okService = object : FakeZjuService() {
            override suspend fun fetchScheduleJson(
                studentId: String,
                password: String,
                option: TermOption
            ): String = sampleJson
        }

        // 用一个「保存凭据就炸」的存储替身，把异常挤到 commit 之后。
        val explodingStorage = object : SecureAccountStorage(
            ApplicationProvider.getApplicationContext(),
            "test_post_commit_prefs",
            SecureAccountStorage.softwareKeyProvider()
        ) {
            override fun saveCredentials(studentId: String, password: String, remember: Boolean) {
                throw IllegalStateException("KeyStore 不可用")
            }
        }
        explodingStorage.clearCredentials()

        val result = ScheduleSyncManager(
            zjuImportService = okService,
            importService = container.importService,
            courseRepo = container.courseRepository,
            calendarRepo = container.calendarRepository,
            secureStorage = explodingStorage,
            examRepo = container.examRepository
        ).sync("3190101234", "pwd", ZjuImportService.TermOption("2026-2027", 0), rememberCredentials = true)

        val failure = (result as SyncResult.Failed).failure
        assertFalse("commit 已经发生，不能报本地原样保留", failure.localDataIntact)
        assertEquals(1, container.courseRepository.getAllCoursesOnce().size)
    }

    /** 成功过一次之后，旧的失败类别必须消失 —— 否则弹层会一直挂着过期信息。 */
    @Test
    fun `a successful sync clears the previous failure kind`() = runBlocking {
        storage.markSyncFailure(ZjuFailureKind.NETWORK)
        assertEquals(ZjuFailureKind.NETWORK, storage.getLastSyncFailureKind())

        storage.markSyncSuccess(1_700_000_000_000L)

        assertNull("成功之后不该还留着失败类别", storage.getLastSyncFailureKind())
        assertEquals(1_700_000_000_000L, storage.getLastSyncTimestamp())
        assertEquals(1_700_000_000_000L, storage.getLastSyncAttemptTime())
    }

    /**
     * 「先失败、再成功」之后，存储里必须干干净净。
     *
     * 用户实际报的现象是：同步成功了，重新打开弹层却还红着
     * 「最近一次尝试失败：浙大教务系统异常」。那次成功的记录本身是对的
     * （成功时间确实更新了），红字是另一条数据没被清掉 ——
     * 所以这条用例走完整的两次 sync，而不是直接调 markSuccess，
     * 免得又测成「只要调了那个方法就没事」，实际却漏在调用方身上。
     */
    @Test
    fun `failure followed by success leaves no stale failure behind`() = runBlocking {
        val sampleJson = createSampleV2Json()

        val failing = ScheduleSyncManager(
            zjuImportService = object : FakeZjuService() {
                override suspend fun fetchScheduleJson(
                    studentId: String,
                    password: String,
                    option: TermOption
                ): String = throw ZjuException("浙大登录服务出错（HTTP 500）", ZjuFailureKind.SERVER)
            },
            importService = container.importService,
            courseRepo = container.courseRepository,
            calendarRepo = container.calendarRepository,
            secureStorage = storage,
            examRepo = container.examRepository
        )
        failing.sync("3190101234", "pwd", ZjuImportService.TermOption("2026-2027", 0))
        assertEquals(ZjuFailureKind.SERVER, storage.getLastSyncFailureKind())

        val succeeding = ScheduleSyncManager(
            zjuImportService = object : FakeZjuService() {
                override suspend fun fetchScheduleJson(
                    studentId: String,
                    password: String,
                    option: TermOption
                ): String = sampleJson
            },
            importService = container.importService,
            courseRepo = container.courseRepository,
            calendarRepo = container.calendarRepository,
            secureStorage = storage,
            examRepo = container.examRepository
        )
        assertTrue(succeeding.sync("3190101234", "pwd", ZjuImportService.TermOption("2026-2027", 0))
            is SyncResult.Success)

        assertNull("成功之后不该还挂着上一次失败", storage.getLastSyncFailureKind())
        assertNotNull("成功时间应当已更新", storage.getLastSyncTimestamp())
    }

    @Test
    fun `syncWithSavedCredentials succeeds when credentials exist`() = runBlocking {
        val sampleJson = createSampleV2Json()
        val fakeZjuService = object : FakeZjuService() {
            override suspend fun fetchScheduleJson(
                studentId: String,
                password: String,
                option: TermOption
            ): String = sampleJson
        }

        val syncManager = ScheduleSyncManager(
            zjuImportService = fakeZjuService,
            importService = container.importService,
            courseRepo = container.courseRepository,
            calendarRepo = container.calendarRepository,
            secureStorage = storage,
            examRepo = container.examRepository
        )

        // 尚未保存凭据时报错
        val failResult = syncManager.syncWithSavedCredentials()
        assertTrue(failResult is SyncResult.Failed)
        assertEquals(
            ZjuFailureKind.CREDENTIAL,
            (failResult as SyncResult.Failed).failure.kind
        )

        // 保存凭据并指定学期
        storage.saveCredentials("3190101234", "saved_secret", remember = true)
        storage.saveLastTerm("2026-2027", 0)

        val okResult = syncManager.syncWithSavedCredentials()
        assertTrue(okResult is SyncResult.Success)
    }
}
