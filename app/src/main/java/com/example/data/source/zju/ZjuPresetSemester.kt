package com.example.data.source.zju

import com.example.data.local.MakeupDayEntity
import com.example.data.local.NoClassDayEntity

/**
 * 内置（预置）的浙大官方校历，**只读**，供「本学期校历」弹层单独显示。
 *
 * 它与库里那份**自定义**校历是两件事，所以刻意做成两个类型而不是同一个：
 *
 * - 自定义 = 用户自己敲进去 / 导入进来的那一份，可能被改过，也可能是别的学校；
 * - 预置   = App 内置的那份官方 config，全校同一份，谁都没改过。
 *
 * 混在一起显示过一次的后果是：用户在界面上看到一份学期日期，**分不清它是官方
 * 的还是他自己上次敲的**，于是改错了也不知道该去哪里改。分开摆开，
 * 「应用」这个动作才说得清它会覆盖什么。
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
    /** 配置里的 `lastUpdated`，界面上标一句「内置于 …」 */
    val updatedOn: String?
)
