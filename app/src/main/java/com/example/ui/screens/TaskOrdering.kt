package com.example.ui.screens

import com.example.data.local.TaskEntity
import com.example.data.model.TaskStar

/**
 * **标星的在前**，其余保持传入的顺序（那已经是「截止靠前」）。
 *
 * ## 为什么星标排在截止前面
 *
 * 星标是用户**手动挑出来**的那几条 —— `TaskStar` 的注释里写着它唯一真正好用的
 * 那档用法是「手动挑出几条重要的」，而且「那是列表排序给不了的（排序每天都在变）」。
 * 既然它存在的意义就是「这几条我要先看」，它就得排在最前面；
 * 截止时间退成**第二把键**：星标之内仍然按截止靠前的排。
 *
 * ## 为什么用稳定排序
 *
 * `sortedBy` 是稳定排序：标星的那几条内部仍按原来的截止顺序排，没标星的也是。
 * 只把「标星」这一层提到前面，不重排别的 —— 于是「星标优先」是**唯一**的改动，
 * 截止时间的排法一个字都没动。
 *
 * ## 谁在用
 *
 * - 清单页：**组内**排一次，四种分组方式（按课程 / 按截止 / 按分类 / 按星标）一起都对
 *   —— 因为 `groupBy` 保留入参顺序，组内拿到的本来就是「截止靠前」的那一串。
 * - 总览页：**整串**排一次，那串待办是平铺的，没有「分类」这一层。
 *
 * 两处以前口径不一样（总览页刻意不做星标优先），后来统一成这一条：
 * 「我标了星」在哪个列表里都该排最前，否则星标就成了只有一处生效的标记。
 */
internal fun List<TaskEntity>.starredFirst(): List<TaskEntity> =
    sortedByDescending { TaskStar.isMarked(it.priority) }

/**
 * 总览页「待办清单」区显示的条数上限。
 *
 * 总览是「今天要做的事」的**流水**，不是完整的清单 —— 所以只摆最上面的几条。
 */
internal const val DASHBOARD_TODO_LIMIT = 5

/**
 * 总览页「待办清单」区那一串待办：**未完成的，标星的在前**，其余按截止靠前。
 *
 * ## 为什么这一串要单独算
 *
 * 总览只显示前 [DASHBOARD_TODO_LIMIT] 条，于是**排序在这里是决定性的**：
 * 一条截止时间靠后的待办，在「按截止排」下根本挤不进这个窗口 ——
 * 哪怕用户给它标了星。所以标星必须参与这一层的排序，
 * 否则「标星」在总览里等于**没用**：用户挑出来的那几条永远看不见。
 *
 * 返回**整串**（不截断）而不是只返回显示的那几条：组头那个「待办清单 · N」
 * 要的是**全部**未完成的条数，不能被显示上限截成 5。
 */
internal fun dashboardPendingTodos(tasks: List<TaskEntity>): List<TaskEntity> =
    tasks.filter { !it.isCompleted }.starredFirst()
