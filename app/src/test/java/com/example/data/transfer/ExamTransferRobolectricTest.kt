package com.example.data.transfer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.di.AppContainer
import com.example.data.local.ExamEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 考试的导入 / 导出。Robolectric + 真实 Room。
 *
 * ## 为什么单独守这一层
 *
 * 考试**曾经完全不在**导出 / 导入管线里：导出的文件里有课程、校历、待办，
 * 唯独没有考试。用户拿到那份文件换机，考场与座位就全丢了 ——
 * 而「导出文件包含课程、校历、待办」这句话本身听着像是把该带的都带上了。
 *
 * 这一层要守住三件事：
 * - 导出的文件**带得上**考试；
 * - 导入把考试**带回来**；
 * - **文件里没有考试时，本机的考试一条都不动**
 *   （浙大同步产出的课表文档就是这种形状，而考试在那条路上是单独拉的 ——
 *   一次同步不该把刚拉到的考试抹掉）。
 */
@RunWith(RobolectricTestRunner::class)
// SDK 34 而不是工程的 36：Robolectric 的 36 号沙箱要求 JDK 21，
// 本机构建固定 JDK 17（AGP 也要求 17）。这里验的是数据搬运，与 API 36 无关。
@Config(sdk = [34])
class ExamTransferRobolectricTest {

    private fun container(): AppContainer =
        AppContainer(ApplicationProvider.getApplicationContext<Context>())

    @After
    fun tearDown() {
        runBlocking {
            val c = container()
            c.database.examDao().clearExams()
            c.database.taskDao().clearTasks()
            c.courseRepository.clearAllCourses()
        }
    }

    private suspend fun seedExam(
        c: AppContainer,
        xkkh: String = "(2026-2027-1)-MATH2432F-0083261-1",
        courseName: String = "大学物理",
        startAt: Long = 1_800_000_000_000L,
        classroom: String = "教十 201",
        seat: String = "12",
        updatedAt: Long = 1_700_000_000_000L
    ) {
        c.database.examDao().insertExams(
            listOf(
                ExamEntity(
                    xkkh = xkkh,
                    courseCode = "MATH2432F",
                    courseName = courseName,
                    kind = ExamEntity.KIND_FINAL,
                    startAt = startAt,
                    endAt = startAt + 2 * 60 * 60_000L,
                    startMinute = 13 * 60,
                    endMinute = 15 * 60,
                    dateLabel = "",
                    classroom = classroom,
                    seat = seat,
                    credit = 3.0,
                    termLabel = "2026-2027 秋冬",
                    updatedAt = updatedAt
                )
            )
        )
    }

    @Test
    fun `export_carries_the_exams`() = runBlocking {
        val c = container()
        c.database.examDao().clearExams()
        seedExam(c)

        val json = c.exportService.exportJson()

        assertTrue("导出必须带 exams 块：$json", json.contains("\"exams\""))
        assertTrue("必须带学号之外的选课课号：$json", json.contains("MATH2432F"))
        assertTrue("必须带课程名：$json", json.contains("大学物理"))
        assertTrue("必须带考场：$json", json.contains("教十 201"))
        assertTrue("必须带座位：$json", json.contains("12"))
    }

    @Test
    fun `import_brings_the_exams_back_to_an_empty_device`() = runBlocking {
        val source = container()
        source.database.examDao().clearExams()
        seedExam(source)
        val json = source.exportService.exportJson()

        // 目标机：什么都没有
        val target = container()
        target.database.examDao().clearExams()
        target.courseRepository.clearAllCourses()

        val preview = target.importService.preview(json, ImportStrategy.SNAPSHOT)
        assertTrue(preview is PreviewResult.Ready)
        val report = target.importService.commit(preview as PreviewResult.Ready)

        val exams = target.database.examDao().getAllExamsOnce()
        assertEquals("导入后应有一场考试", 1, exams.size)
        assertEquals("大学物理", exams[0].courseName)
        assertEquals("教十 201", exams[0].classroom)
        assertEquals("12", exams[0].seat)
        assertEquals(1, report.examMerge.added)
    }

    @Test
    fun `a_file_without_exams_leaves_the_local_ones_alone`() = runBlocking {
        val c = container()
        c.database.examDao().clearExams()
        seedExam(c)

        // 浙大同步产出的课表文档：没有 exams 块。
        val zjuDocument = """
            {
              "schemaVersion": 4,
              "timetable": { "courses": [] }
            }
        """.trimIndent()

        val preview = c.importService.preview(zjuDocument, ImportStrategy.REPLACE)
        assertTrue(preview is PreviewResult.Ready)
        val report = c.importService.commit(preview as PreviewResult.Ready)

        assertEquals(
            "包里没有考试就不该动本机的考试",
            1,
            c.database.examDao().getAllExamsOnce().size
        )
        assertEquals(0, report.examMerge.added)
        assertEquals(0, report.examMerge.overwritten)
    }

    @Test
    fun `the_newer_exam_wins_and_the_older_one_is_kept`() = runBlocking {
        val c = container()
        c.database.examDao().clearExams()
        // 本机这场是**较新**的（教务网补发了座位号）
        seedExam(c, seat = "12", updatedAt = 1_800_000_000_000L)

        val older = """
            {
              "schemaVersion": 4,
              "exams": [ {
                "xkkh": "(2026-2027-1)-MATH2432F-0083261-1",
                "courseCode": "MATH2432F",
                "courseName": "大学物理",
                "kind": "FINAL",
                "startAt": 1800000000000,
                "endAt": 1800007200000,
                "startMinute": 780,
                "endMinute": 900,
                "classroom": "教十 201",
                "seat": "9",
                "credit": 3.0,
                "termLabel": "2026-2027 秋冬",
                "updatedAt": 1700000000000
              } ]
            }
        """.trimIndent()

        val preview = c.importService.preview(older, ImportStrategy.SNAPSHOT)
        assertTrue(preview is PreviewResult.Ready)
        val report = c.importService.commit(preview as PreviewResult.Ready)

        val exams = c.database.examDao().getAllExamsOnce()
        assertEquals(1, exams.size)
        assertEquals("本机较新时保留本机的座位号", "12", exams[0].seat)
        assertEquals(1, report.examMerge.keptLocal)
        assertEquals(0, report.examMerge.overwritten)
    }

    @Test
    fun `a_file_with_an_exam_updates_the_local_one`() = runBlocking {
        val c = container()
        c.database.examDao().clearExams()
        seedExam(c, seat = "9", updatedAt = 1_700_000_000_000L)

        val newer = """
            {
              "schemaVersion": 4,
              "exams": [ {
                "xkkh": "(2026-2027-1)-MATH2432F-0083261-1",
                "courseCode": "MATH2432F",
                "courseName": "大学物理",
                "kind": "FINAL",
                "startAt": 1800000000000,
                "endAt": 1800007200000,
                "startMinute": 780,
                "endMinute": 900,
                "classroom": "教十 201",
                "seat": "12",
                "credit": 3.0,
                "termLabel": "2026-2027 秋冬",
                "updatedAt": 1800000000000
              } ]
            }
        """.trimIndent()

        val preview = c.importService.preview(newer, ImportStrategy.SNAPSHOT)
        assertTrue(preview is PreviewResult.Ready)
        val report = c.importService.commit(preview as PreviewResult.Ready)

        val exams = c.database.examDao().getAllExamsOnce()
        assertEquals(1, exams.size)
        assertEquals("12", exams[0].seat)
        assertEquals(1, report.examMerge.overwritten)
        assertNotNull(exams[0].id)
    }
}
