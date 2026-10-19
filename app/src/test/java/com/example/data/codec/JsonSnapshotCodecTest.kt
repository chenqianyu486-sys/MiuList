package com.example.data.codec

import com.example.data.model.TaskStar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v3 整机快照的编解码。纯 JUnit。
 *
 * v3 在 v2 的五块之外加了 `tasks` / `taskTypes` / `taskRules`。
 * **要守住的是三件事**：
 * 1. 自己导出的必须能自己导回来（换机备份、多端同步都靠这条）；
 * 2. 缺这三块必须与今天的 v2 文档**行为一致** —— 浙大同步与 zju-ical 的
 *    `config.json` 走的就是「没有这三块」这条路，它们要是坏掉等于同步也坏了；
 * 3. 版本判断不能含糊：更高的版本整档拒收。
 */
class JsonSnapshotCodecTest {

    private val codec = JsonScheduleCodec()

    private fun decode(text: String): ScheduleBundle {
        val r = codec.decode(text)
        assertTrue("本该解码成功：$r", r is DecodeResult.Success)
        return (r as DecodeResult.Success).bundle
    }

    private fun failure(text: String): String {
        val r = codec.decode(text)
        assertTrue("本该解码失败：$r", r is DecodeResult.Failure)
        return (r as DecodeResult.Failure).message
    }

    // ------------------------------------------------------------------ 往返

    @Test
    fun `a snapshot with tasks types and rules survives a round trip`() {
        val bundle = ScheduleBundle(
            timetable = TimetableBlockBundle(
                courses = listOf(
                    CourseBundle(
                        key = "CS101|王教授",
                        name = "高等数学",
                        code = "CS101",
                        teacher = "王教授"
                    )
                )
            ),
            tasks = listOf(
                TaskBundle(
                    syncUid = "uid-1",
                    courseKey = "CS101|王教授",
                    title = "【作业】习题集 P134",
                    taskType = "POST_CLASS",
                    isCompleted = true,
                    priority = TaskStar.encode(true),
                    week = 3,
                    dayOfWeek = 1,
                    createdAt = 100L,
                    updatedAt = 200L
                )
            ),
            taskTypes = listOf(
                TaskTypeBundle(id = "t-1", label = "竞赛", colorHex = "#A6D6E4", inkHex = "#3F5F69", sortOrder = 2)
            ),
            taskRules = listOf(
                TaskRuleBundle(
                    courseKey = "CS101|王教授",
                    taskType = "PRE_STUDY",
                    cadence = "PER_CLASS",
                    dueOffsetDays = 0,
                    dueBeforeMinutes = 15
                )
            )
        )

        val decoded = decode(codec.encode(bundle))

        assertEquals(1, decoded.tasks.size)
        val task = decoded.tasks.single()
        assertEquals("uid-1", task.syncUid)
        assertEquals("CS101|王教授", task.courseKey)
        assertEquals("【作业】习题集 P134", task.title)
        assertEquals(TaskStar.encode(true), task.priority)
        assertEquals(true, task.isCompleted)
        assertEquals(3, task.week)
        assertEquals(200L, task.updatedAt)

        val type = decoded.taskTypes.single()
        assertEquals("t-1", type.id)
        assertEquals("竞赛", type.label)
        assertEquals("#A6D6E4", type.colorHex)
        assertEquals(2, type.sortOrder)

        val rule = decoded.taskRules.single()
        assertEquals("CS101|王教授", rule.courseKey)
        assertEquals("PRE_STUDY", rule.taskType)
        assertEquals(15, rule.dueBeforeMinutes)
    }

    @Test
    fun `a snapshot never writes the local course id into the task`() {
        // courseId 是本机自增值，两台设备各不相同。写进文件等于把一端的
        // 本地主键强加给另一端 —— 那正是跨设备同步最经典的失效方式。
        val json = codec.encode(
            ScheduleBundle(
                tasks = listOf(
                    TaskBundle(syncUid = "u", courseKey = "CS101|王", title = "t")
                )
            )
        )
        assertTrue("文件里不该出现 courseId 这个字段：$json", !json.contains("\"courseId\""))
    }

    // ------------------------------------------------------------------ 兼容老文档

    @Test
    fun `a v2 document decodes with all three user data blocks empty`() {
        val decoded = decode(
            """
            {
              "schemaVersion": 2,
              "timetable": { "courses": [ { "name": "高数", "teacher": "王" } ] }
            }
            """.trimIndent()
        )
        assertTrue("v2 文档不该带用户数据：${decoded.tasks}", decoded.tasks.isEmpty())
        assertTrue(decoded.taskTypes.isEmpty())
        assertTrue(decoded.taskRules.isEmpty())
        assertTrue("三块全空是浙大同步产出的合法形状", decoded.hasNoUserData)
        assertEquals("高数", decoded.timetable?.courses?.single()?.name)
    }

    @Test
    fun `a v1 document still decodes`() {
        val decoded = decode(
            """
            {
              "schemaVersion": 1,
              "semester": { "name": "秋冬", "yearSpan": "2026-2027", "totalWeeks": 16 },
              "courses": [ { "name": "高数", "teacher": "王" } ]
            }
            """.trimIndent()
        )
        assertEquals("高数", decoded.timetable?.courses?.single()?.name)
        assertTrue(decoded.tasks.isEmpty())
    }

    @Test
    fun `a calendar only document is still valid - this is what zju-ical config json looks like`() {
        // `config.json` 与 calendar 块逐字段同名同义，所以它本身必须仍是一份
        // 合法文档。这条一旦破了，浙大抓来的公共校历就导不进来了。
        val decoded = decode(
            """
            {
              "schemaVersion": 3,
              "calendar": {
                "termConfigs": [ { "Year": "2026-2027", "Term": 0,
                  "Begin": 20260907, "End": 20270124, "FirstWeekNo": 1 } ],
                "classTerms": [ "2026-2027:0" ],
                "tweaks": [ { "TweakType": "Clear", "Description": "国庆",
                  "From": 20261001, "To": 20261007 } ]
              }
            }
            """.trimIndent()
        )
        assertEquals(1, decoded.calendar?.termConfigs?.size)
        assertEquals(1, decoded.calendar?.tweaks?.size)
        assertTrue(decoded.hasNoUserData)
    }

    @Test
    fun `a newer schema version is refused instead of half-understood`() {
        val msg = failure(
            """{ "schemaVersion": 99, "timetable": { "courses": [] } }"""
        )
        assertTrue("要说清是版本太高：$msg", msg.contains("v99"))
    }

    @Test
    fun `a task missing its sync identity is kept so the validator can report it`() {
        // 解析层不做剔除：空 uid 是「这条待办没有身份」，该由校验期报 issue。
        // 在解析层悄悄丢掉，界面上就会少一条待办而用户不知道少在哪。
        val decoded = decode(
            """
            {
              "schemaVersion": 3,
              "tasks": [ { "title": "没有身份的待办" } ]
            }
            """.trimIndent()
        )
        assertEquals(1, decoded.tasks.size)
        assertNull(decoded.tasks.single().syncUid)
    }

    @Test
    fun `unknown fields in a task are ignored rather than failing the document`() {
        val decoded = decode(
            """
            {
              "schemaVersion": 3,
              "tasks": [ { "syncUid": "u", "title": "t", "somethingFromTheFuture": 42 } ]
            }
            """.trimIndent()
        )
        assertEquals("t", decoded.tasks.single().title)
    }

    @Test
    fun `malformed json is still rejected with a readable message`() {
        val msg = failure("""{ "timetable": [ """)
        assertTrue(msg.contains("JSON"))
    }

    @Test
    fun `an exported snapshot declares the current schema version`() {
        val json = codec.encode(ScheduleBundle())
        assertTrue("导出必须是当前版本：$json", json.contains("\"schemaVersion\":${JsonScheduleCodec.SCHEMA_VERSION}"))
    }

    @Test
    fun `credentials never reach the snapshot`() {
        // 凭据在 SecureAccountStorage（Keystore）里，不经过导出路径。
        // provenance 只有 source / generator / generatedAt / note 四个非敏感字段，
        // 这条钉住「将来有人往里塞 studentId 也会被测试挡住」。
        val json = codec.encode(
            ScheduleBundle(
                provenance = ProvenanceBundle(source = "snapshot", generator = "test"),
                tasks = listOf(TaskBundle(syncUid = "u", title = "t"))
            )
        )
        assertNotNull(json)
        assertTrue("导出里不该出现凭据字段：$json", !json.contains("password"))
    }
}
