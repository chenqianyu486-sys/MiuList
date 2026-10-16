package com.example.data.calendar

import com.example.data.local.MakeupDayEntity
import com.example.data.local.NoClassDayEntity
import com.example.data.local.SemesterEntity

/**
 * 「应用官方校历」的差异预览。
 *
 * ## 为什么要先看差异
 *
 * 应用是**全量替换**：用户自己敲进去的停课 / 补课会被官方那份整个换掉。
 * 之前这个动作是 `noClassDrafts.clear()` 一下就完了，界面上没有任何提示 ——
 * 一个人补的「周六晚自习不算课」这类本地约定，会在点完按钮后无声消失。
 *
 * 这个函数只负责**算出**差多少，写不写由用户决定。
 *
 * ## 比较的是「即将写下去的值」
 *
 * 比的不是两个 Bundle，而是**应用后会落库的那份状态** —— 学期起止、
 * 停课日、补课日这三样。作息表不参与：官方校历不带作息，
 * 应用时也刻意**不动**它（保持 `AcademicCalendarDialog.applyPreset` 的既有语义）。
 */
data class CalendarDiff(
    /** 学期字段的逐项变化。 */
    val semesterChanges: List<FieldChange>,
    /** 新增的停课日。 */
    val noClassAdded: List<Long>,
    /** 会被删掉的停课日。 */
    val noClassRemoved: List<Long>,
    /** 新增的补课日。 */
    val makeupAdded: List<MakeupDayEntity>,
    /** 会被删掉的补课日。 */
    val makeupRemoved: List<MakeupDayEntity>,
    /** 同一天但「补周几」或「来源日」变了。 */
    val makeupChanged: List<MakeupChange>
) {
    /** 没有任何变化 —— 界面上该说「已经和官方一致」，而不是给一个没用的确认按钮。 */
    val isEmpty: Boolean
        get() = semesterChanges.isEmpty() && noClassAdded.isEmpty() && noClassRemoved.isEmpty() &&
            makeupAdded.isEmpty() && makeupRemoved.isEmpty() && makeupChanged.isEmpty()
}

/** 一个字段的前后取值。界面上直接显示 [label] 与 before/after 即可。 */
data class FieldChange(
    val label: String,
    val before: String,
    val after: String
)

/** 同一天补课的内容变化。 */
data class MakeupChange(
    val before: MakeupDayEntity,
    val after: MakeupDayEntity
)

/**
 * 一次比对的两端。刻意做成中性快照，让比对逻辑不依赖 Room 实体也不依赖
 * 任何来源类型 —— 两端各由调用方转换过来。
 */
data class CalendarSnapshot(
    val semester: Semester?,
    val noClassDays: Set<Long>,
    /** key = 补课日（epochDay）。 */
    val makeupDays: Map<Long, Makeup>
) {
    data class Semester(
        val name: String,
        val startEpochDay: Long,
        val firstMondayEpochDay: Long,
        val endEpochDay: Long,
        val totalWeeks: Int
    )

    data class Makeup(
        val epochDay: Long,
        /** 唯一真源。补周几由它算，所以比较也只比它。 */
        val sourceEpochDay: Long
    )

    companion object {
        /** 本机当前正在用的那一份。 */
        fun of(
            semester: SemesterEntity?,
            noClassDays: List<NoClassDayEntity>,
            makeupDays: List<MakeupDayEntity>
        ): CalendarSnapshot = CalendarSnapshot(
            semester = semester?.let {
                Semester(
                    name = it.name,
                    startEpochDay = it.startEpochDay,
                    firstMondayEpochDay = it.firstMondayEpochDay,
                    endEpochDay = it.endEpochDay,
                    totalWeeks = it.totalWeeks
                )
            },
            noClassDays = noClassDays.map { it.epochDay }.toSet(),
            makeupDays = makeupDays.associate {
                it.epochDay to Makeup(it.epochDay, it.sourceEpochDay)
            }
        )
    }
}

object CalendarDiffCalculator {

    /**
     * 比出 [current] → [incoming] 的差异。
     *
     * 停课与补课都以 **epochDay 为键**：同一天就是同一条，跨学期也不会撞。
     *
     * 补课只比 `sourceEpochDay` 一个字段 —— 补周几由它算，比它是比一个派生值，
     * 改了来源日而派生的周几恰好没变的话，那条其实没有任何行为差异。
     * **不比较 `note`**。`note` 装的是官方那条描述（"[国庆节] 10月2日（周五）停课，
     * 安排在10月17日（周六）补课"，由 `ScheduleBundleValidator` 从 tweak 描述带下来），
     * 它对用户有用 —— 解释了"这天为什么上课"。但它只写文案，
     * 拿它当差异判据会制造"内容其实没变、只是措辞动了"的假变更。
     * 所以：**不算差异，但应用时会写上去。**
     *
     * [current] 没有学期（本机还没配过）时，学期那几项全算新增，
     * 停课/补课则是纯粹的新增、没有移除 —— 这是最常见的首次应用场景。
     */
    fun compute(current: CalendarSnapshot, incoming: CalendarSnapshot): CalendarDiff {
        val semesterChanges = semesterChanges(current.semester, incoming.semester)

        val noClassAdded = (incoming.noClassDays - current.noClassDays).sorted()
        val noClassRemoved = (current.noClassDays - incoming.noClassDays).sorted()

        val makeupAdded = (incoming.makeupDays.keys - current.makeupDays.keys)
            .sorted()
            .map { incoming.makeupDays.getValue(it).toEntity() }
        val makeupRemoved = (current.makeupDays.keys - incoming.makeupDays.keys)
            .sorted()
            .map { current.makeupDays.getValue(it).toEntity() }

        val makeupChanged = (current.makeupDays.keys intersect incoming.makeupDays.keys)
            .sorted()
            .mapNotNull { key ->
                val before = current.makeupDays.getValue(key)
                val after = incoming.makeupDays.getValue(key)
                if (before.sourceEpochDay == after.sourceEpochDay) {
                    null
                } else {
                    MakeupChange(before.toEntity(), after.toEntity())
                }
            }

        return CalendarDiff(
            semesterChanges = semesterChanges,
            noClassAdded = noClassAdded,
            noClassRemoved = noClassRemoved,
            makeupAdded = makeupAdded,
            makeupRemoved = makeupRemoved,
            makeupChanged = makeupChanged
        )
    }

    private fun semesterChanges(
        before: CalendarSnapshot.Semester?,
        after: CalendarSnapshot.Semester?
    ): List<FieldChange> {
        if (before == null) {
            // 本机压根没配学期：整份学期都是新增，但只报一条，不要五项全列出来刷屏。
            return listOf(
                FieldChange(
                    label = "学期",
                    before = "未设置",
                    after = "${after?.name.orEmpty()} · 第 1-${after?.totalWeeks ?: 0} 周"
                )
            )
        }
        if (after == null) return emptyList()

        val out = mutableListOf<FieldChange>()
        fun put(label: String, b: String, a: String) {
            if (b != a) out += FieldChange(label, b, a)
        }
        put("学期名称", before.name, after.name)
        put(
            "学期开始日",
            EpochDays.formatCn(before.startEpochDay),
            EpochDays.formatCn(after.startEpochDay)
        )
        put(
            "首个周一",
            EpochDays.formatCn(before.firstMondayEpochDay),
            EpochDays.formatCn(after.firstMondayEpochDay)
        )
        put("学期结束日", EpochDays.formatCn(before.endEpochDay), EpochDays.formatCn(after.endEpochDay))
        put("总周数", "${before.totalWeeks} 周", "${after.totalWeeks} 周")
        return out
    }

    /**
     * 把快照里的补课转成实体，**只用于把差异显示出来**。
     *
     * 落库时不走这里：确认应用后写的是 `ZjuPresetSemester.makeupDays`，
     * 那份带着官方描述当 note。
     */
    private fun CalendarSnapshot.Makeup.toEntity() = MakeupDayEntity(
        epochDay = epochDay,
        makeupForDayOfWeek = EpochDays.dayOfWeek1To7(sourceEpochDay),
        note = "",
        sourceEpochDay = sourceEpochDay
    )
}
