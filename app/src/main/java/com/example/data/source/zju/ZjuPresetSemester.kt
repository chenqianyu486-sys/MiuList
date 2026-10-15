package com.example.data.source.zju

import com.example.data.calendar.CalendarOrigin
import com.example.data.calendar.CalendarSnapshot
import com.example.data.local.MakeupDayEntity
import com.example.data.local.NoClassDayEntity

/**
 * 官方校历，**只读**，供「本学期校历」弹层单独显示。
 *
 * 它与库里那份**自定义**校历是两件事，所以刻意做成两个类型而不是同一个：
 *
 * - 自定义 = 用户自己敲进去 / 导入进来的那一份，可能被改过，也可能是别的学校；
 * - 官方   = 从托管源 / 缓存 / 内置读出来的官方数据，谁都没改过。
 *
 * 混在一起显示过一次的后果是：用户在界面上看到一份学期日期，**分不清它是官方
 * 的还是他自己上次敲的**，于是改错了也不知道该去哪里改。分开摆开，
 * 「应用」这个动作才说得清它会覆盖什么。
 *
 * @param origin 这份数据是从哪儿来的。**界面上必须显示**——用户凭什么相信
 *   弹层顶部那一块，唯一的依据就是它能说清自己读的是远程、缓存还是内置。
 * @param lastUpdated 配置里的 `lastUpdated`（`yyyyMMdd` 整数）。
 *   它同时是**版本号**：检查更新时就是拿它跟远程那份比大小。
 */
data class ZjuPresetSemester(
    /** 「2026-2027」 */
    val yearSpan: String,
    /** 「2026-2027学年 秋冬 学期」 */
    val label: String,
    val startEpochDay: Long,
    val firstMondayEpochDay: Long,
    val endEpochDay: Long,
    val totalWeeks: Int,
    val noClassDays: List<NoClassDayEntity>,
    val makeupDays: List<MakeupDayEntity>,
    val origin: CalendarOrigin,
    val lastUpdated: Int? = null
) {
    /**
     * 人读的版本号 `2026-09-17`；没有 `lastUpdated` 时返回 null。
     *
     * 不直接打印整数：界面上出现一串「内置于 20260917」既难读，
     * 而"内置"这个词现在也不成立了 —— 数据可能来自远程。
     */
    val versionLabel: String?
        get() = lastUpdated?.let { "%04d-%02d-%02d".format(it / 10000, it / 100 % 100, it % 100) }

    /** 界面上那一行「来源 … · 版本 …」。 */
    val originLabel: String
        get() = when (origin) {
            CalendarOrigin.REMOTE -> "远程"
            CalendarOrigin.CACHED -> "缓存"
            CalendarOrigin.BUNDLED -> "内置"
        }

    /** 转成差异比对用的中性快照。 */
    fun toSnapshot() = CalendarSnapshot(
        semester = CalendarSnapshot.Semester(
            name = label,
            startEpochDay = startEpochDay,
            firstMondayEpochDay = firstMondayEpochDay,
            endEpochDay = endEpochDay,
            totalWeeks = totalWeeks
        ),
        noClassDays = noClassDays.map { it.epochDay }.toSet(),
        makeupDays = makeupDays.associate {
            it.epochDay to CalendarSnapshot.Makeup(
                epochDay = it.epochDay,
                makeupForDayOfWeek = it.makeupForDayOfWeek,
                sourceEpochDay = it.sourceEpochDay
            )
        }
    )
}
