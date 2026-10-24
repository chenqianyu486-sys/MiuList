package com.example.data.source.zju

import com.example.data.codec.AutomationBundle
import com.example.data.codec.CourseBundle
import com.example.data.codec.ProvenanceBundle
import com.example.data.codec.ScheduleBundle
import com.example.data.codec.SlotBundle
import com.example.data.codec.TermRefBundle
import com.example.data.codec.TimetableBlockBundle
import com.example.data.model.COURSE_PALETTE
import com.example.data.model.CourseCategory
import com.example.data.model.CourseKeys
import com.squareup.moshi.JsonClass

/**
 * zdbk 课表接口 `kbList` 里的一条课。
 *
 * 字段名是浙大教务的原始拼音缩写，**不能改**：改了就与接口脱节。
 *
 * `xqj`（星期几）、`djj`（第几节）、`skcd`（上几节）声明成 `Any?` 是有意的：
 * 浙大这几个字段在不同接口下会以字符串 `"6"` 或数字 `6` 两种形态出现，
 * 写成 `Int?` 遇到字符串会直接让整个 JSON 解析失败——**失败时一条课都拿不到**，
 * 而且报错信息还指向 Moshi，看不出是哪个字段。
 */
@JsonClass(generateAdapter = true)
data class ZjuClassRow(
    val xkkh: String?,      // 选课课号，如 (2026-2027-1)-MATH2432F-0083261-1
    val kcb: String?,       // 课程名<br>学期{第1-8周|3节/周}<br>教师<br>教室
    val xqj: Any?,          // 星期几：1=周一 … 7=周日
    val djj: Any?,          // 起始节次
    val skcd: Any?,         // 连上几节
    val dsz: String?,       // 单双周："0" 单周 / "1" 双周 / 其他（实测 "2"）每周
    val xxq: String?,       // 学期，"秋" / "秋冬" / "春"…
    val sfqd: Any?          // 是否确认
)

/** 映射的结果。课程已经按自然键合并、时段已经归一化。 */
data class ZjuScheduleRows(
    val courses: List<CourseBundle>,
    /** 被跳过的条目数与原因，进导入预览的「问题」清单。 */
    val skipped: Int,
    val skipReason: String?
)

/**
 * zdbk `kbList` → MiuList 的 `timetable` 块。
 *
 * ## `kcb` 是这个接口里最难啃的一处
 *
 * 课程名、教师、教室、上课周次**全挤在一个字段**里，用 `<br>` 分隔，
 * 末尾还缀着一段教学周说明（上游用 `.split("zwf")[0]` 切掉）：
 *
 * ```
 * 概率论与数理统计<br>秋冬{第1-8周|3节/周}<br>吴建军<br>紫金港西2-101zwf2027年01月12日(10:30-12:30)zwf
 * ```
 *
 * 切出四段分别是：课程名 / 学期+周次 / 教师 / 教室。
 * **必须先按 `zwf` 切再按 `<br>` 切**：反过来会把「zwf2027年01月12日」后面的
 * 换课说明粘在教室名尾巴上，界面上表现为教室名后面跟一串日期。
 *
 * ## `kcb` 里的周次是**子学期**口径，不是学期口径
 *
 * 浙大一个学期是两个 8 周的子学期拼起来的：秋冬学期的第 1–8 周是秋、第 9–16 周是冬，
 * 春夏同理。而教务网 `kcb` 里写的「第1-8周」，那个 8 数的是**子学期**——
 * 连 `xxq` 标着「秋冬」的课也这么写（实测 19 条里无一例外）。
 *
 * 所以这行字不能照字面直接当周次集合用：秋冬学期的课会被解析成 1–8 周，
 * 于是后半学期整段没课，而界面上看不出任何报错。正确的换算见 [resolveWeeks]。
 *
 * ## 单双周要展开成具体周号，必须先知道总周数
 *
 * 所以入参里有 [toCourses] 的 `totalWeeks`；纯函数，无 Android 依赖，可直接单测。
 */
object ZjuScheduleMapper {

    /**
     * 浙大一个学期内部是**两个 8 周的子学期**：`秋冬` 里第 1–8 周是秋、第 9–16 周是冬；
     * `春夏` 同理。本项目把一整个学期按 16 周来排课，所以这个常量既是子学期长度，
     * 也是从前半平移到后半要加的偏移量。
     *
     * 与 [ZjuTerms] 的四个独立查询串（`1|秋` / `1|冬` / …）并不矛盾：
     * 那是**教务网怎么捞数据**，这里管的是**MiuList 怎么排 16 周的课表**。
     */
    private const val SUBTERM_WEEKS = 8

    /** 一门课占学期的前半、后半，还是两半都占。 */
    private enum class Half { FIRST, SECOND }

    /**
     * @param totalWeeks 该学期总周数，用来把单双周展开成周号集合。
     * @param termCode 学期代码（0..6），只写进 provenance，不影响排课。
     */
    fun toCourses(
        rows: List<ZjuClassRow>,
        totalWeeks: Int,
        termCode: Int
    ): ZjuScheduleRows {
        val skipped = mutableListOf<String>()

        val parsed = rows.mapNotNull { row ->
            row.toParsed(totalWeeks) ?: run {
                skipped += "${row.courseName().orEmpty()}（${row.skipReason()}）"
                null
            }
        }

        // 按自然键合并成课程：同一门课的多个时段要在同一门课下
        val byKey = LinkedHashMap<String, MutableList<SlotBundle>>()
        val meta = LinkedHashMap<String, CourseBundle>()

        parsed.forEach { p ->
            val key = CourseKeys.compute(p.code.orEmpty(), p.name.orEmpty(), p.teacher.orEmpty())
            val slot = SlotBundle(
                dayOfWeek = p.dayOfWeek,
                startPeriod = p.startPeriod,
                endPeriod = p.endPeriod,
                classroom = p.classroom.orEmpty(),
                // 单双周在这里就展开成周号集合，后面 WeekSet.parse 不再需要理解「单/双」
                weeks = p.weeks
            )
            byKey.getOrPut(key) { mutableListOf() } += slot
            if (key !in meta) {
                meta[key] = CourseBundle(
                    key = key,
                    name = p.name.orEmpty(),
                    code = p.code.orEmpty(),
                    teacher = p.teacher.orEmpty(),
                    // 教务网不给类别，只能按课程名判定：名字带「实验/实训/实践/实习」
                    // 的进「实验实践」，其余一律落默认类别（公共基础）。
                    category = CourseCategory.classifyOnImport(p.name),
                    // 教务网不给课程颜色；按出现顺序从调色板取，保证同一门课每次抓都同色
                    colorHex = null,
                    automation = AutomationBundle(
                        preStudy = true,
                        postHomework = true,
                        // 解析层只认课程名决定**类别**，不猜开关：识别出来的实验课会进
                        // 「实验实践」类别，实验报告由该类别的生成规则兜底。
                        // 同名带「实验」的课未必真交实验报告，这里写死 true 会凭空多出待办。
                        labReport = false,
                        review = false,
                        defaultPostDueDays = 6,
                        preStudyMinutes = 30,
                        postHomeworkMinutes = 60,
                        labReportMinutes = 90
                    )
                )
            }
        }

        val courses = meta.entries.mapIndexed { index, (key, course) ->
            course.copy(
                colorHex = COURSE_PALETTE[index % COURSE_PALETTE.size],
                slots = byKey[key].orEmpty()
            )
        }

        return ZjuScheduleRows(
            courses = courses,
            skipped = skipped.size,
            skipReason = skipped.takeIf { it.isNotEmpty() }?.joinToString("；")
        )
    }

    /**
     * 学期代码 → provenance 里的一句话。
     *
     * [termQueries] 是**实际发出去的每一个**查询串。学年学期要查两半
     * （秋冬 = `1|秋` + `1|冬`），只写一个会让人以为后半学期没导入。
     */
    fun termNote(termCode: Int, termQueries: List<String>) =
        "${ZjuTerms.labelOf(termCode)}（${termQueries.joinToString(" + ")}）"

    /** 组装成可以直接交给导入管线的 v2 Bundle。 */
    fun toBundle(
        rows: ZjuScheduleRows,
        calendarBundle: com.example.data.codec.CalendarBundle,
        termYear: String,
        termCode: Int,
        termQueries: List<String>,
        studentId: String
    ): ScheduleBundle = ScheduleBundle(
        provenance = ProvenanceBundle(
            source = "zju",
            generator = "MiuList 内置浙大导入",
            studentId = studentId,
            note = termNote(termCode, termQueries)
        ),
        calendar = calendarBundle,
        timetable = TimetableBlockBundle(
            termRef = TermRefBundle(termYear, termCode),
            courses = rows.courses
        )
    )

    // ---------------------------------------------------------------- 内部

    private data class ParsedClass(
        val name: String?,
        val code: String?,
        val teacher: String?,
        val classroom: String?,
        val dayOfWeek: Int?,
        val startPeriod: Int?,
        val endPeriod: Int?,
        /** 展开后的周号集合；null = 每周 */
        val weeks: List<Int>?
    )

    private fun ZjuClassRow.toParsed(totalWeeks: Int): ParsedClass? {
        // `xxq` 里秋/冬/春/夏一个都没有 → 不是我们要的课表行，放进来只会污染统计与周次计算。
        if (!hasRecognisedTerm()) return null

        val day = xqj.asIntOrNull() ?: return null
        if (day !in 1..7) return null

        // djj = 起始节次，skcd = 连上几节。末节 = djj + skcd - 1（上游的 end 是开区间）。
        val start = djj.asIntOrNull() ?: return null
        val length = skcd.asIntOrNull() ?: return null
        if (start < 1 || length < 1) return null

        val parts = kcbSegments()
        val name = parts.getOrNull(0)?.trim()?.takeIf { it.isNotEmpty() } ?: return null

        // 周次有三层来源：kcb 里写明的「第1-8周」、dsz 代表的单双周、以及 xxq 说的
        // 这门课占哪几个子学期。**先在子学期内部取交集，再平移到学期坐标**——
        // 少了平移这一步，秋冬学期的课会在第 9 周凭空消失，详见 [resolveWeeks]。
        val declared = parts.getOrNull(1)?.let { parseWeekSpec(it) }
        // `when` 的分支里直接写 lambda 会推断不出类型（Kotlin 的 New inference error），
        // 所以先把「周号是否属于这一档」声明成显式的 (Int) -> Boolean。
        val parity: ((Int) -> Boolean)? = when (dsz?.trim()) {
            "0" -> { p: Int -> p % 2 == 1 }   // 单周
            "1" -> { p: Int -> p % 2 == 0 }   // 双周
            else -> null                       // 每周（实测 "2"）
        }

        val weeks = resolveWeeks(totalWeeks, subTermHalves(), declared, parity)

        return ParsedClass(
            name = name,
            code = courseCodeOf(),
            teacher = parts.getOrNull(2)?.trim(),
            classroom = parts.getOrNull(3)?.trim(),
            dayOfWeek = day,
            startPeriod = start,
            endPeriod = start + length - 1,
            weeks = weeks
        )
    }

    /**
     * `xxq` → 这门课占哪几个子学期。
     *
     * 「秋」「春」是前半，「冬」「夏」是后半，「秋冬」「春夏」两半都占。
     * 返回 null 表示认不出（两段式之外的学期），调用方按整个学期处理。
     *
     * 这也是 [hasRecognisedTerm] 的唯一判据来源，别再单独维护一份关键词表：
     * 两处各写一遍的话，放宽其中一处就会让另一处悄悄按旧含义读。
     */
    private fun ZjuClassRow.subTermHalves(): Set<Half>? {
        val raw = xxq.orEmpty()
        val front = FRONT_SEASONS.any { it in raw }   // 秋 / 春
        val back = BACK_SEASONS.any { it in raw }     // 冬 / 夏
        return when {
            front && back -> setOf(Half.FIRST, Half.SECOND)
            front -> setOf(Half.FIRST)
            back -> setOf(Half.SECOND)
            else -> null
        }
    }

    private val FRONT_SEASONS = listOf("秋", "春")
    private val BACK_SEASONS = listOf("冬", "夏")

    /**
     * 把周次换算到 MiuList 的学期坐标。
     *
     * ## 教务网的周次是**子学期口径**，不是学期口径
     *
     * 这是整个解析里最容易安静出错的一处。实抓响应（2026-10-13，`xq=1|秋`，
     * 19 条 `kbList`）里**每一条**都写「第1-8周」，包括 `xxq` 明确是「秋冬」的课：
     * ```
     * 概率论与数理统计<br>秋冬{第1-8周|3节/周}<br>吴建军<br>紫金港西2-101
     * 量子力学<br>秋冬{第1-8周|3节/周}<br>刘峰<br>紫金港西2-504
     * ```
     * 那个「1-8」数的是**秋**这个子学期的 8 周，不是 MiuList 那个 16 周学期的 1–8 周。
     * 照字面直接取 1..8，秋冬学期的 6 门课会在第 9 周集体消失，
     * 而界面上看不出任何异常——只是「后半学期没课了」。
     *
     * ## 换算规则
     *
     * 1. 先在**子学期内部**把 kcb 的「第1-8周」与 dsz 的单双周取交集（两层都看，少一层都错）；
     * 2. 再按 `xxq` 把这份模式平移到它占的每个子学期：前半 +0，后半 +8。
     *    `秋冬`/`春夏` 占两半 → 1..16；`冬`/`夏` 只占后半 → 9..16；`秋`/`春` 只占前半 → 1..8；
     * 3. `xxq` 里没有季节（短学期、暑）→ 不平移，维持整学期口径；
     * 4. 平移量是偶数，**单双周口径不受影响**：`第1-8周` + 单周的秋冬课展开成
     *    1,3,5,7 与 9,11,13,15，前后两半都落在奇数周上，与教务网的意思一致；
     * 5. 学期本身只有 8 周时不平移（见 [resolveWeeks] 里的 `splitTerm`）：
     *    上游 config.json 把春夏拆成两段时会出现那种配置，而那时导入的这一期
     *    **就是**那个子学期，再加 8 会把课全推到学期外、直接整门课消失。
     */
    private fun resolveWeeks(
        totalWeeks: Int,
        halves: Set<Half>?,
        declared: List<Int>?,
        parity: ((Int) -> Boolean)?
    ): List<Int>? {
        // 只有「认得出子学期」且「学期确实够长装得下两个子学期」时才按子学期拆。
        val splitTerm = halves != null && totalWeeks > SUBTERM_WEEKS
        val span = if (splitTerm) SUBTERM_WEEKS else totalWeeks

        // 第 1 步：子学期内部的两层来源取交集。只看 dsz 会让「第1-8周」的单周课
        // 在整个学期都出现；只看 kcb 会让没标单双周的课在不该上的周次出现。
        val relative: List<Int>? = when {
            declared != null && parity != null ->
                declared.filter(parity).takeIf { it.isNotEmpty() }
            declared != null -> declared
            parity != null ->
                (1..span).filter(parity).takeIf { it.isNotEmpty() }
            else -> null
        }

        if (halves == null) {
            // 第 3 条规则：认不出子学期 → 不平移，裁到学期内即可。
            return relative?.filter { it in 1..totalWeeks }
        }

        val offsets = when {
            !splitTerm -> listOf(0)
            Half.FIRST in halves && Half.SECOND in halves -> listOf(0, SUBTERM_WEEKS)
            Half.SECOND in halves -> listOf(SUBTERM_WEEKS)
            else -> listOf(0)
        }

        // 第 2 步：平移。kcb 既没写周次、也没有单双周时，这门课覆盖它占的**整个**子学期。
        // 不能退回「整个学期每周」——那会让冬学期的课落到第 1–8 周，
        // 也就是把它排到压根不属于它的秋学期里去，和这次修掉的是同一类错。
        val pattern = relative ?: (1..span).toList()

        return offsets.flatMap { off -> pattern.map { it + off } }
            .filter { it in 1..totalWeeks }
            .distinct()
            .sorted()
            .takeIf { it.isNotEmpty() }
    }

    /**
     * 从 `xkkh` 里取出「哪门课」，把教师号与班号段丢掉。
     *
     * `xkkh` 的形状是 `(学年-学年-学期)-课程代码-教师工号-班号`，实测三种：
     * ```
     * (2026-2027-1)-ISEE2020M-0088111-1     智能物联网 周1 第6节
     * (2026-2027-1)-ISEE2020M-0088111-1A    智能物联网 周1 第9-10节   ← 同一门课！
     * ```
     *
     * 同一个「课程代码 + 教师」的课会被教务系统按班拆成多条 `xkkh`，
     * 尾号多一个字母就直接分家 —— 于是界面上同一门课出现两次，
     * 各带一半时段（实测：AIoT 系统设计被拆成 2 个时段 + 1 个时段两门）。
     *
     * **不要用上游那个 `xkkh[:22]`**：那个下标是按 `(2026-2027-1)` 正好 13 个字符
     * 算的，学年或学期的书写方式一变（比如将来写成 `(2026-2027-01)`）就会切错位置，
     * 而且切出来的东西不认得。这里按分隔符解析，形状变了会退回整串而不是错位。
     *
     * 抽取规则本身在 [ZjuCourseCode] 里 —— **考试那边也要用同一套**，
     * 否则同一门课的考试与课程认不出是同一门。
     */
    private fun ZjuClassRow.courseCodeOf(): String? = ZjuCourseCode.of(xkkh)

    /**
     * 把 `kcb` 切成四段。
     *
     * **顺序不能反**：先按 `zwf` 切掉末尾的教学周/调课说明，再按 `<br>` 分段。
     * 反过来做的话教室那一段会粘上 `zwf2027年01月12日(10:30-12:30)` 这样的尾巴。
     *
     * 上游对第 3 段判空是有理由的：教师可能整段为空，此时下标要靠 `orEmpty` 兜住，
     * 不能直接 `[3]` 取——那会越界。
     */
    private fun ZjuClassRow.kcbSegments(): List<String> =
        kcb.orEmpty().substringBefore("zwf").split("<br>")

    /**
     * 从 `秋冬{第1-8周|3节/周}` 里取出周号集合。解析不出来返回 null（= 不限周次）。
     *
     * 返回的是**子学期口径**的周号（1..8），不是 MiuList 学期坐标；
     * 平移成学期坐标由 [resolveWeeks] 负责，别在这里加偏移量。
     */
    private fun parseWeekSpec(timeString: String): List<Int>? {
        val inner = timeString.substringAfter('{', "").substringBefore('}', "")
        if (inner.isBlank()) return null
        val spec = inner.substringBefore('|')
        if (!spec.startsWith("第")) return null
        val body = spec.removePrefix("第").removeSuffix("周")
        if (body.isBlank()) return null

        // 「1-8」是区间；「1,3,5」是枚举。两种都要认。
        val range = body.split('-')
        if (range.size == 2) {
            val from = range[0].trim().toIntOrNull() ?: return null
            val to = range[1].trim().toIntOrNull() ?: return null
            if (from > to) return null
            return (from..to).toList()
        }
        val listed = body.split(',').mapNotNull { it.trim().toIntOrNull() }
        return listed.takeIf { it.isNotEmpty() }
    }

    /** Moshi 把数字字段解成 `Any?`，字符串/数字两种形态都要收。 */
    private fun Any?.asIntOrNull(): Int? = when (this) {
        is String -> trim().toIntOrNull()
        is Number -> toInt()
        else -> null
    }

    /** `xxq` 里是否认得出子学期。判据见 [subTermHalves]，不另维护关键词表。 */
    private fun ZjuClassRow.hasRecognisedTerm(): Boolean = subTermHalves() != null

    /** 供跳过清单显示课程名，不做完整解析。 */
    private fun ZjuClassRow.courseName(): String? =
        kcbSegments().getOrNull(0)?.trim()?.takeIf { it.isNotEmpty() }

    /** 跳过的原因要写进导入预览，让用户知道哪几行没进来、为什么。 */
    private fun ZjuClassRow.skipReason(): String = when {
        !hasRecognisedTerm() -> "学期无法识别"
        xqj.asIntOrNull() !in 1..7 -> "星期无法解析"
        djj.asIntOrNull() == null || skcd.asIntOrNull() == null -> "节次无法解析"
        courseName().isNullOrEmpty() -> "课程名无法解析"
        else -> "课程信息不完整"
    }
}